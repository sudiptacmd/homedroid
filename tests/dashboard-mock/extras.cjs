// Mock endpoints for the console pages: databases and documents, domains, request logs, dynamic DNS, firewall,
// MCP and its agent, deployment settings/hooks/uploads/pipelines, MacroPads and paired computers.
// Everything is in memory and simulated: no SQLite file is opened, no DNS provider or computer is contacted.
'use strict';
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const net = require('node:net');
const crypto = require('node:crypto');
const F = require('./fixtures.cjs');

const hex = n => crypto.randomBytes(Math.ceil(n / 2)).toString('hex').slice(0, n);
const fail = (code, message) => { throw Object.assign(new Error(message), {code}); };
const ok = {ok: true};

// The dashboard's own MacroPads validator, so the mock rejects exactly what the page would flag.
function dashboardHelpers() {
  const html = fs.readFileSync(path.join(F.ASSETS, 'dashboard.html'), 'utf8');
  const a = html.indexOf('// ---- Console helpers'), b = html.indexOf('// ---- End console helpers');
  if (a < 0 || b < 0) return {macroProblems: () => []};
  const ctx = vm.createContext({bytes: n => n + ' B', URL, location: {}});
  vm.runInContext(html.slice(a, b) + '\nthis.macroProblems = macroProblems;', ctx);
  return ctx;
}

// ---- Databases -------------------------------------------------------------------------------
const T = (name, cols, total, fks = []) => ({name, cols, total, fks});
const col = (name, type, pk = 0, notnull = 0) => ({name, type, pk, notnull});
const DB_SPECS = {
  blog: [
    T('users', [col('id', 'INTEGER', 1, 1), col('name', 'TEXT', 0, 1), col('email', 'TEXT'), col('created_at', 'TEXT')], 42),
    T('posts', [col('id', 'INTEGER', 1, 1), col('author_id', 'INTEGER', 0, 1), col('title', 'TEXT', 0, 1), col('body', 'TEXT'), col('published', 'INTEGER'), col('views', 'INTEGER')], 318, [['author_id', 'users']]),
    T('comments', [col('id', 'INTEGER', 1, 1), col('post_id', 'INTEGER', 0, 1), col('user_id', 'INTEGER'), col('body', 'TEXT')], 1204, [['post_id', 'posts'], ['user_id', 'users']]),
    T('tags', [col('id', 'INTEGER', 1, 1), col('name', 'TEXT', 0, 1)], 17),
    T('post_tags', [col('post_id', 'INTEGER', 1, 1), col('tag_id', 'INTEGER', 2, 1)], 640, [['post_id', 'posts'], ['tag_id', 'tags']]),
    T('images', [col('id', 'INTEGER', 1, 1), col('post_id', 'INTEGER'), col('data', 'BLOB'), col('alt', 'TEXT')], 96, [['post_id', 'posts']]),
  ],
  ha: [
    T('state_attributes', [col('attributes_id', 'INTEGER', 1, 1), col('hash', 'INTEGER'), col('shared_attrs', 'TEXT')], 5210),
    T('states', [col('state_id', 'INTEGER', 1, 1), col('entity_id', 'TEXT'), col('state', 'VARCHAR(255)'), col('attributes_id', 'INTEGER'), col('last_changed', 'TEXT')], 184210, [['attributes_id', 'state_attributes']]),
    T('events', [col('event_id', 'INTEGER', 1, 1), col('event_type', 'TEXT'), col('time_fired', 'REAL')], 20113),
  ],
  jf: [
    T('BaseItems', [col('Id', 'BLOB', 1, 1), col('Type', 'TEXT', 0, 1), col('Name', 'TEXT'), col('Path', 'TEXT'), col('RunTimeTicks', 'INTEGER')], 3480),
    T('UserData', [col('Key', 'TEXT', 1, 1), col('UserId', 'INTEGER'), col('ItemId', 'BLOB'), col('Played', 'INTEGER')], 912, [['ItemId', 'BaseItems']]),
  ],
  docs: [T('docs_notes', [col('id', 'TEXT', 1, 1), col('data', 'TEXT', 0, 1), col('created', 'INTEGER', 0, 1), col('updated', 'INTEGER', 0, 1)], 5)],
};
const NAMES = ['Ada Lovelace', 'Grace Hopper', 'Linus T.', 'Margaret H.', 'Alan Turing', 'Radia Perlman', 'Ken Thompson', 'Barbara Liskov'];
const WORDS = 'server home phone deploy cache build route proxy domain secure local cloud logs tunnel stream'.split(' ');
function cellFor(t, c, i) {
  const n = i + 1, nullable = !c.notnull && !c.pk;
  if (nullable && n % 9 === 0) return null;
  if (c.pk) return c.type === 'BLOB' ? {blob: 16, hex: crypto.createHash('md5').update(String(n)).digest('hex')} : c.type === 'TEXT' ? 'id-' + n : n;
  if (c.type === 'BLOB') return {blob: 18200 + n * 31, hex: '89504e470d0a1a0a0000000d49484452' + hex(16)};
  if (/name/i.test(c.name)) return NAMES[i % NAMES.length];
  if (/email/.test(c.name)) return NAMES[i % NAMES.length].toLowerCase().replace(/[^a-z]/g, '.') + '@example.com';
  if (/title/i.test(c.name)) return `${WORDS[i % WORDS.length]} ${WORDS[(i * 3 + 1) % WORDS.length]} notes #${n}`;
  if (/body|attrs|attributes|data|Path/.test(c.name) && c.type === 'TEXT') {
    if (n % 7 === 0) return {text: '{"friendly_name": "Living room", "unit": "°C", "history": [' + Array.from({length: 120}, (_, k) => k * 3).join(', ') + ']}'.slice(0, 2000), size: 6400 + n};
    return t.name.startsWith('docs') ? JSON.stringify({title: 'Hello ' + n}) : `Lorem ipsum ${WORDS[i % WORDS.length]} ${WORDS[(i + 5) % WORDS.length]} dolor sit amet.`;
  }
  if (/_at$|changed|created|updated/.test(c.name)) return new Date(1.75e12 + i * 86400000).toISOString().slice(0, 19).replace('T', ' ');
  if (/entity/.test(c.name)) return ['sensor.living_room_temp', 'light.kitchen', 'switch.fan', 'binary_sensor.door'][i % 4];
  if (/^state$/.test(c.name)) return ['on', 'off', '21.4', 'unavailable'][i % 4];
  if (c.type === 'REAL') return 1.75e9 + i * 0.5;
  if (/INT/.test(c.type)) return (n * 37) % 1000;
  return `${c.name}-${n}`;
}
function dbFiles() {
  const now = Date.now();
  return [
    {root: 'alpine', rootLabel: 'Alpine (app settings and databases)', path: 'home/homeassistant/.config/home-assistant_v2.db', name: 'home-assistant_v2.db', size: 184 * 1048576, walSize: 4 * 1048576, modified: now - 40000, wal: true, app: 'Home Assistant', inUse: true, key: 'ha'},
    {root: 'alpine', rootLabel: 'Alpine (app settings and databases)', path: 'var/lib/jellyfin/data/library.db', name: 'library.db', size: 96 * 1048576, walSize: 0, modified: now - 3600000, wal: false, app: 'Jellyfin', inUse: true, key: 'jf'},
    {root: 'alpine', rootLabel: 'Alpine (app settings and databases)', path: 'srv/my-blog/data/app.sqlite', name: 'app.sqlite', size: 2.4 * 1048576, walSize: 32768, modified: now - 86400000, wal: true, app: 'my-blog', inUse: false, key: 'blog'},
    {root: 'homedroid', rootLabel: 'Homedroid documents', path: 'docs.sqlite', name: 'docs.sqlite', size: 28672, walSize: 0, modified: now - 7200000, wal: false, app: null, inUse: false, key: 'docs'},
  ];
}
const sqlOf = t => `CREATE TABLE ${t.name} (${t.cols.map(c => `${c.name} ${c.type}${c.pk ? ' PRIMARY KEY' : ''}${c.notnull ? ' NOT NULL' : ''}`).join(', ')}${t.fks.map(([f, to]) => `, FOREIGN KEY (${f}) REFERENCES ${to}`).join('')})`;
function dbSchema(file) {
  const spec = DB_SPECS[file.key];
  const tables = spec.map(t => ({name: t.name, type: 'table', sql: sqlOf(t), internal: false,
    columns: t.cols.map(c => ({name: c.name, type: c.type, notnull: !!c.notnull, default: null, pk: c.pk})),
    indexes: [...(t.cols.some(c => c.pk && c.type !== 'INTEGER') ? [{name: `sqlite_autoindex_${t.name}_1`, unique: true, origin: 'pk', partial: false, columns: t.cols.filter(c => c.pk).map(c => c.name)}] : []),
      ...t.fks.map(([f]) => ({name: `idx_${t.name}_${f}`, unique: false, origin: 'c', partial: false, columns: [f]}))],
    foreignKeys: t.fks.map(([f, to]) => ({table: to, from: [f], to: null, onUpdate: 'NO ACTION', onDelete: f === 'post_id' ? 'CASCADE' : 'NO ACTION'})),
    rows: t.total, rowsCapped: t.total > 100000}));
  tables.push({name: 'sqlite_sequence', type: 'table', sql: 'CREATE TABLE sqlite_sequence(name,seq)', internal: true, columns: [{name: 'name', type: '', notnull: false, default: null, pk: 0}, {name: 'seq', type: '', notnull: false, default: null, pk: 0}], indexes: [], foreignKeys: [], rows: spec.length, rowsCapped: false});
  const relations = spec.flatMap(t => t.fks.map(([f, to]) => ({from: t.name, fromColumns: [f], to, toColumns: ['id']})));
  const f = {...file}; delete f.key;
  return {file: f, sqlite: '3.45.1', journal: file.wal ? 'wal' : 'delete', userVersion: 7, tables, relations, triggers: []};
}

