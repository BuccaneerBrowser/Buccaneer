const fs=require('fs'),vm=require('vm'),path=require('path'),assert=require('assert');
const source=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/page_helpers.js'),'utf8');
const ui=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/tv_ui.js'),'utf8');
let failed=0;function check(name,fn){try{fn();console.log('PASS: '+name);}catch(e){failed++;console.log('FAIL: '+name+' — '+e.message);}}
function makePlayer(){
 const w={location:{hostname:'flixer.gd'},innerWidth:1280,innerHeight:720,Buccaneer:{isDiagnosticsEnabled:()=>false},getComputedStyle:()=>({visibility:'visible',display:'block'})};
 const rect={left:500,top:100,right:904,bottom:404,width:404,height:304};
 let t=10;const v={tagName:'VIDEO',paused:true,ended:false,duration:180,videoHeight:720,play(){this.paused=false},pause(){this.paused=true},getBoundingClientRect:()=>({left:20,top:30,right:320,bottom:230,width:300,height:200})};Object.defineProperty(v,'currentTime',{get:()=>t,set:x=>t=x});
 const child={getElementsByTagName:k=>k==='video'?[v]:[],documentElement:{contains:el=>el===v}};
 const fr={tagName:'IFRAME',contentDocument:child,offsetWidth:404,offsetHeight:304,clientLeft:2,clientTop:2,getBoundingClientRect:()=>rect};
 const cw={innerWidth:400,innerHeight:300,getComputedStyle:w.getComputedStyle,frameElement:fr};child.defaultView=cw;v.ownerDocument=child;
 const doc={getElementsByTagName:k=>k==='iframe'?[fr]:[],activeElement:fr,documentElement:{contains:el=>el===fr},elementFromPoint:()=>fr};fr.ownerDocument=doc;doc.defaultView=w;
 const c={window:w,document:doc,Date};vm.createContext(c);vm.runInContext(source,c);return {w,v,fr,doc,cw};
}
check('focused same-origin iframe exposes its reachable movie instead of becoming opaque',()=>{let {w}=makePlayer();assert.equal(JSON.parse(w.__fb.state()).f,1);});
check('nested reachable movie center is mapped into top-page coordinates',()=>{let {w,doc}=makePlayer();doc.activeElement=null;let s=JSON.parse(w.__fb.frameCenter());assert(Math.abs(s.x-672/1280)<.001);assert(Math.abs(s.y-232/720)<.001);});
function style(){let m={},pr={};return {setProperty(k,v,p){m[k]=v.endsWith('px')?String(Math.round(parseFloat(v)*1000)/1000)+'px':v;pr[k]=p||'';},getPropertyValue:k=>m[k]||'',getPropertyPriority:k=>pr[k]||'',removeProperty(k){delete m[k];delete pr[k];}};}
const fitting=ui.slice(ui.indexOf('  var fitted='),ui.indexOf('  function fitMenus()'));
check('CSS serialization cannot prevent restoration of a fractional menu offset',()=>{let c={};vm.createContext(c);vm.runInContext(fitting,c);let el={style:style()};c.setFit(el,'margin-top','148.000006px');c.restoreMenus();assert.equal(el.style.getPropertyValue('margin-top'),'');});
check('multiple fits of one wrapper restore the original value, not an earlier temporary value',()=>{let c={};vm.createContext(c);vm.runInContext(fitting,c);let el={style:style()};el.style.setProperty('margin-top','3px','');c.setFit(el,'margin-top','10px');c.setFit(el,'margin-top','20px');c.restoreMenus();assert.equal(el.style.getPropertyValue('margin-top'),'3px');});
const quality=source.slice(source.indexOf('  var QUALITY_MIN'),source.indexOf('  // Players usually live'));
check('Shaka honors manual ABR-off mode without replacing the chosen rendition',()=>{let c={window:{location:{href:'https://fixture.test/'}},diagnostic(){}};vm.createContext(c);vm.runInContext(quality,c);let selected=0;let cfg={abr:{enabled:false,restrictions:{maxHeight:Infinity}}};let tracks=[{id:1,height:1080,language:'en',active:true},{id:2,height:720,language:'en'}];let p={getVariantTracks:()=>tracks,getConfiguration:()=>cfg,configure(){},selectVariantTrack(){selected++;}};c.capShaka(p);assert.equal(selected,0);});
check('Shaka repairs an externally reset cap even when the ladder is unchanged',()=>{let c={window:{location:{href:'https://fixture.test/'}},diagnostic(){}};vm.createContext(c);vm.runInContext(quality,c);let cfg={abr:{enabled:true,restrictions:{maxHeight:Infinity}}};let tracks=[{id:1,height:720,language:'en',active:true},{id:2,height:1080,language:'en'}];let p={getVariantTracks:()=>tracks,getConfiguration:()=>cfg,configure(v){cfg.abr.restrictions.maxHeight=v.abr.restrictions.maxHeight},selectVariantTrack(){}};c.capShaka(p);cfg.abr.restrictions.maxHeight=Infinity;c.capShaka(p);assert.equal(cfg.abr.restrictions.maxHeight,720);});
if(failed)process.exitCode=1;
