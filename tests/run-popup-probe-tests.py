#!/usr/bin/env python3
"""Real-browser test for the popup-probe stand-in in page_helpers.js (installPopupProbeDecoy).

Some embedded players refuse to play and print "Sandbox Not Allowed - Remove Sandbox from iFrame to Play". They
cannot read the sandbox attribute from another site; they ask whether a popup can be opened (window.open() of a blank
window, then check that a window came back). popup_guard.js makes that answer "no" on purpose, so inside the app
the player is told it is sandboxed whether or not it is. page_helpers.js answers that one question: in a frame, while
Popup Guard is ON, a call that asks for a new BLANK window gets an inert stand-in window. Nothing opens. With the guard
OFF the page gets whatever the browser says.

This test serves a site and a player frame from one local port (Chromium maps every host name to 127.0.0.1),
injects the real popup_guard.js and page_helpers.js into every frame the way the app does, and runs the probes
players run. Needs the `playwright` Python package and a `chromium` binary on PATH.

  python3 tests/run-popup-probe-tests.py                    # the repo's page_helpers.js, expects the stand-in
  python3 tests/run-popup-probe-tests.py --baseline FILE    # an older page_helpers.js, expects the old answer (null)
  python3 tests/run-popup-probe-tests.py --helpers FILE     # another page_helpers.js, expects the stand-in
"""
import argparse
import json
import pathlib
import re
import shutil
import sys
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'app' / 'src' / 'main' / 'assets'
PORT = [0]

# The player: probes run on load, and again on a click (a click is a user gesture, as for a real play button).
PLAYER = r"""<!doctype html><meta charset=utf-8><title>player</title>
<body><button id=go>play</button>
<script>
var R = {};
// Anything that is not a primitive is reported as the string 'object' so the result serialises cleanly.
function rec(k, f) {
  try { var v = f(); R[k] = (v === null || v === undefined || (typeof v !== 'object' && typeof v !== 'function')) ? v : 'object'; }
  catch (e) { R[k] = 'threw:' + e.name + ':' + e.message; }
}
function run() {
  rec('blank', function () { var w = window.open('about:blank'); var ok = !!w; if (w) w.close(); return ok; });
  rec('empty', function () { return !!window.open(''); });
  rec('noargs', function () { return !!window.open(); });
  rec('features', function () { return !!window.open('', '_blank', 'width=1,height=1,left=-1000,top=-1000'); });
  rec('closed-lifecycle', function () {
    var w = window.open('about:blank');
    if (!w) return 'null';
    var before = w.closed; w.close(); return before === false && w.closed === true;
  });
  rec('shape', function () { var w = window.open('about:blank'); return !!w && typeof w.focus === 'function' && typeof w.close === 'function' && w.outerWidth > 0; });
  rec('tolerates-use', function () {
    var w = window.open('about:blank');
    w.document.write('<h1>ad</h1>'); w.document.close();
    w.document.body.appendChild(w.document.createElement('div')).style.display = 'none';
    w.location.href = 'http://ads.example/x'; w.location = 'http://ads.example/y';
    w.focus(); w.blur(); w.postMessage('x', '*'); window.focus();
    w.document.title = 't'; w.name = 'n';
    return true;
  });
  rec('named', function () { return !!window.open('', 'probe-window'); });
  rec('doc-typeof', function () { var w = window.open('about:blank'); return w ? typeof w.document : 'null'; });
  // Without the guard these three would really navigate the frame / the page, so they only run while the guard script
  // is in the frame and the app says it is on.
  var guardOn = false;
  try { guardOn = window.__popupGuardInstalled === true && !!(window.Buccaneer && window.Buccaneer.isGuardEnabled()); } catch (e) {}
  if (guardOn) {
    rec('self-target', function () { return window.open('about:blank', '_self'); });
    rec('top-target', function () { return window.open('', '_top'); });
    rec('parent-target', function () { return window.open('', '_parent'); });
  }
  rec('noopener', function () { return window.open('about:blank', '_blank', 'noopener'); });
  rec('noreferrer-list', function () { return window.open('', '_blank', 'width=1,height=1,noreferrer'); });
  rec('other-features', function () { return !!window.open('', '_blank', 'width=1,height=1,left=-1000'); });
  rec('real-url', function () { return window.open('http://ads.example/popunder'); });
  rec('real-url-fallback', function () { var ran = false; var w = window.open('http://ads.example/popunder'); if (!w) ran = true; return ran; });
  rec('javascript-url', function () { return window.open('javascript:void(0)'); });
  window.__probe = R;
}
run();
document.getElementById('go').onclick = function () {
  var w = null; try { w = window.open('about:blank'); } catch (e) {}
  R.click = w ? (typeof w.closed === 'boolean' ? 'window' : 'other') : 'null';
  R.clickClosedIsFalse = w ? w.closed === false : null;
  try { R.clickTag = Object.prototype.toString.call(w); } catch (e) { R.clickTag = 'threw'; }
  try { if (w) w.close(); } catch (e) {}
};
</script>"""

