// Run with node --test tests/media-stack.test.cjs. Needs sh, curl and jq.
// Runs the real media-stack helper against fake Radarr, Sonarr, Prowlarr, qBittorrent, aria2 and Seerr
// servers on their usual loopback ports, with its files in a temporary folder.
const {test, before, after} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const http = require('node:http');
const {spawn} = require('node:child_process');

const SCRIPT = path.resolve('app/src/main/assets/media-stack.sh');
const catalog = JSON.parse(fs.readFileSync('app/src/main/assets/apps.json', 'utf8'));
const script = fs.readFileSync(SCRIPT, 'utf8');

const KEYS = {radarr: 'r'.repeat(32), sonarr: 's'.repeat(32), prowlarr: 'p'.repeat(32), seerr: 'e'.repeat(32)};
const field = (name, value = '') => ({name, value});
// Shaped like the real schema endpoints: a list of providers with their default fields.
const SCHEMAS = {
  qbt: cat => [
    {implementation: 'Transmission', name: '', fields: [field('host', 'localhost'), field('port', 9091)]},
    {implementation: 'QBittorrent', name: '', enable: false, protocol: 'torrent', priority: 1, tags: [],
      fields: [field('host', 'localhost'), field('port', 8080), field('useSsl', false), field('urlBase'),
        field('username'), field('password'), field(cat, 'default'), field('initialState', 0)]},
    {implementation: 'Aria2', name: '', enable: false, protocol: 'torrent', priority: 1, tags: [],
      fields: [field('host', 'localhost'), field('port', 6800), field('rpcPath', '/rpc'), field('useSsl', false),
        field('secretToken'), field('directory')]},
  ],
  apps: [
    {implementation: 'Radarr', name: '', syncLevel: 'addOnly', tags: [], fields: [field('prowlarrUrl'), field('baseUrl'), field('apiKey'), field('syncCategories', [2000])]},
    {implementation: 'Sonarr', name: '', syncLevel: 'addOnly', tags: [], fields: [field('prowlarrUrl'), field('baseUrl'), field('apiKey'), field('syncCategories', [5000])]},
  ],
};

function arr(app, ver, category, store) {
  return (req, body) => {
    if (req.headers['x-api-key'] !== KEYS[app]) return [401, {}];
    const p = req.url.replace(`/api/${ver}`, '');
    const list = p.split('?')[0];
    if (p === '/system/status') return [200, {version: '1'}];
    if (p === '/downloadclient/schema') return [200, SCHEMAS.qbt(category)];
    if (p === '/applications/schema') return [200, SCHEMAS.apps];
    if (p === '/qualityprofile') return [200, [{id: 1, name: 'Any'}, {id: 4, name: 'HD-1080p'}]];
    if (['/downloadclient', '/rootfolder', '/applications'].includes(list)) {
      store[list] ??= [];
      if (req.method === 'GET') return [200, store[list]];
      store[list].push({...body, query: p.split('?')[1] || ''});
      return [201, body];
    }
    return [404, {}];
  };
}

let root, servers = [], seerrDown = false, aria2Calls = 0;
const ARIA2_SECRET = 'a'.repeat(32);
const stores = {radarr: {}, sonarr: {}, prowlarr: {}, seerr: {radarr: [], sonarr: []}};

function serve(port, handler) {
  return new Promise((resolve, reject) => {
    const s = http.createServer((req, res) => {
      let data = '';
      req.on('data', c => data += c);
      req.on('end', () => {
        const [status, out] = handler(req, data ? JSON.parse(data) : null);
        res.writeHead(status, {'Content-Type': 'application/json'});
        res.end(JSON.stringify(out));
      });
    });
    s.on('error', reject);
    s.listen(port, '127.0.0.1', () => { servers.push(s); resolve(); });
  });
}

function run(args, ms = 20000) {
  return new Promise(resolve => {
    // Its own process group, so a timeout also stops the helper's background sleeps.
    const child = spawn('sh', [SCRIPT, ...args], {env: {...process.env, MEDIA_STACK_ROOT: root}, detached: true});
    let out = '';
    child.stdout.on('data', d => out += d);
    child.stderr.on('data', d => out += d);
    const timer = setTimeout(() => { try { process.kill(-child.pid, 'SIGKILL'); } catch {} }, ms);
    child.on('close', code => { clearTimeout(timer); resolve({code, out}); });
  });
}