function dbRoute(x, s, method, b, url, res, send) {
  const q = Object.fromEntries(url.searchParams);
  const files = dbFiles();
  const target = () => files.find(f => f.root === q.root && f.path === q.path) || fail(404, 'No such database');
  if (method === 'GET' && !s.length) return send(res, files.map(({key, ...f}) => f));
  if (method === 'GET' && s[0] === 'schema') return send(res, dbSchema(target()));
  if (method === 'GET' && s[0] === 'rows') {
    const file = target(), t = DB_SPECS[file.key].find(x => x.name === q.table) || fail(400, `No table ${q.table}`);
    const limit = Math.min(1000, Math.max(1, +q.limit || 100)), offset = Math.max(0, +q.offset || 0);
    const idx = Array.from({length: Math.max(0, Math.min(limit, t.total - offset))}, (_, k) => offset + k);
    if (q.order && t.cols.some(c => c.name === q.order) && q.dir === 'desc') idx.reverse();
    return send(res, {table: t.name, columns: t.cols.map(c => c.name), types: t.cols.map(c => c.type), offset, limit, total: t.total, totalCapped: false, rows: idx.map(i => t.cols.map(c => cellFor(t, c, i)))});
  }
  if (method === 'POST' && s[0] === 'query') {
    const file = files.find(f => f.root === b.root && f.path === b.path) || fail(404, 'No such database');
    const sql = String(b.sql || '').trim().replace(/;$/, ''), read = /^\s*(select|with|pragma|explain)\b/i.test(sql);
    if (!sql) fail(400, 'Enter a statement');
    if (/;\s*\S/.test(sql)) fail(400, 'Run one statement at a time');
    if (!b.write && !read) fail(400, 'Only SELECT, WITH, PRAGMA and EXPLAIN run read-only; turn on writing to change the database');
    if (b.write && !b.confirm) fail(400, 'Writing changes the database: confirm it first');
    if (b.write && file.inUse) fail(409, `${file.app} is running and uses this database; stop it before writing to it`);
    if (/sleep|recursive.*select.*select/i.test(sql)) fail(408, 'Stopped after 10 seconds; narrow the query down');
    const started = Date.now(), done = r => send(res, {rowsAffected: null, truncated: false, ...r, ms: 1.5 + (Date.now() - started) + (sql.length % 7)});
    if (!read) return done({columns: [], rows: [], rowsAffected: 3, ms: 4});
    const spec = DB_SPECS[file.key], tm = /from\s+"?(\w+)"?/i.exec(sql), t = tm && spec.find(x => x.name === tm[1]);
    if (tm && !t && !/sqlite_master/.test(sql)) fail(400, `no such table: ${tm[1]}`);
    if (/pragma\s+integrity_check/i.test(sql)) return done({columns: ['integrity_check'], rows: [['ok']]});
    if (/pragma\s+table_info/i.test(sql) && t) return done({columns: ['cid', 'name', 'type', 'notnull', 'dflt_value', 'pk'], rows: t.cols.map((c, i) => [i, c.name, c.type, c.notnull, null, c.pk])});
    if (/sqlite_master/i.test(sql)) return done({columns: ['name', 'type'], rows: [...spec.map(x => [x.name, 'table']), ['sqlite_sequence', 'table']].sort()});
    if (/count\s*\(\s*\*\s*\)/i.test(sql) && t) return done({columns: ['rows'], rows: [[t.total]]});
    if (/sqlite_version/i.test(sql)) return done({columns: ['sqlite_version()'], rows: [['3.45.1']]});
    if (t) {
      const lim = /limit\s+(\d+)/i.exec(sql), want = lim ? +lim[1] : 1e9, n = Math.min(want, t.total, 1000);
      const params = Array.isArray(b.params) ? b.params : [];
      return done({columns: t.cols.map(c => c.name), rows: Array.from({length: n}, (_, i) => t.cols.map(c => cellFor(t, c, i + (params.length ? +params[0] || 0 : 0)))), truncated: want > 1000 && t.total > 1000});
    }
    return done({columns: ['result'], rows: [[1]]});
  }
  return false;
}

// ---- Documents -------------------------------------------------------------------------------
function docState() {
  const t = Date.now();
  const mk = (col, o, i) => ({_id: o._id || hex(12), ...o, _created: t - (20 - i) * 3600000, _updated: t - (10 - i) * 600000});
  return {
    notes: [{title: 'Replace the router', status: 'open', tags: ['home'], priority: 2}, {title: 'Back up photos', status: 'done', tags: ['backup'], priority: 1}, {title: 'Renew domain', status: 'open', tags: ['dns'], priority: 3},
      {_id: 'groceries', title: 'Groceries', status: 'open', items: ['milk', 'bread', 'coffee']}, {title: 'Call mum', status: 'done', priority: 1}].map((o, i) => mk('notes', o, i)),
    bookmarks: Array.from({length: 12}, (_, i) => mk('bookmarks', {title: 'Bookmark ' + (i + 1), url: `https://example.com/${i + 1}`, visits: i * 3}, i)),
    settings: [mk('settings', {_id: 'theme', mode: 'dark', accent: '#6366f1'}, 0), mk('settings', {_id: 'locale', language: 'en', timezone: 'Europe/London'}, 1)],
  };
}
function docsRoute(x, s, method, b, url, res, send) {
  const docs = x.docs, col = s[0], id = s[1];
  const idOk = v => /^[A-Za-z0-9_.:-]{1,128}$/.test(v), nameOk = v => /^[A-Za-z0-9_-]{1,64}$/.test(v);
  const clean = o => { const c = {...o}; for (const k of ['_id', '_created', '_updated']) delete c[k]; return c; };
  if (!col) return method === 'GET' ? send(res, Object.entries(docs).map(([name, list]) => ({name, count: list.length}))) : false;
  if (!nameOk(col)) fail(400, 'Collection names use letters, digits, - and _ (up to 64)');
  if (method === 'POST' && !id) {
    const doc = docs[col] ||= [], _id = b._id || hex(24);
    if (!idOk(_id)) fail(400, 'Ids use letters, digits and . _ : - (up to 128)');
    if (doc.some(d => d._id === _id)) fail(409, `A document ${_id} already exists in ${col}`);
    const d = {...clean(b), _id, _created: Date.now(), _updated: Date.now()}; doc.push(d); return send(res, d, 201);
  }
  if (!docs[col]) fail(404, `No collection ${col}`);
  if (method === 'GET' && !id) {
    let filter = {}; try { filter = url.searchParams.get('q') ? JSON.parse(url.searchParams.get('q')) : {}; } catch { fail(400, 'Unexpected token in JSON'); }
    for (const v of Object.values(filter)) if (v && typeof v === 'object') fail(400, 'Filter values must be strings, numbers, booleans or null');
    const all = docs[col].filter(d => Object.entries(filter).every(([k, v]) => d[k] === v)), limit = Math.min(1000, +url.searchParams.get('limit') || 50), offset = +url.searchParams.get('offset') || 0;
    return send(res, {collection: col, total: all.length, offset, limit, items: all.slice(offset, offset + limit)});
  }
  if (method === 'DELETE' && !id) { delete docs[col]; return send(res, ok); }
  const d = docs[col].find(x => x._id === id);
  if (method === 'GET') return send(res, d || fail(404, `No document ${id}`));
  if (!d) fail(404, `No document ${id}`);
  if (method === 'PUT') { Object.keys(d).forEach(k => delete d[k]); Object.assign(d, clean(b), {_id: id, _created: d._created || Date.now(), _updated: Date.now()}); return send(res, d); }
  if (method === 'DELETE') { docs[col] = docs[col].filter(x => x !== d); return send(res, ok); }
  return false;
}

