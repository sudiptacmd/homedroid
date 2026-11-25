// Run with node --test tests/resources.test.cjs (no dependencies).
// The memory and CPU estimates live in app/src/main/assets/resources.json; the README and the
// website repeat them. These checks keep every module covered and the copies in step.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');

const res = JSON.parse(fs.readFileSync('app/src/main/assets/resources.json', 'utf8'));
const catalog = JSON.parse(fs.readFileSync('app/src/main/assets/apps.json', 'utf8'));
const dashboardKt = fs.readFileSync('app/src/main/java/dev/homedroid/Dashboard.kt', 'utf8');
const ids = Object.keys(res).filter(k => !k.startsWith('_'));

// Built-in modules: the Core(...) list plus the ones Dashboard.modules() adds by hand.
const coreIds = [...dashboardKt.matchAll(/Core\("([a-z]+)"/g)].map(m => m[1])
  .concat([...dashboardKt.matchAll(/core\.put\(JSONObject\(\)\.put\("id", "([a-z]+)"\)/g)].map(m => m[1]), 'network');

test('every module has an estimate, and nothing else does', () => {
  const modules = [...new Set([...coreIds, ...catalog.map(a => a.id)])].sort();
  assert.deepEqual([...ids].sort(), modules);
});

test('estimates are filled in and CPU is low, medium or high', () => {
  for (const id of ids) {
    const r = res[id];
    assert.ok(r.ram && /\d/.test(r.ram), `${id} memory`);
    assert.ok(['low', 'medium', 'high'].includes(r.cpu), `${id} cpu ${r.cpu}`);
    assert.ok(r.note && r.note.endsWith('.'), `${id} note`);
  }
});

for (const [file, label] of [['README.md', 'README'], ['site/index.html', 'website']]) {
  test(`the ${label} shows the same figures`, () => {
    const text = fs.readFileSync(file, 'utf8').replace(/&amp;/g, '&');
    for (const id of ids) {
      assert.ok(text.includes(res[id].ram), `${label} is missing ${id}'s memory figure "${res[id].ram}"`);
    }
  });
}
