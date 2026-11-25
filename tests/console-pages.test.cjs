'use strict';
// Pure helpers of the console pages (databases, domains, firewall, deployments, MCP, MacroPads) and the mock endpoints they use.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const html = fs.readFileSync('app/src/main/assets/dashboard.html', 'utf8');
const a = html.indexOf('// ---- Console helpers'), b = html.indexOf('// ---- End console helpers');
const ctx = vm.createContext({bytes: n => n + ' B', URL, location: {hash: ''}});
vm.runInContext(html.slice(a, b) + `\nthis.api = {${[...html.slice(a, b).matchAll(/^(?:async )?(?:function|const) (\w+)/gm)].map(m => m[1]).join(',')}};`, ctx);
const H = ctx.api, run = s => vm.runInContext(s, ctx);
test('markers are present', () => { assert.ok(a > 0 && b > a); });
test('addresses and ranges, v4 and v6', () => {
  assert.equal(H.parseIpAddr('192.168.1.5').bits, 32); assert.equal(H.parseIpAddr('192.168.01.5'), null); assert.equal(H.parseIpAddr('300.1.1.1'), null);
  assert.equal(H.parseIpAddr('::ffff:10.0.0.1').bits, 32); assert.equal(H.parseIpAddr('2001:db8::1').bits, 128);
  assert.ok(H.parseCidrText('10.0.0.0/8')); assert.equal(H.parseCidrText('10.0.0.0/33'), null); assert.equal(H.parseCidrText('nope'), null);
  assert.equal(H.cidrHas(H.parseCidrText('192.168.0.0/16'), H.parseIpAddr('192.168.7.9')), true); assert.equal(H.cidrHas(H.parseCidrText('192.168.0.0/16'), H.parseIpAddr('192.169.0.1')), false);
  assert.equal(H.cidrHas(H.parseCidrText('2001:db8::/32'), H.parseIpAddr('2001:db8:1::5')), true);
});
test('firewall: deny wins, allow lists, loopback and lock-out', () => {
  const you = H.parseIpAddr('192.168.1.23');
  assert.equal(H.fwAllows([], 'dashboard', you), true);
  assert.equal(H.fwAllows([{action: 'allow', cidr: '10.0.0.0/8', scope: 'all'}], 'dashboard', you), false);
  assert.equal(H.fwAllows([{action: 'allow', cidr: '192.168.0.0/16', scope: 'all'}, {action: 'deny', cidr: '192.168.1.23', scope: 'all'}], 'dashboard', you), false);
  assert.equal(H.fwAllows([{action: 'deny', cidr: '192.168.0.0/16', scope: 'web'}], 'dashboard', you), true);
  assert.equal(H.fwAllows([{action: 'deny', cidr: '0.0.0.0/0', scope: 'all'}], 'dashboard', H.parseIpAddr('127.0.0.1')), true);
  assert.equal(H.fwProblems([{action: 'deny', cidr: '192.168.0.0/16', scope: 'dashboard'}], '192.168.1.23').lockout, true);
  const p = H.fwProblems([{action: 'allow', cidr: 'bad', scope: 'all'}], '192.168.1.23'); assert.equal(p.valid, false); assert.match(p.errors[0], /Enter an address/); assert.equal(p.lockout, false);
  assert.deepEqual(JSON.parse(JSON.stringify(H.fwBody([{action: 'allow', cidr: ' 1.2.3.4 ', note: ' x '}]))), {rules: [{action: 'allow', cidr: '1.2.3.4', scope: 'all', note: 'x'}]});
});
test('domains: targets, labels and the request body', () => {
  assert.deepEqual(JSON.parse(JSON.stringify(H.makeTarget('static', '/blog/'))), {type: 'static', path: 'blog'});
  assert.deepEqual(JSON.parse(JSON.stringify(H.makeTarget('port', '3000'))), {type: 'port', port: 3000});
  assert.equal(H.targetLabel({type: 'deploy', id: 'x'}, [{id: 'x', name: 'My X', port: 3000}]), 'Deployment My X (port 3000)');
  assert.match(H.targetLabel({type: 'deploy', id: 'gone'}), /missing/);
  const body = H.domainBody({host: ' Blog.Example.com ', target: {type: 'port', port: 1}, headers: [{k: 'X-A', v: '1'}, {k: '-Server', v: 'ignored'}, {k: ' ', v: 'x'}], routes: [{path: '/api', target: {type: 'port', port: 2}, strip: 1}, {path: '', target: null}]});
  assert.equal(body.host, 'blog.example.com'); assert.deepEqual(JSON.parse(JSON.stringify(body.headers)), {'X-A': '1', '-Server': ''}); assert.equal(body.routes.length, 1); assert.equal(body.routes[0].strip, true);
  assert.equal(H.verifyUrl({host: 'a.b', token: 't'}), 'http://a.b/.well-known/homedroid-verify/t');
});
test('ddns bodies keep a blank token and name entries', () => {
  assert.equal(H.ddnsBody({provider: 'duckdns', domain: ' me ', token: ''}).token, ''); assert.equal(H.ddnsBody({provider: 'cloudflare', zone: 'a.com', record: 'h.a.com', proxied: 1}).proxied, true);
  assert.equal(H.ddnsName({provider: 'duckdns', domain: 'a,b'}), 'a.duckdns.org, b.duckdns.org');
});
test('deployments: env, steps, balancing, hooks and workflow', () => {
  assert.deepEqual(JSON.parse(JSON.stringify(H.envToMap('A=1\nB=***\n=x\nC = two=2'))), {A: '1', C: 'two=2'});
  assert.deepEqual(JSON.parse(JSON.stringify(H.stepsClean([{name: '', run: ' npm test '}, {name: 'x', run: ''}]))), [{name: 'Step 1', run: 'npm test'}]);
  assert.equal(H.balanceBody({on: false}), null); assert.deepEqual(JSON.parse(JSON.stringify(H.balanceBody({on: true, port: '9000', policy: '', healthUri: '', interval: ''}))), {port: 9000, policy: 'round_robin', healthUri: '/', interval: 10});
  assert.equal(H.hookOrigin({protocol: 'http:', port: '8800', origin: 'http://me:8800'}, null), 'http://me:8800'); assert.equal(H.hookOrigin({protocol: 'http:', port: '8800'}, '10.0.0.2'), 'http://10.0.0.2:8800');
  const y = H.ghActionsYaml({source: 'git', branch: 'main', hooks: {deploy: '/hooks/deploy/x', artifact: '/hooks/deploy/x/artifact'}}, 'https://h.example');
  assert.match(y, /https:\/\/h\.example\/hooks\/deploy\/x\?wait=1/); assert.match(y, /secrets\.HOMEDROID_DEPLOY_TOKEN/); assert.match(y, /branches: \[main\]/);
  assert.match(H.ghActionsYaml({source: 'upload', branch: 'main', hooks: {deploy: '/d', artifact: '/a'}}, 'https://h'), /--data-binary @site\.zip/);
  assert.equal(H.fmtMs(0.2), '<1 ms'); assert.equal(H.fmtMs(1500), '1.50 s'); assert.equal(H.statusClass(404), '4xx');
});
test('mcp snippets and agent history', () => {
  const s = H.mcpSnippets('http://h:8800/mcp', 'hdm_x');
  assert.match(s.claude, /claude mcp add --transport http homedroid http:\/\/h:8800\/mcp/); assert.match(s.claude, /Bearer hdm_x/);
  assert.equal(JSON.parse(s.cursor).mcpServers.homedroid.headers.Authorization, 'Bearer hdm_x'); assert.equal(JSON.parse(s.vscode).servers.homedroid.type, 'http'); assert.ok(JSON.parse(s.desktop).mcpServers.homedroid.args.includes('--allow-http'));
  assert.match(H.mcpSnippets('u').claude, /hdm_…/);
  assert.deepEqual(JSON.parse(JSON.stringify(H.agentHistory([{role: 'user', content: 'a'}, {role: 'assistant', error: 'x'}, {role: 'assistant', content: 'b', toolCalls: []}]))), [{role: 'user', content: 'a'}, {role: 'assistant', content: 'b'}]);
});
test('databases: cells, parameters and relation layout', () => {
  assert.equal(H.dbCell(null).kind, 'null'); assert.equal(H.dbCell(4).kind, 'num'); assert.equal(H.dbCell({blob: 9, hex: 'ff'}).kind, 'blob'); assert.equal(H.dbCell({text: 'a', size: 5000}).kind, 'long');
  assert.equal(H.hexDump('ff00ab'), 'ff 00 ab'); assert.deepEqual(JSON.parse(JSON.stringify(H.parseParams(''))), {ok: true, value: []}); assert.equal(H.parseParams('{}').ok, false); assert.equal(H.parseParams('[1').ok, false);
  assert.equal(H.sqlIsRead('  -- c\n select 1'), true); assert.equal(H.sqlIsRead('DELETE FROM x'), false);
  const tables = [{name: 'a', columns: [{name: 'id', type: 'INT', pk: 1}]}, {name: 'b', columns: [{name: 'a_id', type: 'INT', pk: 0}]}, {name: 'c', columns: []}];
  const lay = H.relLayout(tables, [{from: 'b', fromColumns: ['a_id'], to: 'a', toColumns: ['id']}]);
  assert.equal(lay.nodes.length, 2); assert.equal(lay.edges.length, 1); assert.ok(lay.width > 0 && lay.nodes.find(n => n.name === 'b').columns[0].fk); assert.equal(H.relLayout(tables, []).nodes.length, 0);
  assert.equal(H.parseJsonObject('').ok, true); assert.equal(H.parseJsonObject('', false).ok, false); assert.equal(H.parseJsonObject('[1]').ok, false); assert.equal(H.parseJsonObject('{"a":1}').value.a, 1);
});
test('macropads: key capture, validation, moving and resizing', () => {
  assert.deepEqual(JSON.parse(JSON.stringify(H.mpCombo({ctrlKey: true, shiftKey: true, code: 'KeyM', key: 'M'}))), ['ctrl', 'shift', 'm']);
  assert.deepEqual(JSON.parse(JSON.stringify(H.mpCombo({metaKey: true, key: 'Meta', code: 'MetaLeft'}))), ['meta']); assert.deepEqual(JSON.parse(JSON.stringify(H.mpCombo({key: 'F5', code: 'F5'}))), ['f5']);
  assert.equal(H.mpEventKey({key: 'ArrowUp'}), 'up'); assert.equal(H.mpEventKey({key: ' '}), 'space'); assert.equal(H.mpEventKey({key: 'Dead'}), '');
  assert.equal(H.pairCommand('phone:8800', '123456'), 'homedroid-desktop pair phone:8800 123456'); assert.equal(H.fmtCode('123456'), '123 456');
  const deck = JSON.parse(JSON.stringify(require('./dashboard-mock/extras.cjs').starterDeck()));
  assert.deepEqual(JSON.parse(JSON.stringify(H.macroProblems(deck))), []);
  const bad = JSON.parse(JSON.stringify(deck)); bad.profiles[0].pages[0].buttons[0].action = {kind: 'hotkey', keys: ['ctrl', 'ctrl']}; bad.profiles[0].pages[0].buttons[1].col = bad.profiles[0].pages[0].buttons[0].col; bad.profiles[0].pages[0].buttons[1].row = 0;
  const p = H.macroProblems(bad); assert.ok(p.some(x => /different keys/.test(x))); assert.ok(p.some(x => /share a cell/.test(x)));
  const link = JSON.parse(JSON.stringify(deck)); link.profiles[0].pages[0].buttons[2].action = {kind: 'page', page: 'nope'}; assert.ok(H.macroProblems(link).some(x => /page link/.test(x)));
  assert.match(H.mpActionError({kind: 'http', method: 'GET', url: 'ftp://x'}), /http or https/); assert.equal(H.mpActionError({kind: 'sequence', steps: [{kind: 'page', page: 'x'}]}).includes('desktop actions only'), true);
  const page = {buttons: [{id: 'a', row: 0, col: 0}, {id: 'b', row: 0, col: 1}]};
  H.mpMoveKey(page, 'a', 0, 1); assert.deepEqual(page.buttons.map(x => [x.id, x.col]), [['a', 1], ['b', 0]]); H.mpMoveKey(page, 'a', 2, 2); assert.equal(page.buttons[0].row, 2);
  const prof = {rows: 3, cols: 3, pages: [{buttons: [{id: 'a', row: 2, col: 2}, {id: 'b', row: 0, col: 0}]}]};
  assert.equal(H.mpResize(prof, 2, 2), 0); assert.deepEqual([prof.pages[0].buttons[0].row, prof.pages[0].buttons[0].col], [0, 1]);
  const full = {rows: 2, cols: 2, pages: [{buttons: [0, 1, 2, 3].map(i => ({id: 'k' + i, row: i >> 1, col: i & 1}))}]}; full.pages[0].buttons.push({id: 'x', row: 5, col: 5}); assert.equal(H.mpResize(full, 2, 2), 1);
  assert.equal(H.mpNewId('b', {profiles: [{id: 'p', pages: [{id: 'g', buttons: [{id: 'b-3'}]}]}]}), 'b-4');
});
// ---- The mock endpoints behind the pages ----
const {createMockServer} = require('./dashboard-mock/server.cjs');
async function mock(t) {
  const server = createMockServer({log() {}, delayMs: 10, jobStepMs: 1});
  await new Promise(r => server.listen(0, '127.0.0.1', r)); t.after(() => { server.closeAllConnections(); server.close(); });
  const base = `http://127.0.0.1:${server.address().port}`;
  const call = async (route, method = 'GET', body, raw) => { const res = await fetch(base + '/api/' + route, {method, headers: {Authorization: 'Bearer demo', ...(raw ? {'Content-Type': 'application/octet-stream'} : body === undefined ? {} : {'Content-Type': 'application/json'})}, body: raw || (body === undefined ? undefined : JSON.stringify(body))}); return {status: res.status, data: await res.json()}; };
  call.base = base; return call;
}
test('mock: databases, rows and the SQL console errors', async t => {
  const r = await mock(t), list = (await r('db')).data, blog = list.find(f => f.name === 'app.sqlite'), q = `root=${blog.root}&path=${encodeURIComponent(blog.path)}`;
  const schema = (await r('db/schema?' + q)).data; assert.ok(schema.tables.some(x => x.foreignKeys.length)); assert.ok(schema.relations.length);
  const rows = (await r('db/rows?' + q + '&table=posts&limit=5')).data; assert.equal(rows.rows.length, 5); assert.equal(rows.columns.length, rows.types.length);
  assert.equal((await r('db/rows?' + q + '&table=nope')).status, 400);
  const body = {root: blog.root, path: blog.path, sql: 'SELECT * FROM users LIMIT 3'};
  assert.equal((await r('db/query', 'POST', body)).data.rows.length, 3);
  assert.equal((await r('db/query', 'POST', {...body, sql: 'DELETE FROM users'})).status, 400);
  assert.equal((await r('db/query', 'POST', {...body, sql: 'SELECT * FROM bogus'})).status, 400);
  assert.equal((await r('db/query', 'POST', {...body, sql: 'select sleep(99)'})).status, 408);
  assert.equal((await r('db/query', 'POST', {...body, sql: 'DELETE FROM users', write: true})).status, 400);
  assert.equal((await r('db/query', 'POST', {...body, sql: 'DELETE FROM users', write: true, confirm: true})).data.rowsAffected, 3);
  const ha = list.find(f => f.inUse); assert.equal((await r('db/query', 'POST', {root: ha.root, path: ha.path, sql: 'DELETE FROM states', write: true, confirm: true})).status, 409);
});
test('mock: documents CRUD and filters', async t => {
  const r = await mock(t);
  assert.ok((await r('docs')).data.some(c => c.name === 'notes'));
  assert.equal((await r('docs/notes?q=' + encodeURIComponent('{"status":"done"}'))).data.total, 2);
  assert.equal((await r('docs/notes?q=%7Bbad')).status, 400);
  const made = await r('docs/todo', 'POST', {title: 'x'}); assert.equal(made.status, 201);
  assert.equal((await r('docs/todo', 'POST', {_id: made.data._id})).status, 409);
  assert.equal((await r(`docs/todo/${made.data._id}`, 'PUT', {title: 'y'})).data.title, 'y');
  assert.equal((await r(`docs/todo/${made.data._id}`, 'DELETE')).status, 200); assert.equal((await r(`docs/todo/${made.data._id}`)).status, 404);
  assert.equal((await r('docs/todo', 'DELETE')).status, 200);
});
test('mock: domains, logs, ddns and firewall', async t => {
  const r = await mock(t), d = (await r('domains')).data;
  assert.equal(d.domains.length, 2); assert.equal((await r('domains', 'POST', {host: 'x', target: {type: 'port', port: 3000}})).status, 400);
  assert.equal((await r('domains', 'POST', {host: 'blog.example.com', target: {type: 'port', port: 3000}})).status, 409);
  const rej = await r('domains', 'POST', {host: 'rejected.example.com', target: {type: 'port', port: 3000}}); assert.equal(rej.status, 409); assert.match(rej.data.error, /Caddy rejected/);
  const added = await r('domains', 'POST', {host: 'new.example.com', target: {type: 'static', path: 'blog'}, routes: [{path: '/api', target: {type: 'port', port: 3100}, strip: true}]}); assert.equal(added.status, 200); assert.equal(added.data.verified, false);
  assert.equal((await r(`domains/${added.data.id}/verify`, 'POST')).data.verified, false); assert.equal((await r(`domains/${added.data.id}/verify`, 'POST')).data.verified, true);
  const logs = (await r('domains/logs?status=5xx&limit=10')).data.entries; assert.ok(logs.length && logs.every(e => e.status >= 500));
  assert.ok((await r('domains/logs?host=photos.example.org')).data.entries.every(e => e.host === 'photos.example.org'));
  const dd = (await r('ddns')).data.entries; assert.equal(dd[0].token, '***');
  assert.equal((await r('ddns', 'POST', {provider: 'duckdns', domain: 'x'})).status, 400);
  assert.equal((await r('ddns', 'POST', {id: dd[0].id, provider: 'duckdns', domain: 'myhome2', token: '***'})).data.domain, 'myhome2'); assert.equal((await r(`ddns/${dd[0].id}/update`, 'POST')).data.result, 'ok');
  const fw = (await r('firewall')).data; assert.ok(fw.you);
  assert.equal((await r('firewall', 'POST', {rules: [{action: 'deny', cidr: '192.168.0.0/16', scope: 'dashboard'}]})).status, 409);
  assert.equal((await r('firewall', 'POST', {rules: [{action: 'allow', cidr: 'bad'}]})).status, 400);
  assert.equal((await r('firewall', 'POST', {rules: [{action: 'allow', cidr: '192.168.1.0/24', scope: 'all'}]})).data.rules.length, 1);
});
test('mock: MCP settings, token, agent approvals', async t => {
  const r = await mock(t), s = (await r('mcp/settings')).data; assert.ok(s.tools.some(x => x.write)); assert.ok(s.urls.length);
  assert.equal((await r('mcp/settings', 'POST', {enabled: 'yes'})).status, 400); assert.equal((await r('mcp/settings', 'POST', {allowWrite: true})).data.allowWrite, true);
  const t1 = (await r('mcp/token')).data.token, t2 = (await r('mcp/token', 'POST')).data.token; assert.notEqual(t1, t2);
  const a = (await r('mcp/agent', 'POST', {message: 'Restart sshd'})).data; assert.equal(a.toolCalls[0].pending, true); assert.equal(a.pending.length, 1);
  assert.equal((await r('mcp/agent/approve', 'POST', {id: a.toolCalls[0].id})).data.ok, true); assert.equal((await r('mcp/agent/pending')).data.pending.length, 0); assert.equal((await r('mcp/agent/approve', 'POST', {id: 'x'})).status, 404);
  assert.ok((await r('mcp/agent', 'POST', {message: 'how much storage'})).data.toolCalls[0].result); assert.equal((await r('mcp/agent', 'POST', {message: ''})).status, 400);
  assert.ok((await r('mcp/audit?limit=5')).data.entries.length);
});
test('mock: deployment settings, hooks, uploads and pipelines', async t => {
  const r = await mock(t), list = (await r('deploys')).data, blog = list.find(x => x.id === 'my-blog');
  assert.equal(blog.hooks.deploy, '/hooks/deploy/my-blog'); assert.equal(blog.env.NODE_ENV, '***'); assert.equal(blog.pipeline.length, 3);
  assert.equal((await r('deploys/my-blog/settings', 'POST', {env: {A: '***'}})).status, 400);
  const saved = (await r('deploys/my-blog/settings', 'POST', {runtime: 'bun', balanced: {port: 9100, policy: 'least_conn', healthUri: '/', interval: 5}, pipeline: [{name: '', run: 'npm test'}]})).data; assert.equal(saved.runtime, 'bun'); assert.equal(saved.balanced.policy, 'least_conn'); assert.equal(saved.pipeline[0].name, 'Step 1'); assert.equal(saved.env.NODE_ENV, '***');
  assert.equal((await r('deploys/my-blog/settings', 'POST', {balanced: {port: 3100, policy: 'random', interval: 5}})).status, 400);
  assert.match((await r('deploys/my-blog/token', 'POST', {kind: 'token'})).data.token, /^hd_/); assert.match((await r('deploys/my-blog/token', 'POST', {kind: 'webhook'})).data.webhookSecret, /^whsec_/);
  assert.equal((await r('deploys/my-blog/upload', 'POST', undefined, Buffer.from('zip'))).status, 409);
  const up = await r('deploys/docs-site/upload?wait=1', 'POST', undefined, Buffer.from('PK-archive')); assert.match(up.data.commit, /^upload-/);
  const runs = (await r('deploys/my-blog/runs')).data.runs; assert.equal(runs[0].steps[0].log, undefined); const full = (await r(`deploys/my-blog/runs/${runs[1].id}`)).data; assert.ok(full.steps[1].log.includes('AssertionError'));
  const started = (await r('deploys/my-blog/pipeline', 'POST')).data; assert.ok(started.run); assert.equal((await r('deploys/status-page/pipeline', 'POST')).status, 409);
  const created = (await r('deploys', 'POST', {name: 'up-site', source: 'upload', port: 3300, runtime: 'static'})).data; assert.match(created.token, /^hd_/); assert.equal(created.source, 'upload');
});
test('mock: MacroPads deck, validation, press and pairing', async t => {
  const r = await mock(t), deck = (await r('macropads')).data; assert.ok(deck.computers.length); assert.equal(deck.version, 2);
  const {computers, ...model} = deck; model.profiles[0].pages[0].buttons[0].action = {kind: 'media', key: 'nope'};
  const bad = await r('macropads', 'PUT', model); assert.equal(bad.status, 400); assert.match(bad.data.error, /media key/);
  model.profiles[0].pages[0].buttons[0].action = {kind: 'media', key: 'next'}; assert.equal((await r('macropads', 'PUT', model)).status, 200);
  const press = b => r('macropads/press', 'POST', {profile: 'p-default', page: 'pg-1', button: b});
  assert.equal((await press('b-1')).status, 200); assert.equal((await press('b-15')).status, 409); assert.equal((await press('zzz')).status, 409);
  const off = model; off.profiles[0].pages[0].buttons[1].computer = 'c-77aa01de'; await r('macropads', 'PUT', off); assert.match((await press('b-2')).data.error, /offline/);
  const pair = (await r('desktop/pair', 'POST')).data; assert.match(pair.code, /^\d{6}$/); assert.ok(pair.expires > Date.now());
  assert.equal((await r('desktop/computers/c-77aa01de', 'DELETE')).status, 200); assert.equal((await r('desktop/computers/c-77aa01de', 'DELETE')).status, 404); assert.equal((await r('desktop/computers')).data.length, 1);
});

