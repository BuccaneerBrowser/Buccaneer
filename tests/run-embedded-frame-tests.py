#!/usr/bin/env python3
"""Real-browser regression test for the embedded ad-frame rule in popup_guard.js.

Incident (2026-11-15, ghostflix.st): the ad injector serves its "View Now" banner in a
SANDBOXED SRCDOC IFRAME nested in a fixed/absolute wrapper. The iframe is position:static
with z-index:auto, so every floating rule in inspectOverlayElement bailed out before it.
killEmbeddedAdFrames() now kills the positioned ancestor of a large docless iframe
(srcdoc / empty / about:blank / data: src) when that ancestor covers >=30% of the viewport
and holds no video or real controls.

This test serves a fixture page that reproduces the incident pattern plus the negative
cases the rule must spare, injects the REAL app asset the way the app does (script before
any page content), and asserts on the resulting DOM. Drives headless Chromium over raw
CDP — no playwright dependency.

  python3 tests/run-embedded-frame-tests.py
"""
import http.server
import json
import os
import socket
import struct
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GUARD = ROOT / 'app' / 'src' / 'main' / 'assets' / 'popup_guard.js'
CHROMIUM_CANDIDATES = [
    os.environ.get('CHROME_BIN', ''),
    os.path.expanduser('~/.local/bin/chromium'),
    'chromium',
    'chromium-browser',
    'google-chrome',
]

FIXTURE = r"""<!doctype html><html><head><meta charset=utf-8>
<title>embedded frame fixture</title>
<style>
  body { margin:0; background:#111; color:#eee; font-family:sans-serif; }
  .panel { position:static; padding:8px; border-bottom:1px solid #333; }
</style>
<script>
  // Bridge shim: the app injects window.Buccaneer before this page's content.
  window.Buccaneer = {
    isGuardEnabled: function () { return true; },
    diagnosticEvent: function (name, data) { (window.__ev = window.__ev || []).push(name + '::' + data); },
    blocked: function (what) { (window.__blk = window.__blk || []).push(what); }
  };
</script>
<script src="/guard.js"></script>
</head>
<body>
<div class="panel">fixture content — the page's own UI</div>

<!-- NEGATIVE: parked 1x1 tracker iframe (tiny, corner wrapper) — must survive. -->
<div id="parked-wrap" style="position:fixed;bottom:0;right:0;width:10px;height:10px;">
  <iframe id="parked" src="about:blank" style="width:1px;height:1px;"></iframe>
</div>

<!-- NEGATIVE: the main player — an http(s)-src iframe in a fixed wrapper — must survive. -->
<div id="player-wrap" style="position:fixed;top:0;left:0;width:960px;height:540px;">
  <iframe id="player" src="/player.html" style="width:960px;height:540px;border:0;"></iframe>
</div>

<!-- NEGATIVE: a legit modal with a real control — must survive despite size + fixed wrapper. -->
<div id="modal" style="position:fixed;top:10%;left:20%;width:600px;height:400px;background:#fff;color:#000;padding:10px;">
  <iframe id="modal-frame" srcdoc="&lt;html&gt;&lt;body&gt;help&lt;/body&gt;&lt;/html&gt;" style="width:500px;height:300px;"></iframe>
  <button id="modal-close">Close</button>
</div>

<!-- NEGATIVE: docless iframe whose wrapper is NOT positioned — must survive. -->
<div id="static-wrap" style="position:static;width:960px;height:540px;">
  <iframe id="static-frame" srcdoc="&lt;html&gt;&lt;body&gt;x&lt;/body&gt;&lt;/html&gt;" style="width:900px;height:500px;display:block;"></iframe>
</div>

<!-- NEGATIVE (review MAJOR 1, proven): a player that mounts with NO src attribute and
     navigates later, in a full-viewport fixed wrapper — must survive. -->
<div id="nosrc-wrap" style="position:fixed;top:0;left:0;width:960px;height:540px;">
  <iframe id="nosrc-player" style="width:960px;height:540px;"></iframe>
</div>

<!-- NEGATIVE (review MAJOR 2, proven): lazy about:blank embed (data-src pattern) in a DEEP
     positioned hero layer with a div-based play button — must survive (weak signal + depth). -->
<main><article>
  <div id="deep-hero" style="position:absolute;top:0;left:0;width:100%;height:60%;">
    <iframe id="lazy-frame" src="about:blank" data-src="/player.html" style="width:640px;height:360px;display:block;"></iframe>
    <div class="play-btn" style="width:64px;height:64px;background:#c00;text-align:center;line-height:64px;">&#9654;</div>
  </div>
</article></main>

<!-- NEGATIVE (review MAJOR 3, proven): player-chrome signature — a .player-poster layer
     holding a srcdoc iframe, with the <video> as a SIBLING — must survive. The stage is
     in-flow BELOW the fold so the sibling video cannot trip the pre-existing covers_video
     rule on the other fixture elements. -->
<div id="stage" class="player-stage" style="width:960px;height:540px;">
  <video id="stage-video" style="width:960px;height:540px;"></video>
  <div id="poster-layer" class="player-poster" style="position:absolute;top:0;left:0;width:960px;height:540px;background:#222;">
    <iframe id="poster-frame" srcdoc="&lt;html&gt;&lt;body&gt;p&lt;/body&gt;&lt;/html&gt;" style="width:960px;height:540px;display:block;"></iframe>
  </div>
</div>

<!-- POSITIVE (review MINOR 4, proven): srcdoc ad in a small fixed tooltip whose parent is
     the full-viewport ad root — the root must be killed (climb past the small ancestor). -->
<div id="ad-root" style="position:fixed;top:0;left:0;width:960px;height:540px;">
  <div id="tooltip" style="position:fixed;top:200px;left:300px;width:200px;height:100px;">
    <iframe id="tooltip-ad" srcdoc="&lt;html&gt;&lt;body&gt;t&lt;/body&gt;&lt;/html&gt;" style="width:200px;height:100px;display:block;"></iframe>
  </div>
</div>

<!-- POSITIVE (live 2026-11-15 "Check This Out" variant): the injector ships the WHOLE
     creative stack with pointer-events:none and the wrapper at z=2147483647 — must be
     killed (the pointer-events skip is the hole that let this variant through). -->
<div id="cto-wrap" style="position:fixed;top:0;left:0;width:960px;height:540px;z-index:2147483647;pointer-events:none;">
  <div id="cto-inner" style="width:100vw;height:100dvh;position:relative;pointer-events:none;">
    <iframe id="cto-frame" srcdoc="&lt;html&gt;&lt;body&gt;c&lt;/body&gt;&lt;/html&gt;" style="width:960px;height:540px;display:block;pointer-events:none;"></iframe>
  </div>
</div>

<!-- POSITIVE: the incident pattern — srcdoc ad iframe in a fixed full-viewport wrapper,
     iframe itself position:static, no z-index anywhere. Must be killed (wrapper hidden). -->
<div id="ad-wrap" style="position:fixed;top:0;left:0;width:100vw;height:100vh;">
  <div style="position:relative;">
    <div class="notranslate">
      <iframe id="ad-frame" sandbox="allow-same-origin allow-scripts allow-popups allow-modals"
        srcdoc="&lt;!doctype html&gt;&lt;html&gt;&lt;body&gt;&lt;div class=&quot;wrapper&quot;&gt;&lt;img src=&quot;data:image/gif;base64,R0lGODlhAQABAAAAACw=&quot;&gt;&lt;/div&gt;&lt;/body&gt;&lt;/html&gt;"
        style="width:960px;height:540px;display:block;"></iframe>
    </div>
  </div>
</div>
</body></html>"""

