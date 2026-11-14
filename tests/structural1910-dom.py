from pathlib import Path
import json,shutil
from playwright.sync_api import sync_playwright
r=Path(__file__).resolve().parent.parent
out=r/'review1910';out.mkdir(exist_ok=True)
checks=[]
def check(name,value,detail=None):
 checks.append({'name':name,'pass':bool(value),'detail':detail});print(('PASS: ' if value else 'FAIL: ')+name+((' '+str(detail)) if detail else ''),flush=True)
with sync_playwright() as pw:
 b=pw.chromium.launch(executable_path=shutil.which('chromium'),headless=True,args=['--no-sandbox','--disable-gpu'])
 try:
  for w,h in [(960,540),(1280,720),(1920,1080)]:
   p=b.new_page(viewport={'width':w,'height':h})
   p.set_content('<style>body{margin:0;height:2000px}#m{position:fixed;top:-100px;left:100px;width:250px;overflow:auto}button{display:block;height:60px;width:100%}</style><div id=m role=menu>'+''.join('<button>Season '+str(i)+'</button>' for i in range(25))+'</div>')
   p.evaluate((r/'app/src/main/assets/tv_ui.js').read_text());p.evaluate('__tvUi.fitMenus()')
   result=p.evaluate('''()=>{let trace=[];for(let i=0;i<70;i++){let r=m.getBoundingClientRect();__tvUi.scrollAt(r.left+30,r.top+30,0,40);trace.push(m.scrollTop)}return {top:m.scrollTop,max:m.scrollHeight-m.clientHeight,trace:trace.slice(0,5)}}''')
   check('Repeated small remote scroll steps reach the bottom of a fitted menu',result['top']==result['max'] and result['max']>0,result)
   before=p.locator('#m').evaluate('e=>e.scrollTop');p.evaluate('__tvUi.fitMenus()');check('Passive refitting preserves existing scroll position',p.locator('#m').evaluate('e=>e.scrollTop')==before)
   result=p.evaluate('''()=>{for(let i=0;i<70;i++){const r=m.getBoundingClientRect();__tvUi.scrollAt(r.left+30,r.top+30,0,-40)}return m.scrollTop}''')
   check(f'{w}x{h}: repeated upward steps return to the first menu item',result==0)
   p.close()
  p=b.new_page(viewport={'width':960,'height':540})
  p.set_content('<div contenteditable=true id=rich>abc</div>');p.evaluate("window.Buccaneer={currentDocument:()=> 'doc',activeInputFrame:()=>window.field?.frame||'',editableFocused:(f,s)=>window.field=JSON.parse(s),editableBlurred:()=>{}}")
  p.evaluate((r/'app/src/main/assets/tv_ui.js').read_text());p.locator('#rich').click();p.evaluate('__tvUi.reportField();document.execCommand=()=>false')
  result=p.evaluate("JSON.parse(__tvUi.edit('insert','XYZ',field.frame,field.field,0))")
  check('Rejected contenteditable command is not reported applied',result['status']!='applied' and p.locator('#rich').inner_text()=='abc',result['status'])
  p.close()
 finally:b.close()
(out/'structural-dom-results.json').write_text(json.dumps(checks,indent=2)+'\n')
assert all(x['pass'] for x in checks)