SITE = """<!doctype html><title>site</title><body>
<script>window.__top = {}; try { var w = window.open('about:blank'); window.__top.blank = w === null ? 'null' : 'window'; if (w) w.close(); } catch (e) { window.__top.blank = 'threw'; }</script>
%s"""


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *a):
        pass

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        if u.path.startswith('/s/'):
            body = (SITE % FRAMES.get(u.path[3:], '').replace('{P}', str(PORT[0]))).encode()
        else:
            body = PLAYER.encode()
        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(body)


FRAMES = {
    'plain': '<iframe id=f src="http://vidsrc.test:{P}/embed/1" width=640 height=360 allowfullscreen></iframe>',
    # Same site as the page, so the sandbox relaxer (a separate fix in page_helpers.js) leaves the sandbox alone and
    # the frame stays sandboxed for the whole test.
    'sandboxed': '<iframe id=f src="http://site.test:{P}/embed/1" sandbox="allow-scripts" width=640 height=360 allowfullscreen></iframe>',
}

BRIDGE = """
(function () {
  var guard = %s, events = [];
  window.__diag = events;
  window.Buccaneer = {
    isDiagnosticsEnabled: function () { return %s; },
    diagnosticEvent: function (n, j) { try { events.push([n, JSON.parse(j)]); } catch (e) {} },
    isGuardEnabled: function () { return guard; },
    blocked: function () {}
  };
})();
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--baseline', metavar='FILE', help='an older page_helpers.js: expect the old answer (null)')
    ap.add_argument('--helpers', metavar='FILE', help='test this page_helpers.js instead of the repo copy and expect the fix '
                    '(a modified copy with one rule removed should fail its check)')
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

    failures = []

    def check(label, ok, detail=''):
        print(('  ok   ' if ok else '  FAIL ') + label + (f'   [{detail}]' if detail and not ok else ''))
        if not ok:
            failures.append(label)

    def session(browser, host, scenario, guard=True, diag=False, guard_js=True):
        ctx = browser.new_context()
        ctx.add_init_script(BRIDGE % ('true' if guard else 'false', 'true' if diag else 'false'))
        if guard_js:
            ctx.add_init_script(path=str(ASSETS / 'popup_guard.js'))
        ctx.add_init_script(path=str(helpers))
        page = ctx.new_page()
        opened = []
        ctx.on('page', lambda p: opened.append(p) if p != page else None)
        page.goto(f'http://{host}:{PORT[0]}/s/{scenario}')
        page.wait_for_load_state('load')
        page.wait_for_timeout(500)
        return ctx, page, opened

    def player(page):
        for fr in page.frames:
            if fr != page.main_frame and '/embed/1' in fr.url:
                return fr
        return None

    want_window = not baseline
    with sync_playwright() as pw:
        browser = pw.chromium.launch(executable_path=chromium, headless=True,
                                     args=['--no-sandbox', '--disable-gpu', '--no-proxy-server',
                                           '--host-resolver-rules=MAP * 127.0.0.1'])
        print(f'helpers: {helpers}  ({"baseline: window.open() of a blank window returns null" if baseline else "fixed: a blank probe in a frame gets a stand-in window"})')

        print('[Popup Guard ON, player frame on another site]')
        ctx, page, opened = session(browser, 'site.test', 'plain')
        R = player(page).evaluate('window.__probe')
        for key in ('blank', 'empty', 'noargs', 'features'):
            check(f'window.open() blank probe "{key}" {"gets a window" if want_window else "returns null (the old answer)"}', R[key] is want_window, str(R[key]))
        if not baseline:
            check('stand-in: closed is false until the page closes it, then true', R['closed-lifecycle'] is True, str(R['closed-lifecycle']))
            check('stand-in: looks like a window (focus, close, size)', R['shape'] is True, str(R['shape']))
            check('stand-in: document.write, body.appendChild, location, focus, postMessage never throw', R['tolerates-use'] is True, str(R['tolerates-use']))
        check('a named blank window (window.open("", name)) is answered like any other blank probe', R['named'] is want_window, str(R['named']))
        if not baseline:
            check('the stand-in\'s document reports typeof "object", like a real window\'s', R['doc-typeof'] == 'object', str(R['doc-typeof']))
        for key, what in (('self-target', '_self'), ('top-target', '_top'), ('parent-target', '_parent')):
            check(f'a blank call aimed at {what} (not a new window) still gets null', R[key] is None, str(R[key]))
        check('a call with noopener still gets null (browsers answer that with null on purpose)', R['noopener'] is None, str(R['noopener']))
        check('a call with noreferrer in a feature list still gets null', R['noreferrer-list'] is None, str(R['noreferrer-list']))
        check('other window features do not stop the answer', R['other-features'] is want_window, str(R['other-features']))
        check('a real URL still gets null (callers keep their fallbacks)', R['real-url'] is None, str(R['real-url']))
        check('the `open(url) || fallback` pattern still runs its fallback', R['real-url-fallback'] is True, str(R['real-url-fallback']))
        check('a javascript: URL still gets null', R['javascript-url'] is None, str(R['javascript-url']))
        check('no window or tab was opened by any probe', len(opened) == 0, str(len(opened)))
        player(page).click('#go')
        page.wait_for_timeout(300)
        R2 = player(page).evaluate('window.__probe')
        check('a click-triggered blank probe gets the same answer', (R2.get('click') == 'window') is want_window, str(R2.get('click')))
        if not baseline:
            check('the click-triggered stand-in reports closed === false', R2.get('clickClosedIsFalse') is True, str(R2.get('clickClosedIsFalse')))
        check('still no window or tab opened after the click', len(opened) == 0, str(len(opened)))
        check('the top document keeps the old answer (null) for its own blank probe', page.evaluate('window.__top.blank') == 'null', page.evaluate('window.__top.blank'))
        check('the stand-in is not installed in the top document', page.evaluate('window.__fbPopupProbeDecoy !== true') or baseline)
        ctx.close()

        print('[Popup Guard ON, player frame sandboxed (opaque origin)]')
        ctx, page, opened = session(browser, 'site.test', 'sandboxed')
        fr = player(page)
        R = fr.evaluate('window.__probe')
        check(f'blank probe {"gets a window" if want_window else "returns null"} in a sandboxed frame too (when the helper runs there)',
              R['blank'] is want_window, str(R['blank']))
        ctx.close()

        print('[frames inside the poster-grid site keep what they had]')
        ctx, page, opened = session(browser, 'www.flixer.gd', 'plain')
        fr = player(page)
        R = fr.evaluate('window.__probe')
        check('blank probe still returns null under flixer.gd', R['blank'] is False, str(R['blank']))
        check('the stand-in is not installed there', fr.evaluate('window.__fbPopupProbeDecoy !== true'))
        ctx.close()

        print('[Popup Guard OFF, player frame sandboxed (no allow-popups): the browser\'s own null is passed through]')
        ctx, page, opened = session(browser, 'site.test', 'sandboxed', guard=False)
        fr = player(page)
        R = fr.evaluate('window.__probe')
        check('blank probe still returns null (the stand-in only answers while Popup Guard is ON)', R['blank'] is False, str(R['blank']))
        check('a real URL still gets null', R['real-url'] is None, str(R['real-url']))
        ctx.close()

        print('[Popup Guard OFF: the real answer is passed through untouched]')
        ctx, page, opened = session(browser, 'site.test', 'plain', guard=False)
        fr = player(page)
        fr.click('#go')
        page.wait_for_timeout(500)
        R2 = fr.evaluate('window.__probe')
        check('with the guard off a click really opens a window (Chromium) and the page gets that window',
              R2.get('click') == 'window' and len(opened) >= 1, f"{R2.get('click')} opened={len(opened)}")
        check('... and what the page holds is the real window, not the stand-in', R2.get('clickTag') == '[object Window]', str(R2.get('clickTag')))
        ctx.close()

        print('[the app says the guard is ON but popup_guard.js is not in the frame: a window the browser really opened is never replaced]')
        ctx, page, opened = session(browser, 'site.test', 'plain', guard_js=False)
        fr = player(page)
        fr.click('#go')
        page.wait_for_timeout(500)
        R2 = fr.evaluate('window.__probe')
        check('a click really opens a window and the page gets that real window', R2.get('click') == 'window' and len(opened) >= 1 and R2.get('clickTag') == '[object Window]',
              f"{R2.get('click')} opened={len(opened)} tag={R2.get('clickTag')}")
        ctx.close()

        if not baseline:
            print('[diagnostics]')
            ctx, page, opened = session(browser, 'site.test', 'plain', diag=True)
            fr = player(page)
            ev = fr.evaluate("window.__diag.filter(function (e) { return e[0] === 'popup_probe_answered'; })")
            check('popup_probe_answered is logged once per frame with the frame and page hosts',
                  len(ev) == 1 and ev[0][1].get('frame') == 'vidsrc.test' and ev[0][1].get('page') == 'site.test', str(ev))
            # DiagnosticLog.sanitize() in the app replaces the value of any field whose name matches this with "[redacted]"
            redacted = re.compile(r'cookie|authorization|token|signature|password|secret|apikey|api_key|^hash$|^query$|^fragment$', re.I)
            bad = sorted({k for e in ev for k in e[1] if redacted.search(k)})
            check("no logged field has a name the app's diagnostic log redacts", not bad, str(bad))
            ctx.close()
        browser.close()
    srv.shutdown()
    print()
    if failures:
        print(f'FAILED {len(failures)} check(s):')
        for f in failures:
            print('  -', f)
        return 1
    print('PASS: popup probe checks.' if not baseline else 'PASS: the baseline gives the old answer (null) everywhere (the bug reproduces).')
    return 0


if __name__ == '__main__':
    sys.exit(main())
