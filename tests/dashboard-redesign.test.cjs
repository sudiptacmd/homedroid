'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const html = fs.readFileSync('app/src/main/assets/dashboard.html', 'utf8');
const script = html.split('<script>')[1].split('</script>')[0];
test('module update calls the selected-phone API and recovers its button after failure', async () => {
  const start = script.indexOf('  async updateMod(b)');
  const method = script.slice(start, script.indexOf('\n  retry:', start));
  const calls = [], button = {dataset: {id: 'jellyfin'}, disabled: false};
  const context = vm.createContext({button, findMod: id => ({id, name: 'Jellyfin'}),
    api: async (path, options) => { calls.push({path, options}); throw new Error('Busy'); },
    run: async promise => { try { await promise; } catch {} },
  });
  await vm.runInContext(`const ACT = {${method}}; ACT.updateMod(button)`, context);
  assert.equal(calls[0].path, 'modules/jellyfin/update'); assert.equal(calls[0].options.method, 'POST');
  assert.equal(button.disabled, false);
});
test('SSH command and setup guides use the selected phone port', async () => {
  const elements = new Map();
  const element = s => { if (!elements.has(s)) elements.set(s, {textContent: ''}); return elements.get(s); };
  const context = vm.createContext({$: element, api: async p => p === 'ssh/history' ? {phone: []} : [], term: {histKind: 'phone'},
    sshPort: () => 2222, hostFor: () => 'den.local', setHTML() {}, icon() {}, esc: String, ext: (url, label) => label});
  const start = script.indexOf('async function renderSsh()');
  vm.runInContext(script.slice(start, script.indexOf("$('#keyForm').onsubmit", start)), context);
  await vm.runInContext('renderSsh()', context); assert.equal(element('#sshCmd').textContent, 'ssh -p 2222 den.local');
  const guides = script.slice(script.indexOf('const GUIDES ='), script.indexOf('/** The address this dashboard'));
  vm.runInContext(guides, context);
  const tunnel = vm.runInContext('GUIDES.tunnel.steps.at(-1).html()', context); // ext is needed by this step
  assert.match(tunnel, /port 2222/);
});
