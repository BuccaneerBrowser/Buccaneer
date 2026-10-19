const fs=require('fs'),vm=require('vm'),assert=require('assert');
const src=fs.readFileSync(require('path').join(__dirname,'../app/src/main/assets/page_helpers.js'),'utf8');
const part=src.slice(src.indexOf('  function qualityPreferenceFor(player)'),src.indexOf('  function capShaka(player)'));
let pref={height:720,revision:0};const c={QUALITY_MIN:400,QUALITY_MAX:800,bridgeCall:()=>JSON.stringify(pref),diagnostic:()=>{},window:{location:{href:'https://fixture.invalid/'}}};vm.createContext(c);vm.runInContext(part,c);
const hls={levels:[{height:480},{height:720},{height:1080}],autoLevelEnabled:false,currentLevel:2,autoLevelCapping:-1};c.capHls(hls);
assert.equal(hls.autoLevelCapping,1);assert.equal(hls.currentLevel,-1);console.log('PASS: first app preference overrides site startup1080, including revision0');
hls.autoLevelEnabled=false;hls.currentLevel=2;c.capHls(hls);assert.equal(hls.currentLevel,2);console.log('PASS: later website manual quality choice is retained');
const only={levels:[{height:1080}],autoLevelEnabled:false,currentLevel:0,autoLevelCapping:-1};c.capHls(only);assert.equal(only.autoLevelCapping,0);console.log('PASS:1080-only source remains playable');
const explicit={levels:[{height:480},{height:720},{height:1080}],autoLevelEnabled:false,currentLevel:1,autoLevelCapping:1,__fbManualQuality:true};c.capHls(explicit);assert.equal(explicit.currentLevel,1);console.log('PASS: explicit app manual-quality marker is not overwritten');

pref={height:480,revision:2};c.capHls(explicit);assert.equal(explicit.autoLevelCapping,0);assert.equal(explicit.currentLevel,-1);console.log("PASS: new explicit app preference overrides older manual marker");