// ---- Domains, logs, DDNS, firewall -----------------------------------------------------------
const hostOk = h => { const s = String(h || '').trim().toLowerCase(); return s.includes('.') && s.split('.').every(l => /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/.test(l)) && !/^\d+$/.test(s.split('.').at(-1)) ? s : null; };
function targetOk(t) {
  if (!t) return 'Choose where the domain points';
  if (t.type === 'deploy' || t.type === 'app') return /^[a-z0-9][a-z0-9-]{0,63}$/.test(t.id || '') ? null : `Choose a ${t.type === 'app' ? 'app' : 'deployment'}`;
  if (t.type === 'static') return !t.path || t.path.split('/').every(p => /^[A-Za-z0-9._~@+-]+$/.test(p) && p !== '.' && p !== '..') ? null : 'The folder must be inside ~/www, like "blog"';
  if (t.type === 'port') return Number.isInteger(t.port) && t.port >= 1 && t.port <= 65535 && ![8080, 8800, 8801].includes(t.port) ? null : "Enter the local port the service listens on (not 8080, 8800 or 8801, Homedroid's own)";
  return `Unknown target type "${String(t.type).slice(0, 20)}"`;
}
function domainsState() {
  const t = Date.now();
  return [
    {id: 'a1b2c3d4e5f6', host: 'blog.example.com', target: {type: 'deploy', id: 'my-blog'}, headers: {'X-Frame-Options': 'DENY', '-Server': ''}, routes: [{path: '/api', target: {type: 'deploy', id: 'api-server'}, strip: true}],
      token: hex(32), verified: true, verifiedAt: t - 5 * 86400000, verifyError: null},
    {id: 'f6e5d4c3b2a1', host: 'photos.example.org', target: {type: 'app', id: 'jellyfin'}, headers: {}, routes: [], token: hex(32), verified: false, verifiedAt: 0, verifyError: 'https://photos.example.org: Unable to resolve host "photos.example.org"'},
  ];
}
function logsState() {
  const t = Date.now(), hosts = ['blog.example.com', 'blog.example.com', 'blog.example.com', 'photos.example.org'];
  const paths = ['/', '/posts/hello-world', '/api/posts?limit=20', '/assets/app.css', '/favicon.ico', '/wp-login.php', '/api/comments', '/rss.xml', '/posts/mock-server'];
  const statuses = [200, 200, 200, 200, 200, 304, 301, 404, 404, 500, 403];
  return Array.from({length: 260}, (_, i) => {
    const status = statuses[(i * 7 + 3) % statuses.length], bad = i % 13 === 0;
    return {time: t - (260 - i) * 41000, host: hosts[i % hosts.length], method: i % 11 === 0 ? 'POST' : 'GET', uri: paths[(i * 5) % paths.length], status: bad ? 502 : status,
      duration: Math.round((2 + ((i * 37) % 140) + (bad ? 900 : 0)) * 1000) / 1000, size: status === 304 ? 0 : 400 + ((i * 7919) % 90000), remote: `${[203, 198, 51, 192][i % 4]}.${(i * 3) % 250}.${(i * 11) % 250}.${(i % 200) + 1}`};
  });
}
function ddnsState() {
  const t = Date.now();
  return [
    {id: 'c0ffee000001', provider: 'duckdns', domain: 'myhome', token: '***', ipv6: false, enabled: true, ip: '203.0.113.42', lastUpdate: t - 1800000, lastCheck: t - 120000, result: 'ok', error: null},
    {id: 'c0ffee000002', provider: 'cloudflare', zone: 'example.com', record: 'home.example.com', proxied: false, token: '***', ipv6: true, enabled: true, ip: '203.0.113.42', ipv6Address: '2001:db8::42', lastUpdate: t - 86400000, lastCheck: t - 120000, result: 'error', error: 'Authentication error (10000): the token can’t edit this zone'},
  ];
}
function ddnsUpsert(x, b) {
  const old = x.ddns.find(e => e.id === b.id), provider = b.provider || old?.provider;
  const token = !b.token || b.token === '***' ? (old?.provider === provider ? old.token : '') : String(b.token).trim();
  if (!token) fail(400, 'Enter the token');
  if (/\s/.test(token)) fail(400, "That token doesn't look right");
  const e = {id: old?.id || hex(12), provider, token: '***', ipv6: !!b.ipv6, enabled: b.enabled !== false, ip: old?.ip, ipv6Address: old?.ipv6Address, lastUpdate: old?.lastUpdate, lastCheck: old?.lastCheck, result: old?.result, error: old?.error};
  if (provider === 'duckdns') {
    const names = String(b.domain || '').split(',').map(v => v.trim().toLowerCase().replace(/\.duckdns\.org$/, '')).filter(Boolean);
    if (!names.length || names.length > 5 || !names.every(n => /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/.test(n))) fail(400, 'Enter your DuckDNS name, like myhome (for myhome.duckdns.org)');
    e.domain = names.join(',');
  } else if (provider === 'cloudflare') {
    const zone = String(b.zone || '').trim().toLowerCase();
    if (!/^[0-9a-f]{32}$/.test(zone) && !hostOk(zone)) fail(400, "Enter the zone ID or the zone's domain, like example.com");
    const record = hostOk(b.record); if (!record) fail(400, "Enter the record's full name, like home.example.com");
    Object.assign(e, {zone, record, proxied: !!b.proxied});
  } else fail(400, 'Choose DuckDNS or Cloudflare');
  if (!old && x.ddns.length >= 32) fail(409, "That's the most entries this list holds");
  const i = x.ddns.findIndex(v => v.id === e.id);
  if (i >= 0) x.ddns[i] = {...e, token}; else x.ddns.push({...e, token});
  const stored = x.ddns.find(v => v.id === e.id); Object.assign(stored, {ip: '203.0.113.42', lastUpdate: Date.now(), lastCheck: Date.now(), result: 'ok', error: null});
  return pub(stored);
}
const pub = e => ({...e, token: '***'});
const cidrList = (rules, scope, action) => rules.filter(r => (r.scope === 'all' || r.scope === scope) && r.action === action);
function blockOf(cidrs) { const bl = new net.BlockList(); for (const c of cidrs) { const [a, p] = c.split('/'); const fam = net.isIPv6(a) ? 'ipv6' : 'ipv4'; if (p === undefined) bl.addAddress(a, fam); else bl.addSubnet(a, +p, fam); } return bl; }
function fwAllowsServer(rules, scope, ip) {
  const fam = net.isIPv6(ip) ? 'ipv6' : 'ipv4';
  if (ip === '127.0.0.1' || ip === '::1') return true;
  if (blockOf(cidrList(rules, scope, 'deny').map(r => r.cidr)).check(ip, fam)) return false;
  const allow = cidrList(rules, scope, 'allow');
  return !allow.length || blockOf(allow.map(r => r.cidr)).check(ip, fam);
}
function firewallRoute(x, s, method, b, res, send) {
  const you = '192.168.1.23';
  if (method === 'GET') return send(res, {rules: x.firewall, you});
  if (method !== 'POST') return false;
  const rules = b.rules || [];
  if (rules.length > 200) fail(400, "That's more than 200 rules");
  const out = rules.map((r, i) => {
    if (!['allow', 'deny'].includes(r.action)) fail(400, `Rule ${i + 1}: action must be allow or deny`);
    const c = String(r.cidr || '').trim(), [a, p] = c.split('/');
    if (!net.isIP(a) || (p !== undefined && !(/^\d{1,3}$/.test(p) && +p <= (net.isIPv6(a) ? 128 : 32)))) fail(400, `Rule ${i + 1}: "${c.slice(0, 60)}" isn't an IP address or range like 192.168.1.0/24`);
    const scope = r.scope || 'all'; if (!['dashboard', 'web', 'all'].includes(scope)) fail(400, `Rule ${i + 1}: scope must be dashboard, web or all`);
    return {action: r.action, cidr: c, scope, note: String(r.note || '').trim().slice(0, 80)};
  });
  if (!fwAllowsServer(out, 'dashboard', you)) fail(409, `These rules would block your own address (${you}) from the dashboard`);
  if (out.some(r => r.cidr === '10.66.66.0/24' && r.action === 'deny' && r.scope !== 'dashboard')) fail(409, 'Caddy rejected the web rules: simulated Caddy error');
  x.firewall = out; return send(res, {rules: out, you});
}