function install(app, title) {
  fs.mkdirSync(path.join(root, 'opt', title), {recursive: true});
  fs.writeFileSync(path.join(root, 'opt', title, title), '#!/bin/sh\n', {mode: 0o755});
  fs.writeFileSync(path.join(root, 'opt', title, `${title}.dll`), '');
  fs.mkdirSync(path.join(root, 'var/lib', app), {recursive: true});
  fs.writeFileSync(path.join(root, 'var/lib', app, 'config.xml'), `<Config>\n  <Port>1</Port>\n  <ApiKey>${KEYS[app]}</ApiKey>\n</Config>\n`);
}

before(async () => {
  root = fs.mkdtempSync(path.join(os.tmpdir(), 'media-stack-'));
  for (const bin of ['qbittorrent-nox', 'jellyfin']) {
    fs.mkdirSync(path.join(root, 'usr/bin'), {recursive: true});
    fs.writeFileSync(path.join(root, 'usr/bin', bin), '#!/bin/sh\n', {mode: 0o755});
  }
  install('radarr', 'Radarr');
  install('sonarr', 'Sonarr');
  install('prowlarr', 'Prowlarr');
  await serve(7878, arr('radarr', 'v3', 'movieCategory', stores.radarr));
  await serve(8989, arr('sonarr', 'v3', 'tvCategory', stores.sonarr));
  await serve(9696, arr('prowlarr', 'v1', 'category', stores.prowlarr));
  await serve(8081, req => req.url === '/api/v2/app/version' ? [200, 'v5.2.1'] : [404, {}]);
  // aria2's JSON-RPC: answers only with the secret from its config file.
  await serve(6800, (req, body) => {
    aria2Calls++;
    if (req.url !== '/jsonrpc' || body?.method !== 'aria2.getVersion') return [404, {}];
    if (body.params?.[0] !== `token:${ARIA2_SECRET}`) return [400, {error: {code: 1, message: 'Unauthorized'}}];
    return [200, {id: body.id, jsonrpc: '2.0', result: {version: '1.37.0'}}];
  });
  await serve(5055, (req, body) => {
    if (req.url === '/api/v1/settings/public') return [200, {initialized: true}];
    if (seerrDown) return [500, {message: 'database is locked'}];
    if (req.headers['x-api-key'] !== KEYS.seerr) return [403, {}];
    const kind = req.url.replace('/api/v1/settings/', '');
    if (!(kind in stores.seerr)) return [404, {}];
    if (req.method === 'GET') return [200, stores.seerr[kind]];
    stores.seerr[kind].push(body);
    return [200, body];
  });
});

after(() => {
  for (const s of servers) s.close();
  fs.rmSync(root, {recursive: true, force: true});
});

const fieldOf = (item, name) => item.fields.find(f => f.name === name)?.value;

test('link connects Radarr and Sonarr to qBittorrent and their library folders', async () => {
  const {code, out} = await run(['link']);
  assert.equal(code, 0, out);
  for (const [app, cat, value, folder] of [['radarr', 'movieCategory', 'radarr', '/media/Movies'], ['sonarr', 'tvCategory', 'tv-sonarr', '/media/Shows']]) {
    const [client] = stores[app]['/downloadclient'];
    assert.equal(client.implementation, 'QBittorrent');
    assert.equal(client.name, 'qBittorrent');
    assert.equal(client.enable, true);
    assert.equal(client.query, 'forceSave=true');
    assert.equal(fieldOf(client, 'host'), '127.0.0.1');
    assert.equal(fieldOf(client, 'port'), 8081);
    assert.equal(fieldOf(client, cat), value);
    assert.equal(fieldOf(client, 'initialState'), 0, 'fields the helper does not set keep their defaults');
    assert.deepEqual(stores[app]['/rootfolder'].map(r => r.path), [folder]);
    assert.ok(fs.existsSync(path.join(root, folder)), `${folder} is created`);
  }
});

test('link gives Prowlarr both apps with their API keys and full sync', async () => {
  const apps = stores.prowlarr['/applications'];
  assert.deepEqual(apps.map(a => a.name), ['Radarr', 'Sonarr']);
  for (const [a, port, app] of [[apps[0], 7878, 'radarr'], [apps[1], 8989, 'sonarr']]) {
    assert.equal(a.syncLevel, 'fullSync');
    assert.equal(fieldOf(a, 'prowlarrUrl'), 'http://127.0.0.1:9696');
    assert.equal(fieldOf(a, 'baseUrl'), `http://127.0.0.1:${port}`);
    assert.equal(fieldOf(a, 'apiKey'), KEYS[app]);
  }
  assert.equal(fieldOf(stores.prowlarr['/downloadclient'][0], 'category'), 'prowlarr');
});