// ---- MacroPads look (schema 2) ----
const clone = v => JSON.parse(JSON.stringify(v));
test('macropads look: validation mirrors the phone, themes are valid, cascade resolves', () => {
  const themes = clone(run('MP_THEMES')); assert.ok(themes.length >= 5);
  for (const t of themes) assert.deepEqual(clone(H.mpLookProblems(t.look, t.id)), [], t.id);
  // The dashboard and the phone ship the same themes.
  const kotlin = fs.readFileSync('app/src/main/java/dev/homedroid/MacroLookCore.kt', 'utf8');
  assert.deepEqual([...kotlin.matchAll(/theme\("(\w+)", "(\w+)"/g)].map(m => m[1]), themes.map(t => t.id));
  const bad = (look, re) => assert.ok(clone(H.mpLookProblems(look, 'x')).some(m => re.test(m)), JSON.stringify(look));
  bad({bg: {type: 'video'}}, /use color/); bad({bg: {type: 'gradient'}}, /gradient needs/); bad({bg: {type: 'image'}}, /choose an image/); bad({bg: {angle: 50}}, /steps of 45/);
  bad({bg: {blur: 26}}, /0 to 25/); bad({bg: {dim: 91}}, /0 to 90/); bad({bg: {color: 'red'}}, /colour like/); bad({bg: {colors: ['#000000']}}, /2 or 3/); bad({bg: {image: '../x'}}, /uploaded file/);
  bad({font: {family: 'comic'}}, /use system/); bad({font: {family: 'custom'}}, /upload a font/); bad({font: {weight: 450}}, /steps of 100/); bad({font: {scale: 49}}, /50 to 200/); bad({font: {upper: 'yes'}}, /on or off/);
  bad({key: {radius: 41}}, /0 to 40/); bad({key: {gap: -1}}, /0 to 24/); bad({key: {border: 7}}, /0 to 6/); bad({icon: {size: 15}}, /16 to 64/); bad({icon: {tint: 'blue'}}, /colour like/); bad({nope: {}}, /unknown look section/); bad({key: {nope: 1}}, /not an option/);
  assert.deepEqual(clone(H.mpLookProblems({bg: {type: 'image', image: 'a-0123456789abcdef', blur: 25, dim: 90}, font: {family: 'custom', custom: 'a-0123456789abcdef', weight: 900}}, 'x')), []);
  const r = clone(H.mpLookResolve({key: {radius: 4, gap: 10}, font: {scale: 120, color: '#FF0000'}}, {key: {radius: 30}}, {font: {color: null, upper: true}}));
  assert.equal(r.key.radius, 30); assert.equal(r.key.gap, 10); assert.equal(r.font.color, '#FF0000'); assert.equal(r.font.upper, true); assert.deepEqual(r.icon, {});
  assert.equal(clone(H.mpLookResolve({bg: {type: 'image'}})).bg.type, undefined);
  let look = H.mpLookWith({theme: 'neon', key: {radius: 4}}, 'key', 'gap', 9); assert.deepEqual(clone(look), {key: {radius: 4, gap: 9}});
  look = H.mpLookWith(look, 'key', 'radius', ''); look = H.mpLookWith(look, 'key', 'gap', ''); assert.equal(look, undefined); assert.equal(clone(H.mpLookWith(undefined, 'key', 'gradient', false)).key.gradient, false);
  const css = clone(H.mpLookCss(H.mpLookResolve(themes.find(t => t.id === 'neon').look), id => '/a/' + id));
  assert.match(css.board, /#07040F/); assert.match(css.key, /border-radius:14px/); assert.ok(css.shadows.length); assert.match(css.label, /uppercase/); assert.equal(css.textColor, '#E0FFFF'); assert.equal(css.keyColor, '#120B22');
  const img = clone(H.mpLookCss({bg: {type: 'image', image: 'a-1234567890', blur: 10, dim: 50}, font: {}, key: {}, icon: {}}, id => '/a/' + id)); assert.match(img.image, /blur\(5px\)/); assert.match(img.image, /rgba\(0,0,0,0\.5\)/);
  assert.match(clone(H.mpLookCss({bg: {type: 'gradient', colors: ['#111111', '#222222'], angle: 90}, font: {position: 'none'}, key: {}, icon: {}})).board, /linear-gradient\(90deg/);
  assert.equal(clone(H.mpLookCss({bg: {}, font: {position: 'inside'}, key: {}, icon: {}})).inside, true);
});
test('macropads look: deck problems cover looks on every level and per-key fields', () => {
  const deck = clone(require('./dashboard-mock/extras.cjs').starterDeck());
  assert.equal(deck.version, 2); assert.ok(deck.look && deck.profiles[1].look); assert.deepEqual(clone(H.macroProblems(deck)), []);
  assert.deepEqual(clone(H.macroProblems({...deck, version: 1})), []); assert.ok(H.macroProblems({...deck, version: 3}).length);
  const probs = d => clone(H.macroProblems(d)), mod = fn => { const d = clone(deck); fn(d); return probs(d); };
  assert.ok(mod(d => { d.look.key.radius = 99; }).some(x => /deck look/.test(x)));
  assert.ok(mod(d => { d.profiles[0].look = {font: {weight: 1}}; }).some(x => /Profile "Everyday" look/.test(x)));
  assert.ok(mod(d => { d.profiles[0].pages[0].look = {bg: {type: 'gradient'}}; }).some(x => /page "Main" look/.test(x)));
  const keyProblem = (f, v) => mod(d => { d.profiles[0].pages[0].buttons[0][f] = v; });
  assert.ok(keyProblem('textSize', 5).some(x => /label size/.test(x))); assert.ok(keyProblem('align', 'top').some(x => /align/.test(x))); assert.ok(keyProblem('image', 'nope').some(x => /image icon/.test(x)));
  assert.deepEqual(keyProblem('textSize', 40), []); assert.deepEqual(keyProblem('align', 'end'), []); assert.deepEqual(keyProblem('image', 'a-0123456789ab'), []);
});
test('mock: MacroPads look, themes and uploaded images and fonts', async t => {
  const r = await mock(t), deck = (await r('macropads')).data;
  assert.equal(deck.version, 2); assert.equal(deck.look.theme, 'studio'); assert.equal(deck.profiles[1].look.bg.type, 'image');
  const themes = (await r('macropads/themes')).data; assert.ok(themes.length >= 5 && themes.every(x => x.id && x.name && x.look.bg));
  const assets = (await r('macropads/assets')).data; assert.equal(assets.length, 1); assert.equal(assets[0].kind, 'image'); assert.ok(assets[0].bytes > 100);
  const got = await fetch(r.base + '/api/macropads/assets/' + assets[0].id, {headers: {Authorization: 'Bearer demo'}}); if (got.ok) assert.equal(got.headers.get('content-type'), 'image/png');
  const png = Buffer.from('89504e470d0a1a0a00000000', 'hex'), ttf = Buffer.from('0001000000000000', 'hex');
  const up = await r('macropads/assets?kind=image&name=pic.png', 'POST', undefined, png); assert.equal(up.status, 200); assert.match(up.data.id, /^a-[a-z0-9]{6,32}$/);
  assert.equal((await r('macropads/assets?kind=font', 'POST', undefined, png)).status, 400); assert.equal((await r('macropads/assets?kind=image', 'POST', undefined, ttf)).status, 400);
  assert.equal((await r('macropads/assets?kind=script', 'POST', undefined, png)).status, 400); assert.equal((await r('macropads/assets?kind=image', 'POST', undefined, Buffer.alloc(0))).status, 400);
  const font = (await r('macropads/assets?kind=font&name=my.ttf', 'POST', undefined, ttf)).data;
  const {computers, ...model} = deck;
  model.look = {bg: {type: 'image', image: up.data.id, blur: 8, dim: 20}, font: {family: 'custom', custom: font.id}}; assert.equal((await r('macropads', 'PUT', model)).status, 200);
  assert.equal((await r('macropads/assets/' + up.data.id, 'DELETE')).status, 409);
  model.look = {bg: {type: 'image', image: 'a-ffffffffffff'}}; const missing = await r('macropads', 'PUT', model); assert.equal(missing.status, 400); assert.match(missing.data.error, /Unknown image/);
  model.look = {font: {family: 'custom', custom: up.data.id}}; assert.match((await r('macropads', 'PUT', model)).data.error, /Unknown font/);
  model.look = {key: {radius: 80}}; assert.equal((await r('macropads', 'PUT', model)).status, 400);
  model.look = {bg: {type: 'oled'}}; assert.equal((await r('macropads', 'PUT', model)).status, 200);
  assert.equal((await r('macropads/assets/' + up.data.id, 'DELETE')).status, 200); assert.equal((await r('macropads/assets/' + up.data.id)).status, 404);
});
