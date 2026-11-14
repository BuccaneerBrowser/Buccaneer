const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync('app/src/main/assets/page_helpers.js','utf8');
const match=source.match(/  \(function installReedBudgetSource\(\) \{[\s\S]*?\n  \}\)\(\);/);
assert.ok(match,'Reed budget source preference must exist');
function setup(host,path,ancestors=[],top=true,available=true,label='Reed-1'){
 let clicks=0,observer, middleware;const listeners={};
 const window={location:{hostname:host,protocol:'https:',pathname:path,ancestorOrigins:ancestors}};window.top=top?window:{};
 const lab={textContent:label,getAttribute(){return label}};
 const button={querySelector(){return lab},click(){clicks++},className:'btn-secondary',getAttribute(){return null}};
 const document={querySelectorAll(){return available?[button]:[]},addEventListener(n,f){listeners[n]=f},removeEventListener(n){delete listeners[n]}};
 function MutationObserver(fn){observer=fn;this.observe=()=>{};}
 const ctx={window,document,MutationObserver,__host:host,URL,console};
 vm.runInNewContext(match[0],ctx);
 return {window,ctx,listeners,get clicks(){return clicks},mutate(){if(observer)observer()},setAvailable(v){available=v},install(){window.videojs={use(t,f){middleware=f()}};if(listeners.load)listeners.load({target:{tagName:'SCRIPT'}});return middleware}};
}
let x=setup('reedstreams.to','/watch/new-york-knicks-vs-indiana-pacers',[],true,false);
assert.equal(x.clicks,0);x.setAvailable(true);x.mutate();assert.equal(x.clicks,1);x.mutate();assert.equal(x.clicks,1,'respect later manual source selections');
x.window.location.pathname='/watch/milwaukee-bucks-vs-orlando-magic';x.mutate();assert.equal(x.clicks,2,'SPA route gets a new preference');
for(const host of ['streamcorner.st','flixer.gd','reedstreams.to.evil.test'])assert.equal(setup(host,'/watch/new-york-knicks-vs-indiana-pacers').clicks,0);
assert.equal(setup('reedstreams.to','/watch/north-alabama-lions-at-miami-hurricanes').clicks,0,'no invented low source for unavailable matches');
for(const bad of ['Oreo','Admin1','Delta1','Golf1','Reeds','MyReed','Reed 1','reed-1'])assert.equal(setup('reedstreams.to','/watch/new-york-knicks-vs-indiana-pacers',[],true,true,bad).clicks,0,'must not click '+bad);
assert.equal(setup('reedstreams.to','/watch/new-york-knicks-vs-indiana-pacers',[],true,true,'Reed').clicks,1,'plain Reed label still matches');
for(const path of ['/nba/knicks.html','/nba/bucks.html']){
 x=setup('sportsembed.store',path,['https://watchthematch.online','https://reedstreams.to'],false);const mw=x.install();assert.ok(mw,'register videojs middleware on script load');
 for(const name of ['knicks','bucks']){let actual;const original={src:'https://sprucefiber.s3.eu-west-3.amazonaws.com/'+name+'.m3u8',type:'application/x-mpegURL'};mw.setSource(original,(err,s)=>{assert.equal(err,null);actual=s});assert.equal(actual.src,'https://sprucefiber.s3.eu-west-3.amazonaws.com/'+name+'_240p30.m3u8');assert.equal(original.src.endsWith(name+'.m3u8'),true,'do not mutate source object');}
 // btsport family: site rotated the live source to btsport_* (2026-11-13). Master
 // and over-budget variants pin to the measured 240p30 (1.109 Mbps avg segment);
 // the 240p30 variant itself passes through.
 for(const src of ['btsport.m3u8','btsport_480p30.m3u8','btsport_720p30.m3u8','btsport_1080p30.m3u8']){let actual;const original={src:'https://sprucefiber.s3.eu-west-3.amazonaws.com/'+src};mw.setSource(original,(e,s)=>actual=s);assert.equal(actual.src,'https://sprucefiber.s3.eu-west-3.amazonaws.com/btsport_240p30.m3u8','pin '+src);}
 for(const src of ['btsport_240p30.m3u8','laliga.m3u8','laliga_480p30.m3u8']){let actual;const original={src:'https://sprucefiber.s3.eu-west-3.amazonaws.com/'+src};mw.setSource(original,(e,s)=>actual=s);assert.equal(actual.src,original.src,'leave '+src+' untouched');}
 let actual;mw.setSource({src:'https://other.test/knicks.m3u8'},(e,s)=>actual=s);assert.equal(actual.src,'https://other.test/knicks.m3u8');
}
for(const ancestry of [[],['https://streamcorner.st'],['https://reedstreams.to.evil.test'],['http://reedstreams.to']])assert.equal(setup('sportsembed.store','/nba/knicks.html',ancestry,false).install(),undefined);
assert.equal(setup('sportsembed.store','/nba/knicks.html',[],true).install(),undefined);
assert.ok(!/setTimeout|setInterval|requestAnimationFrame/.test(match[0]),'new mechanism must be event-driven');
console.log('PASS Reed budget source: delayed DOM + SPA selection, manual override, strict host/ancestor scope, exact measured 240p source middleware, no timers');