test('link adds Movies and Shows libraries to Jellyfin', () => {
  for (const [name, type] of [['Movies', 'movies'], ['Shows', 'tvshows']]) {
    const dir = path.join(root, 'var/lib/jellyfin/root/default', name);
    assert.equal(fs.readFileSync(path.join(dir, `${name}.mblink`), 'utf8'), `/media/${name}`);
    assert.ok(fs.existsSync(path.join(dir, `${type}.collection`)));
    assert.match(fs.readFileSync(path.join(dir, 'options.xml'), 'utf8'), new RegExp(`<Path>/media/${name}</Path>`));
  }
});

test('running link again changes nothing, and removed connections stay removed', async () => {
  stores.radarr['/rootfolder'].length = 0; // the user deleted Radarr's root folder
  stores.prowlarr['/applications'].pop(); // and Sonarr in Prowlarr
  const before = JSON.stringify([stores.radarr, stores.sonarr, stores.prowlarr]);
  fs.rmSync(path.join(root, 'var/lib/jellyfin/root/default/Shows'), {recursive: true});
  const {code} = await run(['link']);
  assert.equal(code, 0);
  assert.equal(JSON.stringify([stores.radarr, stores.sonarr, stores.prowlarr]), before);
  assert.ok(!fs.existsSync(path.join(root, 'var/lib/jellyfin/root/default/Shows')), 'a library removed in Jellyfin is not recreated');
});

test('passes started at the same moment add each connection once', async () => {
  for (const app of ['radarr', 'sonarr', 'prowlarr']) {
    for (const k of Object.keys(stores[app])) stores[app][k].length = 0;
    for (const f of fs.readdirSync(path.join(root, 'var/lib', app))) if (f.startsWith('.homedroid-linked-')) fs.rmSync(path.join(root, 'var/lib', app, f));
  }
  const runs = await Promise.all([run(['link']), run(['link']), run(['link'])]);
  for (const r of runs) assert.equal(r.code, 0, r.out);
  assert.equal(stores.radarr['/downloadclient'].length, 1);
  assert.equal(stores.sonarr['/rootfolder'].length, 1);
  assert.equal(stores.prowlarr['/applications'].length, 2);
});

test('aria2 is left alone until it is installed, then added beside qBittorrent', async () => {
  aria2Calls = 0;
  await run(['link']);
  assert.equal(aria2Calls, 0, 'no aria2c binary, no RPC call');
  for (const app of ['radarr', 'sonarr', 'prowlarr']) {
    assert.deepEqual(stores[app]['/downloadclient'].map(c => c.name), ['qBittorrent']);
  }

  fs.writeFileSync(path.join(root, 'usr/bin/aria2c'), '#!/bin/sh\n', {mode: 0o755});
  fs.mkdirSync(path.join(root, 'var/lib/aria2'), {recursive: true});
  fs.writeFileSync(path.join(root, 'var/lib/aria2/aria2.conf'), `dir=/media/Downloads\nrpc-secret=${ARIA2_SECRET}\n`);
  const {code, out} = await run(['link']);
  assert.equal(code, 0, out);
  for (const app of ['radarr', 'sonarr', 'prowlarr']) {
    const clients = stores[app]['/downloadclient'];
    assert.deepEqual(clients.map(c => c.name), ['qBittorrent', 'aria2'], app);
    const aria2 = clients[1];
    assert.equal(aria2.implementation, 'Aria2');
    assert.equal(aria2.enable, true);
    assert.equal(aria2.query, 'forceSave=true');
    assert.equal(fieldOf(aria2, 'host'), '127.0.0.1');
    assert.equal(fieldOf(aria2, 'port'), 6800);
    assert.equal(fieldOf(aria2, 'rpcPath'), '/rpc');
    assert.equal(fieldOf(aria2, 'secretToken'), ARIA2_SECRET);
    assert.ok(fs.existsSync(path.join(root, 'var/lib', app, '.homedroid-linked-aria2')));
  }

  const before = JSON.stringify([stores.radarr, stores.sonarr, stores.prowlarr]);
  await run(['link']);
  assert.equal(JSON.stringify([stores.radarr, stores.sonarr, stores.prowlarr]), before, 'added once');
});

