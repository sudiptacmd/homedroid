'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {createMockServer, CSP} = require('./dashboard-mock/server.cjs');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function fixture(t) {
  const logs = [], server = createMockServer({log: s => logs.push(s), delayMs: 15, jobStepMs: 1});
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  t.after(() => { server.closeAllConnections(); server.close(); });
  const request = async (route, method = 'GET', body, auth = true) => {
    const res = await fetch(base + '/api/' + route, {method, headers: {...(auth ? {Authorization: 'Bearer demo'} : {}), ...(body === undefined ? {} : {'Content-Type': 'application/json'})}, body: body === undefined ? undefined : JSON.stringify(body)});
    return {status: res.status, data: await res.json(), headers: res.headers};
  };
  return {base, server, request, logs};
}
test('mock serves CSP dashboard/vendor assets, login cookies, and explicit unknown-route errors', async t => {
  const f = await fixture(t);
  const page = await fetch(f.base); assert.equal(page.status, 200); assert.equal(page.headers.get('content-security-policy'), CSP); assert.match(await page.text(), /How to add a page/);
  const font = await fetch(f.base + '/vendor/JetBrainsMono-Regular.woff2'); assert.equal(font.status, 200); assert.equal(font.headers.get('content-type'), 'font/woff2');
  assert.equal((await f.request('status', 'GET', undefined, false)).status, 401);
  assert.equal((await f.request('login', 'POST', {password: 'wrong'}, false)).status, 401);
  const login = await f.request('login', 'POST', {password: 'demo'}, false); assert.equal(login.status, 200);
  const cookie = login.headers.get('set-cookie').split(';')[0];
  assert.equal((await fetch(f.base + '/api/status', {headers: {Cookie: cookie}})).status, 200);
  assert.equal((await f.request('missing')).status, 404); assert.match(f.logs[0], /Unhandled GET \/api\/missing/);
  assert.equal((await fetch(f.base + '/vendor/%2e%2e%2fDashboard.kt')).status, 404);
});
test('mock reads every dashboard page with Kotlin-shaped fixtures and independent phones', async t => {
  const f = await fixture(t);
  for (const route of ['status', 'overview', 'settings', 'modules', 'deploys', 'services', 'job', 'files', 'files/media', 'files/transfers', 'ssh/keys', 'ssh/sessions', 'ssh/history', 'ai', 'ai/routines', 'ai/briefs', 'network', 'network/enabled', 'camera', 'camera/audio', 'cluster', 'wol', 'update', 'tailscale']) {
    const r = await f.request(route); assert.equal(r.status, 200, route); assert.ok(r.data, route);
  }
  const overview = (await f.request('overview')).data;
  assert.ok(overview.status.metrics.rxBps >= 0); assert.equal(typeof overview.status.metrics.cpu, 'number');
  assert.ok(overview.modules.apps.some(a => a.installed));
  const {members} = (await f.request('cluster')).data;
  const remote = `nodes/${members[0].id}/`;
  assert.equal((await f.request(remote + 'settings', 'POST', {sshPort: 2223})).status, 200);
  assert.equal((await f.request(remote + 'settings')).data.sshPort, 2223);
  assert.equal((await f.request('settings')).data.sshPort, 8022);
  assert.equal((await f.request(`nodes/${members[1].id}/status`)).status, 502);
});
test('mock validates and persists SSH/DNS settings, including server errors and password sessions', async t => {
  const f = await fixture(t);
  for (const sshPort of [22, 8800, 8080, 8801, 70000, 2222.5]) assert.equal((await f.request('settings', 'POST', {sshPort})).status, 400);
  assert.equal((await f.request('settings', 'POST', {appsDns: 'not-an-ip'})).status, 400);
  assert.equal((await f.request('settings', 'POST', {sshPort: 2222, appsDns: '1.1.1.1, 9.9.9.9'})).status, 200);
  const settings = (await f.request('settings')).data; assert.equal(settings.sshPort, 2222); assert.equal(settings.appsDns, '1.1.1.1, 9.9.9.9');
  assert.equal((await f.request('modules')).data.core.find(x => x.id === 'ssh').port, 2222);
  await f.request('settings', 'POST', {appsDns: ''}); assert.equal((await f.request('settings')).data.appsDns, '');
  assert.equal((await f.request('settings', 'POST', {password: 'new-demo-password', currentPassword: 'wrong'})).status, 403);
  const changed = await f.request('settings', 'POST', {password: 'new-demo-password', currentPassword: 'demo'}); assert.equal(changed.status, 200); assert.ok(changed.headers.get('set-cookie'));
  assert.equal((await f.request('status')).status, 401);
});
test('module update shares the install progress job and rejects overlapping work', async t => {
  const f = await fixture(t);
  assert.equal((await f.request('modules/jellyfin/update', 'POST')).status, 200);
  const started = (await f.request('job')).data; assert.equal(started.running, true); assert.match(started.title, /Updating Jellyfin/);
  assert.equal((await f.request('modules/jellyfin/update', 'POST')).status, 409);
  await sleep(40);
  const finished = (await f.request('job')).data; assert.equal(finished.running, false); assert.ok(finished.log.includes('Done'));
  assert.ok(f.server.state.self.apps.find(a => a.def.id === 'jellyfin').updatedAt);
  assert.equal((await f.request('modules/immich/update', 'POST')).status, 409);
});
test('mock file upload, collision, move, cross-phone transfer, download and delete mutate memory', async t => {
  const f = await fixture(t), headers = {Authorization: 'Bearer demo'};
  await f.request('files/home/mkdir?path=demo', 'POST');
  const put = () => fetch(f.base + '/api/files/home?path=demo/readme.txt', {method: 'PUT', headers, body: 'hello from upload'});
  assert.equal((await put()).status, 201); assert.equal((await put()).status, 409);
  const dl = await fetch(f.base + '/api/files/home/download?path=demo/readme.txt', {headers}); assert.equal(await dl.text(), 'hello from upload');
  await f.request('files/home/move?path=demo/readme.txt', 'POST', {to: 'demo/renamed.txt'});
  const peer = f.server.state.members[0];
  const copy = await f.request(`nodes/${peer.id}/files/home/fetch?path=copy.txt`, 'POST', {from: f.server.state.self.id, root: 'home', path: 'demo/renamed.txt', move: false}); assert.equal(copy.status, 200);
  await sleep(30); assert.equal((await f.request(`nodes/${peer.id}/files/transfers`)).data[0].state, 'done');
  const zip = await fetch(f.base + '/api/files/home/download?path=demo', {headers}); assert.equal(zip.headers.get('content-type'), 'application/zip'); assert.equal(Buffer.from(await zip.arrayBuffer()).readUInt32LE(), 0x04034b50);
  assert.equal((await f.request('files/home?path=demo/renamed.txt', 'DELETE')).status, 200);
  assert.equal((await f.request('files/home?path=demo')).data.entries.length, 0);
});
test('mock camera provides decodable media, state transitions, audio chunks and saved preferences', async t => {
  const f = await fixture(t);
  await f.request('camera/settings', 'POST', {id: '0', rotation: 180, fps: 10});
  assert.equal((await f.request('camera')).data.cameras[0].settings.rotation, 180);
  await f.request('camera/live/start', 'POST', {id: '0'});
  const frame = await fetch(f.base + '/api/camera/live/frame', {headers: {Authorization: 'Bearer demo'}});
  assert.equal(frame.status, 200); assert.equal(frame.headers.get('x-frame'), '1'); assert.equal(Buffer.from(await frame.arrayBuffer()).subarray(1, 4).toString(), 'PNG');
  await f.request('camera/photo', 'POST', {id: '0'}); await sleep(30);
  const c = (await f.request('camera')).data; assert.equal(c.files.length, 4);
  await f.request('camera/microphone/start', 'POST', {record: true});
  assert.equal((await f.request('camera/audio')).data.running, true);
  await f.request('camera/microphone/stop', 'POST'); assert.equal((await f.request('camera')).data.files.length, 5);
});
test('mock network jobs, AI streaming/downloads/routines and deployments complete', async t => {
  const f = await fixture(t);
  await f.request('network/run/http', 'POST', {url: 'https://example.com'}); await sleep(30);
  assert.equal((await f.request('network')).data.jobs[0].result.Status, 200);
  await f.request('network/run/ping', 'POST', {host: 'denied'}); await sleep(30);
  assert.equal((await f.request('network')).data.jobs.find(j => j.kind === 'ping').tcpFallback, true);
  const ai = (await f.request('ai')).data;
  const chat = await fetch(f.base + '/api/ai/chat', {method: 'POST', headers: {Authorization: 'Bearer demo', 'Content-Type': 'application/json'}, body: JSON.stringify({model: ai.models[0].id, messages: [{role: 'user', content: 'Hello'}]})});
  assert.equal(chat.headers.get('content-type'), 'text/event-stream'); assert.match(await chat.text(), /data: \[DONE\]/);
  await f.request('ai/download', 'POST', {id: 'qwen2.5-0.5b'}); await sleep(100);
  assert.equal((await f.request('ai')).data.download.state, 'done');
  const id = (await f.request('ai/routines')).data.routines[0].id;
  await f.request(`ai/routines/${id}/run`, 'POST'); await sleep(30); assert.match((await f.request('ai/briefs')).data[0].text, /Demo brief/);
  assert.equal((await f.request('deploys', 'POST', {name: 'test', repo: 'https://example.com/test', port: 3300, runtime: 'node', env: {}})).status, 200);
  await sleep(40); assert.ok((await f.request('deploys')).data.find(d => d.id === 'test').commit);
});
