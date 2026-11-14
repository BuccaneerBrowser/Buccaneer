'use strict';
// Differential execution of real function bodies. The reference is reconstructed from
// the executable helpers (then verified against the unchanged protected hashes in Python).
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert'),acorn=require('acorn');
const root=path.resolve(__dirname,'..');
const registry=JSON.parse(fs.readFileSync(path.join(__dirname,'js-extractions.json'),'utf8'));
function restore(file,text){
  for(const move of [...registry[file]].reverse()){
    const a=text.indexOf(move.prefix),b=text.indexOf(move.suffix,a+move.prefix.length);
    assert(a>=0&&b>=0,move.name);let body=text.slice(a+move.prefix.length,b);
    text=text.slice(0,a)+text.slice(b+move.suffix.length);
    for(const [before,after] of [...move.rewrites].reverse())body=body.replace(after,before);
    text=text.replace(move.call,body);
  }return text;
}
function walk(node,fn,parent){if(!node||typeof node!=='object')return;fn(node,parent);for(const value of Object.values(node)){if(Array.isArray(value))value.forEach(x=>walk(x,fn,node));else if(value&&typeof value==='object')walk(value,fn,node);}}
function scopeSource(source,name){
  const ast=acorn.parse(source,{ecmaVersion:2018});let owner;
  walk(ast,node=>{if(node.type==='BlockStatement'&&node.body.some(x=>x.type==='FunctionDeclaration'&&x.id.name===name))owner=node;});
  assert(owner,name);return owner.body.filter(x=>x.type==='FunctionDeclaration'||x.type==='VariableDeclaration').map(x=>source.slice(x.start,x.end)).join('\n');
}
function complexity(source){let result={};walk(acorn.parse(source,{ecmaVersion:2018}),node=>{if(node.type==='FunctionDeclaration'){let count=1;function visit(n){if(n!==node&&(n.type==='FunctionDeclaration'||n.type==='FunctionExpression'||n.type==='ArrowFunctionExpression'))return;if(['IfStatement','ForStatement','ForInStatement','ForOfStatement','WhileStatement','DoWhileStatement','CatchClause','ConditionalExpression','LogicalExpression'].includes(n.type))count++;if(n.type==='SwitchCase'&&n.test)count++;for(const v of Object.values(n)){if(Array.isArray(v))v.forEach(x=>{if(x&&x.type)visit(x);});else if(v&&v.type)visit(v);}}visit(node);result[node.id.name]=count;}});return result;}
function common(){const trace=[];let now=10000;const context={trace,location:{host:'flixer.gd',hostname:'flixer.gd'},console,Date:{now:()=>now++},Math,JSON,document:{},window:{},fixture:{}};context.window=context;context.Buccaneer={currentDocument:()=> 'doc',diagnosticEvent:(n,s)=>trace.push(['diagnostic',n,JSON.parse(s)]),tmdbAllowlist:s=>trace.push(['allow',JSON.parse(s)]),isGuardEnabled:()=>true};return context;}
function runPopup(source,seed){
  const c=common();c.innerWidth=960;c.innerHeight=540;const rect=(w,h,x=0,y=0)=>({width:w,height:h,left:x,top:y,right:x+w,bottom:y+h});
  function element(i,tag,box,style){const attrs={};return {tagName:tag,src:i%2?'https://player.invalid':'about:blank',className:'candidate'+i,style:{},getBoundingClientRect(){c.trace.push(['rect',i]);return box;},getAttribute:k=>attrs[k],setAttribute(k,v){attrs[k]=v;},querySelector(q){return q==='video'?(i%9===0?{}:null):(i%7===0?{}:null);},contains:()=>i%11===0,textContent:'sample',_style:style};}
  const videos=seed%3?[element(-1,'VIDEO',rect(640,360),{})]:[];const els=[];
  for(let i=0;i<48;i++){let n=i+seed;els.push(element(n,n%4===0?'IFRAME':'DIV',rect([10,40,400,960][n%4],[10,80,300,540][n%4],n%5===0?-80:0),{display:n%17===0?'none':'block',visibility:n%19===0?'hidden':'visible',pointerEvents:n%13===0?'none':'auto',position:['static','relative','fixed','absolute'][n%4],zIndex:['auto','0','1000','9999','2147483647'][n%5]}));}
  const frames=els.filter(x=>x.tagName==='IFRAME');
  c.getComputedStyle=el=>{c.trace.push(['style',el.className]);return el._style;};c.document={getElementsByTagName:tag=>tag==='video'?videos:frames,querySelectorAll:()=>els};
  // Scope declarations only: do not install unrelated event listeners/timers.
  const code=scopeSource(source,'killOverlays');vm.createContext(c);vm.runInContext('(function(){'+code+';enabled=function(){return true};elText=function(el){return el.textContent};killOverlays();})();',c);
  return JSON.stringify({trace:c.trace,styles:els.map(x=>x.style)});
}
function runSync(source,seed){
  const c=common();c.pageYOffset=12;c.document.documentElement={scrollTop:12,scrollHeight:3000};const rows=seed%3?[{scrollLeft:seed}]:[];
  const imgs=Array.from({length:seed%60},(_,i)=>({src:'https://image.tmdb.org/'+i%35,currentSrc:'',naturalWidth:[0,1,120][i%3],naturalHeight:i%3===1?1:120,parentElement:{className:i%2?'fixed overflow-hidden':'normal'},_tmdb:i%5!==0,_visible:i%4!==0,_ep:i%3===0,getAttribute(){return this.src;}}));
  c.fixture={imgs,rows};c.document.querySelectorAll=()=>{c.trace.push(['queryImages']);return imgs;};
  let code=scopeSource(source,'sync');
  const stubs=`virtualize=function(){trace.push(['virtualize'])};virtualizeCards=function(){trace.push(['cards'])};findInfoOverlay=function(){return fixture.rows.length?{id:'overlay'}:null};findEpRows=function(){return fixture.rows};isTmdbImg=function(im){return im._tmdb};isTmdbPoster=isTmdbImg;inViewport=function(im){return im._visible};isEpRowImg=function(im){return im._ep};inRowViewport=inViewport;bkOvId=function(){return 'ov'};bkRowId=function(){return 'row'};epSigOf=function(){return 'sig'};epSigHash=function(){return 42};`;
  vm.createContext(c);vm.runInContext('(function(){'+code+stubs+'sync();sync();sync();trace.push(["visible",visible]);})();',c);
  assert(c.trace.some(x=>x[0]==='allow'),'sync must reach allowlist publication');
  return JSON.stringify({trace:c.trace,images:imgs.map(x=>[x.src,!!x.__flixerBlocked])});
}
function runMenus(source,seed){
  const c=common();c.innerHeight=540;const all=[];
  for(let i=0;i<16;i++){const n=i+seed,styles={},el={isConnected:true,clientHeight:40,scrollTop:n,scrollLeft:2,offsetHeight:100,parentElement:null,clientTop:0,style:styles,contains:()=>n%3!==0,querySelector:()=>n%13===0?{}:null,querySelectorAll:()=>n%4===0?[{}]:[],closest:()=>n%5===0?null:el,getBoundingClientRect(){c.trace.push(['rect',i]);return {width:n%11===0?10:200,height:n%7===0?0:100,top:n%2?-10:490,bottom:n%2?90:590};},_computed:{display:'block',visibility:n%17===0?'hidden':'visible',position:n%5===0?'static':'absolute',boxSizing:n%3===0?'content-box':'border-box',maxHeight:n%2?'none':'120px',paddingTop:'4',paddingBottom:'4',borderTopWidth:'1',borderBottomWidth:'1',marginTop:'0'}};all.push(el);}
  c.fixture={all};c.getComputedStyle=el=>el._computed;c.document={};const code=scopeSource(source,'fitMenus');
  vm.createContext(c);vm.runInContext('(function(){'+code+`;fitted=fixture.all.slice(0,2).map(function(el){return {el:el}});restoreMenus=function(){trace.push(['restore']);fitted.forEach(function(x){x.el.scrollTop=0;x.el.scrollLeft=0});};menuCandidates=function(){return fixture.all};verticalBounds=function(){trace.push(['bounds']);return {top:20,bottom:520}};setFit=function(el,key,value){trace.push(['fit',fixture.all.indexOf(el),key,value]);};trace.push(['changed',fitMenus()]);})();`,c);
  return JSON.stringify({trace:c.trace,scroll:all.map(x=>[x.scrollTop,x.scrollLeft])});
}
const report={};let cases=0;
for(const [file,run] of [['app/src/main/assets/popup_guard.js',runPopup],['app/src/main/assets/flixer_image_guard.js',runSync],['app/src/main/assets/tv_ui.js',runMenus]]){
 const current=fs.readFileSync(path.join(root,file),'utf8'),before=restore(file,current);
 for(let i=0;i<120;i++){assert.strictEqual(run(current,i),run(before,i),file+' seed='+i);cases++;}
 const a=complexity(before),b=complexity(current);report[file]={before:a,after:b};
}
fs.writeFileSync(path.join(root,'untangle-v237/js-complexity.json'),JSON.stringify(report,null,2)+'\n');
console.log('PASS: '+cases+' differential guard/menu scenarios; AST complexity in untangle-v237/js-complexity.json');
