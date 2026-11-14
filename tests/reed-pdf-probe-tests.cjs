const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync('app/src/main/assets/page_helpers.js', 'utf8');
const match = source.match(/  \(function installReedPdfProbeGuard\(\) \{[\s\S]*?\n  \}\)\(\);/);
assert.ok(match, 'Reed PDF probe guard must exist');
function setup(ancestors, top = false) {
  const listeners = [];
  const window = {location: {ancestorOrigins: ancestors}};
  window.top = top ? window : {};
  const document = {addEventListener(...args) { listeners.push(args); }};
  vm.runInNewContext(match[0], {window, document});
  return listeners;
}
for (const origins of [['https://reedstreams.to'], ['https://reedstreams.st'], ['https://reedstreams.link'], ['https://rotating.test','https://reedstreams.to']]) {
  const ls = setup(origins);
  assert.equal(ls.length, 1);
  assert.equal(ls[0][0], 'error');
  assert.equal(ls[0][2], true, 'capture before inline onerror');
  for (const [tag, id, data, expected] of [
    ['OBJECT','sandDetect','data:application/pdf;base64,aG1t',true],
    ['VIDEO','sandDetect','data:application/pdf;base64,aG1t',false],
    ['OBJECT','different','data:application/pdf;base64,aG1t',false],
    ['OBJECT','sandDetect','https://site.test/document.pdf',false]
  ]) {
    let stopped = 0, prevented = 0;
    ls[0][1]({target:{tagName:tag,id,getAttribute(){return data;}},preventDefault(){prevented++;},stopImmediatePropagation(){stopped++;}});
    assert.equal(stopped, +expected); assert.equal(prevented, +expected);
  }
}
for (const origins of [[], ['https://streamcorner.st'], ['https://flixer.gd'], ['https://reedstreams.to.evil.test'], ['http://reedstreams.to']]) assert.equal(setup(origins).length, 0);
assert.equal(setup(['https://reedstreams.to'], true).length, 0);
assert.equal(setup(undefined).length, 0);
console.log('PASS Reed PDF probe: exact payload, ancestry, capture, unrelated errors untouched');
