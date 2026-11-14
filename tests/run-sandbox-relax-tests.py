#!/usr/bin/env python3
"""Real-browser test for the sandbox relaxer in page_helpers.js (installSandboxRelaxer).

Embedded video players (VidSrc and similar) refuse to play inside a sandboxed iframe ("Sandbox Not Allowed -
Remove Sandbox from iFrame to Play"). The page_helpers.js relaxer takes the sandbox off player frames in the TOP
document. This test serves several "sites" and "players" from one local port (Chromium maps every host name to
127.0.0.1), injects the real page_helpers.js into every frame the way the app does, and checks what each player
sees: whether its document has an opaque origin (sandboxed) or a real one, and whether window.open() works.

  python3 tests/run-sandbox-relax-tests.py                       # the repo's page_helpers.js, expects the fix
  python3 tests/run-sandbox-relax-tests.py --baseline FILE       # an older page_helpers.js, expects the bug

Needs the `playwright` Python package and a `chromium` binary on PATH (same as the other DOM tests here).
Not part of CI (needs a browser); run it by hand after changing installSandboxRelaxer().
"""
import argparse
import json
import pathlib
import re
import shutil
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'app' / 'src' / 'main' / 'assets'

# ---------------------------------------------------------------------------------------------- the player page
# What a player that objects to sandboxes does: a null origin or blocked storage is "sandboxed"; a click probes
# window.open() (a sandbox without allow-popups returns null).
PLAYER = r"""<!doctype html><meta charset=utf-8><title>player</title>
<body style="margin:0;background:#123;color:#fff;font:16px sans-serif">
<div id=msg>checking</div><button id=b>play</button>
<script>
(function () {
  var s = {origin: window.origin, ls: 'ok', ck: 'ok', popup: 'untested', href: location.href};
  try { localStorage.setItem('x', '1'); } catch (e) { s.ls = 'err'; }
  try { void document.cookie; } catch (e) { s.ck = 'err'; }
  window.__state = s;
  function show() {
    var bad = s.origin === 'null' || s.ls === 'err' || s.ck === 'err' || s.popup === 'blocked';
    document.getElementById('msg').textContent = bad ? 'Sandbox Not Allowed - Remove Sandbox from iFrame to Play' : 'PLAYING';
  }
  show();
  document.getElementById('b').onclick = function () {
    var w = null;
    try { w = window.open('about:blank', '_blank'); } catch (e) { w = null; }
    s.popup = w ? 'opened' : 'blocked';
    try { if (w) w.close(); } catch (e) {}
    show();
  };
  if (location.search.indexOf('nest=1') >= 0) {
    var n = document.createElement('iframe');
    n.setAttribute('sandbox', 'allow-scripts');
    n.src = '/embed/nested?id=n2';
    document.body.appendChild(n);
  }
})();
</script>"""

LOADS = {}          # player id -> number of times the player document was requested
SCENARIOS = {}      # name -> page html
PORT = [0]
SPORT = [0]       # the https twin of the same server (HSTS-preloaded hosts such as cloudflare.com need https)


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *a):
        pass

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        q = urllib.parse.parse_qs(u.query)
        if u.path.startswith('/s/'):
            html = SCENARIOS.get(u.path[3:])
            body = (html or 'no such scenario').replace('{P}', str(PORT[0])).replace('{S}', str(SPORT[0])).encode()
            code = 200 if html else 404
        else:
            pid = (q.get('id') or [''])[0]
            if pid:
                LOADS[pid] = LOADS.get(pid, 0) + 1
            delay = int((q.get('delay') or ['0'])[0])      # a slow player: its first load is still in flight when the repair starts
            if delay:
                time.sleep(delay / 1000.0)
            body, code = PLAYER.encode(), 200
        self.send_response(code)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(body)


def url(host, path):
    return f'http://{host}:{{P}}{path}'


def surl(host, path):
    return f'https://{host}:{{S}}{path}'


