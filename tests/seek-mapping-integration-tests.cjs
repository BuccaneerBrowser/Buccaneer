const fs=require('fs'),vm=require('vm'),assert=require('assert'),path=require('path');
const source=fs.readFileSync(process.env.SEEK_SOURCE||path.join(__dirname,'../app/src/main/assets/seek_click.js'),'utf8');
let failed=0;function check(name,fn){try{fn();console.log('PASS: '+name)}catch(e){failed++;console.log('FAIL: '+name+' — '+e.message)}}
function player(){
 let current=0;const v={duration:100,seekable:{length:0}};Object.defineProperty(v,'currentTime',{get:()=>current,set:x=>{current=x},configurable:true});
 const container={parentElement:null,querySelectorAll:()=>[v]};
 const track={tagName:'DIV',className:'',parentElement:container,querySelectorAll:()=>[],getAttribute:k=>k==='role'?'slider':k==='aria-label'?'Seek':null,hasAttribute:()=>false,getBoundingClientRect:()=>({left:0,right:400,top:250,bottom:270,width:400,height:20})};
 const inner={innerWidth:400,innerHeight:300,document:{elementFromPoint:(x,y)=>x>=0&&x<=400&&y>=250&&y<=270?track:null},getComputedStyle:()=>({direction:'ltr'})};
 const frame={tagName:'IFRAME',contentDocument:inner.document,contentWindow:inner,clientWidth:400,clientHeight:300,clientLeft:2,clientTop:2,offsetWidth:404,offsetHeight:304,getBoundingClientRect:()=>({left:100,top:50,width:202,height:152})};
 const window={innerWidth:1000,innerHeight:700,document:{elementFromPoint:()=>frame}};const c={window,isFinite};vm.createContext(c);return {repair:vm.runInContext(source,c),v,inner,window,track};
}
check('scaled iframe timeline maps the intended quarter position including borders',()=>{const {repair,v}=player();assert.equal(repair((100+(2+100)*.5)/1000,(50+(2+260)*.5)/700),'seeked');assert.equal(v.currentTime,25)});
check('timeline setter that silently rejects the request is not reported seeked',()=>{const {repair,v,window,track}=player();window.document.elementFromPoint=()=>track;Object.defineProperty(v,'currentTime',{get:()=>0,set(){},configurable:true});assert.equal(repair(.2,260/700),'none')});
if(failed)process.exitCode=1;