test('the aria2 module is set up like the other torrent client', () => {
  const app = catalog.find(a => a.id === 'aria2');
  assert.equal(app.port, 6800);
  assert.equal(app.storage.shared, 'Media', 'aria2 shares the media library with Jellyfin');
  assert.deepEqual(app.run, ['/usr/local/bin/homedroid-aria2']);
  assert.match(app.install, /^dir=\/media\/Downloads$/m);
  assert.match(app.install, /^rpc-listen-port=6800$/m);
  assert.match(app.install, /^rpc-secret=\$secret$/m);
  assert.match(app.install, /echo "RPC secret: /);
  assert.ok(new RegExp(app.notice.pattern).test('RPC secret: 0123abcd'), 'the notice finds the secret in the log');
  // Jellyfin's Downloads library appears with either torrent client, whichever is installed last.
  for (const id of ['jellyfin', 'qbittorrent', 'aria2']) {
    assert.match(catalog.find(a => a.id === id).install, /\{ \[ -x \/usr\/bin\/qbittorrent-nox \] \|\| \[ -x \/usr\/bin\/aria2c \]; \}/, id);
  }
});

test('a Seerr error is not taken for "already connected"', async () => {
  fs.mkdirSync(path.join(root, 'var/lib/seerr'), {recursive: true});
  fs.writeFileSync(path.join(root, 'var/lib/seerr/settings.json'), JSON.stringify({main: {apiKey: KEYS.seerr}}));
  seerrDown = true;
  await run(['seerr-link'], 1500);
  seerrDown = false;
  assert.ok(!fs.existsSync(path.join(root, 'var/lib/seerr/.homedroid-linked-radarr')));
  assert.equal(stores.seerr.radarr.length, 0);
});

test('Seerr gets Radarr and Sonarr with the HD-1080p profile once set up', async () => {
  fs.mkdirSync(path.join(root, 'var/lib/seerr'), {recursive: true});
  fs.writeFileSync(path.join(root, 'var/lib/seerr/settings.json'), JSON.stringify({main: {apiKey: KEYS.seerr}}));
  // seerr-link keeps running after it is done; stop it once both are added.
  const done = run(['seerr-link'], 5000);
  for (let i = 0; i < 100 && stores.seerr.sonarr.length === 0; i++) await new Promise(r => setTimeout(r, 100));
  const [radarr] = stores.seerr.radarr, [sonarr] = stores.seerr.sonarr;
  assert.equal(radarr.hostname, '127.0.0.1');
  assert.equal(radarr.port, 7878);
  assert.equal(radarr.apiKey, KEYS.radarr);
  assert.equal(radarr.activeProfileId, 4);
  assert.equal(radarr.activeProfileName, 'HD-1080p');
  assert.equal(radarr.activeDirectory, '/media/Movies');
  assert.equal(sonarr.port, 8989);
  assert.equal(sonarr.activeDirectory, '/media/Shows');
  assert.equal(sonarr.isDefault, true);
  assert.ok(fs.existsSync(path.join(root, 'var/lib/seerr/.homedroid-linked-radarr')));
  await done;
});

test('keys lists every API key', async () => {
  const {out} = await run(['keys']);
  for (const k of Object.values(KEYS)) assert.match(out, new RegExp(k));
});

test('every CPU an app is offered on has a pinned build', () => {
  const builds = [...script.matchAll(/^ {2}(\w+)\/(\w+)\)\s+v=[\d.]+; echo "(\S+) ([0-9a-f]{64})"/gm)];
  const arch = {arm64: 'aarch64', arm: 'armv7', amd64: 'x86_64'};
  for (const id of ['prowlarr', 'radarr', 'sonarr']) {
    const app = catalog.find(a => a.id === id);
    const arches = app.arches?.length ? app.arches : Object.keys(arch);
    for (const a of arches) assert.ok(builds.some(b => b[1] === id && b[2] === arch[a]), `${id} on ${a}`);
    assert.deepEqual(app.run, ['/usr/local/lib/homedroid/media-stack', 'run', id]);
    assert.equal(app.storage.shared, 'Media', `${id} shares the media library with Jellyfin and qBittorrent`);
  }
  for (const b of builds) assert.match(b[3], /^https:\/\/github\.com\/(Prowlarr|Radarr|Sonarr)\//);
});

test('app ports are unique and match the helper', () => {
  const ports = catalog.map(a => a.port);
  assert.equal(new Set(ports).size, ports.length);
  for (const [id, port] of [['prowlarr', 9696], ['radarr', 7878], ['sonarr', 8989], ['seerr', 5055], ['aria2', 6800]]) {
    assert.equal(catalog.find(a => a.id === id).port, port);
  }
});