# ---------------------------------------------------------------------------------------------- scenarios
# frame spec: (dom id of the <iframe>, player id in its URL or None, expected 'open' | 'sandboxed', player loads
# expected, probe). 'open' = the player sees no sandbox. loads is exact when given as int, a max when a tuple (1, 2).
FRAMES = {}
BASELINE_OPEN = {'nested-stays-sandboxed'}   # the page does not sandbox that frame, so it is open before and after
NEEDS_HTTPS = {'cloudflare-turnstile'}
# DiagnosticLog.sanitize() in the app replaces the value of any field whose name matches this with "[redacted]"
REDACTED_KEY = re.compile(r'cookie|authorization|token|signature|password|secret|apikey|api_key|^hash$|^query$|^fragment$', re.I)


def add(name, host, html, frames, wait=2.0):
    SCENARIOS[name] = html
    FRAMES[name] = (host, frames, wait)


VID = url('vidsrc.test', '/embed/movie/1?id={id}')


def static_frame(dom, pid, attrs, src=None, size='width=640 height=360'):
    src = src or VID.replace('{id}', pid)
    return f'<iframe id={dom} src="{src}" {attrs} {size}></iframe>'


def page(body, script=''):
    # window.__loadFired: the repair must never keep the top document's load event from firing (a stuck load bar)
    return ('<!doctype html><title>site</title><body>'
            '<script>window.__loadFired=false;window.addEventListener("load",function(){window.__loadFired=true})</script>'
            f'{body}<script>{script}</script>')


# A frame that came in the markup (or innerHTML) is already loading sandboxed: one load with the sandbox, then one repair.
add('static', 'site.test', page(static_frame('f', 's1', 'sandbox="allow-scripts" allowfullscreen')),
    [('f', 's1', 'open', 2, None)])
add('static-slow-first-load', 'site.test',
    page(static_frame('f', 's6', 'sandbox="allow-scripts" allowfullscreen', url('vidsrc.test', '/embed/movie/1?delay=1500&id=s6'))),
    [('f', 's6', 'open', 2, None)], wait=4.0)
add('static-popup-probe', 'site.test',
    page(static_frame('f', 's2', 'sandbox="allow-scripts allow-same-origin allow-forms" allowfullscreen')),
    [('f', 's2', 'open', 2, 'popup')])
add('large-unknown-no-fullscreen', 'site.test',
    page(static_frame('f', 's3', 'sandbox="allow-scripts"', url('cdn7.test', '/9fa2c?id=s3'), 'width=700 height=500')),
    [('f', 's3', 'sandboxed', 1, None)], wait=4.0)
add('large-unknown-fullscreen', 'site.test',
    page(static_frame('f', 's4', 'sandbox="allow-scripts" allowfullscreen', url('cdn7.test', '/9fa2c?id=s4'), 'width=700 height=500')),
    [('f', 's4', 'open', 2, None)])
add('allow-attr-fullscreen', 'site.test',
    page(static_frame('f', 's5', 'sandbox="allow-scripts" allow="autoplay; fullscreen"', url('cdn8.test', '/q/77?id=s5'), 'width=700 height=500')),
    [('f', 's5', 'open', 2, None)])

CREATE = ("var f=document.createElement('iframe');{body};f.width=640;f.height=360;f.id='f';document.body.appendChild(f);")
add('create-src-first', 'site.test', page('', CREATE.format(body=(
    "f.setAttribute('src','" + VID.replace('{id}', 'c1') + "');f.setAttribute('allowfullscreen','');"
    "f.setAttribute('sandbox','allow-scripts')"))), [('f', 'c1', 'open', 1, None)])
add('create-sandbox-first', 'site.test', page('', CREATE.format(body=(
    "f.setAttribute('sandbox','allow-scripts');f.setAttribute('src','" + VID.replace('{id}', 'c2') + "')"))),
    [('f', 'c2', 'open', 1, None)])
add('create-prop-src-first', 'site.test', page('', CREATE.format(body=(
    "f.src='" + VID.replace('{id}', 'c3') + "';f.sandbox='allow-scripts'"))), [('f', 'c3', 'open', 1, None)])
