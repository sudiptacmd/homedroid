#!/usr/bin/env node
'use strict';
// Local, dependency-free simulation. It never executes commands, contacts providers,
// sends packets or writes uploaded files to disk. See README.md for API coverage.
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const net = require('node:net');
const zlib = require('node:zlib');
const F = require('./fixtures.cjs');
const X = require('./extras.cjs');
const CSP = "default-src 'self'; img-src 'self' blob:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'";
const MIME = {'.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.png': 'image/png', '.wav': 'audio/wav', '.webm': 'video/webm'};
const fail = (code, message) => { throw Object.assign(new Error(message), {code}); };
const need = (value, message = 'Not found') => value || fail(404, message);
const ok = {ok: true};
function send(res, data, code = 200, headers = {}) {
  const binary = Buffer.isBuffer(data);
  const body = binary ? data : Buffer.from(JSON.stringify(data));
  res.writeHead(code, {'Content-Type': binary ? 'application/octet-stream' : 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'Content-Length': body.length, ...headers});
  res.end(body);
}
async function readBody(req) {
  const parts = []; let bytes = 0;
  for await (const part of req) { bytes += part.length; if (bytes > 32 * 1024 * 1024) fail(413, 'Mock uploads are limited to 32 MB'); parts.push(part); }
  const raw = Buffer.concat(parts);
  if (!(req.headers['content-type'] || '').includes('application/json')) return {raw, body: {}};
  try { return {raw, body: raw.length ? JSON.parse(raw) : {}}; } catch { fail(400, 'Invalid JSON'); }
}
function zip(entries) {
  // Stored ZIP entries: enough to exercise a folder download without an archiver.
  const local = [], central = []; let offset = 0;
  for (const [name, data] of entries) {
    const n = Buffer.from(name), crc = F.crc32(data), h = Buffer.alloc(30), c = Buffer.alloc(46);
    h.writeUInt32LE(0x04034b50); h.writeUInt16LE(20, 4); h.writeUInt32LE(crc, 14); h.writeUInt32LE(data.length, 18); h.writeUInt32LE(data.length, 22); h.writeUInt16LE(n.length, 26);
    c.writeUInt32LE(0x02014b50); c.writeUInt16LE(20, 4); c.writeUInt16LE(20, 6); c.writeUInt32LE(crc, 16); c.writeUInt32LE(data.length, 20); c.writeUInt32LE(data.length, 24); c.writeUInt16LE(n.length, 28); c.writeUInt32LE(offset, 42);
    local.push(h, n, data); central.push(c, n); offset += h.length + n.length + data.length;
  }
  const directory = Buffer.concat(central), end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50); end.writeUInt16LE(entries.length, 8); end.writeUInt16LE(entries.length, 10); end.writeUInt32LE(directory.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...local, directory, end]);
}
function download(req, res, url, name, data, type) {
  const headers = {'Content-Type': type, 'Accept-Ranges': 'bytes', 'Content-Disposition': `${url.searchParams.has('inline') ? 'inline' : 'attachment'}; filename*=UTF-8''${encodeURIComponent(name)}`};
  const range = /^bytes=(\d*)-(\d*)$/.exec(req.headers.range || '');
  if (range) {
    const start = range[1] ? +range[1] : Math.max(0, data.length - +range[2]);
    const end = range[1] && range[2] ? Math.min(+range[2], data.length - 1) : data.length - 1;
    if (start > end || start >= data.length) return send(res, Buffer.alloc(0), 416, {'Content-Range': `bytes */${data.length}`});
    return send(res, data.subarray(start, end + 1), 206, {...headers, 'Content-Range': `bytes ${start}-${end}/${data.length}`});
  }
  send(res, data, 200, headers);
}
function createMockServer({log = console.warn, jobStepMs = 350, delayMs = 800} = {}) {
  const cluster = F.createCluster(), timers = new Set(), extras = X.createExtras();
  const later = (fn, ms = delayMs) => { const t = setTimeout(() => { timers.delete(t); fn(); }, ms); timers.add(t); return t; };
  const phones = () => [cluster.self, ...cluster.members];
  const phoneById = id => need(phones().find(p => p.id === id), 'No such phone');
  const session = req => /(?:^|;\s*)homedroid_session=([^;]+)/.exec(req.headers.cookie || '')?.[1];
  const authorized = req => cluster.self.sessions.has(session(req)) || req.headers.authorization === 'Bearer ' + cluster.self.password;
  const cookie = token => `homedroid_session=${token}; Path=/; HttpOnly; SameSite=Strict${token ? '' : '; Max-Age=0'}`;
  const newSession = res => { const token = F.hex(40); cluster.self.sessions.add(token); send(res, ok, 200, {'Set-Cookie': cookie(token)}); };
  const job = (p, title, done, lines = F.installLines(title)) => {
    const error = F.startJob(p, title, lines, {stepMs: jobStepMs, onDone: done});
    if (error) fail(409, error); return ok;
  };
  function clusterJson() {
    const p = cluster.self;
    return {self: {id: p.id, name: p.name, model: p.model, version: p.version, fingerprint: p.fingerprint.slice(0, 16), ips: p.ips},
      members: cluster.members.map(m => ({id: m.id, name: m.name, model: m.model, address: m.ips[0], port: 8801, online: m.online, status: m.online ? F.status(m) : null, error: m.online ? null : 'Phone is offline or unreachable'})),
      pending: cluster.pending, outgoing: cluster.outgoing, discovering: cluster.discovering, discovered: cluster.discovered};
  }
  function saveSettings(p, b, req, res) {
    if (b.password && b.currentPassword !== p.password) fail(403, 'Wrong current password');
    if (b.password && (b.password.length < 12 || b.password.length > 128 || /[\x00-\x1f\x7f]/.test(b.password))) fail(400, 'Use 12–128 characters without control characters');
    if ('autostart' in b && typeof b.autostart !== 'boolean') fail(400, 'Invalid startup preference');
    if ('sshPort' in b) {
      if (!Number.isInteger(b.sshPort) || b.sshPort < 1024 || b.sshPort > 65535 || [8800, 8801, 8080].includes(b.sshPort)) fail(400, "Choose a port between 1024 and 65535 that Homedroid doesn't use");
      if (p.apps.some(a => a.installed && a.enabled && a.def.port === b.sshPort)) fail(409, `Another app on the phone is already using port ${b.sshPort}`);
    }
    if ('appsDns' in b) {
      const ips = String(b.appsDns).trim().split(/[,\s]+/).filter(Boolean);
      if (ips.length > 3 || ips.some(ip => !net.isIP(ip))) fail(400, 'Enter up to three IP addresses, like 1.1.1.1, 9.9.9.9');
      p.appsDns = ips.join(', ');
    }
    for (const k of ['autostart', 'sshPort']) if (k in b) p[k] = b[k];
    if (b.password) {
      p.password = b.password;
      if (p === cluster.self) { p.sessions.clear(); newSession(res); return; }
    }
    send(res, ok);
  }
  function moduleAction(p, id, action, b) {
    if (action === 'move') {
      const source = phoneById(b.from), from = need(source.apps.find(a => a.def.id === id && a.installed)), to = need(p.apps.find(a => a.def.id === id));
      return job(p, `Moving ${to.def.name}`, () => { to.installed = to.enabled = true; from.enabled = false; if (b.removeSource) from.installed = false; });
    }
    const core = F.CORE.find(c => c.id === id);
    if (core || ['ai', 'camera', 'wol', 'network'].includes(id)) {
      if (!['enable', 'disable'].includes(action)) fail(400, 'Built-in modules can only be enabled or disabled');
      const on = action === 'enable';
      if (core) { p.core[id] = on; if (id === 'tailscale') Object.assign(p.tailscale, b); if (id === 'tunnel' && b.token) p.tunnelToken = b.token; }
      else if (id === 'camera') p.cameraEnabled = on;
      else if (id === 'wol') p.wolEnabled = on;
      else p[id].enabled = on;
      return ok;
    }
    const a = need(p.apps.find(x => x.def.id === id), 'No such module');
    if (action === 'enable' || action === 'disable') { a.enabled = action === 'enable'; return ok; }
    if (!['install', 'update', 'remove', 'clear'].includes(action)) fail(404, 'Unknown module action');
    if (['update', 'clear'].includes(action) && !a.installed) fail(409, `${a.def.name} isn't installed`);
    const verb = {install: 'Installing', update: 'Updating', remove: 'Removing', clear: 'Clearing'}[action];
    return job(p, `${verb} ${a.def.name}`, () => {
      a.installed = action !== 'remove'; if (action === 'install') a.enabled = true;
      if (action === 'update') a.updatedAt = Date.now();
      F.serviceNames(a.def).forEach(n => F.bump(p, n));
    });
  }
  function parent(root, rel, create = false) {
    const parts = rel.split('/').filter(Boolean), name = parts.pop(); let dir = root.tree;
    if (!name || [...parts, name].some(x => x === '.' || x === '..')) fail(403, 'Invalid path');
    for (const part of parts) {
      let next = dir.children.find(x => x.name === part);
      if (!next && create) { next = {name: part, dir: true, size: 0, modified: Date.now(), children: []}; dir.children.push(next); }
      if (!next?.dir) fail(404, 'Parent folder not found'); dir = next;
    }
    return {dir, name};
  }
  function fileData(file) {
    if (file.data) return Buffer.from(file.data);
    if (/\.(jpg|png)$/i.test(file.name)) return F.frame(8);
    if (/\.wav$/i.test(file.name)) return F.wav();
    if (/\.(mp4|webm)$/i.test(file.name)) return fs.readFileSync(path.join(__dirname, 'sample.webm'));
    return Buffer.from(`# ${file.name}\nHomedroid mock file. Uploads preserve their actual contents in memory.\n`);
  }
  function fileType(name) { return /\.(jpg|png)$/i.test(name) ? 'image/png' : /\.(mp4|webm)$/i.test(name) ? 'video/webm' : /\.wav$/i.test(name) ? 'audio/wav' : 'text/plain; charset=utf-8'; }
  function filesRoute(p, s, method, b, raw, req, res, url) {
    if (!s.length && method === 'GET') return send(res, p.files.map(({tree, ...r}) => r));
    if (s[0] === 'transfers' && method === 'GET') return send(res, p.transfers);
    const root = need(p.files.find(x => x.id === s[0]), 'No such location'), rel = url.searchParams.get('path') || '';
    if (rel.split('/').some(x => x === '..' || x === '.')) fail(403, 'Outside this location');
    const f = F.walk(root, rel), action = s[1];
    if (method === 'GET' && !action) {
      if (!f?.dir) fail(404, 'No such folder');
      return send(res, {root: root.id, label: root.label, free: root.free, entries: f.children.map(({children, data, ...e}) => e)});
    }
    if (method === 'GET' && action === 'download') {
      need(f, 'No such file');
      if (f.dir) {
        const entries = []; const visit = (n, prefix) => n.dir ? n.children.forEach(c => visit(c, prefix + n.name + '/')) : entries.push([prefix + n.name, fileData(n)]);
        visit(f, ''); if (!entries.length) entries.push([f.name + '/', Buffer.alloc(0)]);
        return download(req, res, url, (f.name || root.id) + '.zip', zip(entries), 'application/zip');
      }
      return download(req, res, url, f.name, fileData(f), fileType(f.name));
    }
    if (method === 'PUT' && !action) {
      if (f && !url.searchParams.has('overwrite')) fail(409, 'File already exists');
      if (f?.dir) fail(409, 'A folder has this name');
      const {dir, name} = parent(root, rel, true);
      dir.children = dir.children.filter(x => x.name !== name);
      dir.children.push({name, dir: false, size: raw.length, modified: Date.now(), data: raw});
      return send(res, ok, 201);
    }
    if (method === 'POST' && action === 'mkdir') {
      if (f) fail(409, 'Already exists');
      const {dir, name} = parent(root, rel, true); dir.children.push({name, dir: true, size: 0, modified: Date.now(), children: []}); return send(res, ok);
    }
    if (method === 'POST' && action === 'move') {
      need(f); if (F.walk(root, b.to)) fail(409, 'Destination already exists');
      if (b.to.startsWith(rel + '/')) fail(400, 'Cannot move a folder inside itself');
      const dest = parent(root, b.to, true), src = parent(root, rel); src.dir.children = src.dir.children.filter(x => x !== f); f.name = dest.name; dest.dir.children.push(f); return send(res, ok);
    }
    if (method === 'DELETE' && !action) { need(f); const src = parent(root, rel); src.dir.children = src.dir.children.filter(x => x !== f); return send(res, ok); }
    if (method === 'POST' && action === 'fetch') {
      const from = phoneById(b.from), sourceRoot = need(from.files.find(r => r.id === b.root)), source = need(F.walk(sourceRoot, b.path));
      if (f) fail(409, 'Destination already exists');
      const dest = parent(root, rel, true), transfer = {id: String(p.nextTransfer++), name: dest.name, state: 'running', done: 0, total: F.treeSize(source), move: !!b.move, error: null};
      p.transfers.push(transfer);
      later(() => { const copy = structuredClone(source); copy.name = dest.name; dest.dir.children.push(copy); transfer.state = 'done'; transfer.done = transfer.total;
        if (b.move) { const src = parent(sourceRoot, b.path); src.dir.children = src.dir.children.filter(x => x !== source); } });
      return send(res, transfer);
    }
    return false;
  }
  function addCapture(p, ext) { const name = `${Date.now()}-${crypto.randomUUID()}.${ext}`; p.camera.files.unshift({name, bytes: ext === 'jpg' ? 412331 : ext === 'wav' ? 64044 : 18220114}); return name; }
  async function cameraRoute(p, s, method, b, req, res, url) {
    if (!p.cameraEnabled) fail(409, 'Enable the IPCam module first');
    const c = p.camera, action = s.join('/');
    if (method === 'GET' && !action) return send(res, F.cameraState(p));
    if (method === 'GET' && s[0] === 'files') { const f = need(c.files.find(x => x.name === s[1]), 'No such capture'); return download(req, res, url, f.name, fileData(f), fileType(f.name)); }
    if (method === 'GET' && action === 'audio') {
      const sequence = Math.floor((Date.now() - c.mic.startedAt) / 100), after = +(url.searchParams.get('after') || sequence);
      return send(res, {running: c.mic.running, rate: 16000, sequence, chunks: c.mic.running ? Array.from({length: Math.min(4, Math.max(0, sequence - after))}, (_, i) => ({sequence: sequence - i, pcm: F.pcm(1600, (sequence - i) * 1600).toString('base64')})).reverse() : []});
    }
    if (method === 'GET' && action === 'live/frame') {
      await new Promise(resolve => later(resolve, 180));
      if (!c.live && !c.monitor.id) return send(res, Buffer.alloc(0), 204);
      return send(res, F.frame(++c.frame, p.variant === 'self' ? 0 : 40), 200, {'Content-Type': 'image/png', 'X-Frame': String(c.frame)});
    }
    if (method !== 'POST') return false;
    if (!['photo', 'record', 'stop', 'torch', 'live/start', 'live/stop', 'microphone/start', 'microphone/stop', 'announce', 'settings', 'storage', 'monitor/start', 'monitor/stop'].includes(action)) return false;
    if (['photo', 'record', 'torch', 'live/start', 'monitor/start', 'settings'].includes(action)) need(c.cameras.find(x => x.id === b.id), 'Unknown camera');
    if (!c.armed && !['settings', 'storage'].includes(action)) fail(409, 'Enable IPCam access in the phone app');
    switch (action) {
      case 'photo': if (c.busy || c.recording || c.monitor.id) fail(409, 'Camera is busy'); c.busy = true; later(() => { c.busy = false; addCapture(p, 'jpg'); }); break;
      case 'record': if (c.busy || c.recording || c.monitor.id) fail(409, 'Camera is busy'); c.recording = true; c.selected = b.id; break;
      case 'stop': if (c.recording) addCapture(p, 'mp4'); c.recording = c.busy = false; c.selected = null; break;
      case 'torch': c.torch = b.enabled ? b.id : null; break;
      case 'live/start': c.live = b.id; break;
      case 'live/stop': c.live = null; break;
      case 'monitor/start': c.monitor.id = c.live = b.id; break;
      case 'monitor/stop': c.monitor.id = null; c.monitor.recording = false; break;
      case 'microphone/start': Object.assign(c.mic, {running: true, recording: !!b.record, startedAt: Date.now()}); break;
      case 'microphone/stop': if (c.mic.recording) addCapture(p, 'wav'); c.mic.running = c.mic.recording = false; break;
      case 'announce': if (!b.text?.trim() || b.text.length > 500) fail(400, 'Enter a message of up to 500 characters'); break;
      case 'settings': { const {id, ...settings} = b; Object.assign(c.settings[id], settings); return send(res, {saved: true}); }
      case 'storage': if (!Number.isInteger(b.quotaMb) || b.quotaMb < 128 || b.quotaMb > 102400) fail(400, 'Storage must be between 128 and 102400 MB'); c.quotaMb = b.quotaMb; return send(res, {saved: true});
    }
    send(res, F.cameraJson(p));
  }
  function aiRoute(p, s, method, b, req, res) {
    const a = p.ai, action = s.join('/');
    if (method === 'GET' && !action) return send(res, F.aiJson(p));
    if (method === 'GET' && action === 'routines') return send(res, F.routinesJson(p));
    if (method === 'GET' && action === 'briefs') return send(res, p.briefs);
    if (method === 'DELETE' && s[0] === 'briefs') { p.briefs = s[1] ? p.briefs.filter(x => x.id !== s[1]) : []; return send(res, ok); }
    if (method === 'DELETE' && s.length === 2) {
      if (s[0] === 'routines') p.routines = p.routines.filter(x => x.id !== s[1]);
      else if (s[0] === 'providers') { a.providers = a.providers.filter(x => x.id !== s[1]); if (a.fallback.startsWith(s[1] + '/')) a.fallback = ''; }
      else if (s[0] === 'files') { a.files = a.files.filter(x => x.file !== s[1]); if (a.localModel === s[1]) a.localModel = null; }
      else return false;
      return send(res, ok);
    }
    if (method !== 'POST') return false;
    if (action === 'chat') {
      if (!a.enabled) fail(409, 'Turn on AI first');
      const model = need(F.aiModels(p).find(x => x.id === b.model), 'No such model');
      res.writeHead(200, {'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store', 'X-Homedroid-Where': model.local ? 'local' : 'cloud', 'X-Homedroid-Model': model.name, 'X-Homedroid-Note': 'Simulated response for visual testing'});
      const words = 'This is a simulated reply from your Homedroid demo. **Your server is online.** You can manage services in Modules, browse Files, or check the Cluster for other phones. No message was sent to a cloud service.'.split(' ');
      let i = 0;
      const tick = () => { if (res.destroyed) return; if (i === words.length) return res.end('data: [DONE]\n\n'); res.write('data: ' + JSON.stringify({choices: [{delta: {content: words[i++] + ' '}}]}) + '\n\n'); later(tick, 35); }; tick(); return;
    }
    if (action === 'settings') {
      if (b.localModel && !a.files.some(x => x.file === b.localModel)) fail(400, 'Download that model first');
      if ('contextSize' in b && (b.contextSize < 512 || b.contextSize > 32768)) fail(400, 'Context must be 512–32768 tokens');
      for (const k of ['localModel', 'contextSize', 'threads', 'device', 'modelsDir', 'fallback']) if (k in b) a[k] = b[k];
      a.loadingUntil = Date.now() + delayMs; return send(res, ok);
    }
    if (action === 'key') { a.key = 'hd-' + F.hex(40); return send(res, ok); }
    if (action === 'providers') {
      if (!['openai', 'gemini', 'compatible'].includes(b.type)) fail(400, 'Pick a service type');
      if (b.type !== 'compatible' && !b.apiKey) fail(400, 'Paste an API key');
      const provider = {id: b.id || F.hex(8), type: b.type, name: b.name || (b.type === 'gemini' ? 'Google Gemini' : 'OpenAI'), baseUrl: b.baseUrl || 'https://api.openai.com/v1', hasKey: !!b.apiKey, models: ['demo-model']};
      a.providers = a.providers.filter(x => x.id !== provider.id).concat(provider); return send(res, provider);
    }
    if (s[0] === 'providers' && s[2] === 'refresh') return send(res, need(a.providers.find(x => x.id === s[1])));
    if (action === 'download') {
      if (a.download?.state === 'running') fail(409, 'Another model is downloading');
      const entry = F.AI_CATALOG.find(x => x.id === b.id);
      const file = entry?.file || String(b.url || '').split('/').pop().split('?')[0];
      if (!entry && (!String(b.url).startsWith('https://') || !/^[\w.-]+\.gguf$/.test(file))) fail(400, 'Paste an https:// link to a .gguf file');
      if (a.files.some(x => x.file === file)) fail(409, 'Model already downloaded');
      const d = a.download = {name: entry?.name || file, file, state: 'running', done: 0, total: entry?.bytes || 100000000, error: null};
      const tick = () => { if (d.state !== 'running') return; d.done = Math.min(d.total, d.done + Math.ceil(d.total / 5)); if (d.done === d.total) { d.state = 'done'; a.files.push({file, bytes: d.total}); } else later(tick); }; later(tick);
      return send(res, d);
    }
    if (action === 'download/cancel') { if (a.download) a.download.state = 'cancelled'; return send(res, ok); }
    if (action === 'runtime/install' || action === 'runtime/upload') return send(res, job(p, 'Installing llama.cpp', () => { a.runtimeInstalled = true; a.runtimeOutdated = false; }));
    if (action === 'benchmark') {
      const result = job(p, 'Benchmarking AI', () => { a.benchmarking = false; a.benchmark = {model: a.localModel, bytes: 1117320736, at: Date.now(), cpu: {pp: 61, tg: 12}, gpu: {pp: 142, tg: 17, name: 'Demo GPU', device: 'Vulkan0'}}; });
      a.benchmarking = true; return send(res, result);
    }
    if (action === 'speak') { p.lastSpoken = b.text; return send(res, ok); }
    if (action === 'routines') {
      if (!b.name?.trim()) fail(400, 'Enter a name');
      const routine = {...b, id: b.id || F.hex(8)};
      p.routines = p.routines.filter(x => x.id !== routine.id).concat(routine); return send(res, routine);
    }
    if (action === 'routines/place') { if (!b.name?.trim()) fail(400, 'Enter a place'); return send(res, {place: b.name + ' (demo result)', lat: 51.50853, lon: -0.12574}); }
    if (action === 'routines/settings') {
      for (const k of ['email', 'ntfy', 'telegram']) if (b[k]) {
        const {password, token, ...safe} = b[k]; Object.assign(p.routineSettings[k], safe);
        if (password) p.routineSettings[k].hasPassword = true;
        if (token) p.routineSettings[k].hasToken = true;
      }
      return send(res, ok);
    }
    if (s[0] === 'routines' && s[2] === 'run') {
      const r = need(p.routines.find(x => x.id === s[1])); if (p.routineRunning) fail(409, 'A routine is running');
      p.routineRunning = r.id;
      later(() => { p.routineRunning = null; r.lastRun = Date.now(); r.lastError = null; p.briefs.unshift({id: F.hex(8), routine: r.id, name: r.name, time: Date.now(), seconds: 2, model: r.model, where: 'local', text: '**Demo brief:** Your phone is online. Check Modules for services that need attention.', delivery: {}}); });
      return send(res, ok);
    }
    return false;
  }
  function networkRoute(p, s, method, b, res) {
    const n = p.network, action = s.join('/');
    if (method === 'GET' && action === 'enabled') return send(res, {enabled: n.enabled});
    if (method === 'GET' && !action) {
      if (n.serverUntil < Date.now() && n.jobs.server) n.jobs.server.running = false;
      return send(res, F.networkJson(p));
    }
    if (!n.enabled) fail(409, 'Turn on Network tools in Modules first');
    if (method !== 'POST') return false;
    if (action === 'discover') {
      n.discovery.running = true; n.discovery.started = Date.now();
      later(() => { if (!n.discovery.running) return; n.discovery.running = false; n.discovery.devices = [
        {ip: '192.168.1.1', name: 'Home router', sources: ['ssdp', 'tcp'], ports: [80, 443], lastSeen: Date.now()},
        {ip: '192.168.1.10', name: 'Living room NAS', sources: ['mdns', 'tcp'], ports: [22, 443, 5000], lastSeen: Date.now()},
        {ip: '192.168.1.40', name: 'Gaming PC', sources: ['tcp'], ports: [3389], lastSeen: Date.now() - 86400000}]; }); return send(res, ok);
    }
    if (action === 'discover/cancel') { n.discovery.running = false; return send(res, ok); }
    if (action === 'server') { n.serverUntil = Date.now() + 15000; n.jobs.server = {kind: 'server', running: !!b.enabled, cancelled: false, output: b.enabled ? 'Server listening on 5201\nWaiting for a demo client…' : 'Server stopped', error: null}; return send(res, ok); }
    if (s[0] === 'cancel') { const j = need(n.jobs[s[1]]); j.running = false; j.cancelled = true; return send(res, ok); }
    if (s[0] === 'run' && ['ping', 'tcp', 'traceroute', 'dns', 'scan', 'http', 'whois', 'iperf', 'speed'].includes(s[1])) {
      const kind = s[1]; if (n.jobs[kind]?.running) fail(409, 'Tool is already running');
      if (kind !== 'speed' && !String(b.host || b.url || '').trim()) fail(400, 'Enter a host or URL');
      const j = n.jobs[kind] = {kind, running: true, cancelled: false, output: `Starting ${kind} (simulated)\n`, error: null, tcpFallback: false, result: null};
      later(() => {
        if (j.cancelled) return; j.running = false;
        const outputs = {ping: '4 packets transmitted, 4 received, 0% packet loss\nrtt min/avg/max = 1.2/2.1/3.4 ms', tcp: 'Connected to port ' + (b.port || 443) + ' in 2.4 ms', traceroute: '1 192.168.1.1  1.4 ms\n2 10.10.0.1  5.2 ms\n3 destination  12.1 ms', dns: 'example.com. 300 IN A 93.184.215.14', scan: '22/tcp open ssh\n80/tcp open http\n443/tcp open https', http: 'HTTP/2 200\nserver: demo\ncontent-type: text/html', whois: 'Domain Name: EXAMPLE.COM\nRegistry: IANA (simulated)', iperf: '[SUM] sender 412 Mbits/sec\n[SUM] receiver 398 Mbits/sec', speed: 'Download 92.4 Mbps; upload 24.8 Mbps; connection latency 12 ms'};
        j.output += outputs[kind];
        if (kind === 'http') j.result = {Status: 200, DNS: .004, Connect: .012, TLS: .024, FirstByte: .055, Total: .082, certExpiry: '2027-05-01'};
        if (kind === 'iperf') j.result = {sentMbps: 412, receivedMbps: 398};
        if (kind === 'ping' && b.host === 'denied') { j.error = 'Operation not permitted'; j.tcpFallback = true; j.output = 'This phone does not allow ping from apps.'; }
      }); return send(res, ok);
    }
    return false;
  }
  async function route(req, res) {
    const url = new URL(req.url, 'http://localhost'), method = req.method;
    if (!url.pathname.startsWith('/api/')) {
      if (method !== 'GET') fail(404, 'Not found');
      const rel = ['/', '/index.html', '/dashboard.html'].includes(url.pathname) ? 'dashboard.html' : decodeURIComponent(url.pathname.slice(1));
      if (rel !== 'dashboard.html' && rel !== 'favicon.svg' && !/^(vendor|guides)\/[\w.-]+$/.test(rel)) fail(404, 'Not found');
      const filename = path.join(F.ASSETS, rel); if (!fs.existsSync(filename)) fail(404, 'Not found');
      const stat = fs.statSync(filename), key = stat.mtimeMs + ':' + stat.size;
      let entry = assets.get(filename);
      if (!entry || entry.key !== key) { const raw = fs.readFileSync(filename); entry = {key, raw, gzip: zlib.gzipSync(raw)}; assets.set(filename, entry); }
      const etag = '"' + key + '"', headers = {'Content-Type': MIME[path.extname(filename)] || 'application/octet-stream', 'Content-Security-Policy': CSP, 'X-Frame-Options': 'DENY', 'Referrer-Policy': 'no-referrer', ETag: etag, 'Cache-Control': 'no-cache', Vary: 'Accept-Encoding'};
      if (req.headers['if-none-match'] === etag) return send(res, Buffer.alloc(0), 304, headers);
      const gzip = /gzip/.test(req.headers['accept-encoding'] || ''); if (gzip) headers['Content-Encoding'] = 'gzip';
      return send(res, gzip ? entry.gzip : entry.raw, 200, headers);
    }
    const {body: b, raw} = await readBody(req);
    if (url.pathname === '/api/login' && method === 'POST') { if (b.password !== cluster.self.password) fail(401, 'Wrong password'); return newSession(res); }
    if (!authorized(req)) fail(401, 'Log in first');
    let s = url.pathname.slice(5).split('/').filter(Boolean).map(decodeURIComponent), p = cluster.self;
    if (s[0] === 'nodes') { p = phoneById(s[1]); if (!p.online) fail(502, `${p.name} is offline or unreachable`); s = s.slice(2); }
    const action = s.join('/');
    if (method === 'POST' && action === 'logout') { cluster.self.sessions.delete(session(req)); return send(res, ok, 200, {'Set-Cookie': cookie('')}); }
    if (action === 'ui/prefs') {
      // Dashboard customization, as the phone stores it. MOCK_NO_PREFS=1 behaves like an older phone without the endpoint.
      if (process.env.MOCK_NO_PREFS) fail(404, 'Not found');
      if (method === 'GET') return send(res, cluster.uiPrefs || {});
      if (req.headers['x-homedroid-request'] !== '1') fail(403, 'Missing X-Homedroid-Request header');
      if (method === 'PUT') {
        if (!b || typeof b !== 'object' || Array.isArray(b)) fail(400, 'Preferences must be a JSON object');
        if (raw.length > 256 * 1024) fail(413, 'Preferences are limited to 256 KB');
        cluster.uiPrefs = {...b, updated: Date.now()};
        return send(res, cluster.uiPrefs);
      }
      if (method === 'DELETE') { cluster.uiPrefs = null; return send(res, {}); }
      return unknown(req, res);
    }
    if (s[0] === 'cluster') {
      if (method === 'GET' && s.length === 1) return send(res, clusterJson());
      if (method === 'POST') {
        if (action === 'cluster/name') { if (!b.name?.trim()) fail(400, 'Enter a name'); cluster.self.name = b.name.trim(); }
        else if (action === 'cluster/discover') { cluster.discovering = true; later(() => { cluster.discovering = false; cluster.discovered = [{id: 'new-phone', name: 'Kitchen phone', address: '192.168.1.52', port: 8801, member: cluster.members.some(m => m.id === 'new-phone')}]; }); }
        else if (action === 'cluster/pair') {
          if (!b.address?.trim()) fail(400, 'Enter the other phone’s address');
          const outgoing = cluster.outgoing = {name: 'Kitchen phone', state: 'waiting', code: '482916'};
          later(() => { if (cluster.outgoing !== outgoing) return; outgoing.state = 'approved'; if (!cluster.members.some(m => m.id === 'new-phone')) cluster.members.push(F.createPhone({id: 'new-phone', name: 'Kitchen phone', model: 'Google Pixel 6', ips: ['192.168.1.52'], variant: 'member'})); }, delayMs * 3);
        } else if (action === 'cluster/pair/cancel') cluster.outgoing = null;
        else if (action === 'cluster/leave') cluster.members = [];
        else if (s[1] === 'members' && s[3] === 'remove') { phoneById(s[2]); cluster.members = cluster.members.filter(m => m.id !== s[2]); }
        else if (s[1] === 'requests' && ['approve', 'reject'].includes(s[3])) { const r = need(cluster.pending.find(x => x.id === s[2])); if (s[3] === 'approve') cluster.members.push(F.createPhone({id: r.id, name: r.name, model: r.model, ips: [r.address], variant: 'member'})); cluster.pending = cluster.pending.filter(x => x !== r); }
        else return unknown(req, res);
        return send(res, ok);
      }
    }
    if (method === 'GET') {
      const views = {status: F.status, overview: p => ({status: F.status(p), modules: F.modules(p), deploys: F.deploys(p), services: F.services(p)}), settings: F.settings, modules: F.modules, job: F.job, services: F.services, deploys: F.deploys, update: F.updateJson, tailscale: F.tailscaleJson};
      if (views[action]) { if (action === 'update' && url.searchParams.has('refresh')) p.update.checkedAt = Math.floor(Date.now() / 1000); return send(res, views[action](p)); }
    }
    if (method === 'POST' && action === 'settings') return saveSettings(p, b, req, res);
    if (method === 'POST' && s[0] === 'modules' && s.length === 3) return send(res, moduleAction(p, s[1], s[2], b));
    if (s[0] === 'services' && s.length === 3) {
      if (method === 'GET' && s[2] === 'logs') return send(res, need(F.logs(p, s[1]), 'Service is not running'));
      if (method === 'POST' && s[2] === 'restart') { need(F.daemon(p, s[1]), 'Service is not running'); F.bump(p, s[1], true); return send(res, ok); }
    }
    // Console pages: databases, documents, domains, logs, DDNS, firewall, MCP, deployment settings and hooks, MacroPads, desktop.
    if (['db', 'docs', 'domains', 'ddns', 'firewall', 'mcp', 'macropads', 'desktop'].includes(s[0]) || (s[0] === 'deploys' && s.length >= 3 && ['settings', 'token', 'upload', 'pipeline', 'runs'].includes(s[2]))) {
      const handled = await X.route(extras, p, s, method, b, raw, url, res, send, {later, stepMs: jobStepMs});
      if (handled !== false) return;
    }
    if (s[0] === 'deploys') {
      if (method === 'POST' && s.length === 1) {
        const upload = b.source === 'upload';
        if (!b.name?.trim() || (!upload && !b.repo) || !(b.port >= 1024 && b.port <= 65535)) fail(400, upload ? 'Enter a name and port' : 'Enter a name, repository URL and port');
        if (p.deploys.some(x => x.port === b.port || x.balanced?.port === b.port)) fail(400, `port ${b.port} is already used`);
        if (p.job.running) fail(409, 'Another job is running');
        const d = {...b, id: b.name.toLowerCase().replace(/[^\w-]/g, '-'), repo: upload ? '' : b.repo, source: b.source || 'git', branch: b.branch || 'main', env: b.env || {}, commit: null, detected: b.runtime === 'auto' ? 'node' : b.runtime, error: null,
          token: 'hd_' + F.hex(40), webhookSecret: upload ? '' : 'whsec_' + F.hex(40), balanced: b.balanced || null, pipeline: b.pipeline || []};
        if (p.deploys.some(x => x.id === d.id)) fail(400, `a deployment called ${d.id} already exists`); p.deploys.push(d);
        const created = {...F.deployJson(p, d), token: d.token, webhookSecret: d.webhookSecret};
        if (!upload) job(p, `Deploying ${d.name}`, () => { d.commit = F.hex(40); });
        return send(res, created);
      }
      if (method === 'POST' && s[2] === 'move') {
        const from = phoneById(b.from), d = need(from.deploys.find(x => x.id === s[1]));
        return send(res, job(p, `Moving ${d.name}`, () => { p.deploys.push(structuredClone(d)); if (b.removeSource) from.deploys = from.deploys.filter(x => x !== d); }));
      }
      if (method === 'POST' && s[2] === 'redeploy') { const d = need(p.deploys.find(x => x.id === s[1])); return send(res, job(p, `Deploying ${d.name}`, () => { d.commit = F.hex(40); d.error = null; F.bump(p, 'deploy-' + d.id); })); }
      if (method === 'DELETE' && s.length === 2) { need(p.deploys.find(x => x.id === s[1])); p.deploys = p.deploys.filter(x => x.id !== s[1]); return send(res, ok); }
    }
    if (s[0] === 'ssh' && s.length === 2 && ['keys', 'sessions', 'history'].includes(s[1])) {
      const key = s[1];
      if (method === 'GET') return send(res, p.ssh[key]);
      if (method === 'DELETE') { p.ssh[key] = key === 'keys' ? p.ssh.keys.filter(k => k.fingerprint !== url.searchParams.get('fingerprint')) : key === 'history' ? {phone: [], alpine: []} : []; return send(res, ok); }
      if (method === 'POST' && key === 'keys') {
        if (!/^ssh-(ed25519|rsa)\s+\S+/.test(b.key || '')) fail(400, 'Paste an SSH public key');
        const entry = {type: b.key.split(' ')[0], name: b.name || '', fingerprint: 'SHA256:' + crypto.createHash('sha256').update(b.key).digest('base64')}; p.ssh.keys.push(entry); return send(res, {fingerprint: entry.fingerprint, name: entry.name}, 201);
      }
    }
    if (s[0] === 'tailscale' && method === 'POST') {
      if (s.length === 1) { const {authKey, clearAuthKey, ...safe} = b; Object.assign(p.tailscale, safe); if (authKey) p.tailscale.hasAuthKey = true; if (clearAuthKey) p.tailscale.hasAuthKey = false; }
      else if (s[1] === 'login') p.tailscale.loggedOut = false;
      else if (s[1] === 'logout') p.tailscale.loggedOut = true;
      else return unknown(req, res); return send(res, ok);
    }
    if (s[0] === 'wol') {
      if (!p.wolEnabled) fail(409, 'Enable Wake on LAN first');
      if (method === 'GET' && s.length === 1) return send(res, F.wolJson(p));
      if (method === 'POST' && s[1] === 'devices') {
        if (!b.name?.trim() || !/^([\da-f]{2}[:-]){5}[\da-f]{2}$/i.test(b.mac || '')) fail(400, 'Enter a name and a valid MAC address');
        const d = {...b, id: b.id || F.hex(12)}; p.wol = p.wol.filter(x => x.id !== d.id).concat(d); return send(res, d);
      }
      if (method === 'DELETE' && s[1] === 'devices' && s.length === 3) { p.wol = p.wol.filter(x => x.id !== s[2]); return send(res, ok); }
      if (method === 'POST' && s[1] === 'wake') { if (b.id) need(p.wol.find(x => x.id === b.id)); return send(res, {sent: ['192.168.1.255']}); }
    }
    if (s[0] === 'update' && method === 'POST' && ['install', 'upload'].includes(s[1])) {
      if (['downloading', 'installing'].includes(p.update.state)) fail(409, 'An update is already running');
      p.update.state = 'downloading'; p.update.done = 0; p.update.total = p.update.latest.size;
      later(() => { p.update.done = p.update.total; p.update.state = 'installing'; later(() => { p.version = p.update.latest.version; p.update.state = 'idle'; }); }); return send(res, ok);
    }
    if (s[0] === 'files' && filesRoute(p, s.slice(1), method, b, raw, req, res, url) !== false) return;
    if (s[0] === 'camera' && await cameraRoute(p, s.slice(1), method, b, req, res, url) !== false) return;
    if (s[0] === 'ai' && aiRoute(p, s.slice(1), method, b, req, res) !== false) return;
    if (s[0] === 'network' && networkRoute(p, s.slice(1), method, b, res) !== false) return;
    if (action === 'terminal' && method === 'GET') return send(res, {error: 'WebSocket upgrade required'}, 426);
    unknown(req, res);
  }
  const assets = new Map();
  function unknown(req, res) { log(`[dashboard-mock] Unhandled ${req.method} ${req.url}`); send(res, {error: 'No such mock endpoint'}, 404); }
  const server = http.createServer((req, res) => route(req, res).catch(e => {
    if (!e.code || typeof e.code !== 'number') log(e.stack);
    if (!res.headersSent) send(res, {error: e.message || 'Mock server error'}, typeof e.code === 'number' ? e.code : 500); else res.end();
  }));
  // Minimal RFC 6455 terminal simulation. No PTY or host shell is ever opened.
  server.on('upgrade', (req, socket, head) => {
    const url = new URL(req.url, 'http://localhost');
    const match = /^\/api\/(?:nodes\/([^/]+)\/)?terminal$/.exec(url.pathname);
    let p = match?.[1] ? phones().find(x => x.id === decodeURIComponent(match[1])) : cluster.self;
    if (!match || !authorized(req) || !p?.online || !p.core.ssh) { socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n'); return; }
    const accept = crypto.createHash('sha1').update(req.headers['sec-websocket-key'] + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
    socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ' + accept + '\r\n\r\n');
    const frame = (data, op = 2) => { data = Buffer.from(data); const h = Buffer.alloc(data.length < 126 ? 2 : 4); h[0] = 0x80 | op; h[1] = data.length < 126 ? data.length : 126; if (h.length === 4) h.writeUInt16BE(data.length, 2); if (!socket.destroyed) socket.write(Buffer.concat([h, data])); };
    frame('\x1b[36mHomedroid demo terminal\x1b[0m\r\nSimulated shell; try help, whoami, uptime or ls.\r\n$ ');
    let buffer = head, line = '';
    const parse = () => {
      while (buffer.length >= 2) {
        const opcode = buffer[0] & 15, masked = buffer[1] & 128; let size = buffer[1] & 127, offset = 2;
        if (size === 127) { socket.destroy(); return; }
        if (size === 126) { if (buffer.length < 4) return; size = buffer.readUInt16BE(2); offset = 4; }
        if (!masked) { socket.destroy(); return; }
        if (buffer.length < offset + 4 + size) return;
        const mask = buffer.subarray(offset, offset + 4), data = Buffer.from(buffer.subarray(offset + 4, offset + 4 + size)); buffer = buffer.subarray(offset + 4 + size);
        for (let i = 0; i < size; i++) data[i] ^= mask[i % 4];
        if (opcode === 8) { frame(data, 8); socket.end(); return; }
        if (opcode === 9) { frame(data, 10); continue; }
        if (opcode !== 2) continue; // Text messages are terminal resize JSON.
        for (const ch of data.toString()) {
          if (ch === '\r' || ch === '\n') {
            frame('\r\n'); const cmd = line.trim(); line = '';
            if (cmd === 'exit') { frame(Buffer.from([3, 232]), 8); socket.end(); return; }
            if (cmd === 'clear') frame('\x1b[2J\x1b[H');
            else if (cmd) { const out = {help: 'help  whoami  uptime  ls  clear  exit', whoami: 'u0_a312', uptime: 'up ' + Math.floor((Date.now() - p.bootAt) / 3600000) + ' hours', ls: 'www  backups  todo.md  server.log'}[cmd] || 'Demo only: no command was executed.'; frame(out + '\r\n'); p.ssh.history.phone.unshift({time: Math.floor(Date.now() / 1000), command: cmd}); }
            frame('$ ');
          } else if (ch === '\x7f') { if (line) { line = line.slice(0, -1); frame('\b \b'); } }
          else if (ch >= ' ') { line += ch; frame(ch); }
        }
      }
    };
    socket.on('data', data => { buffer = Buffer.concat([buffer, data]); parse(); }); socket.on('error', () => {}); if (head.length) parse();
  });
  server.on('close', () => { for (const timer of timers) clearTimeout(timer); });
  server.state = cluster;
  return server;
}
module.exports = {createMockServer, CSP};
if (require.main === module) {
  const port = Number(process.env.PORT || 8787), host = process.env.HOST || '127.0.0.1';
  const server = createMockServer();
  server.listen(port, host, () => console.log(`Homedroid mock: http://${host}:${server.address().port} — password: demo`));
  for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, () => { server.close(); server.closeAllConnections(); });
}
