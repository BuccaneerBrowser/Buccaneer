// Isolated tests of the actual functions in the shipped helper asset; no DOM needed.
const fs = require('fs'), vm = require('vm'), assert = require('assert');
const path = require('path');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/page_helpers.js'), 'utf8');
const quality = source.slice(source.indexOf('  var QUALITY_MIN'), source.indexOf('  // Players usually live'));
const wrapper = source.slice(source.indexOf('  var hlsInstances'), source.indexOf("  document.addEventListener('loadedmetadata'"));
const events = [];
function Hls(options) { this.options = options; this.levels = []; this.callbacks = {}; this.destroy = function () { this.didDestroy=true; return 'destroyed'; }; this.on = (name, cb) => { this.callbacks[name] = cb; }; }
Hls.Events = { MANIFEST_PARSED: 'parsed', LEVELS_UPDATED: 'updated' };
Hls.isSupported = () => true;
Object.defineProperty(Hls, 'DefaultConfig', { get: () => ({ marker: 42 }), configurable: true });
const c = { window: { Hls, location: {href: "https://test.invalid"} }, diagnostic: (...args) => events.push(args) };
vm.createContext(c); vm.runInContext(quality + wrapper, c);
const cases = [
  [[144,240,360,480,720,1080],720], [[480,1080],480], [[400,800,1080],800],
  [[720,1080],720], [[1080],1080], [[1080,2160],1080], [[360,1080],360],
  [[144,240,360],360], [[800,400,720],800], [[0,1080],1080], [[0],null], [[],null]
];
for (const [heights, expected] of cases) {
  const levels = heights.map(height => ({height}));
  const idx = c.pickCapLevel(levels);
  assert.equal(idx < 0 ? null : levels[idx].height, expected, JSON.stringify(heights));
}
c.wrapHlsConstructor();
assert(c.window.Hls.isSupported());
assert.strictEqual(c.window.Hls.Events, Hls.Events);
assert.equal(c.window.Hls.DefaultConfig.marker,42);
const inst = new c.window.Hls({ marker: 'constructor arguments preserved' });
assert(inst instanceof Hls); assert(inst instanceof c.window.Hls);
assert.equal(inst.options.marker, 'constructor arguments preserved');
assert(inst.callbacks.parsed && inst.callbacks.updated);
inst.levels = [480,720,1080].map(height=>({height,bitrate:height*1000}));
inst.callbacks.parsed(); assert.equal(inst.autoLevelCapping,1); assert.equal(inst.nextAutoLevel,1);
const previousEvents = events.length; c.capHls(inst); assert.equal(events.length,previousEvents);
inst.levels = [{height:1080}]; inst.callbacks.updated(); assert.equal(inst.autoLevelCapping,0);
// Wrapper fallback works without Object.setPrototypeOf.
const d = { window: {Hls, location: {href: "https://test.invalid"}}, diagnostic:()=>{} }; vm.createContext(d);
vm.runInContext('Object.setPrototypeOf = undefined;\n' + quality + wrapper,d); d.wrapHlsConstructor();
assert(d.window.Hls.isSupported()); assert.equal(d.window.Hls.DefaultConfig.marker,42);
// Legacy constructor fallback remains usable without Reflect.
vm.runInContext('Reflect = undefined;', d);
const legacy = new d.window.Hls({marker:'legacy'});
assert(legacy instanceof Hls); assert.equal(legacy.options.marker,'legacy');
// Shaka preserves the active language/audio group and permits higher-only fallback.
let configured, selected;
let tracks = [{id:1,height:1080,language:'en',audioId:4,roles:[],active:true},
  {id:2,height:720,language:'en',audioId:4,roles:[]},
  {id:3,height:800,language:'es',audioId:5,roles:[]}];
const player={getVariantTracks:()=>tracks,configure:x=>configured=x,selectVariantTrack:t=>selected=t};
c.capShaka(player); assert.equal(selected.id,2); assert.equal(configured.abr.restrictions.maxHeight,720);
tracks=[{id:4,height:1080,language:'en',audioId:4,roles:[],active:true}];
selected=null;c.capShaka(player); assert.equal(selected,null); assert.equal(configured.abr.restrictions.maxHeight,1080);
console.log('PASS: 12 quality ladders, HLS constructor/statics/events/fallback/reapplication, Shaka language and 1080p-only fallback.');

assert.equal(inst.destroy(),'destroyed'); assert(inst.didDestroy); assert(!c.hlsInstances.includes(inst));
c.trackHls(inst); assert(!c.hlsInstances.includes(inst));
const fail={window:{Hls:function(){throw new Error('constructor failure');},location:{href:'https://test.invalid'}},diagnostic:()=>{}};
vm.createContext(fail);vm.runInContext(quality+wrapper,fail);fail.wrapHlsConstructor();
assert.throws(()=>new fail.window.Hls(),/constructor failure/);
console.log('PASS: destroyed HLS instances are untracked; constructor errors preserve original behavior.');
// Manual quality lowering should choose the nearest supported height, not the lowest rung.
const manualSource=source.slice(source.indexOf('    setQuality: function'),source.indexOf('    // Diagnostic: report the iframe'));
vm.runInContext(manualSource.replace('setQuality: function','var setQuality = function').replace(/,\s*$/,';'),c);
const targetVideo={};c.pickTarget=()=>({video:targetVideo});
const manual=new c.window.Hls();manual.media=targetVideo;manual.levels=[144,360,480,720,1080].map(height=>({height}));
assert.equal(c.setQuality(480),'ok');assert.equal(manual.currentLevel,2);assert.equal(manual.autoLevelCapping,2);
// The real full helper must remain callable on Flixer, where active hooks are skipped.
const flixer={window:{location:{hostname:'flixer.gd'},Buccaneer:{isDiagnosticsEnabled:()=>false}},document:{}};
vm.createContext(flixer);vm.runInContext(source,flixer);
assert.equal(JSON.parse(flixer.window.__fb.listLevels()).ok,0);
assert.equal(flixer.window.__fb.setQuality(720),'none');
console.log('PASS: manual quality chooses nearest lower height; Flixer passive quality queries do not throw.');
