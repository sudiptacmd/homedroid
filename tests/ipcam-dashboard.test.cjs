// Run with node --test tests/ipcam-dashboard.test.cjs (no dependencies).
const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const html = fs.readFileSync('app/src/main/assets/dashboard.html', 'utf8');
const fullScript = html.split('<script>')[1].split('</script>')[0];
const script = fullScript.slice(fullScript.indexOf('const $ ='), fullScript.indexOf('// ---- Dialogs'))
  + "\nlet tab = 'overview'; function refresh() {} function showLogin() {}\n"
  + fullScript.slice(fullScript.indexOf('// ---- IPCam and settings'), fullScript.indexOf("api('status').then(showApp)"));
function setup() {
  const nodes = new Map();
  function element(key) {
    if (!nodes.has(key)) {
      const classes = new Set();
      nodes.set(key, {dataset: {}, innerHTML: '', textContent: '', disabled: false, value: '',
        classList: {add: x => classes.add(x), remove: x => classes.delete(x), contains: x => classes.has(x),
          toggle(x, yes) { if (yes) classes.add(x); else classes.delete(x); }},
        querySelectorAll: () => [], addEventListener() {}, append() {}, remove() {},
        click() { this.onclick?.(); },
      });
    }
    return nodes.get(key);
  }
  const nav = ['overview', 'modules', 'deploys', 'camera'].map(x => {
    const el = element(x === 'camera' ? '#cameraNav' : `nav-${x}`); el.dataset.tab = x; return el;
  });
  const sections = ['overview', 'modules', 'deploys', 'camera'].map(x => { const el = element('#tab-' + x); el.id = 'tab-' + x; return el; });
  const document = {querySelector: x => x === '[data-tab="overview"]' ? nav[0] : element(x),
    querySelectorAll: x => x === '[data-camera-nav]' ? [element('#cameraNav')] : x === 'nav button' ? nav : x === 'main > section' ? sections : [],
    createElement: element, body: {append() {}}};
  let state = {armed: true, busy: false, recording: false, cameras: [{id: '0', name: 'Rear', flash: true}, {id: '1', name: 'Front', flash: false}], files: [], microphone: {running: false}};
  let enabled = true, failure = null;
  const requests = [];
  const context = vm.createContext({document, location: {hostname: 'phone'}, console, setTimeout: () => 1, clearTimeout() {}, setInterval: () => 1, clearInterval() {},
    fetch: async (path, options) => {
      requests.push({path, options});
      if (failure && options.method === 'POST') return {status: 409, ok: false, json: async () => ({error: failure})};
      const data = path === '/api/modules' ? {core: [{id: 'camera', enabled}], apps: [], job: {}} : path === '/api/settings' ? {cameraEnabled: enabled, autostart: false} : path === '/api/camera' ? state : {};
      return {status: 200, ok: true, json: async () => data};
    }});
  vm.runInContext(script, context);
  return {element, requests, run: s => vm.runInContext(s, context), state: x => state = {...state, ...x}, enabled: x => enabled = x, fail: x => failure = x};
}
test('IPCam navigation follows module enablement', async () => {
  const t = setup(); t.enabled(false); await t.run("api('settings').then(syncCameraNavigation)");
  assert.equal(t.element('#cameraNav').classList.contains('hidden'), true);
  t.enabled(true); await t.run("api('settings').then(syncCameraNavigation)");
  assert.equal(t.element('#cameraNav').classList.contains('hidden'), false);
});
test('unarmed IPCam explains phone setup and disables capture, microphone and announce', async () => {
  const t = setup(); t.state({armed: false}); await t.run('renderCamera()');
  assert.match(t.element('#cameraStatus').textContent, /phone/);
  assert.match(t.element('#cameraControls').innerHTML, /data-action="photo" disabled/);
  for (const id of ['listenMic', 'recordMic', 'announceButton']) assert.equal(t.element('#' + id).disabled, true);
});
test('recording locks other cameras and offers stop on the selected camera', async () => {
  const t = setup(); t.state({recording: true, selected: '0'}); await t.run('renderCamera()');
  const markup = t.element('#cameraControls').innerHTML;
  assert.equal((markup.match(/data-action="photo" disabled/g) || []).length, 2);
  assert.equal((markup.match(/data-action="stop"/g) || []).length, 1);
  assert.match(markup, /unavailable/);
});
test('saved capture names and camera labels are escaped', async () => {
  const t = setup(); t.state({cameras: [{id: '0', name: '<img onerror=alert(1)>', flash: false}], files: [{name: '<bad>.jpg', bytes: 1024}]});
  await t.run('renderCamera()');
  assert.ok(!t.element('#cameraControls').innerHTML.includes('<img'));
  assert.match(t.element('#cameraFiles').innerHTML, /%3Cbad%3E.jpg/);
});
test('announce submits text only after the form is triggered', async () => {
  const t = setup(); await t.run('renderCamera()');
  assert.equal(t.requests.some(x => x.path.endsWith('/announce')), false);
  t.element('#announcement').value = 'Dinner is ready';
  await t.element('#announceForm').onsubmit({preventDefault() {}});
  const request = t.requests.find(x => x.path.endsWith('/announce'));
  assert.equal(request.options.method, 'POST');
  assert.deepEqual(JSON.parse(request.options.body), {text: 'Dinner is ready'});
});
test('failed capture restores its button so the user can retry', async () => {
  const t = setup(); await t.run('renderCamera()'); t.fail('Camera busy');
  const button = {dataset: {camera: '0', action: 'photo'}, disabled: false};
  await t.element('#cameraControls').onclick({target: {closest: () => button}});
  assert.equal(button.disabled, false);
});
test('settings rejects mismatched passwords without making a request', async () => {
  const t = setup(); t.element('#newPassword').value = 'a-long-new-password'; t.element('#confirmPassword').value = 'different';
  await t.element('#settingsForm').onsubmit({preventDefault() {}});
  assert.match(t.element('#settingsMessage').textContent, /do not match/);
  assert.equal(t.requests.length, 0);
});
test('settings submits startup preference and password then clears sensitive fields', async () => {
  const t = setup();
  t.element('#currentPassword').value = 'old-password';
  t.element('#newPassword').value = t.element('#confirmPassword').value = 'a-long-new-password';
  t.element('#settingsAutostart').checked = false;
  await t.element('#settingsForm').onsubmit({preventDefault() {}});
  const request = t.requests.find(x => x.path === '/api/settings');
  assert.deepEqual(JSON.parse(request.options.body), {autostart: false, password: 'a-long-new-password', currentPassword: 'old-password'});
  assert.equal(t.element('#currentPassword').value, '');
  assert.equal(t.element('#newPassword').value, '');
  assert.match(t.element('#settingsMessage').textContent, /phone now shows/);
});
test('settings polling preserves an in-progress edit', async () => {
  const t = setup(); await t.run('renderSettings()');
  t.element('#settingsAutostart').checked = true;
  await t.run('renderSettings()');
  assert.equal(t.element('#settingsAutostart').checked, true);
  assert.equal(t.requests.filter(x => x.path === '/api/settings').length, 1);
});
