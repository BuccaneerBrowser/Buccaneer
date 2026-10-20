from pathlib import Path
import re,hashlib,json,xml.etree.ElementTree as E
from PIL import Image
r=Path(__file__).resolve().parent.parent
baseline=json.loads((r/'tests/hardware-compatibility-baseline-1.9.1.json').read_text())
s=(r/'app/build.gradle.kts').read_text()
for k in ['minSdk','compileSdk','targetSdk']:assert re.search(r'\b'+k+r'\s*=\s*'+str(baseline[k])+r'\b',s),k
assert 'androidx.webkit:webkit:'+baseline['webkit'] in s
import runpy
runpy.run_path(str(r/'tests/check-protected-fixes.py'))
# Other protected media and theme scripts retain the original hardware baseline.
for name in ['flixer_image_guard.js','popup_guard.js','fmhy_theme.js','seek_click.js']:
 assert hashlib.sha256((r/'app/src/main/assets'/name).read_bytes()).hexdigest()==baseline['scriptSha256'][name],name
manifest=E.parse(r/'app/src/main/AndroidManifest.xml').getroot();a='{http://schemas.android.com/apk/res/android}';t='{http://schemas.android.com/tools}'
app=manifest.find('application');assert app.get(a+'name')=='.BuccaneerApplication'
assert app.get(a+'usesCleartextTraffic')=='true' and app.get(t+'targetApi')=='m'
assert {e.get(a+'name') for e in manifest.findall('uses-permission')}=={'android.permission.INTERNET','android.permission.WRITE_EXTERNAL_STORAGE'}
assert manifest.findall('uses-permission')[1].get(a+'maxSdkVersion')=='28'
for p in (r/'app/src/main/res').glob('mipmap-*/ic_launcher.png'):
 im=Image.open(p);assert im.mode=='RGBA' and im.getpixel((0,0))[3]==0 and im.getpixel((im.width//2,im.height//2))[3]==255
assert (r/'app/src/main/res/drawable-nodpi/banner.png').exists()
assert not (r/'app/src/main/res/drawable-xhdpi').exists()
config=E.parse(r/'app/lint.xml').getroot();assert [e.get('id') for e in config.findall('issue')]==['OldTargetApi','GradleDependency']
annotations=[]
for p in (r/'app/src/main/java').rglob('*.kt'):annotations+=re.findall(r'@SuppressLint\("([^"]+)"\)',p.read_text())
assert sorted(annotations)==sorted(['ApplySharedPref']*4+['SetJavaScriptEnabled']*2),annotations
assert 'disable' not in (r/'app/build.gradle.kts').read_text()
print('PASS: SDK22/34 compatibility configuration, target33, WebView1.9.0, unchanged protected scripts plus enumerated1.9.9 editor/menu and quality repairs, unchanged permissions, icons, banner and narrowly scoped exception inventory.')
