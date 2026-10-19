from pathlib import Path
import hashlib, json
root = Path(__file__).resolve().parent.parent
for check in json.loads((root / 'tests/protected-fixes.json').read_text()):
    data = (root / check['path']).read_bytes()
    if check['path'].endswith('page_helpers.js'):
        continue # Verified against the exact shipped1.9.8 baseline below.
    if check['path'].endswith('MainActivity.kt'):
        # 1.9.2 removes an unused private argument; the two calls keep identical behavior.
        assert data.count(b'injectDarkMode(view)') == 2, 'Unexpected dark cleanup call drift'
        data = data.replace(b'injectDarkMode(view)', b'injectDarkMode(view, url)')
    if 'after' in check or 'before' in check:
        text = data.decode()
        if 'after' in check: text = text[text.index(check['after']):]
        if 'before' in check: text = text[:text.index(check['before'])]
        data = text.encode()
    assert hashlib.sha256(data).hexdigest() == check['sha256'], check['path']
print('PASS: original Flixer intro/image guard, Popup Guard/Coreflix click workarounds, documented passive API normalization, native Flixer guards, and dark mode preserved.')

baseline=json.loads((root/'tests/repair199-baseline.json').read_text())
helper=(root/'app/src/main/assets/page_helpers.js').read_text()
assert helper.count(baseline['qualityAfter'])==1
helper=helper.replace(baseline['qualityAfter'],baseline['qualityBefore'],1)
assert hashlib.sha256(helper.encode()).hexdigest()==baseline['helperSha256'], 'Unexpected helper change outside startup preference repair'
for name,digest in baseline['exactAssets'].items():
 assert hashlib.sha256((root/'app/src/main/assets'/name).read_bytes()).hexdigest()==digest,name
ui=(root/'app/src/main/assets/tv_ui.js').read_text()
assert "if (/(^|\\.)flixer\\.(gd|su)$/.test(host)) return;" in ui
print('PASS: exact shipped1.9.8 helper preserved except enumerated startup preference; Flixer skip, Popup Guard/Coreflix and FMHY theme unchanged.')

cinejoy=(root/'app/src/main/assets/cinejoy_quality.js').read_text()
assert cinejoy.count(baseline['cinejoyAfter'])==1
assert hashlib.sha256(cinejoy.replace(baseline['cinejoyAfter'],baseline['cinejoyBefore'],1).encode()).hexdigest()==baseline['cinejoySha256']
print('PASS: private-player adapter changed only by explicitly reviewed first-preference ownership repair.')