add('create-prop-sandbox-first', 'site.test', page('', CREATE.format(body=(
    "f.sandbox='allow-scripts';f.src='" + VID.replace('{id}', 'c4') + "'"))), [('f', 'c4', 'open', 1, None)])
# DOMTokenList mutations are not hooked: the frame loads sandboxed once and is repaired by the timer.
add('create-token-add', 'site.test', page('', CREATE.format(body=(
    "f.src='" + VID.replace('{id}', 'c5') + "';f.sandbox.add('allow-scripts')"))), [('f', 'c5', 'open', 2, None)])
add('react-order', 'site.test', page('', (
    "var p=[['class','player'],['src','" + VID.replace('{id}', 'r1') + "'],['allowfullscreen','true'],"
    "['allow','autoplay'],['sandbox','allow-scripts allow-same-origin'],['frameborder','0']];"
    "var f=document.createElement('iframe');for(var i=0;i<p.length;i++)f.setAttribute(p[i][0],p[i][1]);"
    "f.width=640;f.height=360;f.id='f';document.body.appendChild(f);")), [('f', 'r1', 'open', 1, 'popup')])
add('innerHTML', 'site.test', page('<div id=d></div>', (
    "document.getElementById('d').innerHTML='" + static_frame('f', 'i1', 'sandbox="allow-scripts" allowfullscreen').replace("'", "\\'") + "'")),
    [('f', 'i1', 'open', 2, None)])
add('insertAdjacentHTML', 'site.test', page('<div id=d></div>', (
    "document.getElementById('d').insertAdjacentHTML('beforeend','" + static_frame('f', 'i2', 'sandbox="allow-scripts" allowfullscreen').replace("'", "\\'") + "')")),
    [('f', 'i2', 'open', 2, None)])
add('late-spa-insert', 'site.test', page('<div id=d></div>', (
    "setTimeout(function(){document.getElementById('d').innerHTML='" + static_frame('f', 'l1', 'sandbox="allow-scripts" allowfullscreen').replace("'", "\\'") + "'},6000)")),
    [('f', 'l1', 'open', 2, None)], wait=11.0)
add('nested-stays-sandboxed', 'site.test',
    page(static_frame('f', 'n1', 'allowfullscreen', url('vidsrc.test', '/embed/movie/2?id=n1&nest=1'))),
    [('f', 'n1', 'open', 1, None)])
add('ad-300x250', 'site.test', page(static_frame('f', 'a1', 'sandbox="allow-scripts"', url('ads.test', '/embed/banner?id=a1'), 'width=300 height=250')),
    [('f', 'a1', 'sandboxed', 1, None)])
add('ad-728x90', 'site.test', page(static_frame('f', 'a2', 'sandbox="allow-scripts"', url('ads.test', '/embed/banner?id=a2'), 'width=728 height=90')),
    [('f', 'a2', 'sandboxed', 1, None)])
add('ad-970x250-fullscreen-hint', 'site.test', page(static_frame('f', 'a3', 'sandbox="allow-scripts" allowfullscreen', url('ads.test', '/embed/banner?id=a3'), 'width=970 height=250')),
    [('f', 'a3', 'sandboxed', 1, None)])
add('tracker-1x1', 'site.test', page(static_frame('f', 'a4', 'sandbox="allow-scripts"', url('track.test', '/embed/px?id=a4'), 'width=1 height=1')),
    [('f', 'a4', 'sandboxed', 1, None)])
# The hooks cannot measure a frame that is not laid out yet. When the page has already given the size they go by it...
add('create-ad-sized-first', 'site.test', page('', (
    "var f=document.createElement('iframe');f.width=300;f.height=250;f.id='f';"
    "f.setAttribute('sandbox','allow-scripts');f.setAttribute('src','" + url('ads.test', '/embed/banner?id=z1') + "');"
    "document.body.appendChild(f);")), [('f', 'z1', 'sandboxed', 1, None)])
