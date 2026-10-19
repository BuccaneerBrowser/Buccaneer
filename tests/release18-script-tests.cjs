const assert=require('assert');const fs=require('fs');const vm=require('vm');
const source=fs.readFileSync('app/src/main/java/com/buccaneer/browser/MainActivity.kt','utf8');
function scriptIn(region,start){
 const s=source.slice(source.indexOf(region));const a=s.indexOf(start),b=s.indexOf(') { raw ->',a);
 return [...s.slice(a,b).matchAll(/"((?:\\.|[^"\\])*)"/g)].map(m=>JSON.parse('"'+m[1]+'"')).join('').replace('$amount','0.5');
}
const seek=scriptIn('private fun seekToFraction','eval(');
function seekRun(state,allow=true){let calls=0;const ctx={window:{},JSON,Math,isFinite};ctx.__fb=ctx.window.__fb={state:()=>JSON.stringify(state),seek:d=>{calls++;if(allow)state.t+=d;return 'ok'}};return {result:vm.runInNewContext(seek,ctx),calls,state};}
let r=seekRun({f:1,d:2400,t:40});assert.equal(r.result,'ok');assert.equal(r.state.t,1200);assert.equal(r.calls,1);
r=seekRun({f:1,d:2400,t:40},false);assert.equal(r.result,'blocked');
for(const state of [{f:0,fr:1},{f:1,d:0,t:0},{f:1,d:Infinity,t:0},{f:1,d:100,t:NaN}]){r=seekRun(state);assert.equal(r.result,'none');assert.equal(r.calls,0);}
assert.equal(vm.runInNewContext(seek,{window:{},JSON,Math,isFinite}),'none');
const idle=scriptIn('private val idleCheck','eval(');
for(const [playing,state,want] of [[true,{f:0,fr:1},1],[false,{f:1,p:0},1],[false,{f:1,p:1},0],[false,{f:0,fr:1},-1],[false,{f:0,fr:0},0]]){
 const fb={state:()=>JSON.stringify(state)};assert.equal(vm.runInNewContext(idle,{window:{__tvUi:{playback:()=>playing},__fb:fb},__tvUi:{playback:()=>playing},__fb:fb,JSON}),want);
}
assert.equal(vm.runInNewContext(idle,{window:{},JSON}),-1);
assert.equal(vm.runInNewContext(idle,{window:{__fb:{state:()=>{throw Error('gone')}}},__fb:{state:()=>{throw Error('gone')}},JSON}),-1);
console.log('PASS: actual emitted absolute-seek and idle scripts: reachable seek, rejection, finite duration, opaque/missing/error/paused/playing states.');