PLAYER_PAGE = "<!doctype html><html><head><title>player</title></head><body>player doc</body></html>"


def free_port():
    s = socket.socket()
    s.bind(('127.0.0.1', 0))
    port = s.getsockname()[1]
    s.close()
    return port


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/':
            body = FIXTURE.encode()
        elif self.path == '/guard.js':
            body = GUARD.read_bytes()
        elif self.path == '/player.html':
            body = PLAYER_PAGE.encode()
        else:
            self.send_response(404)
            self.end_headers()
            return
        self.send_response(200)
        ctype = 'application/javascript' if self.path.endswith('.js') else 'text/html'
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        pass


def ws_connect(port, path):
    """Minimal RFC6455 client: handshake + one text frame send/recv pair."""
    import base64
    s = socket.create_connection(('127.0.0.1', port), timeout=10)
    key = base64.b64encode(os.urandom(16)).decode()
    req = (f"GET {path} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nUpgrade: websocket\r\n"
           f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n")
    s.sendall(req.encode())
    resp = b''
    while b'\r\n\r\n' not in resp:
        chunk = s.recv(4096)
        if not chunk:
            raise RuntimeError('websocket handshake failed')
        resp += chunk
    if b'101' not in resp.split(b'\r\n', 1)[0]:
        raise RuntimeError('websocket upgrade refused: ' + resp[:200].decode(errors='replace'))
    buf = resp.split(b'\r\n\r\n', 1)[1]

    def send_text(payload):
        data = payload.encode()
        hdr = bytearray([0x81])
        n = len(data)
        mask = os.urandom(4)
        if n < 126:
            hdr.append(0x80 | n)
        elif n < 65536:
            hdr.append(0x80 | 126)
            hdr += struct.pack('>H', n)
        else:
            hdr.append(0x80 | 127)
            hdr += struct.pack('>Q', n)
        hdr += mask
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
        s.sendall(bytes(hdr) + masked)

    def recv_msg():
        nonlocal buf
        while True:
            if len(buf) >= 2:
                ln = buf[1] & 0x7F
                off = 2
                if ln == 126:
                    if len(buf) < 4:
                        break
                    ln = struct.unpack('>H', buf[2:4])[0]
                    off = 4
                elif ln == 127:
                    if len(buf) < 10:
                        break
                    ln = struct.unpack('>Q', buf[2:10])[0]
                    off = 10
                if len(buf) >= off + ln:
                    payload = buf[off:off + ln]
                    buf = buf[off + ln:]
                    return payload.decode(errors='replace')
            chunk = s.recv(65536)
            if not chunk:
                raise RuntimeError('websocket closed')
            buf += chunk

    return s, send_text, recv_msg


def cdp_evaluate(send_text, recv_msg, expression, timeout_ms=15000):
    send_text(json.dumps({"id": 1, "method": "Runtime.evaluate",
                          "params": {"expression": expression, "awaitPromise": True,
                                     "returnByValue": True, "timeout": timeout_ms}}))
    while True:
        msg = json.loads(recv_msg())
        if msg.get('id') == 1:
            res = msg.get('result', {}).get('result', {})
            if 'exceptionDetails' in msg.get('result', {}):
                raise RuntimeError('evaluate threw: ' + json.dumps(msg['result']['exceptionDetails'])[:400])
            return res.get('value')


def main():
    chrom = next((c for c in CHROMIUM_CANDIDATES if c and (os.path.exists(c) if '/' in c else True)), None)
    if not chrom:
        print('FAIL: no chromium binary found (set CHROME_BIN)')
        return 2
    http_port = free_port()
    dbg_port = free_port()
    srv = http.server.ThreadingHTTPServer(('127.0.0.1', http_port), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()

    proc = subprocess.Popen([
        chrom, '--headless=new', '--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage',
        f'--remote-debugging-port={dbg_port}', '--window-size=960,540',
        f'http://127.0.0.1:{http_port}/',
    ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        # Wait for the devtools endpoint.
        ws_path = None
        for _ in range(60):
            try:
                targets = json.load(urllib.request.urlopen(f'http://127.0.0.1:{dbg_port}/json', timeout=1))
                pages = [t for t in targets if t.get('type') == 'page' and 'webSocketDebuggerUrl' in t]
                if pages:
                    from urllib.parse import urlparse
                    ws_path = urlparse(pages[0]['webSocketDebuggerUrl']).path
                    break
            except Exception:
                pass
            time.sleep(0.5)
        if not ws_path:
            print('FAIL: devtools endpoint never came up')
            return 2
        sock, send_text, recv_msg = ws_connect(dbg_port, ws_path)
        # Give the guard its initial scan + a couple of mutation batches.
        time.sleep(3.0)
        verdict = cdp_evaluate(send_text, recv_msg, r"""
        (() => {
          const disp = id => getComputedStyle(document.getElementById(id)).display;
          const ev = window.__ev || [];
          const kills = ev.filter(e => e.startsWith('overlay_killed::') && e.indexOf('embedded_ad_frame') >= 0);
          return JSON.stringify({
            ad_wrap: disp('ad-wrap'),
            ad_root: disp('ad-root'),
            cto_wrap: disp('cto-wrap'),
            cto_inner: disp('cto-inner'),
            nosrc_wrap: disp('nosrc-wrap'),
            deep_hero: disp('deep-hero'),
            stage: disp('stage'),
            poster: disp('poster-layer'),
            parked_wrap: disp('parked-wrap'),
            player_wrap: disp('player-wrap'),
            modal: disp('modal'),
            static_wrap: disp('static-wrap'),
            kill_events: kills.length,
            events: ev.slice(0, 16)
          });
        })()
        """)
        v = json.loads(verdict)
        checks = [
            ('ad wrapper killed (incident pattern)', v['ad_wrap'] == 'none'),
            ('ad root killed past small tooltip (review minor 4)', v['ad_root'] == 'none'),
            ('pointer-events:none ad stack killed (live CTO variant)', v['cto_inner'] == 'none' or v['cto_wrap'] == 'none'),
            ('kill logged with reason embedded_ad_frame (x3)', v['kill_events'] >= 3),
            ('src-less player survives (review major 1)', v['nosrc_wrap'] != 'none'),
            ('deep lazy hero survives (review major 2)', v['deep_hero'] != 'none'),
            ('player-chrome poster survives (review major 3)', v['poster'] != 'none'),
            ('parked 1x1 tracker survives', v['parked_wrap'] != 'none'),
            ('player iframe survives', v['player_wrap'] != 'none'),
            ('controlled modal survives', v['modal'] != 'none'),
            ('static-wrapper frame survives', v['static_wrap'] != 'none'),
        ]
        rc = 0
        for name, ok in checks:
            print(('PASS' if ok else 'FAIL') + ': ' + name)
            if not ok:
                rc = 1
        print('events seen:', json.dumps(v['events'], indent=1))
        return rc
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except Exception:
            proc.kill()
        srv.shutdown()


if __name__ == '__main__':
    sys.exit(main())