# ... and a URL that only has a player-ish word in it (video, watch ...) is judged by the timer, which measures:
# an ad slot stays sandboxed, a player-sized frame is repaired (one more load).
add('create-broad-url-ad-slot', 'site.test', page('', (
    "var f=document.createElement('iframe');f.setAttribute('sandbox','allow-scripts');"
    "f.setAttribute('src','" + url('ads.test', '/video/banner?id=z2') + "');f.id='f';"
    "document.body.appendChild(f);f.style.width='300px';f.style.height='250px';")), [('f', 'z2', 'sandboxed', 1, None)])
add('create-broad-url-player', 'site.test', page('', (
    "var f=document.createElement('iframe');f.setAttribute('sandbox','allow-scripts');"
    "f.setAttribute('src','" + url('player.test', '/watch/9?id=z3') + "');f.id='f';f.width=640;f.height=360;"
    "document.body.appendChild(f);")), [('f', 'z3', 'open', 2, None)])
# A hidden frame (0 x 0) cannot be measured: a player-ish word alone is not enough, a player URL still is.
add('hidden-broad-url-stays', 'site.test', page(
    '<iframe id=f src="' + url('ads.test', '/video/track?id=z4') + '" sandbox="allow-scripts" style="display:none"></iframe>'),
    [('f', 'z4', 'sandboxed', 1, None)], wait=4.0)
add('hidden-strong-url-repaired', 'site.test', page(
    '<iframe id=f src="' + url('vidsrc.test', '/embed/movie/3?id=z5') + '" sandbox="allow-scripts" style="display:none"></iframe>'),
    [('f', 'z5', 'open', 2, None)])
add('same-site', 'site.test', page(static_frame('f', 'm1', 'sandbox="allow-scripts" allowfullscreen', url('site.test', '/embed/1?id=m1'))),
    [('f', 'm1', 'sandboxed', 1, None)])
add('sibling-subdomain', 'a.site.test', page(static_frame('f', 'm2', 'sandbox="allow-scripts" allowfullscreen', url('b.site.test', '/embed/1?id=m2'))),
    [('f', 'm2', 'sandboxed', 1, None)])
add('cloudflare-turnstile', 'site.test', page(
    '<iframe id=f src="' + surl('challenges.cloudflare.com', '/cdn-cgi/challenge-platform/h/g/turnstile/if/ov2/av0/rcv/1/light/new/normal/auto/?id=w1') +
    '" sandbox="allow-scripts" allow="cross-origin-isolated; fullscreen; autoplay" style="width:300px;height:65px"></iframe>'),
    [('f', 'w1', 'sandboxed', 1, None)])
add('captcha-recaptcha-host', 'site.test', page(static_frame('f', 'w2', 'sandbox="allow-scripts" allowfullscreen', url('www.google.com', '/recaptcha/api2/anchor?id=w2'), 'width=700 height=500')),
    [('f', 'w2', 'sandboxed', 1, None)])
add('srcdoc-and-blank', 'site.test', page(
    '<iframe id=d1 srcdoc="<b>hi</b>" sandbox="allow-scripts" allowfullscreen width=640 height=360></iframe>'
    '<iframe id=d2 src="about:blank" sandbox="allow-scripts" allowfullscreen width=640 height=360></iframe>'),
    [('d1', None, 'attr', None, None), ('d2', None, 'attr', None, None)])
add('flixer-untouched', 'www.flixer.gd', page(static_frame('f', 'x1', 'sandbox="allow-scripts" allowfullscreen')),
    [('f', 'x1', 'sandboxed', 1, None)])
# StreamCorner keeps its own older handler: it takes the attribute off at 500 ms but does not reload, so a frame that
# was already loading stays sandboxed there. Recorded here so a change in that behaviour is noticed; not changed.
add('streamcorner-own-handler', 'streamcorner.st', page(static_frame('f', 'x2', 'sandbox="allow-scripts" allowfullscreen')),
    [('f', 'x2', 'latched', 1, None)])
