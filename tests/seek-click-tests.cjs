// Run the actual lazy seek repair. These are simulated DOM/media objects, not device playback.
const assert=require('assert'),vm=require('vm'),fs=require('fs'),path=require('path');
const source=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/seek_click.js'),'utf8');
let listeners=0,timers=0,writes=0,current=10;
function node(attrs={},cls=''){
 return {tagName:'DIV',className:cls,parentElement:null,getAttribute:k=>attrs[k]||null,hasAttribute:k=>k in attrs,
 querySelectorAll:()=>[],getBoundingClientRect:()=>({left:100,right:900,top:600,bottom:620,width:800,height:20})};
}
const root=node(),track=node({'role':'slider','aria-label':'Seek'});track.parentElement=root;
const video={duration:1000,seekable:{length:0}};Object.defineProperty(video,'currentTime',{get:()=>current,set:v=>{current=v;writes++;}});
root.querySelectorAll=s=>s==='video'?[video]:[];
let hit=track;
const window={innerWidth:1000,innerHeight:700,document:{elementFromPoint:()=>hit},getComputedStyle:()=>({direction:'ltr'}),addEventListener:()=>listeners++};
const ctx={window,isFinite,setTimeout:()=>timers++,setInterval:()=>timers++,document:window.document};vm.createContext(ctx);
const repair=vm.runInContext(source,ctx);
assert.equal(listeners,0);assert.equal(timers,0);assert.equal(writes,0);
assert.equal(repair(.5,610/700,'probe'),'track');assert.equal(writes,0,'probe never seeks');
assert.equal(repair(.5,610/700),'seeked');assert.equal(current,500);
assert.equal(repair(.5,610/700),'seeked');assert.equal(writes,1,'already completed native seek is not performed twice');
track.getAttribute=k=>({'role':'slider','aria-label':'Volume'})[k]||null;
assert.equal(repair(.7,610/700),'none');assert.equal(current,500);
track.getAttribute=k=>({'role':'slider','aria-label':'Seek'})[k]||null;
hit={...node(),tagName:'BUTTON',parentElement:track};assert.equal(repair(.7,610/700),'none');
hit=node();hit.parentElement=root;assert.equal(repair(.7,610/700),'none');
hit=track;video.duration=Infinity;assert.equal(repair(.7,610/700),'none');video.duration=1000;
root.querySelectorAll=()=>[video,{}];assert.equal(repair(.7,610/700),'none');root.querySelectorAll=()=>[video];
assert.equal(repair(-1,.8),'none');assert.equal(repair(NaN,.8),'none');
video.seekable={length:2,start:i=>i?700:100,end:i=>i?900:300};assert.equal(repair(.5,610/700),'seeked');assert.equal(current,300,'gap clamps to nearest seekable boundary');
video.seekable={length:0};window.getComputedStyle=()=>({direction:'rtl'});repair(.3,610/700);assert.equal(current,750);
window.getComputedStyle=()=>({direction:'ltr'});
hit={...node(),tagName:'IFRAME',contentDocument:null};assert.equal(repair(.5,.5),'frame');
// Same-origin frame: normalize its content coordinates instead of using the top-frame dimensions.
const inner={innerWidth:1000,innerHeight:700,document:{elementFromPoint:()=>track},getComputedStyle:window.getComputedStyle};
hit={...node(),tagName:'IFRAME',contentDocument:inner.document,contentWindow:inner,clientLeft:0,clientTop:0,clientWidth:800,clientHeight:560,getBoundingClientRect:()=>({left:100,top:100,width:800,height:560})};
assert.equal(repair(.5,(100+610*.8)/700),'seeked');assert.equal(current,500);
assert.equal(listeners,0);assert.equal(timers,0);
console.log('PASS: seek click mapping, no duplicate seek, volume/buttons/nontracks excluded, finite duration, player association, seekable gaps, RTL, iframe mapping and zero installed listeners/timers.');