function domainsRoute(x, s, method, b, url, res, send, F2) {
  if (method === 'GET' && !s.length) return send(res, {domains: x.domains, error: x.domainsError, config: '/data/data/dev.homedroid/files/etc/Caddyfile.d/homedroid-domains.caddy'});
  if (method === 'GET' && s[0] === 'logs') {
    x.logs.push(...Array.from({length: 2}, (_, i) => ({...x.logs[(Date.now() + i) % x.logs.length], time: Date.now() - i * 900})));
    const host = url.searchParams.get('host'), status = (url.searchParams.get('status') || '').replace(/xx$/, ''), limit = Math.min(1000, +url.searchParams.get('limit') || 100);
    const list = x.logs.filter(e => (!host || e.host === host) && (!status || String(e.status).startsWith(status))).slice(-limit).reverse();
    return send(res, {entries: list});
  }
  if (method === 'POST' && !s.length) {
    const old = x.domains.find(d => d.id === b.id), host = hostOk(b.host);
    if (!host) fail(400, 'Enter a domain name like blog.example.com');
    const te = targetOk(b.target); if (te) fail(400, te);
    for (const [k, v] of Object.entries(b.headers || {})) { if (!/^-?[A-Za-z0-9!#$%&'*+.^_`|~-]{1,100}$/.test(k)) fail(400, `"${k.slice(0, 40)}" isn't a valid header name`); if (/["\\\n]/.test(String(v))) fail(400, `The value of ${k} can't contain quotes, backslashes or line breaks`); }
    const seen = new Set();
    (b.routes || []).forEach((r, i) => { if (!/^\/[A-Za-z0-9._~@+\/-]*$/.test(r.path) || r.path === '/') fail(400, `Route ${i + 1}: the path must look like /api`); if (seen.has(r.path)) fail(400, `The path ${r.path} is listed twice`); seen.add(r.path); const e = targetOk(r.target); if (e) fail(400, `Route ${r.path}: ${e}`); });
    if (x.domains.some(d => d.host === host && d.id !== old?.id)) fail(409, `${host} is already set up`);
    if (host.startsWith('rejected.')) fail(409, 'Caddy rejected the config: adapting config using caddyfile: parsing caddyfile tokens for \'reverse_proxy\': simulated error at Caddyfile.d/homedroid-domains.caddy:12');
    const same = old && old.host === host;
    const d = {id: old?.id || hex(12), host, target: b.target, headers: b.headers || {}, routes: (b.routes || []).map(r => ({path: r.path, target: r.target, strip: !!r.strip})), token: old?.token || hex(32),
      verified: !!(same && old.verified), verifiedAt: same ? old.verifiedAt : 0, verifyError: same ? old.verifyError : null};
    const i = x.domains.indexOf(old); if (i >= 0) x.domains[i] = d; else x.domains.push(d);
    return send(res, d);
  }
  if (method === 'DELETE' && s.length === 1) { need(x.domains.find(d => d.id === s[0]), 'No such domain'); x.domains = x.domains.filter(d => d.id !== s[0]); return send(res, ok); }
  if (method === 'POST' && s[1] === 'verify') {
    const d = need(x.domains.find(v => v.id === s[0]), 'No such domain');
    x.verifyTries[d.id] = (x.verifyTries[d.id] || 0) + 1;
    if (d.host.includes('blog') || x.verifyTries[d.id] > 1) Object.assign(d, {verified: true, verifiedAt: Date.now(), verifyError: null});
    else Object.assign(d, {verified: false, verifyError: `http://${d.host}: connection timed out. Check the tunnel's public hostname or your port forwarding.`});
    return send(res, d);
  }
  return false;
}
const need = (v, m = 'Not found') => v || fail(404, m);

// ---- MCP -------------------------------------------------------------------------------------
const MCP_TOOLS = [['get_status', 'Phone status', 0], ['list_services', 'List services', 0], ['get_service_logs', 'Service logs', 0], ['restart_service', 'Restart service', 1], ['list_modules', 'List modules', 0],
  ['list_deployments', 'List deployments', 0], ['redeploy', 'Redeploy', 1], ['list_files', 'List files', 0], ['read_text_file', 'Read text file', 0], ['wake_on_lan', 'Wake on LAN', 1], ['network_info', 'Network info', 0]];
function mcpState() {
  const t = Date.now();
  return {enabled: true, allowWrite: false, token: 'hdm_' + hex(40), pending: [], audit: [
    {time: t - 3600000, client: '192.168.1.40', via: 'mcp', tool: 'get_status', args: '{}', ok: true}, {time: t - 3000000, client: '192.168.1.40', via: 'mcp', tool: 'list_services', args: '{}', ok: true},
    {time: t - 1800000, client: '192.168.1.40', via: 'mcp', tool: 'get_service_logs', args: '{"name":"caddy","lines":50}', ok: true}, {time: t - 900000, client: '127.0.0.1', via: 'agent', tool: 'list_deployments', args: '{}', ok: true},
    {time: t - 300000, client: '192.168.1.40', via: 'mcp', tool: 'read_text_file', args: '{"root":"home","path":"../etc/passwd"}', ok: false, error: 'Outside this location'}]};
}
function mcpRoute(x, p, s, method, b, res, send) {
  const m = x.mcp, a = s.join('/');
  const settings = () => ({enabled: m.enabled, allowWrite: m.allowWrite, versions: ['2025-06-18', '2025-03-26', '2024-11-05'], urls: ['http://192.168.1.20:8800/mcp', 'http://100.101.102.103:8800/mcp'],
    sseUrls: ['http://192.168.1.20:8800/mcp/sse', 'http://100.101.102.103:8800/mcp/sse'], tailscaleUrl: 'https://homedroid.<your-tailnet>.ts.net/mcp', tools: MCP_TOOLS.map(([name, title, write]) => ({name, title, write: !!write}))});
  if (a === 'settings') {
    if (method === 'POST') { for (const k of ['enabled', 'allowWrite']) if (k in b) { if (typeof b[k] !== 'boolean') fail(400, `${k} must be true or false`); m[k] = b[k]; } }
    return send(res, settings());
  }
  if (a === 'token') { if (method === 'POST') m.token = 'hdm_' + hex(40); return send(res, {token: m.token}); }
  if (a === 'audit') return send(res, {entries: m.audit.slice(-(+x.auditLimit || 100))});
  if (a === 'agent/pending') return send(res, {pending: m.pending});
  if (a === 'agent' && method === 'POST') {
    const text = String(b.message || '').trim();
    if (!text || text.length > 8000) fail(400, 'Write a message (up to 8000 characters)');
    const models = F.aiModels(p); if (!models.length) fail(409, 'Set up a model in AI first: one on this phone or a cloud service');
    const model = models.find(v => v.id === b.model) || models[0], calls = [], now = Date.now();
    const run = (name, args, result) => { m.audit.push({time: now, client: '192.168.1.20', via: 'agent', tool: name, args: JSON.stringify(args), ok: true}); calls.push({name, args, result}); };
    let reply;
    const restart = /restart\s+([a-z0-9-]+)/i.exec(text);
    if (restart) {
      const id = hex(16), tool = MCP_TOOLS.find(t => t[0] === 'restart_service');
      calls.push({name: 'restart_service', args: {name: restart[1]}, pending: true, id}); m.pending.push({id, name: tool[0], title: tool[1], args: {name: restart[1]}, created: now});
      reply = `I queued a restart of ${restart[1]}. It will only run after you approve it.`;
    } else if (/storage|disk|battery|status|temperature|memory/i.test(text)) {
      const st = F.status(p), used = Math.round((1 - st.storageFreeMb / st.storageTotalMb) * 100);
      run('get_status', {}, JSON.stringify({battery: st.battery, charging: st.charging, ramFreeMb: st.ramFreeMb, storageFreeMb: st.storageFreeMb, storageTotalMb: st.storageTotalMb, uptime: st.uptime}));
      reply = `Your phone is doing fine: **${used}% of storage** is used (${(st.storageFreeMb / 1024).toFixed(0)} GB free) and the battery is at ${st.battery}%.`;
    } else if (/service|running/i.test(text)) {
      run('list_services', {}, F.serviceList(p).slice(0, 4).map(v => `${v.name}: ${v.state}`).join('\n'));
      reply = 'Everything important is running: caddy, sshd and your apps. Open **Modules** to see the full list.';
    } else reply = `I can look up your phone’s status, services, logs and files, or queue changes for your approval. You asked: “${text.slice(0, 80)}”. Try “How much storage is left?” or “Restart sshd”.`;
    return send(res, {reply, toolCalls: calls, model: model.id, where: model.local ? 'local' : 'cloud', pending: m.pending});
  }
  if (a === 'agent/approve' || a === 'agent/reject') {
    const i = m.pending.findIndex(v => v.id === b.id); if (i < 0) fail(404, 'That action is gone; ask the agent again');
    const [q] = m.pending.splice(i, 1);
    if (a === 'agent/reject') { m.audit.push({time: Date.now(), client: '192.168.1.20', via: 'agent', tool: q.name, args: JSON.stringify(q.args), ok: false, error: 'rejected by the user'}); return send(res, ok); }
    m.audit.push({time: Date.now(), client: '192.168.1.20', via: 'agent (approved)', tool: q.name, args: JSON.stringify(q.args), ok: true});
    return send(res, {ok: true, id: q.id, name: q.name, result: `Restarted ${q.args.name}`});
  }
  return false;
}

// ---- Deployment extras -----------------------------------------------------------------------
function runsState() {
  const t = Date.now(), mk = (i, status, steps) => ({id: `${String(t - i * 3600000).padStart(13, '0')}-${hex(4)}`, trigger: ['push', 'manual', 'hook'][i % 3], status, started: t - i * 3600000, ms: steps.reduce((a, s) => a + s.ms, 0), steps});
  const st = (name, status, ms, log = '') => ({name, status, ms, log});
  return [
    mk(1, 'passed', [st('Install', 'passed', 8200, 'added 214 packages in 8s\n'), st('Test', 'passed', 5600, '> my-blog@1.4.0 test\n> vitest run\n\n ✓ src/posts.test.ts (12 tests) 340ms\n ✓ src/api.test.ts (8 tests) 210ms\n\n Test Files  2 passed (2)\n      Tests  20 passed (20)\n'), st('Lint', 'passed', 2100, '')]),
    mk(5, 'failed', [st('Install', 'passed', 7900, 'added 214 packages in 8s\n'), st('Test', 'failed', 4300, '> vitest run\n\n ✓ src/posts.test.ts (12 tests)\n ✗ src/api.test.ts > GET /api/posts returns 20 items\n   AssertionError: expected 19 to equal 20\n\n Test Files  1 failed | 1 passed (2)\nnpm ERR! Test failed. See above for more details.\n'), st('Lint', 'skipped', 0, '')]),
    mk(30, 'passed', [st('Install', 'passed', 8100), st('Test', 'passed', 5400), st('Lint', 'passed', 2000)]),
  ];
}
function deployExtras(x, p, s, method, b, raw, url, res, send, later, stepMs) {
  const d = (p.deploys.find(v => v.id === s[1])) || fail(404, `no deployment ${s[1]}`), act = s[2], runs = (x.runs[d.id] ||= d.id === 'my-blog' ? runsState() : []);
  const json = () => F.deployJson(p, d);
  if (act === 'settings' && method === 'POST') {
    if (d.replica) fail(400, 'this is a copy of another phone\'s deployment: change it there');
    if ('env' in b) { const env = b.env || {}; if (Object.keys(env).some(k => !/^[A-Za-z_][A-Za-z0-9_]*$/.test(k))) fail(400, 'environment variable names must be letters, digits and _'); if (Object.values(env).some(v => v === '***')) fail(400, 'masked values can\'t be saved'); d.env = env; }
    for (const k of ['branch', 'build', 'start', 'runtime', 'autoDeploy', 'replicated']) if (k in b) d[k] = b[k];
    if (!['auto', 'node', 'python', 'go', 'php', 'bun', 'deno', 'static'].includes(d.runtime)) fail(400, `unknown runtime ${d.runtime}`);
    if ('balanced' in b) {
      if (b.balanced) {
        const bal = b.balanced; if (!(bal.port >= 1024 && bal.port <= 65535)) fail(400, 'the balancer port must be between 1024 and 65535');
        if (p.deploys.some(o => o !== d && (o.port === bal.port || o.balanced?.port === bal.port)) || bal.port === d.port) fail(400, `balancer port ${bal.port} is already used`);
        if (!['round_robin', 'first', 'least_conn', 'ip_hash', 'random'].includes(bal.policy)) fail(400, `unknown balancing policy ${bal.policy}`);
        if (!/^\/[A-Za-z0-9._~\/?&=%+-]*$/.test(bal.healthUri || '/')) fail(400, 'the health check path must start with / and have no spaces');
        if (!(bal.interval >= 2 && bal.interval <= 3600)) fail(400, 'the health check interval must be 2–3600 seconds');
        d.balanced = {port: bal.port, policy: bal.policy, healthUri: bal.healthUri || '/', interval: bal.interval};
      } else d.balanced = null;
    }
    if ('pipeline' in b) { if (b.pipeline.length > 30) fail(400, 'a pipeline can have at most 30 steps'); b.pipeline.forEach((st, i) => { if (!String(st.run || '').trim()) fail(400, `pipeline step ${i + 1} needs a run command`); }); d.pipeline = b.pipeline.map((st, i) => ({name: st.name || `Step ${i + 1}`, run: st.run})); }
    return send(res, json());
  }
  if (act === 'token' && method === 'POST') {
    if (b.kind === 'webhook') { d.webhookSecret = 'whsec_' + hex(40); return send(res, {webhookSecret: d.webhookSecret, url: `/hooks/github/${d.id}`}); }
    d.token = 'hd_' + hex(40); return send(res, {token: d.token, url: `/hooks/deploy/${d.id}`});
  }
  if (act === 'upload' && method === 'POST') {
    if (d.source !== 'upload') fail(409, `${d.name} deploys from Git`);
    if (!raw.length) fail(400, 'Send the .zip or .tar.gz as the request body');
    const commit = 'upload-' + crypto.createHash('sha256').update(raw).digest('hex').slice(0, 12);
    const apply = () => { d.commit = commit; d.error = null; };
    if (url.searchParams.get('wait') === '1') return new Promise(r => later(() => { apply(); r(send(res, {ok: true, commit})); }, 900));
    later(apply); return send(res, {ok: true, queued: true});
  }
  if (act === 'pipeline' && method === 'POST') {
    if (!d.pipeline?.length) fail(409, 'No pipeline: add .homedroid/pipeline.json to the project or set steps');
    const run = {id: `${String(Date.now()).padStart(13, '0')}-${hex(4)}`, trigger: 'manual', status: 'queued', started: Date.now(), ms: 0, steps: d.pipeline.map(st => ({name: st.name, status: 'pending', ms: 0, log: ''}))};
    runs.unshift(run);
    run.steps.forEach((st, i) => {
      later(() => { run.status = 'running'; st.status = 'running'; st.log = `$ ${d.pipeline[i].run}\n`; }, i * 3 * stepMs + 1);
      later(() => { st.status = 'passed'; st.ms = 1500 + i * 900; st.log += 'ok\n'; run.ms += st.ms; if (i === run.steps.length - 1) run.status = 'passed'; }, i * 3 * stepMs + 2 * stepMs);
    });
    return send(res, {ok: true, run: run.id});
  }
  if (act === 'runs' && method === 'GET') {
    if (s[3]) return send(res, need(runs.find(r => r.id === s[3]), 'No such run'));
    return send(res, {runs: runs.map(r => ({...r, steps: r.steps.map(({log, ...st}) => st)}))});
  }
  return false;
}

// ---- MacroPads and paired computers ----------------------------------------------------------
function starterDeck() {
  const a = (label, icon, action, color = '#285A78') => ({label, icon, action, color, textColor: '#FFFFFF', disabled: false});
  const media = k => ({kind: 'media', key: k}), hot = (...keys) => ({kind: 'hotkey', keys});
  const list = [a('Play / pause', 'play', media('play_pause')), a('Previous', 'previous', media('previous')), a('Next', 'next', media('next')), {...a('Mute mic', 'mic-off', hot('ctrl', 'shift', 'm'), '#67509B'), toggle: {label: 'Unmute mic', icon: 'mic', color: '#A53E54'}},
    a('Screenshot', 'screenshot', {kind: 'key', key: 'printscreen'}), a('Volume down', 'volume-down', media('volume_down')), a('Volume up', 'volume-up', media('volume_up')), a('Mute', 'volume-mute', media('mute')), a('Lock screen', 'lock', hot('meta', 'l'), '#67509B'),
    a('Browser', 'browser', {kind: 'open', target: 'https://example.com'}), a('Copy', 'copy', hot('ctrl', 'c')), a('Paste', 'paste', hot('ctrl', 'v')), a('Undo', 'undo', hot('ctrl', 'z')), a('Redo', 'redo', hot('ctrl', 'shift', 'z')),
    {...a('Home Assistant', 'light', {kind: 'http', method: 'POST', url: 'http://homeassistant.local:8123/api/webhook/REPLACE_ME', body: ''}, '#806331'), disabled: true}];
  const buttons = list.map((b, i) => ({id: 'b-' + (i + 1), row: Math.floor(i / 5), col: i % 5, ...b}));
  const second = [a('Dev setup', 'terminal', {kind: 'sequence', steps: [{kind: 'launch', path: 'code', args: ['.']}, {kind: 'hotkey', keys: ['ctrl', '`'], delayMs: 800}]}, '#1F6F6B'), a('Docs', 'browser', {kind: 'open', target: 'https://developer.mozilla.org'}), a('Back', 'back', {kind: 'back'}, '#444B57')]
    .map((b, i) => ({id: 'b-' + (20 + i), row: 0, col: i, ...b}));
  return {version: 1, profiles: [{id: 'p-default', name: 'Everyday', rows: 3, cols: 5, pages: [{id: 'pg-1', name: 'Main', buttons: [...buttons, {id: 'b-19', row: 2, col: 4, ...a('More', 'page', {kind: 'page', page: 'pg-2'}, '#444B57')}].slice(0, 15)}, {id: 'pg-2', name: 'Work', buttons: second}]},
    {id: 'p-stream', name: 'Streaming', rows: 2, cols: 4, pages: [{id: 'pg-s1', name: 'Scenes', buttons: [{id: 'b-40', row: 0, col: 0, ...a('Go live', 'camera', hot('ctrl', 'alt', 'l'), '#A53E54')}, {id: 'b-41', row: 0, col: 1, ...a('Mute', 'mic-off', hot('ctrl', 'alt', 'm'), '#67509B')}]}]}]};
}
function desktopState() {
  const t = Date.now();
  return {computers: [{id: 'c-3f2a9b10', name: 'Work laptop', os: 'windows', online: true, lastSeen: t - 4000}, {id: 'c-77aa01de', name: 'Studio desktop', os: 'linux', online: false, lastSeen: t - 3 * 86400000}], deck: starterDeck(), code: null};
}
function macroRoute(x, H, route, method, b, res, send) {
  const dk = x.desktop;
  if (route === 'macropads' && method === 'GET') return send(res, {...JSON.parse(JSON.stringify(dk.deck)), computers: dk.computers});
  if (route === 'macropads' && method === 'PUT') {
    if (JSON.stringify(b).length > 524288) fail(400, 'Deck exceeds 512 KB');
    const {computers, ...model} = b, problems = H.macroProblems(model);
    if (problems.length) fail(400, problems[0]);
    dk.deck = model; return send(res, ok);
  }
  if (route === 'macropads/press' && method === 'POST') {
    const p = dk.deck.profiles.find(v => v.id === b.profile) || fail(409, 'Unknown profile'), g = p.pages.find(v => v.id === b.page) || fail(409, 'Unknown page'), k = g.buttons.find(v => v.id === b.button) || fail(409, 'Unknown button');
    if (k.disabled) fail(409, 'Configure this button and enable it in the editor first');
    if (['page', 'profile', 'back'].includes(k.action.kind)) return send(res, {ok: true, navigation: k.action});
    if (k.action.kind === 'http') { if (/REPLACE_ME/.test(k.action.url)) fail(409, 'HTTP 404'); return send(res, {ok: true}); }
    const target = k.computer || k.action.computer, list = target ? dk.computers.filter(c => c.id === target) : dk.computers.filter(c => c.online);
    if (!list.length || list.some(c => !c.online)) fail(409, 'Computer is offline; open Homedroid Desktop');
    if (k.action.kind === 'command') fail(409, 'Commands are turned off in Homedroid Desktop (Allow commands)');
    return send(res, {ok: true});
  }
  if (route === 'desktop/pair' && method === 'POST') { dk.code = {code: String(Math.floor(Math.random() * 1e6)).padStart(6, '0'), expires: Date.now() + 300000}; return send(res, dk.code); }
  if (route === 'desktop/computers' && method === 'GET') return send(res, dk.computers);
  if (/^desktop\/computers\/[^/]+$/.test(route) && method === 'DELETE') {
    const id = route.split('/')[2]; if (!dk.computers.some(c => c.id === id)) fail(404, 'Unknown computer');
    dk.computers = dk.computers.filter(c => c.id !== id); return send(res, ok);
  }
  return false;
}

// ---- Mail (Stalwart) and the Wi-Fi repeater --------------------------------------------------------------------
// The Mail page's phases follow the module: install Stalwart from Modules (the same job as any app), answer the setup, then it "starts" for
// a few seconds and is ready. Nothing listens, resolves or sends: DNS records and checks are made up from the saved setup.
// Mock-only: POST /api/mail/_mock {phase: not_installed|needs_setup|starting|ready|stopped} and POST /api/repeater/_mock {state, ...} set
// a state directly (the screenshots use them); the real phone has no such routes.
function dashboardPageHelpers() {
  const html = fs.readFileSync(path.join(F.ASSETS, 'dashboard.html'), 'utf8');
  const a = html.indexOf('// ---- Mail and repeater helpers'), b = html.indexOf('// ---- End mail and repeater helpers');
  const ctx = vm.createContext({});
  vm.runInContext(html.slice(a, b) + '\nthis.api = {mailAllErrors, mailFormFrom, mailPemProblem, mailChunks, repeaterName, repeaterPassOk};', ctx);
  return ctx.api;
}
const MAIL_PORT_LIST = [['SMTP: mail from other servers', 'smtp', 2525, 25], ['Submission (STARTTLS): sending from mail apps', 'smtp', 2587, 587], ['Submission over TLS: sending from mail apps', 'smtp', 4650, 465],
  ['IMAP over TLS: reading mail in apps', 'imap', 9930, 993], ['Web admin, JMAP and auto-setup (plain HTTP)', 'http', 8825, 8825]];
const MAIL_MODES = [
  {id: 'direct', title: 'Directly from my home internet', summary: 'Other mail servers connect to your phone through your router. You forward ports to the phone and your internet provider must allow it.',
    needs: ['Router port forwarding: 25 to 2525, 465 to 4650, 587 to 2587, 993 to 9930', 'A public IP address (not behind carrier-grade NAT), fixed or kept up to date with dynamic DNS', 'Port 25 open in both directions. Most home providers block it.']},
  {id: 'relay', title: 'Send through a relay (smarthost)', summary: 'Incoming mail still needs your router, but all outgoing mail goes through a provider\'s SMTP server, which avoids blocked port 25 and most spam-filter trouble.',
    needs: ['An account with an SMTP service: server, port, user name and password', 'The SPF include value the provider tells you (like spf.provider.com)', 'Port forwarding for incoming mail, as for direct delivery']},
  {id: 'receive_only', title: 'Receive only, through a small server (VPS)', summary: 'A cheap server with a public address accepts mail on port 25 and forwards it to the phone over a private link such as Tailscale or an SSH tunnel. Nothing needs to be opened at home.',
    needs: ['A VPS (or any machine with a public address) that forwards its port 25 to the phone\'s port 2525', 'The VPS\'s public address as the mail server\'s address, and its name as the hostname', 'A relay as well if you also want to send mail']}];
const MAIL_LIMITS = ['Most home internet providers block port 25. If they do, direct delivery cannot work: use a relay for sending and a VPS for receiving.',
  'Phones behind carrier-grade NAT (many mobile and some home connections) have no public address. Nothing can reach them from outside without a VPS or a tunnel.',
  'Reverse DNS (the PTR record for your IP) is set by your internet provider, not by you. Without one that matches your mail hostname, big providers often send your mail to spam.',
  'A home IP address is often on spam lists. Even with perfect setup, mail to Gmail, Outlook and others may land in spam. A relay is the reliable fix.',
  'Mail is only received while the phone is on, charged and connected. Other servers retry for a few days, then give up and bounce the mail.',
  'Nothing here is backed up by itself. Copy the mail data off the phone regularly (see the guide).',
  'The certificate is self-signed until you install a real one. Mail apps ask you to accept it once.'];
const MAIL_LAN = '192.168.1.23';
const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
const fakeKey = () => { let s = 'MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA'; for (let i = 0; s.length < 392; i++) s += B64[(i * 31 + i * i * 7 + 11) % 64]; return s + 'IDAQAB'; };
function mailState() {
  return {setup: null, readyAt: 0, forced: null, certInstalled: false, accounts: [], password: 'Zq4mT8vN2hKx7RpL',
    log: ['Starting Stalwart 1.2.0', 'Listening on 0.0.0.0:2525 (smtp)', 'Listening on 0.0.0.0:2587 (submission)', 'Listening on 0.0.0.0:4650 (submissions)', 'Listening on 0.0.0.0:9930 (imaps)', 'Listening on 0.0.0.0:8825 (http)']};
}
const mailApp = p => p.apps.find(a => a.def.id === 'stalwart');
function mailPhase(x, p) {
  const app = mailApp(p), m = x.mail;
  if (!app || !app.installed) return 'not_installed';
  if (!m.setup) return 'needs_setup';
  if (m.forced === 'stopped' || !app.enabled) return 'stopped';
  if (m.forced === 'starting' || Date.now() < m.readyAt) return 'starting';
  return 'ready';
}
const mailDemoSetup = () => ({domain: 'example.com', hostname: 'mail.example.com', mode: 'relay', address: '203.0.113.42', spfInclude: ['spf.mailgun.org'], dmarcPolicy: 'none', reportEmail: 'postmaster@example.com',
  relay: {host: 'smtp.mailgun.org', port: 587, username: 'postmaster@mg.example.com', password: 'key-demo-1234', implicitTls: false}});
function mailStatus(x, p) {
  const phase = mailPhase(x, p), m = x.mail, app = mailApp(p), up = phase === 'ready', s = m.setup;
  return {installed: !!app?.installed, enabled: !!app?.enabled, phase, state: up ? 'running' : phase === 'starting' ? 'starting' : 'stopped', version: app?.installed ? '1.2.0' : null,
    setup: s ? {...s, relay: s.relay ? {...s.relay, password: s.relay.password ? '***' : ''} : null} : null,
    admin: s && (up || phase === 'stopped') ? {user: 'admin@' + s.domain, password: m.password, url: `http://${MAIL_LAN}:8825/admin`} : null,
    ports: MAIL_PORT_LIST.map(([name, protocol, local, pub]) => ({name, protocol, local, public: pub, listening: up})), lan: [MAIL_LAN], modes: MAIL_MODES, limits: MAIL_LIMITS.map(text => ({text})),
    log: phase === 'not_installed' || phase === 'needs_setup' ? [] : phase === 'starting' ? m.log.slice(0, 2) : m.log};
}
const zoneTxt = v => { const parts = (v.match(/[\s\S]{1,255}/g) || ['']).map(c => '"' + c.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"'); return parts.length === 1 ? parts[0] : '(' + parts.join(' ') + ')'; };
function mailRecords(s, dkim) {
  const d = s.domain, h = s.hostname, rel = n => n === d ? '@' : n.endsWith('.' + d) ? n.slice(0, -d.length - 1) : n, out = [];
  const rec = (id, type, name, host, value, o = {}) => out.push({id, type, name, host, value, priority: o.priority ?? null, group: o.group || 'essential', title: o.title, note: o.note, pending: !!o.pending, extra: o.extra ?? null});
  const v4 = /^\d+\.\d+\.\d+\.\d+$/.test(s.address);
  if (h === d || h.endsWith('.' + d)) {
    if (!s.address) rec('address', 'A', h, rel(h), '<your public IP address>', {title: 'Address of the mail server', pending: true, note: 'Type your home\'s public IP address, or fill in the address in the setup. If it changes now and then, use a dynamic DNS name instead.'});
    else if (v4) rec('address', 'A', h, rel(h), s.address, {title: 'Address of the mail server', note: 'Your public IPv4 address. Must stay the same, or be updated by dynamic DNS.'});
    else if (s.address.includes(':')) rec('address', 'AAAA', h, rel(h), s.address, {title: 'Address of the mail server', note: 'Your public IPv6 address.'});
    else if (s.address !== h) rec('address', 'CNAME', h, rel(h), s.address + '.', {title: 'Address of the mail server', note: 'Points the mail hostname at your dynamic DNS name, which keeps your changing IP up to date.'});
  }
  rec('mx', 'MX', d, '@', h + '.', {priority: 10, title: `Where mail for ${d} is delivered`, note: `Tells other mail servers to deliver mail for @${d} to ${h}.`});
  const sends = s.mode !== 'receive_only' || s.relay, spf = !sends ? 'v=spf1 -all' : ['v=spf1', ...(s.mode === 'direct' ? ['mx', ...(v4 ? ['ip4:' + s.address] : [])] : []), ...s.spfInclude.map(i => 'include:' + i), '~all'].join(' ');
  rec('spf', 'TXT', d, '@', spf, {group: 'authentication', title: `SPF: who may send as ${d}`, note: s.mode === 'relay' && !s.spfInclude.length ? 'Your relay provider will tell you the include: value to put here. Without it, mail may be rejected.' : 'Starts as soft fail (~all). Change to -all once mail flows and you\'ve checked the results.'});
  if (s.mode === 'direct') rec('spf-helo', 'TXT', h, rel(h), 'v=spf1 a -all', {group: 'authentication', title: 'SPF for the server\'s own name', note: 'Some servers check the name this phone introduces itself with.'});
  if (!dkim) rec('dkim', 'TXT', `<selector>._domainkey.${d}`, '<selector>._domainkey', 'v=DKIM1; k=rsa; h=sha256; p=<key made by the server>', {group: 'authentication', title: 'DKIM: signature key', pending: true, note: 'Created when the mail server first starts. Start it, then reload this list.'});
  else rec('dkim-rsa', 'TXT', `rsa._domainkey.${d}`, 'rsa._domainkey', 'v=DKIM1; k=rsa; h=sha256; p=' + fakeKey(), {group: 'authentication', title: 'DKIM: signature key', note: 'Long keys are split into 255-character strings in a zone file; most DNS panels want the whole value in one box.'});
  rec('dmarc', 'TXT', '_dmarc.' + d, '_dmarc', `v=DMARC1; p=${s.dmarcPolicy}${s.reportEmail ? '; rua=mailto:' + s.reportEmail : ''}`, {group: 'authentication', title: 'DMARC: what to do with mail that fails',
    note: s.dmarcPolicy === 'none' ? 'Starts by only watching (p=none). Move to quarantine, then reject, once your mail passes.' : `Receivers will ${s.dmarcPolicy === 'reject' ? 'reject' : 'put in spam'} mail from your domain that fails SPF and DKIM.`});
  rec('autoconfig', 'CNAME', 'autoconfig.' + d, 'autoconfig', h + '.', {group: 'optional', title: 'Mail app auto-setup (Thunderbird)', note: 'Only works if port 80 reaches the phone (forward 80 to 8825). Skip it otherwise; set up mail apps by hand.'});
  rec('autodiscover', 'CNAME', 'autodiscover.' + d, 'autodiscover', h + '.', {group: 'optional', title: 'Mail app auto-setup (Outlook)', note: 'Outlook needs HTTPS with a trusted certificate. Skip unless you installed one.'});
  rec('srv-imaps', 'SRV', '_imaps._tcp.' + d, '_imaps._tcp', `1 993 ${h}.`, {priority: 0, group: 'optional', title: 'Finds the IMAP server', note: 'Lets mail apps find the server from the address alone. 993 is the public port; forward it to 9930.'});
  rec('srv-submissions', 'SRV', '_submissions._tcp.' + d, '_submissions._tcp', `1 465 ${h}.`, {priority: 0, group: 'optional', title: 'Finds the sending server', note: 'Public port 465, forwarded to 4650.'});
  rec('tlsrpt', 'TXT', '_smtp._tls.' + d, '_smtp._tls', `v=TLSRPTv1; rua=mailto:${s.reportEmail || 'postmaster@' + d}`, {group: 'optional', title: 'TLS reports', pending: !s.reportEmail, note: 'Other servers email you if they can\'t deliver securely. Needs a mailbox that exists.'});
  rec('mta-sts', 'TXT', '_mta-sts.' + d, '_mta-sts', 'v=STSv1; id=1', {group: 'optional', title: 'MTA-STS: require encrypted delivery', note: `Only useful with a trusted HTTPS certificate on mta-sts.${d} (port 443), which a phone usually can't get. Skip it unless you have that.`,
    extra: `version: STSv1\nmode: testing\nmx: ${h}\nmax_age: 86400\n`});
  return out.map(r => ({...r, zone: `${r.name}. IN ${r.type} ${r.type === 'TXT' ? zoneTxt(r.value) : r.type === 'MX' || r.type === 'SRV' ? (r.priority ?? 0) + ' ' + r.value : r.value}`, chunks: r.type === 'TXT' ? (r.value.match(/[\s\S]{1,255}/g) || ['']) : null}));
}
function mailDns(x, p) {
  const s = x.mail.setup || fail(409, 'Answer the setup questions first'), ready = mailPhase(x, p) === 'ready', records = mailRecords(s, ready);
  return {domain: s.domain, hostname: s.hostname, dkimReady: ready, records, zone: records.filter(r => !r.pending && r.group !== 'optional').map(r => r.zone).join('\n'),
    groups: [{id: 'essential', title: 'Needed to receive mail'}, {id: 'authentication', title: 'Needed so your mail isn\'t treated as spam'}, {id: 'optional', title: 'Optional'}]};
}
function mailCheck(x, p) {
  const s = x.mail.setup || fail(409, 'Answer the setup questions first'), up = mailPhase(x, p) === 'ready', c = [];
  const add = (id, group, title, status, detail, fix = '') => c.push({id, group, title, status, detail, fix});
  if (up) add('server', 'server', 'Mail server is running', 'ok', 'Stalwart answers on port 8825.'); else add('server', 'server', 'Mail server is running', 'fail', 'Stalwart isn\'t answering yet (starting). The first start takes a minute or two.', 'Make sure the server is started in the Homedroid app and the Mail server module is on. Look at its log for errors.');
  for (const [port, name, pub] of [[2525, 'SMTP', 25], [2587, 'Submission', 587], [4650, 'Submission over TLS', 465], [9930, 'IMAP over TLS', 993]]) add('port-' + port, 'ports', `${name} on port ${port} (public ${pub})`, up ? 'ok' : 'fail', up ? 'Listening on the phone.' : 'Nothing is listening.', up ? '' : 'Wait for the server to finish starting, or check its log.');
  if (x.mail.certInstalled) add('tls', 'tls', 'Certificate on the IMAP port', 'ok', `Issued for ${s.hostname}, valid until 2027-02-14.`);
  else add('tls', 'tls', 'Certificate on the IMAP port', 'warn', 'Self-signed. Mail apps will ask you to trust it, and other mail servers can\'t verify it (most accept that).', `Install a certificate for ${s.hostname} with the Mail page's certificate form.`);
  if (s.mode === 'direct') add('out25', 'network', 'Sending directly (outbound port 25)', 'fail', 'Port 25 is blocked or unreachable from here. Most home providers do this.', 'Use "Send through a relay" in the setup. Receiving mail can still work.');
  if (s.relay) add('relay', 'network', `Relay ${s.relay.host}:${s.relay.port}`, 'ok', 'The relay\'s server answers.');
  add('public-ip', 'network', 'Public address', 'info', 'The internet sees this phone\'s connection as 203.0.113.42.', 'If your router\'s own WAN address differs from 203.0.113.42 (or starts with 100.64 to 100.127), your provider shares addresses between customers (CGNAT) and incoming mail can\'t reach you.');
  if (s.mode === 'receive_only') add('host-ip', 'network', `${s.hostname} address`, 'info', `In receive-only mode ${s.hostname} should lead to your VPS: 198.51.100.20.`);
  else if (s.address === '203.0.113.42') add('host-ip', 'network', `${s.hostname} points to this connection`, 'ok', `${s.hostname} leads to 203.0.113.42.`);
  else add('host-ip', 'network', `${s.hostname} points to this connection`, 'fail', `${s.hostname} has no address in DNS yet.`, 'Add the address record from the DNS list.');
  if (s.mode === 'direct') add('ptr', 'network', 'Reverse DNS (PTR) of 203.0.113.42', 'warn', 'No reverse DNS is set. Big mail providers may reject or junk your mail.', `Ask your internet provider to set the PTR record of 203.0.113.42 to ${s.hostname}. Only they can.`);
  for (const r of mailRecords(s, up)) {
    const title = `${r.type} ${r.name}`, opt = r.group === 'optional';
    if (r.pending) add('dns-' + r.id, 'dns', title, opt ? 'info' : 'warn', 'Not ready yet: ' + r.note);
    else if (r.id === 'mx') add('dns-mx', 'dns', title, 'fail', 'Not found yet. DNS changes can take from a few minutes to a few hours to show up.', 'Add the record from the DNS list.');
    else if (r.id === 'spf') add('dns-spf', 'dns', title, 'warn', 'There is an SPF record but it differs from the suggested one. That\'s fine if you changed it on purpose; there must be only one SPF record.', 'Compare it with the suggested record.');
    else if (opt) add('dns-' + r.id, 'dns', title, 'info', 'Not found yet. DNS changes can take from a few minutes to a few hours to show up.');
    else add('dns-' + r.id, 'dns', title, 'ok', 'Found, and it matches.');
  }
  const n = k => c.filter(i => i.status === k).length;
  return {checks: c, summary: {ok: n('ok'), warn: n('warn'), fail: n('fail')}, note: 'These checks run from the phone. Whether the internet can reach it can only be tested from outside: send a message to this domain from another provider\'s mailbox.'};
}
function mailClients(x, user) {
  const s = x.mail.setup || fail(409, 'Answer the setup questions first'), name = (user.includes('@') ? user.split('@')[0] : user).trim().toLowerCase(), email = (/^[a-z0-9]([a-z0-9._-]{0,62}[a-z0-9])?$/.test(name) ? name : 'you') + '@' + s.domain;
  const server = (host, imap, tls, start) => ({incoming: {protocol: 'IMAP', server: host, port: imap, security: 'SSL/TLS', authentication: 'Normal password', username: email},
    outgoing: {protocol: 'SMTP', server: host, port: tls, security: 'SSL/TLS', authentication: 'Normal password', username: email, alternative: {port: start, security: 'STARTTLS'}}});
  return {email, away: {...server(s.hostname, 993, 465, 587), when: 'From anywhere, once the router forwards 993, 465 and 587 to the phone.'}, home: {...server(MAIL_LAN, 9930, 4650, 2587), when: 'On your home Wi-Fi, straight to the phone. No router setup needed.'},
    webAdmin: `http://${MAIL_LAN}:8825/admin`, jmap: `http://${MAIL_LAN}:8825/jmap/session`,
    certificate: 'The mail server starts with a self-signed certificate. Your mail app will warn about it once; accept it, or install your own certificate (see the guide).',
    steps: ['Add an account of type IMAP (not Gmail or Exchange).', `Incoming: the server and port above, security SSL/TLS, user name ${email}.`, 'Outgoing: the same user name and password, security SSL/TLS (or STARTTLS on the alternative port).', 'Accept the certificate warning if you see one.']};
}
const mailPassword = () => Array.from({length: 16}, () => B64[crypto.randomInt(52 + 10)]).join('');
function mailRoute(x, p, rest, method, b, res, send, ctx) {
  const m = x.mail, H = x.pageHelpers, phase = () => mailPhase(x, p), one = rest.join('/');
  if (one === '' && method === 'GET') return send(res, mailStatus(x, p));
  if (one === '_mock' && method === 'POST') {
    const app = mailApp(p), ph = b.phase;
    if (!['not_installed', 'needs_setup', 'starting', 'ready', 'stopped'].includes(ph)) fail(400, 'Unknown phase');
    if (ph === 'not_installed') { app.installed = app.enabled = false; m.setup = null; } else { app.installed = app.enabled = true; if (ph === 'needs_setup') m.setup = null; else if (!m.setup) m.setup = b.setup || mailDemoSetup(); }
    if (m.setup && b.setup) m.setup = b.setup;
    m.forced = ph === 'starting' || ph === 'stopped' ? ph : null; m.readyAt = 0; m.certInstalled = false;
    if (m.setup && !m.accounts.length) m.accounts = [{id: 'd0a1', name: 'admin', email: 'admin@' + m.setup.domain, admin: true}, {id: 'a17c', name: 'alice', email: 'alice@' + m.setup.domain, admin: false}, {id: 'b29f', name: 'bob', email: 'bob@' + m.setup.domain, admin: false}];
    return send(res, mailStatus(x, p));
  }
  if (one === 'setup' && method === 'POST') {
    if (phase() === 'not_installed') fail(409, 'Install the Mail server (Stalwart) from Modules first');
    const old = m.setup, relay = b.relay && String(b.relay.host || '').trim() ? {...b.relay} : null;
    if (relay && (!relay.password || relay.password === '***')) relay.password = old?.relay?.password || '';
    const body = {domain: String(b.domain || ''), hostname: String(b.hostname || ''), mode: b.mode, address: b.address || '', spfInclude: b.spfInclude || [], dmarcPolicy: b.dmarcPolicy || 'none', reportEmail: b.reportEmail || '', relay};
    const errs = H.mailAllErrors(H.mailFormFrom({...body, hostname: body.hostname || 'mail.' + body.domain}));
    if (!errs.valid) fail(400, Object.values(errs.steps.find(s => Object.keys(s).length))[0]);
    const first = !old;
    m.setup = {...body, domain: body.domain.trim().toLowerCase(), hostname: (body.hostname || 'mail.' + body.domain).trim().toLowerCase()};
    m.forced = null; m.readyAt = Date.now() + (first ? 4500 : 2500);
    return send(res, mailStatus(x, p));
  }
  if (one === 'dns' && method === 'GET') return send(res, mailDns(x, p));
  if (one === 'check' && method === 'POST') return new Promise(done => setTimeout(() => { try { done(send(res, mailCheck(x, p))); } catch (e) { done(send(res, {error: e.message}, e.code || 500)); } }, 900));
  if (one === 'clients' && method === 'GET') return send(res, mailClients(x, ctx.url.searchParams.get('user') || ''));
  if (rest[0] === 'accounts') {
    if (phase() !== 'ready') fail(409, 'The mail server isn\'t running');
    if (rest.length === 1 && method === 'GET') return send(res, {accounts: m.accounts.map(a => ({id: a.id, name: a.name, email: a.email, admin: a.admin}))});
    if (rest.length === 1 && method === 'POST') {
      const name = String(b.name || '').trim().toLowerCase(), pw = String(b.password || '');
      if (!/^[a-z0-9]([a-z0-9._-]{0,62}[a-z0-9])?$/.test(name)) fail(400, 'Use letters, digits, dots, dashes or underscores for the name, like alice');
      if (pw && (pw.length < 8 || pw.length > 128)) fail(400, 'The password needs 8 to 128 characters');
      if (m.accounts.some(a => a.name === name)) fail(409, `${name}@${m.setup.domain} already exists`);
      const a = {id: hex(8), name, email: `${name}@${m.setup.domain}`, admin: false}; m.accounts.push(a);
      return send(res, {id: a.id, email: a.email, password: pw || mailPassword(), generated: !pw});
    }
    const acct = m.accounts.find(a => a.id === rest[1]);
    if (rest.length === 3 && rest[2] === 'password' && method === 'POST') {
      if (!acct) fail(404, 'No such account');
      const pw = String(b.password || ''); if (pw && (pw.length < 8 || pw.length > 128)) fail(400, 'The password needs 8 to 128 characters');
      return send(res, {password: pw || mailPassword(), generated: !pw});
    }
    if (rest.length === 2 && method === 'DELETE') {
      if (!acct) fail(404, 'No such account'); if (acct.admin) fail(409, 'The admin account can\'t be deleted here');
      m.accounts = m.accounts.filter(a => a !== acct); return send(res, ok);
    }
    return false;
  }
  if (one === 'certificate' && method === 'POST') {
    const problem = H.mailPemProblem(b.certificate, b.privateKey); if (problem) fail(400, problem);
    m.certInstalled = true; m.readyAt = Date.now() + 2000; return send(res, ok);
  }
  return false;
}

const RP_INSTRUCTIONS = (addr, ssid, pass) => {
  const pac = `http://${addr}:8283/proxy.pac`, join = `Join the Wi-Fi network "${ssid}" with the passphrase shown here (or scan its QR code).`, os = (name, ...steps) => ({os: name, steps});
  return [os('Android', join, 'Wi-Fi settings: tap the network (or the gear next to it) > Advanced > Proxy > Manual.', `Proxy hostname ${addr}, port 8282. Save. (Or choose Proxy auto-config and enter ${pac}.)`, 'If Android says "no internet", choose to stay connected.'),
    os('iPhone and iPad', join, 'Settings > Wi-Fi > the (i) next to the network > Configure Proxy > Manual.', `Server ${addr}, port 8282, authentication off. (Or choose Automatic and enter ${pac}.)`, 'Tap Save. If iOS offers to leave a network without internet, stay.'),
    os('Windows 11', join, 'Settings > Network & internet > Proxy.', `Under Manual proxy setup, Set up: on, address ${addr}, port 8282, Save.`, `Or turn on "Use setup script", Script address ${pac}, and Save.`),
    os('macOS', join, 'System Settings > Network > Wi-Fi > Details > Proxies.', `Turn on Web Proxy (HTTP) and Secure Web Proxy (HTTPS): server ${addr}, port 8282. Or turn on Automatic Proxy Configuration with URL ${pac}.`, 'Click OK, then Apply.'),
    os('Linux (GNOME)', join, `Settings > Network > Network Proxy > Manual: HTTP and HTTPS proxy ${addr} port 8282; Socks host ${addr} port 8284.`, `Or Automatic with Configuration URL ${pac}.`),
    os('Command line and apps with their own setting', `export http_proxy=http://${addr}:8282 https_proxy=http://${addr}:8282`, `curl --socks5-hostname ${addr}:8284 https://example.com`, 'Firefox: Settings > Network Settings > Manual proxy, or SOCKS Host ' + addr + ' port 8284 with SOCKS v5 and "Proxy DNS when using SOCKS v5" on.')];
};
function repeaterState() {
  return {enabled: false, state: 'off', message: '', name: 'DIRECT-hd-Homedroid', passphrase: 'k3Fm-9tQw-Zx7R', band: 'auto', allowLan: false, permission: true, supported: true, stationKept: true,
    clients: [], traffic: {bytesUp: 0, bytesDown: 0, connections: 0, totalConnections: 0}, timer: null};
}
const escapeWifi = s => s.replace(/([\;,:"])/g, '\\$1');
function repeaterJson(x) {
  const r = x.repeater, running = r.state === 'running', addr = running ? '192.168.49.1' : '';
  if (running) { const t = r.traffic, n = r.clients.length; t.bytesUp += n * (30000 + crypto.randomInt(90000)); t.bytesDown += n * (200000 + crypto.randomInt(900000)); t.connections = n ? 3 + crypto.randomInt(9) : 0; t.totalConnections += n ? crypto.randomInt(6) : 0; }
  return {enabled: r.enabled, state: r.state, message: r.message, supported: r.supported, permission: {granted: r.permission, name: 'NEARBY_WIFI_DEVICES', label: 'Nearby devices'},
    network: {name: r.name, passphrase: r.passphrase, band: r.band, interface: running ? 'p2p-wlan0-0' : '', address: addr}, wifiQr: `WIFI:T:WPA;S:${escapeWifi(r.name)};P:${escapeWifi(r.passphrase)};;`,
    clients: {count: running ? r.clients.length : 0, devices: running ? r.clients : []}, traffic: {...r.traffic},
    proxy: {http: `${addr}:8282`, socks: `${addr}:8284`, pac: `http://${addr}:8283/proxy.pac`, allowLan: r.allowLan},
    concurrent: {stationKept: running ? r.stationKept : null, note: !running || r.stationKept === null ? 'Whether the phone can stay on its Wi-Fi while hosting is checked a few seconds after the network starts.'
      : r.stationKept ? 'The phone stayed connected to its own Wi-Fi while hosting the network.' : 'The phone\'s own Wi-Fi dropped when the network started: this phone can\'t do both at once, so it has no internet source other than mobile data.'},
    instructions: RP_INSTRUCTIONS(addr || '192.168.49.1', r.name, r.passphrase),
    limits: ['Only apps that follow the device\'s proxy settings are carried. Many games, VoIP and streaming apps ignore them.', 'TCP goes through the HTTP proxy or SOCKS5. UDP (some games, calls, QUIC) works only in apps that speak SOCKS5 UDP.',
      'Speed is limited to what Wi-Fi Direct and the phone\'s single radio can share with its own Wi-Fi; expect a fraction of the line speed.', 'The phone\'s radio and battery work harder: keep it charging.']};
}
function repeaterRoute(x, rest, method, b, res, send, ctx) {
  const r = x.repeater, H = x.pageHelpers, later = ctx.later;
  if (rest[0] === '_mock' && method === 'POST') {
    const {state, clients, ...more} = b; Object.assign(r, more);
    if (state) { r.state = state; r.enabled = !['off', 'unsupported'].includes(state); r.message = {needs_permission: 'Allow "Nearby devices" in the Homedroid app (Settings, Wi-Fi repeater) so the phone can create the network.', unsupported: 'This phone doesn\'t support Wi-Fi Direct, which the repeater needs.',
      error: 'Android couldn\'t create the network. Make sure Wi-Fi is on.', waiting: 'Android closed the Wi-Fi network (Wi-Fi was turned off, or another app took Wi-Fi Direct).', starting: 'Creating the Wi-Fi network…'}[state] || ''; if (state === 'unsupported') r.supported = false; if (state === 'needs_permission') r.permission = false; }
    if (clients !== undefined) r.clients = clients; else if (state === 'running') r.clients = [{name: 'Sudipta\'s Pixel 7', mac: '9a:3f:21:c4:7b:08'}, {name: 'MacBook Air', mac: '4e:11:b9:60:d2:5c'}];
    if (state === 'off' || state === 'running') r.traffic = {bytesUp: 0, bytesDown: 0, connections: 0, totalConnections: 0};
    return send(res, repeaterJson(x));
  }
  if (rest.length) return false;
  if (method === 'GET') return send(res, repeaterJson(x));
  if (method !== 'POST') fail(405, 'GET or POST only');
  if (['name', 'passphrase', 'band', 'allowLan'].some(k => k in b) || b.regenerate) {
    const pass = b.regenerate ? Array.from({length: 3}, () => Array.from({length: 4}, () => 'abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'[crypto.randomInt(54)]).join('')).join('-') : ('passphrase' in b && b.passphrase ? b.passphrase : null);
    if (pass && !H.repeaterPassOk(pass)) fail(400, 'The passphrase needs 8 to 63 letters, digits or symbols');
    if ('band' in b && !['', 'auto', '2.4', '5'].includes(String(b.band).toLowerCase())) fail(400, 'Band must be auto, 2.4 or 5');
    if ('name' in b) r.name = H.repeaterName(b.name); if (pass) r.passphrase = pass; if ('band' in b) r.band = b.band || 'auto'; if ('allowLan' in b) r.allowLan = !!b.allowLan;
  }
  if ('enabled' in b) {
    clearTimeout(r.timer);
    if (b.enabled) {
      r.enabled = true;
      if (!r.supported) { r.state = 'unsupported'; fail(409, 'This phone doesn\'t support Wi-Fi Direct, which the repeater needs.'); }
      if (!r.permission) { r.state = 'needs_permission'; fail(409, 'Allow "Nearby devices" in the Homedroid app (Settings, Wi-Fi repeater) so the phone can create the network.'); }
      r.state = 'starting'; r.message = 'Creating the Wi-Fi network…';
      r.timer = later(() => { r.state = 'running'; r.message = ''; r.traffic = {bytesUp: 0, bytesDown: 0, connections: 0, totalConnections: 0}; r.clients = [{name: 'Sudipta\'s Pixel 7', mac: '9a:3f:21:c4:7b:08'}, {name: 'MacBook Air', mac: '4e:11:b9:60:d2:5c'}]; }, 1500);
    } else { r.enabled = false; r.state = 'off'; r.message = ''; r.clients = []; }
  }
  return send(res, repeaterJson(x));
}

function createExtras() {
  return {docs: docState(), domains: domainsState(), domainsError: null, verifyTries: {}, logs: logsState(), ddns: ddnsState(), firewall: [{action: 'allow', cidr: '192.168.0.0/16', scope: 'all', note: 'Home network'}, {action: 'deny', cidr: '203.0.113.0/24', scope: 'web', note: 'Scanner'}],
    mcp: mcpState(), runs: {}, desktop: desktopState(), helpers: dashboardHelpers(), pageHelpers: dashboardPageHelpers(), mail: mailState(), repeater: repeaterState()};
}
/** Handles a console-page request, or returns false. [s] is the path after /api/ (node prefix removed). */
function route(x, p, s, method, b, raw, url, res, send, ctx) {
  const area = s[0], rest = s.slice(1);
  if (area === 'db') return dbRoute(x, rest, method, b, url, res, send);
  if (area === 'docs') return docsRoute(x, rest, method, b, url, res, send);
  if (area === 'domains') return domainsRoute(x, rest, method, b, url, res, send);
  if (area === 'ddns') {
    if (method === 'GET' && !rest.length) return send(res, {entries: x.ddns.map(pub)});
    if (method === 'POST' && !rest.length) return send(res, ddnsUpsert(x, b));
    if (method === 'POST' && rest[1] === 'update') { const e = need(x.ddns.find(v => v.id === rest[0]), 'No such entry'); Object.assign(e, {ip: '203.0.113.42', lastUpdate: Date.now(), lastCheck: Date.now(), result: 'ok', error: null}); return send(res, pub(e)); }
    if (method === 'DELETE' && rest.length === 1) { need(x.ddns.find(v => v.id === rest[0]), 'No such entry'); x.ddns = x.ddns.filter(v => v.id !== rest[0]); return send(res, ok); }
    return false;
  }
  if (area === 'firewall') return firewallRoute(x, rest, method, b, res, send);
  if (area === 'mcp') { x.auditLimit = url.searchParams.get('limit'); return mcpRoute(x, p, rest, method, b, res, send); }
  if (area === 'deploys' && s.length >= 3 && ['settings', 'token', 'upload', 'pipeline', 'runs'].includes(s[2])) return deployExtras(x, p, s, method, b, raw, url, res, send, ctx.later, ctx.stepMs);
  if (area === 'mail') return mailRoute(x, p, rest, method, b, res, send, {...ctx, url});
  if (area === 'repeater') return repeaterRoute(x, rest, method, b, res, send, ctx);
  if (area === 'macropads' || area === 'desktop') return macroRoute(x, x.helpers, s.join('/'), method, b, res, send);
  return false;
}
module.exports = {createExtras, route, DB_SPECS, starterDeck};