# mirrors rotate apexes: any streamcorner.* site keeps the same handler as the main site
add('streamcorner-mirror-own-handler', 'streamcorner.xyz', page(static_frame('f', 'x3', 'sandbox="allow-scripts" allowfullscreen')),
    [('f', 'x3', 'latched', 1, None)])
# Not a check of the fix: what a window.open() probe sees inside the app (popup_guard.js is injected in every frame).
add('guard-popup-probe-no-sandbox', 'site.test', page(static_frame('f', 'g1', 'allowfullscreen')),
    [('f', 'g1', 'info', None, 'popup')])

# many frames added one after another: reloads are limited so a page that keeps re-adding frames cannot loop
RC_FRAME = '<iframe src="' + VID.replace('{id}', 'k') + '" sandbox="allow-scripts" allowfullscreen width=400 height=300></iframe>'
add('reload-cap', 'site.test', page('<div id=d></div>', (
    "var n=0,h=" + json.dumps(RC_FRAME) + ";var t=setInterval(function(){var d=document.createElement('div');"
    "d.innerHTML=h.replace('?id=k','?id=k'+n);"
    "document.getElementById('d').appendChild(d);if(++n>=12)clearInterval(t)},150)")),
    [], wait=4.0)

PERF_FRAMES = ''.join(
    f'<iframe src="{url("site.test", "/embed/x?id=pf" + str(i))}" sandbox="allow-scripts" width=400 height=300></iframe>' for i in range(30)
) + ''.join(
    f'<iframe src="{url("ads%d.test" % (i % 5), "/slot?id=pa" + str(i))}" sandbox="allow-scripts" width=300 height=250></iframe>' for i in range(20)
) + ''.join(
    f'<iframe src="{url("plain.test", "/embed/y?id=pp" + str(i))}" width=200 height=100></iframe>' for i in range(10)
)
add('perf-many-frames', 'site.test', page(PERF_FRAMES), [], wait=6.0)


# ---------------------------------------------------------------------------------------------- browser side
BRIDGE = """
(function () {
  var DIAG = %s, events = [];
  window.__diag = events;
  window.Buccaneer = {
    isDiagnosticsEnabled: function () { return DIAG; },
    diagnosticEvent: function (name, json) { try { events.push([name, JSON.parse(json)]); } catch (e) {} },
    isGuardEnabled: function () { return true; },
    blocked: function () {}
  };
  // time the helper's own timer callbacks (named `tick`)
  window.__tickMs = [];
  var st = window.setTimeout;
  window.setTimeout = function (fn, ms) {
    if (typeof fn !== 'function' || fn.name !== 'tick') return st.apply(window, arguments);
    var args = Array.prototype.slice.call(arguments, 2);
    return st.call(window, function () {
      var t = performance.now();
      try { return fn.apply(this, args); } finally { window.__tickMs.push(performance.now() - t); }
    }, ms);
  };
})();
"""


def find_frame(page, pid):
    for fr in page.frames:
        if f'id={pid}' in fr.url.split('?', 1)[-1].split('&'):
            return fr
        if ('?id=' + pid) in fr.url or ('&id=' + pid) in fr.url:
            return fr
    return None


def observe(page, frame_spec):
    """('open' | 'sandboxed' | 'attr' | 'unknown', has_sandbox_attr, text)"""
    dom, pid, expect, loads, probe = frame_spec
    has_attr = page.evaluate("(id) => { var f = document.getElementById(id); return !!f && f.hasAttribute('sandbox'); }", dom)
    if expect == 'attr':
        return 'attr', has_attr, ''
    if expect == 'info':
        has_attr = None
    fr = find_frame(page, pid)
    if fr is None:
        return 'unknown', has_attr, ''
    try:
        if probe == 'popup':
            fr.click('#b', timeout=1500)
            time.sleep(0.15)
            seen = fr.evaluate('window.__state.popup')
            kind = 'sandboxed' if seen == 'blocked' else 'open'
        else:
            kind = 'sandboxed' if fr.evaluate('window.__state.origin') == 'null' else 'open'
        return kind, has_attr, fr.inner_text('#msg', timeout=1500)
    except Exception:
        return 'unknown', has_attr, ''


