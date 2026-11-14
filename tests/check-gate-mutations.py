"""Negative controls: prove the release gates reject unsafe/drifting fixtures, not just pass HEAD."""
from pathlib import Path
import os,shutil,subprocess,tempfile
ROOT=Path(__file__).resolve().parent.parent
CASES=[
 ('input/media hidden dependency','app/src/main/java/com/buccaneer/browser/EditorController.kt',lambda s:s+'\nprivate val forbidden: MediaController?=null\n','check-architecture.py'),
 ('second input writer','app/src/main/java/com/buccaneer/browser/MainActivity.kt',lambda s:s+'\nprivate var inputFrame="bad"\n','check-architecture.py'),
 ('store/application cycle','app/src/main/java/com/buccaneer/browser/BookmarkStore.kt',lambda s:s+'\nprivate val upward: BuccaneerApplication?=null\n','check-architecture.py'),
 ('ABI return type','tests/bridge-contract.json',lambda s:s.replace('"returns": "String"','"returns": "Boolean"',1),'check-architecture.py'),
 ('unknown JS method alias','app/src/main/assets/page_helpers.js',lambda s:s.replace('b.lowQualityEnabled','b.nonexistent',1),'check-bridge.js'),
 ('native JS arity','app/src/main/assets/tv_ui.js',lambda s:s.replace('window.Buccaneer.currentDocument()','window.Buccaneer.currentDocument(123)',1),'check-bridge.js'),
 ('protected darkening strategy','app/src/main/java/com/buccaneer/browser/BrowserSettings.kt',lambda s:s.replace('DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING','DARK_STRATEGY_USER_AGENT_DARKENING_ONLY'),'check-protected-fixes.py'),
 ('protected popup rule','app/src/main/assets/popup_guard.js',lambda s:s.replace('fa > playerArea','fa < playerArea',1),'check-protected-fixes.py'),
 ('new video guard GPU work','app/src/main/assets/video_layer_guard.js',lambda s:s+'\nwindow.requestAnimationFrame(function(){});\n','check-architecture.py'),
 ('wrong release version','app/build.gradle.kts',lambda s:s.replace('versionCode = 30','versionCode = 29'),'check-architecture.py'),
]
def main():
 scratch_root=Path(os.environ.get('TMPDIR',ROOT/'untangle-v237'));scratch_root.mkdir(parents=True,exist_ok=True)
 with tempfile.TemporaryDirectory(prefix='buccaneer-gate-mutations-',dir=scratch_root) as scratch:
  dest=Path(scratch)
  shutil.copytree(ROOT/'app/src',dest/'app/src');shutil.copyfile(ROOT/'app/build.gradle.kts',dest/'app/build.gradle.kts')
  (dest/'tests').mkdir()
  for file in (ROOT/'tests').iterdir():
   if file.is_file():shutil.copyfile(file,dest/'tests'/file.name)
  (dest/'tests/node_modules').symlink_to(ROOT/'tests/node_modules',target_is_directory=True)
  passed=0
  for label,file,mutate,gate in CASES:
   target=dest/file;before=target.read_text();after=mutate(before);assert after!=before,(label,'fixture mutation did not apply')
   target.write_text(after)
   try:
    cmd=['node' if gate.endswith('.js') else 'python3',str(dest/'tests'/gate)]
    result=subprocess.run(cmd,capture_output=True,text=True,cwd=dest)
    assert result.returncode!=0,(label,'gate accepted bad fixture',result.stdout,result.stderr)
    print('REJECTED:',label);passed+=1
   finally:target.write_text(before)
  print(f'PASS: {passed} negative controls rejected; actual source was never modified')
if __name__=='__main__':main()
