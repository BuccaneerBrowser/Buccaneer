from pathlib import Path
import subprocess,shutil,re,json,html
fixture=Path(__file__).with_name('diagnostic-safety-fixture.html').resolve()
chromium=shutil.which('chromium') or shutil.which('chromium-browser') or shutil.which('google-chrome')
if not chromium: raise SystemExit('Install Chromium or Chrome and put it in PATH.')
result=subprocess.run([chromium,'--headless','--no-sandbox','--disable-gpu','--allow-file-access-from-files','--window-size=1280,720','--virtual-time-budget=5000','--dump-dom',fixture.as_uri()],capture_output=True,text=True,timeout=60)
match=re.search(r'<pre id="results">(.*?)</pre>',result.stdout,re.S)
if not match: raise SystemExit('No DOM test results: '+result.stderr[-600:])
checks=json.loads(html.unescape(match.group(1)))
for check in checks: print(('PASS: ' if check.get('pass') else 'FAIL: ')+str(check.get('name',check)))
assert len(checks)>=5 and all(c.get('pass') for c in checks), checks