def run_scenario(browser, name, helpers, popup_guard, diag=False):
    host, frames, wait = FRAMES[name]
    ctx = browser.new_context()
    ctx.add_init_script(BRIDGE % ('true' if diag else 'false'))
    if popup_guard:
        ctx.add_init_script(path=str(ASSETS / 'popup_guard.js'))
    ctx.add_init_script(path=str(helpers))
    page = ctx.new_page()
    # popups the players open for the window.open() probe: close them again
    ctx.on('page', lambda p: p.close() if p != page else None)
    ids = [f[1] for f in frames if f[1]]
    for i in ids:
        LOADS.pop(i, None)
    for k in [k for k in LOADS if k.startswith(('k', 'pf', 'pa', 'pp'))]:
        LOADS.pop(k)
    page.goto(f'http://{host}:{PORT[0]}/s/{name}')
    page.wait_for_load_state('load')
    hist0 = page.evaluate('history.length')
    results = []
    deadline = time.time() + wait + 3.0
    for spec in frames:
        dom, pid, expect, loads, probe = spec
        got = ('unknown', None, '')
        if expect == 'info':
            time.sleep(1.0)
            got = observe(page, spec)
        elif expect == 'open':
            while time.time() < deadline:
                got = observe(page, spec)
                if got[0] == 'open':
                    break
                time.sleep(0.2)
        else:
            time.sleep(wait if name != 'late-spa-insert' else 0)
            got = observe(page, spec)
        results.append((spec, got))
    if not frames:
        time.sleep(wait)   # scenarios that watch the page as a whole (reload cap, timer cost) get their full time
    time.sleep(0.2)
    extra = {
        'hist0': hist0,
        'hist1': page.evaluate('history.length'),
        'load_fired': page.evaluate('window.__loadFired === true'),
        'relax_installed': page.evaluate('window.__fbSandboxRelaxInstalled === true'),
        'top_hook': page.evaluate("Object.prototype.hasOwnProperty.call(HTMLIFrameElement.prototype, 'setAttribute')"),
        'loads': {i: LOADS.get(i, 0) for i in ids},
        'diag': page.evaluate('window.__diag.map(function(e){return [e[0], e[1]]})') if diag else [],
        'ticks': page.evaluate('window.__tickMs.slice()'),
        'url': page.url,
    }
    if name == 'nested-stays-sandboxed':
        n2 = find_frame(page, 'n2')
        extra['nested_origin'] = n2.evaluate('window.__state.origin') if n2 else 'missing'
        extra['nested_hook'] = n2.evaluate("Object.prototype.hasOwnProperty.call(HTMLIFrameElement.prototype, 'setAttribute')") if n2 else None
        extra['nested_diag'] = n2.evaluate('window.__diag.map(function(e){return e[0]})') if (n2 and diag) else []
    if name == 'reload-cap':
        extra['total_loads'] = sum(v for k, v in LOADS.items() if k.startswith('k'))
    if name == 'perf-many-frames':
        extra['total_loads'] = sum(v for k, v in LOADS.items() if k.startswith(('pf', 'pa', 'pp')))
    ctx.close()
    return results, extra


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--baseline', metavar='FILE', help='an older page_helpers.js: expect the sandbox error to remain')
    ap.add_argument('--helpers', metavar='FILE', help='test this page_helpers.js instead of the repo copy and expect the fix '
                    '(for trying a modified copy: a copy with one rule removed should fail its scenario)')
    ap.add_argument('--only', metavar='NAME', action='append')
    ap.add_argument('--popup-guard', action='store_true',
                    help='inject popup_guard.js into every frame, as the app does (its window.open() returns null, so '
                         'the popup probes in this test then fail with or without a sandbox)')
    args = ap.parse_args()
    helpers = pathlib.Path(args.baseline or args.helpers) if (args.baseline or args.helpers) else ASSETS / 'page_helpers.js'
    baseline = bool(args.baseline)
    chromium = shutil.which('chromium')
    if not chromium:
        print('SKIP: no chromium on PATH')
        return 0

    srv = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    PORT[0] = srv.server_address[1]
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    tmp = tempfile.mkdtemp(prefix='sbx-tls-')
    have_tls = False
    try:
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '2', '-subj', '/CN=localhost',
                        '-keyout', tmp + '/k.pem', '-out', tmp + '/c.pem'], check=True, capture_output=True)
        tls = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        ctx_tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx_tls.load_cert_chain(tmp + '/c.pem', tmp + '/k.pem')
        tls.socket = ctx_tls.wrap_socket(tls.socket, server_side=True)
        SPORT[0] = tls.server_address[1]
        threading.Thread(target=tls.serve_forever, daemon=True).start()
        have_tls = True
    except Exception as e:  # no openssl: the https-only scenario is skipped
        print(f'note: no https server ({e.__class__.__name__}); skipping {sorted(NEEDS_HTTPS)}')

    failures = []

    def check(label, ok, detail=''):
        print(('  ok   ' if ok else '  FAIL ') + label + (f'   [{detail}]' if detail and not ok else ''))
        if not ok:
            failures.append(label)

    with sync_playwright() as pw:
        browser = pw.chromium.launch(executable_path=chromium, headless=True,
                                     args=['--no-sandbox', '--disable-gpu', '--ignore-certificate-errors', '--no-proxy-server',
                                           '--host-resolver-rules=MAP * 127.0.0.1'])
        print(f'helpers: {helpers}  ({"baseline: the error is expected to remain" if baseline else "fixed: the error is expected to go"})')
        for name in FRAMES:
            if args.only and name not in args.only:
                continue
            if name in NEEDS_HTTPS and not have_tls:
                continue
            print(f'[{name}]')
            # the app injects popup_guard.js into every frame; only the info scenario (and --popup-guard) does too
            guard = args.popup_guard or name.startswith('guard-')
            results, extra = run_scenario(browser, name, helpers, guard)
            check('top document load event fired (the repair does not stall loading)', extra['load_fired'])
            for spec, got in results:
                dom, pid, expect, loads, probe = spec
                kind, has_attr, text = got
                if expect == 'attr':
                    check(f'{dom}: sandbox attribute kept (srcdoc / about:blank)', has_attr)
                    continue
                if expect == 'info':
                    print(f'  info a window.open() probe inside the app\'s popup guard, in a frame with NO sandbox, sees: '
                          f'{"popups blocked" if kind == "sandboxed" else "popups allowed"}  ({text!r})')
                    continue
                want = expect if (baseline and name in BASELINE_OPEN) else ('sandboxed' if baseline else expect)
                if expect == 'latched':
                    want = 'sandboxed'
                check(f'{dom}: player sees {want}', kind == want, f'{kind}; attr={has_attr}; {text!r}')
                if not baseline and expect == 'open':
                    check(f'{dom}: player shows PLAYING, not the sandbox error', text == 'PLAYING', text)
                    check(f'{dom}: sandbox attribute is gone', not has_attr)
                if not baseline and expect == 'sandboxed':
                    check(f'{dom}: sandbox attribute untouched', has_attr)
                if not baseline and expect == 'latched':
                    check(f'{dom}: its own older handler took the attribute off without a reload (unchanged)', not has_attr)
                n = extra['loads'].get(pid, 0)
                if not baseline and loads is not None:
                    ok = (n == loads) if isinstance(loads, int) else (loads[0] <= n <= loads[1])
                    check(f'{dom}: player document requested {loads} time(s)', ok, str(n))
            if name in ('static', 'static-popup-probe', 'innerHTML') and not baseline:
                check('history length unchanged by the repair (Back is not polluted)', extra['hist1'] == extra['hist0'], f"{extra['hist0']}->{extra['hist1']}")
            if name in ('flixer-untouched', 'streamcorner-own-handler', 'streamcorner-mirror-own-handler'):
                check('relaxer not installed on this host', not extra['relax_installed'] and not extra['top_hook'])
            elif not baseline:
                check('relaxer installed in the top document', extra['relax_installed'] and extra['top_hook'])
            if name == 'nested-stays-sandboxed':
                check('frame inside the player stays sandboxed', extra['nested_origin'] == 'null', extra['nested_origin'])
                if not baseline:
                    check('subframes get no hooks', extra['nested_hook'] is False, str(extra['nested_hook']))
            if name == 'reload-cap' and not baseline:
                check('reloads are capped (12 frames added, at most 8 reloaded)', 16 <= extra['total_loads'] <= 20, str(extra['total_loads']))
            if name == 'perf-many-frames' and not baseline:
                t = extra['ticks']
                mx = max(t) if t else 0
                mean = sum(t) / len(t) if t else 0
                print(f'       timer: {len(t)} ticks, mean {mean:.2f} ms, max {mx:.2f} ms (60 frames, 50 sandboxed)')
                check('timer callbacks stay cheap (max < 60 ms with 50 sandboxed frames)', bool(t) and mx < 60, f'{mx:.1f}')

        if not baseline and not args.only:
            print('[diagnostic events (diagnostics ON)]')
            for name in ('static', 'same-site', 'nested-stays-sandboxed', 'large-unknown-no-fullscreen'):
                results, extra = run_scenario(browser, name, helpers, True, diag=True)
                names = [e[0] for e in extra['diag']]
                bad = sorted({k for e in extra['diag'] for k in e[1] if REDACTED_KEY.search(k)})
                check(f'{name}: no logged field has a name the app\'s diagnostic log redacts', not bad, str(bad))
                if name == 'static':
                    ev = [e for e in extra['diag'] if e[0] == 'sandbox_relaxed']
                    check('static: sandbox_relaxed logged once with host, page, flags and the queued reload',
                          len(ev) == 1 and ev[0][1].get('host') == 'vidsrc.test' and ev[0][1].get('page') == 'site.test'
                          and ev[0][1].get('extra') == 'reload queued' and ev[0][1].get('flags') == 'allow-scripts', str(ev))
                    rl = [e for e in extra['diag'] if e[0] == 'sandbox_reloaded']
                    check('static: sandbox_reloaded logged once', len(rl) == 1 and rl[0][1].get('host') == 'vidsrc.test', str(rl))
                if name == 'same-site':
                    check('same-site: sandbox_kept logged with its reason', any(e[0] == 'sandbox_kept' and e[1].get('via') == 'same-site' for e in extra['diag']), str(names))
                if name == 'nested-stays-sandboxed':
                    check('nested: the sandboxed subframe reports frame_sandboxed', 'frame_sandboxed' in extra['nested_diag'], str(extra['nested_diag']))
                if name == 'large-unknown-no-fullscreen':
                    um = [e for e in extra['diag'] if e[0] == 'sandbox_unmatched']
                    check('unmatched: a sandboxed frame that never looked like a player is reported with its size',
                          len(um) == 1 and re.match(r'^7\d\dx5\d\d$', str(um[0][1].get('extra'))) is not None, str(um))
            # the same fix with the app's popup guard injected, as in the app (origin-based players are unaffected by it)
            results, extra = run_scenario(browser, 'static', helpers, True)
            check('static with popup_guard.js injected behaves the same', results[0][1][0] == 'open', str(results[0][1]))
            results, extra = run_scenario(browser, 'create-src-first', helpers, True)
            check('create-src-first with popup_guard.js injected behaves the same', results[0][1][0] == 'open', str(results[0][1]))

        browser.close()
    srv.shutdown()
    print()
    if failures:
        print(f'FAILED {len(failures)} check(s):')
        for f in failures:
            print('  -', f)
        return 1
    print('PASS: sandbox relaxer checks.' if not baseline else 'PASS: the baseline shows the sandbox error everywhere (the bug reproduces).')
    return 0


if __name__ == '__main__':
    sys.exit(main())
