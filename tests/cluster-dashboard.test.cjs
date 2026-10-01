// Run with node --test tests/cluster-dashboard.test.cjs (no dependencies).
const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const html = fs.readFileSync('app/src/main/assets/dashboard.html', 'utf8');
const full = html.split('<script>')[1].split('</script>')[0];
const script = full.slice(full.indexOf('const $ ='), full.indexOf('// ---- Dialogs'));

function setup(hostname = 'phone.local') {
  const requests = [];
  const context = vm.createContext({
    document: {querySelector: () => null, querySelectorAll: () => []},
    location: {hostname, protocol: 'http:'},
    fetch: async (path, options) => { requests.push({path, options}); return {status: 200, ok: true, json: async () => ({})}; },
    showLogin() {},
  });
  vm.runInContext(script, context);
  return {requests, run: s => vm.runInContext(s, context)};
}

test('without a selected phone, everything goes to this phone', async () => {
  const t = setup();
  await t.run("api('modules')");
  assert.equal(t.requests[0].path, '/api/modules');
  assert.equal(t.run("appUrl(8096)"), 'http://phone.local:8096');
});

test('a selected phone gets every call, except logins and the cluster itself', async () => {
  const t = setup();
  t.run("cluster = {members: [{id: 'a1b2c3d4e5f60718', address: '192.168.1.31'}]}; node = 'a1b2c3d4e5f60718'");
  await t.run("api('files/media?path=x')");
  await t.run("api('cluster', {local: true})");
  await t.run("api('login', {method: 'POST', body: {}})");
  await t.run("api('logout', {method: 'POST'})");
  assert.deepEqual(t.requests.map(r => r.path), [
    '/api/nodes/a1b2c3d4e5f60718/files/media?path=x', '/api/cluster', '/api/login', '/api/logout']);
  // Its apps open on its own address, over plain HTTP like the phone serves them.
  assert.equal(t.run("appUrl(8096)"), 'http://192.168.1.31:8096');
  assert.equal(t.run("hostFor()"), '192.168.1.31');
});

test('phone ids are encoded into the forwarding path', async () => {
  const t = setup();
  t.run("node = '../x?y'");
  await t.run("api('status')");
  assert.equal(t.requests[0].path, '/api/nodes/..%2Fx%3Fy/status');
});

test('IPv6 addresses are bracketed in app links', () => {
  const t = setup();
  t.run("cluster = {members: [{id: 'n', address: 'fe80::1'}]}; node = 'n'");
  assert.equal(t.run("appUrl(8080)"), 'http://[fe80::1]:8080');
});
