from pathlib import Path
import shutil,json
from playwright.sync_api import sync_playwright
root=Path(__file__).resolve().parent.parent
source=(root/'app/src/main/assets/tv_ui.js').read_text()
checks=[]
def check(name,value):
 checks.append({'name':name,'pass':bool(value)});print(('PASS: ' if value else 'FAIL: ')+name,flush=True);assert value,name
with sync_playwright() as pw:
 b=pw.chromium.launch(executable_path=shutil.which('chromium'),headless=True,args=['--no-sandbox','--disable-gpu'])
 try:
  for w,h in [(960,540),(1280,720),(1920,1080)]:
   p=b.new_page(viewport={'width':w,'height':h})
   p.set_content('''<style>body{margin:0}#anchor{position:relative;top:''' + str(h-80)+'''px;left:80px;width:280px}#choices{display:none;position:absolute;top:100%;width:280px;max-height:320px;overflow-y:auto;background:white}#choices button{display:block;height:64px;width:100%}#unrelated{position:absolute;top:0;width:200px;height:70px}</style><div id=anchor><button id=trigger>Seasons</button><div id=choices>'''+''.join('<button>Season '+str(i)+'</button>' for i in range(12))+'''</div></div><div id=unrelated><button>A</button><button>B</button></div><script>trigger.onclick=()=>{setTimeout(()=>choices.style.display='block',200)};</script>''')
   p.evaluate(source);p.locator('#trigger').click();p.wait_for_timeout(900)
   box=p.locator('#choices').bounding_box();check(f'{w}x{h}: unlabelled delayed popup fits viewport',box['y']>=h*.05-1 and box['y']+box['height']<=h*.95+1)
   check(f'{w}x{h}: site max-height is not enlarged',p.locator('#choices').evaluate('(e)=>e.offsetHeight<=320'))
   check(f'{w}x{h}: unrelated visible controls are not restyled',p.locator('#unrelated').get_attribute('style') is None)
   result=p.evaluate('''()=>{const r=choices.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2;let down=__tvUi.scrollAt(x,y,0,10000),last=choices.scrollTop,max=choices.scrollHeight-choices.clientHeight;let up=__tvUi.scrollAt(x,y,0,-10000);return {down,last,max,up,first:choices.scrollTop}}''')
   check(f'{w}x{h}: internal scroll reaches last and first items',result['down']=='nested' and result['last']==result['max'] and result['max']>0 and result['up']=='nested' and result['first']==0)
   p.evaluate("choices.style.color='red';__tvUi.restoreMenus()")
   check(f'{w}x{h}: cleanup restores own properties and preserves website styles',p.locator('#choices').evaluate("e=>e.style.maxHeight===''&&e.style.color==='red'&&e.style.display==='block'"))
   p.close()
 finally:b.close()
(root/'review199/menu-dom-results.json').write_text(json.dumps(checks,indent=2)+'\n')
