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
function setup(extra = {}) {
  const nodes = new Map();
  function element(key) {
    if (!nodes.has(key)) {
      const classes = new Set();
      nodes.set(key, {dataset: {}, style: {}, innerHTML: '', textContent: '', disabled: false, value: '',
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
    createElement: element, body: {append() {}}, addEventListener() {}};
  let state = {armed: true, busy: false, recording: false, cameras: [{id: '0', name: 'Rear', flash: true}, {id: '1', name: 'Front', flash: false}], files: [], microphone: {running: false}};
  let enabled = true, failure = null;
  const requests = [];
  const context = vm.createContext({document, location: {hostname: 'phone'}, console, setTimeout: () => 1, clearTimeout() {}, setInterval: () => 1, clearInterval() {},
    fetch: async (path, options) => {
      requests.push({path, options});
      if (failure && options.method === 'POST') return {status: 409, ok: false, json: async () => ({error: failure})};
      const data = path === '/api/modules' ? {core: [{id: 'camera', enabled}], apps: [], job: {}} : path === '/api/settings' ? {cameraEnabled: enabled, autostart: false} : path === '/api/camera' ? state : {};
      return {status: 200, ok: true, json: async () => data};
    }, ...extra});
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
test('saved captures can be previewed in the page before downloading', async () => {
  const t = setup(); t.state({files: [{name: '1790000000000-ab.jpg', bytes: 2048}, {name: '1790000000001-cd.mp4', bytes: 4096}]});
  await t.run('renderCamera()');
  const markup = t.element('#cameraFiles').innerHTML;
  assert.equal((markup.match(/data-act="previewCapture"/g) || []).length, 2);
  assert.match(markup, /1790000000000-ab\.jpg\?inline=1/);
  assert.match(markup, /download/);
});
test('a capture taken from the page is offered for preview', async () => {
  const t = setup(); await t.run('renderCamera()');
  await t.element('#cameraControls').onclick({target: {closest: () => ({dataset: {camera: '0', action: 'photo'}, disabled: false})}});
  t.state({files: [{name: '1790000000002-ef.jpg', bytes: 10}]}); await t.run('renderCamera()');
  assert.match(t.element('#captureNotice').innerHTML, /Photo saved/);
  assert.match(t.element('#captureNotice').innerHTML, /data-name="1790000000002-ef.jpg"/);
});
test('each camera offers a live view', async () => {
  const t = setup(); await t.run('renderCamera()');
  assert.equal((t.element('#cameraControls').innerHTML.match(/data-action="live"/g) || []).length, 2);
  t.state({live: '0'}); await t.run('renderCamera()');
  assert.match(t.element('#cameraControls').innerHTML, /Close live view/);
});
test('API mutations include the browser request protection header', async () => {
  const t = setup(); await t.run("api('camera/monitor/stop', {method: 'POST'})");
  assert.equal(t.requests[0].options.headers['X-Homedroid-Request'], '1');
  assert.equal(t.requests[0].options.method, 'POST');
});

test('camera settings and storage drafts survive polling', async () => {
  const t = setup(); t.state({storage: {quotaMb: 2048, usedBytes: 1024}, cameras: [
    {id: '0', name: 'Rear', settings: {rotation: 90, fps: 5}},
    {id: '1', name: 'Front', settings: {rotation: 270, fps: 15}},
  ]});
  await t.run('renderCamera()');
  const markup = t.element('#cameraSettings').innerHTML;
  assert.match(markup, /data-camera-settings="0"/);
  assert.match(markup, /data-camera-settings="1"/);
  assert.match(markup, /value="90" selected/);
  assert.match(markup, /value="270" selected/);
  t.element('#cameraSettings').innerHTML = 'user editing';
  t.element('#cameraQuota').value = 512;
  await t.run('renderCamera()');
  assert.equal(t.element('#cameraSettings').innerHTML, 'user editing');
  assert.equal(t.element('#cameraQuota').value, 512);
});

test('monitoring blocks manual capture and offers a stop without closing the browser view', async () => {
  const t = setup(); t.state({monitor: {id: '0', recording: true, width: 640, height: 480, fpsMin: 5, fpsMax: 15}});
  await t.run('renderCamera()');
  assert.match(t.element('#cameraStatus').textContent, /Motion detected/);
  assert.equal((t.element('#cameraControls').innerHTML.match(/data-action="photo" disabled/g) || []).length, 2);
  assert.match(t.element('#cameraControls').innerHTML, /data-action="monitor\/stop"/);
});

test('settings save targets only the selected camera and sends numeric options', async () => {
  const t = setup({FormData: class { constructor(form) { return form.values; } }});
  const button = {disabled: false}, message = {textContent: ''};
  const form = {dataset: {cameraSettings: '1'},
    values: [['resolution', '720'], ['fps', '15'], ['rotation', '270'], ['mode', 'watch'], ['sensitivity', '5'], ['quietSeconds', '10'], ['clipSeconds', '120']],
    querySelector: selector => selector === 'button[type="submit"]' ? button : message};
  await t.element('#cameraSettings').onsubmit({preventDefault() {}, target: form});
  const request = t.requests.find(x => x.path === '/api/camera/settings');
  assert.deepEqual(JSON.parse(request.options.body), {id: '1', resolution: 720, fps: 15, rotation: 270, mode: 'watch', sensitivity: 5, quietSeconds: 10, clipSeconds: 120});
  assert.equal(button.disabled, false);
  assert.match(message.textContent, /saved on the phone/);
});

test('saved rotation combines with sensor orientation', async () => {
  const t = setup(); t.state({cameras: [{id: '0', rotation: 90, settings: {rotation: 180}}]});
  await t.run('renderCamera()');
  const canvas = t.element('#liveImg'); canvas.width = 640; canvas.height = 480;
  t.run("live.id = '0'; fitLive()");
  assert.match(canvas.style.transform, /rotate\(270deg\)/);
  assert.equal(t.element('#liveFrame').style.aspectRatio, '480 / 640');
});

test('live canvas keeps the current frame visible until decoding completes', async () => {
  let decode, closed = 0, draws = 0;
  const t = setup({AbortController, createImageBitmap: () => new Promise(resolve => decode = resolve),
    fetch: async () => ({status: 200, headers: {get: () => '1'}, blob: async () => ({})})});
  const canvas = t.element('#liveImg'); canvas.width = 640; canvas.height = 480;
  canvas.getContext = () => ({drawImage() { draws++; t.run('live.id = null'); }});
  const loop = t.run("live.id = '0'; live.gen = 1; liveLoop(1)");
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(draws, 0); assert.equal(canvas.width, 640);
  decode({width: 640, height: 480, close() { closed++; }});
  await loop;
  assert.equal(draws, 1); assert.equal(closed, 1);
});

test('closing a live view during decoding prevents a late frame from being drawn', async () => {
  let decode, closed = 0, draws = 0;
  const t = setup({AbortController, createImageBitmap: () => new Promise(resolve => decode = resolve),
    fetch: async () => ({status: 200, headers: {get: () => '1'}, blob: async () => ({})})});
  const canvas = t.element('#liveImg'); canvas.width = 640; canvas.height = 480;
  canvas.getContext = () => ({drawImage() { draws++; }, clearRect() {}});
  const loop = t.run("live.id = '0'; live.gen = 1; liveLoop(1)");
  await new Promise(resolve => setImmediate(resolve));
  t.run('closeLive(false)');
  decode({width: 640, height: 480, close() { closed++; }});
  await loop;
  assert.equal(draws, 0); assert.equal(closed, 1);
});
