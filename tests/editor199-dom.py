from pathlib import Path
import shutil,json
from playwright.sync_api import sync_playwright
r=Path(__file__).resolve().parent.parent
source=(r/'app/src/main/assets/tv_ui.js').read_text()
bridge="window.reports=[];window.owner='';window.Buccaneer={currentDocument:()=> 'test-doc',activeInputFrame:()=>owner,editableFocused:(f,s)=>{owner=f;reports.push(JSON.parse(s))},editableBlurred:()=>{}};"
checks=[]
def check(name,value):
 checks.append({'name':name,'pass':bool(value)});print(('PASS: ' if value else 'FAIL: ')+name,flush=True);assert value,name
with sync_playwright() as pw:
 b=pw.chromium.launch(executable_path=shutil.which('chromium'),headless=True,args=['--no-sandbox','--disable-gpu'])
 try:
  p=b.new_page(viewport={'width':960,'height':540})
  p.set_content('<form id="search"><input name="q"><button>Search</button></form><input id="other"><input id="pw" type="password"><div contenteditable="true" id="rich"></div>')
  p.evaluate("window.submits=[];search.addEventListener('submit',e=>{e.preventDefault();submits.push(new FormData(search).get('q'))});void 0")
  p.evaluate(bridge);p.evaluate(source);p.locator('input[name=q]').click();p.wait_for_function('reports.length>0')
  def edit(action,text=''):
   return p.evaluate("([a,t])=>{let f=reports.at(-1);return JSON.parse(__tvUi.edit(a,t,f.frame,f.field,0))}",[action,text])
  x=edit('insert','Arrivax');check('insert changes actual named form field',p.locator('input[name=q]').input_value()=='Arrivax' and x['v']=='Arrivax')
  x=edit('delete');check('delete changes actual site field at caret',x['v']=='Arriva')
  edit('insert','l');x=edit('submit');check('Enter invokes real form submit handler with typed query',x['status']=='submitted' and p.evaluate("submits.length===1&&submits[0]==='Arrival'"))
  check('site submit handler is not bypassed by raw form.submit',p.locator('input[name=q]').input_value()=='Arrival')
  p.evaluate("document.querySelector('input[name=q]').addEventListener('keydown',e=>{if(e.key==='Enter'){e.preventDefault();window.keySearch=true}});void 0")
  edit('submit');check('form-contained SPA Enter handler prevents unwanted form reload',p.evaluate('keySearch===true&&submits.length===1'))
  edit('clear');check('clear empties actual field',p.locator('input[name=q]').input_value()=='')
  p.locator('input[name=q]').fill('hello world');p.evaluate("document.querySelector('input[name=q]').setSelectionRange(6,11)")
  edit('insert','search');check('selected range is replaced instead of appended',p.locator('input[name=q]').input_value()=='hello search')
  p.locator('#other').click();p.wait_for_timeout(30);state=p.evaluate('reports.at(-1)')
  x=p.evaluate("f=>JSON.parse(__tvUi.edit('insert','wrong',f.frame,'not-current',0))",state);check('stale field identity cannot edit a replacement',x['status']=='stale' and p.locator('#other').input_value()=='')
  p.evaluate("other.addEventListener('beforeinput',e=>e.preventDefault());void 0");x=edit('insert','blocked');check('prevented beforeinput is not called applied',x['status']=='blocked' and p.locator('#other').input_value()=='')
  p.locator('#pw').click();p.wait_for_timeout(30);x=edit('insert','private');check('password edit updates field without returning secret',p.locator('#pw').input_value()=='private' and x['pw']==1 and x['v']=='' and x['length']==7)
  p.locator('#rich').click();p.wait_for_timeout(30);edit('insert','alpha');check('contenteditable receives text',p.locator('#rich').inner_text()=='alpha')
  edit('clear');check('contenteditable clear uses actual range',p.locator('#rich').text_content()=='')
  p.evaluate("rich.addEventListener('keydown',e=>{if(e.key==='Enter'){e.preventDefault();window.enterHandled=(window.enterHandled||0)+1}});void 0");origin=p.evaluate('performance.timeOrigin');x=edit('submit');check('formless search Enter reaches listener without forced reload',x['status']=='key-dispatched' and p.evaluate('enterHandled')==1 and p.evaluate('performance.timeOrigin')==origin)
  x=p.evaluate("JSON.parse(__tvUi.edit('insert','x','opaque-unreachable','1',0))");check('unreachable frame reports pre-action fallback status',x['status']=='unreachable')
  p.close()
 finally:b.close()
(r/'review199/editor-dom-results.json').write_text(json.dumps(checks,indent=2)+'\n')
