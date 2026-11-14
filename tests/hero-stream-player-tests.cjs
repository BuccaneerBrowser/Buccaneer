const vm = require('node:vm');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const source = fs.readFileSync('app/src/main/assets/hero_churn_guard.js', 'utf8');
function run(ancestors, topFrame) {
  const native = function setInterval() {};
  const win = {setInterval: native, clearInterval() {}, innerWidth: 960,
    location: {ancestorOrigins: ancestors}, addEventListener() {}};
  win.top = topFrame ? win : {};
  const ctx = {window: win, document: {addEventListener() {}}, history: {}, console};
  vm.runInNewContext(source, ctx);
  return {win, native};
}
const live = run(['https://streamcorner.st'], false);
assert.equal(live.win.setInterval, live.native, 'StreamCorner child player must retain native interval for KeyGuard');
assert.equal(live.win.__heroKiller, undefined, 'player exemption must not claim hero guard installed');
for (const origins of [['https://other-cdn.test','https://streamcorner.st'], ['https://www.streamcorner.st']]) {
  const r = run(origins, false);
  assert.equal(r.win.setInterval, r.native, 'nested StreamCorner player preserves native timer');
}
for (const [origins, top] of [[[], true], [['https://flixer.gd'], false], [['https://streamcorner.st.evil.test'], false]]) {
  const r = run(origins, top);
  assert.notEqual(r.win.setInterval, r.native, 'hero crash mitigation remains active outside StreamCorner player trees');
  assert.equal(r.win.__heroKiller, true);
  assert.equal(Object.getOwnPropertyDescriptor(r.win, 'setInterval').configurable, false);
}
console.log('PASS hero player scope and unchanged crash mitigation');
