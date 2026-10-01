// Run with node tests/network-dashboard.test.cjs (no dependencies).
const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const html = fs.readFileSync('app/src/main/assets/dashboard.html', 'utf8');
const script = html.slice(html.indexOf('// ---- Network ----'), html.indexOf('// ---- End Network ----'));

function setup() {
  const nodes = new Map(), requests = [], timers = [];
  function $(key) {
    if (!nodes.has(key)) {
      const classes = new Set();
      nodes.set(key, {textContent: '', innerHTML: '', disabled: false, attributes: {}, value: '',
        scrollTop: 0, clientHeight: 200, scrollHeight: 200,
        classList: {add: x => classes.add(x), toggle: (x, yes) => yes ? classes.add(x) : classes.delete(x), contains: x => classes.has(x)},
        setAttribute(k, v) { this.attributes[k] = v; },
        querySelector: selector => $(key + ' ' + selector), scrollIntoView() {},
      });
    }
    return nodes.get(key);
  }
  let state = {enabled: true, interfaces: [{name: 'wlan0', addresses: ['192.168.1.5/24', 'fe80::5/64']}],
    ssid: null, gateway: ['192.168.1.1'], dns: ['192.168.1.1'], metrics: {rxBps: 1000, txBps: 500},
    discovery: {running: false, devices: []}, jobs: []};
  const context = vm.createContext({$, $$: () => [], POLL: {}, ACT: {}, node: null, tab: 'network', authed: true,
    pollTimer: 0, seq: 0, document: {hidden: false}, location: {hash: '#network'},
    esc: s => String(s).replaceAll('<', '&lt;').replaceAll('>', '&gt;'),
    setHTML: (el, content) => el.innerHTML = content, hist: {rx: [], tx: []}, push() {}, rate: n => n + ' B/s', spark: () => '<svg></svg>',
    refresh() {}, noteNodeError() {}, toast() {},
    setTimeout: (fn, ms) => { timers.push(ms); return 1; }, clearTimeout() {},
    api: async (path, options) => { requests.push({path, options}); return path === 'network/enabled' ? {enabled: state.enabled} : state; },
    run: async promise => { await promise; return true; },
  });
  vm.runInContext(script, context);
  return {$, requests, timers, state: s => state = {...state, ...s}, run: code => vm.runInContext(code, context)};
}

test('disabled module redirects to Modules and leaves the server off', async () => {
  const t = setup(); t.state({enabled: false});
  await t.run('renderNetwork()');
  assert.equal(t.run('location.hash'), '#modules');
  assert.equal(t.run('networkServer'), false);
});

test('quiet devices say not found and untrusted discovery names are escaped', async () => {
  const t = setup();
  t.state({discovery: {running: true, devices: [{ip: '192.168.1.10', name: '<script>bad</script>', sources: ['SSDP'], ports: [80], lastSeen: 123, found: false}]}});
  await t.run('renderNetwork()');
  assert.match(t.$('#netDevices').innerHTML, /not found in this scan/);
  assert.match(t.$('#netDevices').innerHTML, /&lt;script&gt;/);
  assert.doesNotMatch(t.$('#netDevices').innerHTML, /offline|<script>/);
  assert.equal(t.$('#netDiscover').disabled, true);
  assert.equal(t.$('#netDiscoveryCancel').disabled, false);
});

test('tool output uses text and running jobs expose cancellation and TCP fallback', async () => {
  const t = setup();
  t.state({jobs: [{kind: 'ping', running: true, output: '<script>bad</script>', tcpFallback: true}]});
  await t.run('renderNetwork()');
  const box = '[data-network-tool="ping"]';
  assert.equal(t.$(box + ' [data-network-output]').textContent, '<script>bad</script>');
  assert.equal(t.$(box + ' [type="submit"]').disabled, true);
  assert.equal(t.$(box + ' [data-act="netCancel"]').disabled, false);
  assert.equal(t.$(box + ' [data-network-fallback]').classList.contains('hidden'), false);
  await t.run('ACT.netCancel({dataset: {kind: "ping"}})');
  assert.equal(t.requests.at(-1).path, 'network/cancel/ping');
});

test('server lease renews only while requested on the same phone and stops after an exit', async () => {
  const t = setup();
  await t.run('ACT.netServer()');
  assert.equal(t.run('networkServer'), true);
  await t.run('renderNetwork()');
  assert.equal(t.requests.at(-1).path, 'network/server');
  t.run('node = "another-phone"');
  const before = t.requests.filter(r => r.path === 'network/server').length;
  await t.run('renderNetwork()');
  assert.equal(t.run('networkServer'), false);
  assert.equal(t.requests.filter(r => r.path === 'network/server').length, before);
  t.run('networkServer = true');
  t.state({jobs: [{kind: 'server', running: false, output: 'Bind failed'}]});
  await t.run('renderNetwork()');
  assert.equal(t.run('networkServer'), false);
});

test('leaving the page or hiding it stops lease renewal and network polling', async () => {
  const t = setup(); t.run('networkServer = true; tab = "modules"');
  await t.run('refresh()');
  assert.equal(t.run('networkServer'), false);
  assert.equal(t.requests.some(r => r.path === 'network/server'), false);
  t.run('tab = "network"; document.hidden = true');
  await t.run('refresh()');
  assert.equal(t.timers.length, 0);
});
