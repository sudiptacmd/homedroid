// Fake phones for the dashboard mock server (see server.cjs). No dependencies.
//
// Each phone is a plain, mutable object. The view functions at the bottom build JSON in
// exactly the shapes the Kotlin handlers return (Dashboard.kt, Ai.kt, Routines.kt, ...), so
// the page behaves as it would against a real phone. To add data for a new page, add a field
// in createPhone() and a route in server.cjs that returns it.
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const crypto = require('node:crypto');

const ASSETS = path.resolve(__dirname, '../../app/src/main/assets');
const VERSION = '0.9.4';
const now = () => Date.now();
const rand = (a, b) => a + Math.random() * (b - a);
const pick = a => a[Math.floor(Math.random() * a.length)];
const hex = n => crypto.randomBytes(Math.ceil(n / 2)).toString('hex').slice(0, n);
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
const round1 = v => Math.round(v * 10) / 10;
const stamp = () => new Date().toISOString().replace('T', ' ').slice(0, 19);

/** The real app catalog, so module cards match the shipped app. */
function catalog() {
  try { return JSON.parse(fs.readFileSync(path.join(ASSETS, 'apps.json'), 'utf8')); }
  catch (e) { return []; }
}
const serviceNames = a => a.services?.length ? a.services.map(s => `${a.id}-${s.name}`) : [a.id];

// The core modules from Dashboard.CORE, with their supervisor service names.
const CORE = [
  {id: 'ssh', name: 'SSH & SFTP', description: 'Shell and file transfer, public-key login only.', port: 8022, service: 'sshd'},
  {id: 'web', name: 'Web hosting', description: 'Caddy web server for ~/www and deployed sites.', port: 8080, service: 'caddy'},
  {id: 'tunnel', name: 'Cloudflare Tunnel', description: 'Publishes services on the internet, no port forwarding.', port: 0, service: 'cloudflared'},
  {id: 'tailscale', name: 'Tailscale', description: 'Private access from your own devices, anywhere. No VPN slot or root needed.', port: 0, service: 'tailscaled'},
];

// AiCore.CATALOG
const AI_CATALOG = [
  ['qwen2.5-0.5b', 'Qwen2.5 0.5B Instruct', 'qwen2.5-0.5b-instruct-q4_k_m.gguf', 491400032, 'Fastest; fine for short summaries'],
  ['qwen2.5-1.5b', 'Qwen2.5 1.5B Instruct', 'qwen2.5-1.5b-instruct-q4_k_m.gguf', 1117320736, 'Good balance for most phones'],
  ['qwen2.5-3b', 'Qwen2.5 3B Instruct', 'qwen2.5-3b-instruct-q4_k_m.gguf', 2104932768, 'Better answers; needs 6 GB of RAM or more'],
  ['llama3.2-1b', 'Llama 3.2 1B Instruct', 'Llama-3.2-1B-Instruct-Q4_K_M.gguf', 807694464, 'Small and quick'],
  ['llama3.2-3b', 'Llama 3.2 3B Instruct', 'Llama-3.2-3B-Instruct-Q4_K_M.gguf', 2019377696, 'Good writing; needs 6 GB of RAM or more'],
  ['gemma3-1b', 'Gemma 3 1B', 'gemma-3-1b-it-Q4_K_M.gguf', 806058240, 'Small, multilingual'],
  ['gemma4-e2b', 'Gemma 4 E2B', 'gemma-4-E2B_q4_0-it.gguf', 3349516256, 'Newest Gemma: smart for its speed, multilingual; runs like a 2B model'],
  ['gemma3-4b', 'Gemma 3 4B', 'gemma-3-4b-it-Q4_K_M.gguf', 2489757856, 'Best quality here; needs 8 GB of RAM'],
].map(([id, name, file, bytes, note]) => ({id, name, file, bytes, note, ramMb: Math.round(bytes / 1048576 * 1.25 + 300)}));
const aiAlias = file => file.replace(/\.gguf$/, '').toLowerCase();

// ---- Files: an in-memory tree per location ------------------------------------------------
const DAY = 86400000;
const f = (name, size, ageDays = 3) => ({name, dir: false, size, modified: now() - ageDays * DAY - Math.floor(rand(0, DAY))});
const d = (name, children = [], ageDays = 5) => ({name, dir: true, size: 0, modified: now() - ageDays * DAY, children});

function fileRoots(variant) {
  const media = d('', [
    d('Movies', [d('Big Buck Bunny (2008)', [f('Big Buck Bunny (2008).mkv', 1_442_118_656, 40), f('Big Buck Bunny (2008).srt', 48_210, 40), f('poster.jpg', 312_004, 40)], 40),
      d('Sintel (2010)', [f('Sintel (2010).mp4', 812_331_008, 31)], 31)], 40),
    d('Shows', [d('Nature Docs', [d('Season 1', [f('S01E01.mkv', 734_003_200, 20), f('S01E02.mkv', 701_212_672, 19), f('S01E03.mkv', 719_847_424, 18)], 20)], 20)], 20),
    d('Music', [d('Lo-fi Beats', [f('01 Morning.flac', 31_208_448, 60), f('02 Rain.flac', 28_011_520, 60), f('cover.png', 1_204_224, 60)], 60)], 60),
    d('Downloads', [f('ubuntu-24.04-desktop-amd64.iso', 6_114_656_256, 2), f('notes.txt', 1_204, 1)], 1),
  ], 1);
  const home = d('', [
    d('www', [f('index.html', 2_318, 9), f('style.css', 1_022, 9), d('img', [f('logo.svg', 4_096, 9)], 9)], 9),
    d('backups', [f('router-config-2026-10-01.tar.gz', 812_440, 54), f('homeassistant-snapshot.tar', 92_334_080, 12)], 12),
    f('.profile', 412, 120), f('todo.md', 640, 1), f('server.log', 88_310, 0),
  ], 1);
  const phone = d('', [
    d('DCIM', [d('Camera', [f('IMG_20261102_091233.jpg', 3_410_022, 22), f('IMG_20261102_091240.jpg', 3_201_877, 22), f('VID_20261103_180501.mp4', 88_220_311, 21)], 21)], 21),
    d('Download', [f('invoice-october.pdf', 182_211, 25), f('homedroid-0.9.3.apk', 18_440_312, 33)], 25),
    d('Documents', [f('lease.pdf', 2_404_112, 200), f('recipes.txt', 6_002, 90)], 90),
    d('Music', [], 300),
  ], 1);
  const models = d('', variant === 'self' ? [f('qwen2.5-1.5b-instruct-q4_k_m.gguf', 1117320736, 15)] : [], 15);
  const roots = [
    {id: 'media', label: 'Media library (Jellyfin, qBittorrent)', free: 98_311_221_248, total: 238_370_684_928, tree: media},
    {id: 'home', label: 'Home (SSH, web hosting in www)', free: 98_311_221_248, total: 238_370_684_928, tree: home},
    {id: 'aimodels', label: 'AI models', free: 98_311_221_248, total: 238_370_684_928, tree: models},
    {id: 'phone', label: 'Phone storage', free: 98_311_221_248, total: 238_370_684_928, tree: phone},
  ];
  if (variant !== 'self') roots.splice(2, 1); // members have AI off
  return roots;
}

/** The node at [rel] in [root]'s tree, or null; with [parent], its folder and name instead. */
function walk(root, rel, parent = false) {
  const parts = String(rel || '').split('/').filter(Boolean);
  if (parts.some(p => p === '..' || p === '.')) return null;
  let node = root.tree;
  const last = parts.pop();
  for (const p of parts) {
    node = node.children?.find(c => c.dir && c.name === p);
    if (!node) return null;
  }
  if (parent) return last === undefined ? null : {dir: node, name: last};
  return last === undefined ? node : node.children?.find(c => c.name === last) || null;
}
const treeSize = n => n.dir ? n.children.reduce((s, c) => s + treeSize(c), 0) : n.size;

// ---- A phone -------------------------------------------------------------------------------
/**
 * variant 'self' is the phone serving the dashboard; 'member' a phone in its cluster.
 */
function createPhone({id, name, model, ips, variant = 'self', online = true, android = '15', ramTotalMb = 11_811}) {
  const apps = catalog();
  const self = variant === 'self';
  const p = {
    id, name, model, ips, variant, online, android, ramTotalMb,
    fingerprint: crypto.createHash('sha256').update(id).digest('hex'),
    bootAt: now() - (self ? 3 * DAY + 4.2 * 3600_000 : 11 * 3600_000),
    password: 'demo',
    sessions: new Set(),
    battery: self ? 82 : 64, charging: self,
    storageTotalMb: 228_000, storageFreeMb: self ? 93_760 : 140_200,
    metrics: {cpu: 6, cpuTemp: 41, batteryTemp: 33.5, rx: 42_000, tx: 9_000, cores: 8},
    autostart: true, sshPort: 8022, appsDns: '',
    cameraEnabled: true, wolEnabled: self,
    core: {ssh: true, web: true, tunnel: false, tailscale: self},
    tunnelToken: '',
    tailscale: {hostname: id === 'self' ? 'homedroid' : name.toLowerCase().replace(/[^a-z0-9]+/g, '-'), hasAuthKey: false, exitNode: false,
      routes: '', tags: '', serve: 'tailnet', proxy: false},
    apps: apps.map(a => ({
      def: a,
      installed: self ? ['jellyfin', 'qbittorrent', 'adguard', 'homeassistant'].includes(a.id) : a.id === 'adguard',
      enabled: true,
      storageDir: a.storage?.label === 'Media library' && self ? '/storage/7A1B-22F0/Homedroid/Media' : null,
    })),
    // Per-service runtime state: {state, restarts, startingUntil}
    svc: {},
    logs: {},
    job: {title: null, running: false, error: null, log: []},
    deploys: self ? [
      {id: 'my-blog', name: 'my-blog', repo: 'https://github.com/example/my-blog', branch: 'main', port: 3000, runtime: 'auto',
        build: 'npm run build', start: 'npm start', env: {NODE_ENV: '***'}, autoDeploy: true, replicated: true, token: 'hd_demo', webhookSecret: 'whsec_demo', pipeline: [{name: 'Install', run: 'npm ci'}, {name: 'Test', run: 'npm test'}, {name: 'Lint', run: 'npm run lint'}], commit: '9f3c2a1d4b5e6f708192a3b4c5d6e7f809102b3c', detected: 'node', error: null},
      {id: 'status-page', name: 'status-page', repo: 'https://github.com/example/status-page', branch: 'main', port: 3100, runtime: 'static',
        build: '', start: '', env: {}, autoDeploy: false, balanced: {port: 9000, policy: 'round_robin', healthUri: '/', interval: 10}, commit: 'a1b2c3d4e5f60718293a4b5c6d7e8f9012345678', detected: 'static', error: null},
      {id: 'docs-site', name: 'docs-site', source: 'upload', repo: '', branch: 'main', port: 3200, runtime: 'static', build: '', start: '', env: {}, autoDeploy: false, token: 'hd_demo2', commit: 'upload-3fa91c27d0b4', detected: 'static', error: null},
      {id: 'api-server', name: 'api-server', repo: 'https://***@github.com/example/api-server', branch: 'develop', port: 5000, runtime: 'python',
        build: 'pip install -r requirements.txt', start: 'python app.py', env: {DATABASE_URL: '***', SECRET_KEY: '***'}, autoDeploy: false,
        commit: null, detected: 'python', error: 'build failed'},
    ] : [],
    files: fileRoots(variant),
    transfers: [], nextTransfer: 1,
    ssh: {
      keys: self ? [
        {type: 'ssh-ed25519', fingerprint: 'SHA256:3v1q8Tz0ZJm7dXk2b5Wl0yQyM4cY8oKfN1rUeP6sHgA', name: 'laptop'},
        {type: 'ssh-rsa', fingerprint: 'SHA256:Qk8r0Lz2bN6vT1yM5xW3cJ7hF9dS4aP0eR2uI8oK6gY', name: 'work-desktop'},
      ] : [],
      sessions: self ? [
        {start: Math.floor(now() / 1000) - 600, end: 0, ip: '192.168.1.23', key: 'laptop', user: 'u0_a312', via: 'ssh', shells: 1, sftp: false, forwards: [], commands: ['htop', 'ls -la www']},
        {start: Math.floor(now() / 1000) - 7200, end: Math.floor(now() / 1000) - 5400, ip: '192.168.1.23', key: 'laptop', user: 'u0_a312', via: 'ssh', shells: 0, sftp: true, forwards: ['localhost:8096'], commands: []},
        {start: Math.floor(now() / 1000) - 86400, end: Math.floor(now() / 1000) - 86000, ip: '127.0.0.1', via: 'web', shells: 1, sftp: false, forwards: [], commands: ['df -h', 'top -n 1']},
        {start: Math.floor(now() / 1000) - 90000, end: Math.floor(now() / 1000) - 90000, ip: '203.0.113.77', user: 'root', failed: 'no matching key'},
      ] : [],
      history: {
        phone: self ? [[3600, 'htop'], [3700, 'ls -la www'], [86400, 'df -h'], [86500, 'top -n 1'], [90000, 'cat /proc/cpuinfo']]
          .map(([ago, command]) => ({time: Math.floor(now() / 1000) - ago, command})) : [],
        alpine: self ? ['apk update', 'apk add htop', 'python3 --version'].map(command => ({command})) : [],
      },
    },
    update: {state: 'idle', error: null, done: 0, total: 0, checkedAt: Math.floor(now() / 1000) - 1200,
      latest: {version: '0.9.5', published: '2026-11-20T10:12:00Z', apk: 'homedroid-0.9.5-arm64-v8a.apk', size: 18_912_331,
        notes: 'Faster file browser\nCluster: copy folders between phones\nAI: Gemma 4 E2B in the catalog\nFixes for Tailscale Funnel'}},
    version: VERSION,
    ai: {
      enabled: self, key: 'hd-' + hex(40), localSupported: true, runtimeInstalled: self, runtimeOutdated: false, runtimeBytes: 17_301_504,
      device: 'auto', devices: self ? [{id: 'Vulkan0', name: 'Adreno (TM) 740'}] : [],
      benchmark: self ? {model: 'qwen2.5-1.5b-instruct-q4_k_m.gguf', bytes: 1117320736, at: now() - 6 * DAY,
        cpu: {pp: 61.4, tg: 11.8}, gpu: {pp: 142.9, tg: 17.3, device: 'Vulkan0', name: 'Adreno (TM) 740'}} : null,
      localModel: self ? 'qwen2.5-1.5b-instruct-q4_k_m.gguf' : null,
      files: self ? [{file: 'qwen2.5-1.5b-instruct-q4_k_m.gguf', bytes: 1117320736}] : [],
      contextSize: 4096, threads: 0, fallback: self ? 'openai/gpt-4o-mini' : '',
      modelsDir: '/data/user/0/dev.homedroid/files/ai/models',
      download: null, benchmarking: false,
      providers: self ? [{id: 'openai', type: 'openai', name: 'OpenAI', baseUrl: 'https://api.openai.com/v1', hasKey: true, models: ['gpt-4o-mini', 'gpt-4.1', 'o4-mini']},
        {id: 'ollama-desktop', type: 'compatible', name: 'Ollama desktop', baseUrl: 'http://192.168.1.23:11434/v1', hasKey: false, models: ['llama3.1:8b', 'qwen2.5-coder:14b']}] : [],
    },
    routines: self ? [
      {id: 'm8k2q1', name: 'Morning brief', enabled: true, schedule: {type: 'weekdays', time: '07:00', hours: 3},
        sources: [{type: 'weather', place: 'London, England, United Kingdom', lat: 51.50853, lon: -0.12574}, {type: 'rss', url: 'https://feeds.bbci.co.uk/news/world/rss.xml'}, {type: 'email'}, {type: 'phone'}],
        instruction: 'Give me my morning brief: the weather and what to wear, the five most important news stories in one line each, emails that need a reply today, and anything wrong with my phone server. Keep it under 200 words so it can be read aloud.',
        model: 'local/qwen2.5-1.5b-instruct-q4_k_m', cloudEmail: false, speak: 'self', ntfy: true, telegram: false, lastRun: now() - 26 * 3600_000, lastError: null},
      {id: 'm8k2q2', name: 'Inbox skim', enabled: false, schedule: {type: 'interval', time: '00:00', hours: 3}, sources: [{type: 'email'}],
        instruction: 'List the unread emails that need my attention, most important first, one line each.',
        model: 'openai/gpt-4o-mini', cloudEmail: true, speak: '', ntfy: false, telegram: true, lastRun: now() - 3 * DAY, lastError: 'IMAP login failed: authentication failed'},
    ] : [],
    routineRunning: null,
    routineSettings: {email: {host: 'imap.example.com', port: 993, user: 'me@example.com', hasPassword: true, hours: 12},
      ntfy: {server: 'https://ntfy.sh', topic: 'homedroid-demo', hasToken: false}, telegram: {chat: '', hasToken: false}},
    briefs: self ? [
      {id: String(now() - 26 * 3600_000), routine: 'm8k2q1', name: 'Morning brief', time: now() - 26 * 3600_000, seconds: 38, model: 'local/qwen2.5-1.5b-instruct-q4_k_m', where: 'local',
        text: '**Weather:** 9 °C and cloudy, light rain after 3 pm. Take a jacket and an umbrella.\n\n**News:**\n- Markets steady ahead of central bank meeting\n- New climate agreement signed by 40 countries\n- Rail strike called off\n\n**Email:** Your landlord asked about the boiler visit; reply today.\n\n**Phone:** All services running, battery 82%, 94 GB free.',
        delivery: {speak: 'sent', ntfy: 'sent'}},
      {id: String(now() - 3 * DAY), routine: 'm8k2q2', name: 'Inbox skim', time: now() - 3 * DAY, seconds: 4, error: 'IMAP login failed: authentication failed'},
    ] : [],
    wol: self ? [
      {id: 'a1b2c3d4e5f6', name: 'Gaming PC', mac: '3c:7c:3f:12:34:56', host: '192.168.1.40', broadcast: ''},
      {id: 'b2c3d4e5f6a1', name: 'NAS', mac: '00:11:32:aa:bb:cc', host: '192.168.1.10', broadcast: '192.168.1.255'},
      {id: 'c3d4e5f6a1b2', name: 'Office workstation', mac: 'd8:bb:c1:01:02:03', host: '', broadcast: ''},
    ] : [],
    network: {enabled: self, installing: false, jobs: {}, discovery: {running: false, devices: [], started: 0}, serverUntil: 0},
    camera: {
      cameras: [
        {id: '0', name: 'Back camera (0)', flash: true, rotation: 90, front: false},
        {id: '1', name: 'Front camera (1)', flash: false, rotation: 270, front: true},
      ],
      settings: {},
      quotaMb: 4096, armed: true, busy: false, recording: false, selected: null, torch: null, live: null,
      monitor: {id: null, recording: false}, error: null, frame: 0,
      mic: {running: false, recording: false, error: null, startedAt: 0},
      files: self ? [
        {name: `${now() - 2 * 3600_000}-${crypto.randomUUID()}.jpg`, bytes: 412_331},
        {name: `${now() - 20 * 3600_000}-${crypto.randomUUID()}.mp4`, bytes: 18_220_114},
        {name: `${now() - 30 * 3600_000}-${crypto.randomUUID()}.wav`, bytes: 1_920_044},
      ] : [{name: `${now() - 5 * 3600_000}-${crypto.randomUUID()}.jpg`, bytes: 388_101}],
    },
  };
  for (const c of p.camera.cameras) p.camera.settings[c.id] = {resolution: 480, fps: 5, rotation: 0, mode: 'motion', sensitivity: 10, quietSeconds: 10, clipSeconds: 60};
  // Something to look at in "keeps crashing" styles.
  if (self) p.svc.homeassistant = {state: 'backoff', restarts: 4};
  if (self) p.svc['deploy-my-blog'] = {state: 'running', restarts: 1};
  return p;
}

// ---- Simulated background work ----------------------------------------------------------------
/** Runs a fake job (the Modules/Deployments log card); returns an error message if one runs already. */
function startJob(p, title, lines, {stepMs = 450, fail = null, onDone} = {}) {
  if (p.job.running) return 'another install is running';
  p.job = {title, running: true, error: null, log: []};
  const job = p.job;
  let i = 0;
  const t = setInterval(() => {
    if (i < lines.length) { job.log.push(lines[i++]); return; }
    clearInterval(t);
    job.running = false;
    if (fail) { job.error = fail; job.log.push('error: ' + fail); }
    else onDone?.();
  }, stepMs);
  return null;
}
const installLines = name => [`==> Installing ${name}`, 'Alpine: up to date', 'fetch https://dl-cdn.alpinelinux.org/alpine/v3.21/main/aarch64/APKINDEX.tar.gz',
  `Downloading ${name}…`, '  12% ━━━━━━━━━━━━━━━━━━', '  47% ━━━━━━━━━━━━━━━━━━', '  83% ━━━━━━━━━━━━━━━━━━', ' 100% ━━━━━━━━━━━━━━━━━━',
  'Unpacking', 'Writing configuration', `Starting ${name}`, 'Done'];

/** A service just (re)started: "starting" for a couple of seconds, then running. */
function bump(p, name, restart = false) {
  const s = p.svc[name] ||= {state: 'running', restarts: 0};
  s.state = 'running'; s.startingUntil = now() + 2500;
  if (restart) s.restarts++;
}

// ---- Views: the JSON shapes of the Kotlin handlers ---------------------------------------------
function sampleMetrics(p) {
  const m = p.metrics;
  m.cpu = clamp(m.cpu + rand(-3, 3.2) + (p.job.running ? 4 : 0), 0.3, p.job.running ? 85 : 45);
  m.cpuTemp = clamp(m.cpuTemp + rand(-0.6, 0.6) + (m.cpu - 15) * 0.02, 34, 58);
  m.batteryTemp = clamp(m.batteryTemp + rand(-0.15, 0.15), 29, 40);
  m.rx = Math.round(clamp(m.rx * rand(0.6, 1.5) + rand(-4000, 4000), 300, 4_500_000));
  m.tx = Math.round(clamp(m.tx * rand(0.6, 1.5) + rand(-1500, 1500), 120, 1_200_000));
  if (Math.random() < 0.04) m.rx = Math.round(rand(800_000, 3_500_000)); // occasional burst
  const thermal = m.cpuTemp > 55 ? 'hot' : m.cpuTemp > 49 ? 'warm' : 'normal';
  return {cpu: round1(m.cpu), cores: m.cores, rxBps: m.rx, txBps: m.tx, cpuTemp: round1(m.cpuTemp), thermal};
}

function status(p) {
  const metrics = sampleMetrics(p);
  if (Math.random() < 0.05) p.battery = clamp(p.battery + (p.charging ? 1 : -1), 5, 100);
  return {
    model: p.model, android: p.android, battery: p.battery, batteryTemp: round1(p.metrics.batteryTemp), charging: p.charging,
    hot: metrics.thermal === 'hot', ramFreeMb: Math.round(p.ramTotalMb * rand(0.36, 0.4)), ramTotalMb: p.ramTotalMb,
    storageFreeMb: p.storageFreeMb, storageTotalMb: p.storageTotalMb, ips: p.ips, uptime: Math.floor((now() - p.bootAt) / 1000),
    running: true, version: p.version, metrics, node: {id: p.id, name: p.name},
  };
}

/** Supervisor daemons that would run with the current configuration. */
function serviceList(p) {
  const names = [];
  for (const c of CORE) if (p.core[c.id]) names.push(c.service);
  for (const a of p.apps) if (a.installed && a.enabled) names.push(...serviceNames(a.def));
  for (const dep of p.deploys) if (dep.commit) names.push('deploy-' + dep.id);
  if (p.ai.enabled && p.ai.localModel && p.ai.runtimeInstalled && !p.ai.benchmarking) names.push('ai-llama');
  return names;
}
function daemon(p, name) {
  if (!serviceList(p).includes(name)) return null;
  const s = p.svc[name] ||= {state: 'running', restarts: 0};
  return {name, state: s.startingUntil > now() ? 'starting' : s.state, restarts: s.restarts};
}
const services = p => serviceList(p).map(n => daemon(p, n));

function tailscaleJson(p) {
  const t = p.tailscale;
  return {enabled: p.core.tailscale, hostname: t.hostname, hasAuthKey: t.hasAuthKey, exitNode: t.exitNode, routes: t.routes, tags: t.tags,
    serve: t.serve, proxy: t.proxy, proxyPort: 1055,
    status: !p.core.tailscale ? {state: 'Stopped'} : t.loggedOut
      ? {error: null, state: 'NeedsLogin', authUrl: 'https://login.tailscale.com/a/1a2b3c4d5e6f', ips: [], dnsName: '', tailnet: '', peers: 0, peersOnline: 0, version: '1.90.4'}
      : {error: null, state: 'Running', authUrl: '', ips: ['100.101.102.103', 'fd7a:115c:a1e0::6a01:6667'], dnsName: `${t.hostname}.tail1234.ts.net.`.replace(/\.$/, ''),
        tailnet: 'you@example.com', peers: 5, peersOnline: 3, version: '1.90.4', ...(t.serve !== 'off' ? {serveUrl: `https://${t.hostname}.tail1234.ts.net`} : {})}};
}

function modules(p) {
  const core = CORE.map(c => {
    const d = daemon(p, c.service);
    const o = {id: c.id, name: c.name, description: c.description, port: c.id === 'ssh' ? p.sshPort : c.port, kind: 'core', installed: true, enabled: p.core[c.id]};
    if (c.id === 'tailscale') o.tailscale = tailscaleJson(p);
    return d ? {...o, state: d.state, restarts: d.restarts} : {...o, state: 'stopped'};
  });
  core.push({id: 'camera', name: 'IPCam', description: 'Remote photos, video, flash, microphone and speaker announcements. Enable access in the phone app.',
    port: 0, kind: 'core', installed: true, enabled: p.cameraEnabled, state: p.cameraEnabled ? 'ready' : 'needs phone setup'});
  core.push({id: 'ai', name: 'AI', description: 'Chat and an OpenAI-compatible API for your other apps, with models on this phone or cloud services you connect.',
    port: 8090, kind: 'core', installed: true, enabled: p.ai.enabled, state: p.ai.enabled ? 'running' : 'stopped'});
  core.push({id: 'wol', name: 'Wake on LAN', description: 'Wake computers at home from anywhere: the phone sends the magic packet on your network.',
    port: 0, kind: 'core', installed: true, enabled: p.wolEnabled, state: p.wolEnabled ? 'ready' : 'stopped'});
  core.push({id: 'network', name: 'Network tools', description: 'Phone network, device discovery, DNS, TCP scans and speed tests.',
    port: 0, kind: 'core', installed: true, enabled: p.network.enabled, state: p.network.installing ? 'installing' : p.network.enabled ? 'ready' : 'stopped'});
  const apps = p.apps.map(a => {
    const def = a.def;
    const shared = def.storage?.shared ? p.apps.filter(x => x !== a && x.installed && x.def.storage?.shared === def.storage.shared).map(x => x.def.name) : [];
    const o = {id: def.id, name: def.name, description: def.description, port: def.port, size: def.size || '', kind: 'app',
      installed: a.installed, enabled: a.enabled, storageLabel: def.storage?.label ?? null, storageDir: a.storageDir, sharedWith: shared};
    if (a.installed && a.enabled && def.id === 'qbittorrent') o.notice = 'Web UI login: admin / Xk3pQ9mZ2 (temporary; set your own under Tools → Options → Web UI)';
    if (a.installed && a.enabled && def.id === 'adguard') o.notice = 'Web UI login: admin / hd7Tq2Lw · DNS on port 1053; open the setup guide to point your devices at it';
    o.services = serviceNames(def);
    const ds = o.services.map(n => daemon(p, n));
    if (ds.some(x => !x)) return {...o, state: 'stopped'};
    const order = ['running', 'starting', 'stopped', 'backoff'];
    const worst = ds.reduce((w, x) => order.indexOf(x.state) > order.indexOf(w.state) ? x : w);
    return {...o, state: worst.state, restarts: ds.reduce((s, x) => s + x.restarts, 0)};
  });
  return {core, apps, job: job(p)};
}
const job = p => ({title: p.job.title, running: p.job.running, error: p.job.error, log: p.job.log.slice(-200)});

function deployJson(p, x, withState = true) {
  const dmn = daemon(p, 'deploy-' + x.id);
  const o = {id: x.id, name: x.name, repo: (x.repo || '').replace(/:\/\/[^@/]+@/, '://***@'), branch: x.branch, port: x.port, runtime: x.runtime, build: x.build,
    start: x.start, env: Object.fromEntries(Object.keys(x.env).map(k => [k, '***'])), autoDeploy: x.autoDeploy,
    source: x.source || 'git', replicated: !!x.replicated, replica: !!x.replica, balanced: x.balanced || null, pipeline: x.pipeline || [],
    hasToken: !!x.token, hasWebhookSecret: !!x.webhookSecret,
    hooks: {deploy: `/hooks/deploy/${x.id}`, artifact: `/hooks/deploy/${x.id}/artifact`, github: `/hooks/github/${x.id}`},
    state: dmn?.state || 'stopped', restarts: dmn?.restarts || 0};
  return withState ? {...o, commit: x.commit, detected: x.detected, error: x.error} : o;
}
const deploys = p => p.deploys.map(x => deployJson(p, x));

const settings = p => ({autostart: p.autostart, cameraEnabled: p.cameraEnabled, wolEnabled: p.wolEnabled, dashboardPort: 8800, sshPort: p.sshPort, appsDns: p.appsDns, webPort: 8080});

const LOG_LINES = {
  sshd: () => pick(['Accepted publickey for u0_a312 from 192.168.1.23', 'session opened', 'sftp subsystem started', 'Connection closed by 192.168.1.23']),
  caddy: () => `{"level":"info","ts":${(now() / 1000).toFixed(3)},"logger":"http.log.access","msg":"handled request","request":{"method":"GET","uri":"${pick(['/', '/style.css', '/img/logo.svg'])}"},"status":200}`,
  tailscaled: () => pick(['magicsock: endpoints changed', 'wgengine: Reconfig: configuring router', 'netmap: peers=5', 'health(warnable=network-status): ok']),
  jellyfin: () => `[${stamp()}] [INF] ${pick(['Playback started: Sintel (2010)', 'Library scan finished in 00:00:04', 'Transcoding with h264_mediacodec'])}`,
  qbittorrent: () => `(N) ${stamp()} - ${pick(['Torrent finished: ubuntu-24.04-desktop-amd64.iso', 'Successfully listening on IP: 0.0.0.0, port: 6881', 'UPnP/NAT-PMP support: ON'])}`,
  homeassistant: () => pick(['ERROR (MainThread) [homeassistant.setup] Setup failed for bluetooth: Unable to set up dependencies', 'Traceback (most recent call last):', 'exit status 1; restarting in 30 s']),
  adguard: () => `${stamp()} [info] ${pick(['dnsproxy: starting', 'blocked doubleclick.net for 192.168.1.23', 'filter list updated: 142,331 rules'])}`,
  'ai-llama': () => pick(['srv  update_slots: all slots are idle', 'slot launch_slot_: id 0 | task 41 | processing task', 'prompt eval time = 812.11 ms / 211 tokens', 'eval time = 4120.07 ms / 72 tokens (17.47 tokens per second)']),
};
function logs(p, name) {
  if (!serviceList(p).includes(name)) return null;
  const l = p.logs[name] ||= {lines: [], at: now() - 60_000};
  const gen = LOG_LINES[name] || (() => name.startsWith('deploy-') ? pick(['GET / 200 3ms', 'GET /api/health 200 1ms', 'listening on :' + (p.deploys.find(x => 'deploy-' + x.id === name)?.port || 3000)]) : `${name}: ${pick(['ok', 'heartbeat', 'listening'])}`);
  if (!l.lines.length) for (let i = 0; i < 25; i++) l.lines.push(gen());
  while (l.at < now() - 3000) { l.at += rand(2000, 6000); l.lines.push(gen()); }
  l.lines = l.lines.slice(-200);
  return {name, lines: l.lines};
}

function aiModels(p) {
  const out = [];
  if (p.ai.localModel && p.ai.files.some(x => x.file === p.ai.localModel)) {
    const a = aiAlias(p.ai.localModel);
    out.push({id: 'local/' + a, name: a, local: true, label: 'On this phone'});
  }
  for (const pr of p.ai.providers) for (const m of pr.models) out.push({id: `${pr.id}/${m}`, name: m, local: false, label: pr.name});
  return out;
}
function aiJson(p) {
  const a = p.ai, ram = p.ramTotalMb, free = 98_311_221_248;
  const localState = !a.localModel ? 'none' : a.benchmarking ? 'benchmarking' : !a.enabled || !a.runtimeInstalled ? 'stopped'
    : a.loadingUntil > now() ? 'loading' : 'ready';
  const speed = a.benchmark ? Math.max(a.benchmark.cpu?.tg || 0, a.benchmark.gpu?.tg || 0) : null;
  let recommended = null;
  const cat = AI_CATALOG.map(m => {
    const fit = m.bytes > free ? 'no-space' : m.ramMb < ram * 0.55 ? 'fits' : m.ramMb < ram * 0.75 ? 'tight' : 'too-big';
    const wps = speed ? round1(speed * 1117320736 / m.bytes * 0.75) : null;
    return {id: m.id, name: m.name, bytes: m.bytes, ramMb: m.ramMb, note: m.note, file: m.file, downloaded: a.files.some(x => x.file === m.file),
      fit, recommended: false, reason: fit === 'fits' ? (wps ? `about ${wps.toFixed(1)} words/s on this phone` : 'fits in memory') : fit === 'tight' ? 'leaves little memory for other apps' : 'needs more memory than this phone has',
      wordsPerSecond: wps};
  });
  recommended = cat.filter(c => c.fit === 'fits' && (c.wordsPerSecond ?? 10) >= 8).sort((x, y) => y.bytes - x.bytes)[0];
  if (recommended) { recommended.recommended = true; recommended.reason = 'The best model this phone runs comfortably and quickly enough'; }
  return {
    enabled: a.enabled, running: a.enabled, port: 8090, key: a.key, ips: p.ips, ramTotalMb: ram, cores: p.metrics.cores,
    localSupported: a.localSupported, runtimeInstalled: a.runtimeInstalled, runtimeOutdated: a.runtimeOutdated, runtimeBytes: a.runtimeBytes,
    runtimeAbi: 'arm64-v8a', device: a.device, devices: a.runtimeInstalled ? a.devices : [], benchmark: a.benchmark,
    localModel: a.localModel, localState, localError: localState === 'loading' ? 'Loading model' : null,
    contextSize: a.contextSize, threads: a.threads, effectiveThreads: a.threads || 4, fallback: a.fallback,
    modelsDir: a.modelsDir, modelsFree: free,
    modelsDirs: [{path: null, label: 'Inside the app'}, {path: '/storage/7A1B-22F0/Android/data/dev.homedroid/files/models', label: 'SD card'}],
    files: a.files, catalog: cat, download: a.download, providers: a.providers, models: aiModels(p),
  };
}

function routinesJson(p) {
  return {routines: p.routines.map(r => ({...r, nextRun: nextRun(r.schedule), running: p.routineRunning === r.id})), settings: p.routineSettings};
}
function nextRun(s) {
  if (s.type === 'interval') return now() + (s.hours || 3) * 3600_000 - (now() % ((s.hours || 3) * 3600_000));
  const [h, m] = String(s.time || '08:00').split(':').map(Number);
  const t = new Date(); t.setHours(h, m, 0, 0);
  if (t.getTime() <= now()) t.setDate(t.getDate() + 1);
  if (s.type === 'weekdays') while ([0, 6].includes(t.getDay())) t.setDate(t.getDate() + 1);
  return t.getTime();
}

function wolJson(p) {
  return {devices: p.wol.map(x => ({...x, ...(x.host ? {online: x.host.endsWith('.40') ? Math.random() < 0.9 : x.host.endsWith('.10')} : {})})),
    broadcasts: ['192.168.1.255']};
}

function networkJson(p) {
  const n = p.network;
  return {
    enabled: n.enabled,
    interfaces: [{name: 'lo', addresses: ['::1/128', '127.0.0.1/8']}, {name: 'wlan0', addresses: [`fe80::a8b3:${p.id.length}ff:fe21:9c4d/64`, ...p.ips.map(ip => ip + '/24')]},
      {name: 'rmnet_data2', addresses: ['10.140.22.9/30']}],
    ssid: p.variant === 'self' ? 'Home WiFi 5G' : 'Home WiFi', gateway: ['192.168.1.1'], dns: ['192.168.1.1', '1.1.1.1'],
    metrics: sampleMetrics(p),
    discovery: {running: n.discovery.running, devices: n.discovery.devices.map(x => ({ip: x.ip, name: x.name, sources: x.sources, ports: x.ports, lastSeen: x.lastSeen, found: x.lastSeen >= n.discovery.started}))},
    jobs: Object.values(n.jobs).sort((a, b) => a.kind.localeCompare(b.kind)).map(j => ({kind: j.kind, running: j.running, cancelled: j.cancelled,
      output: j.output, result: j.result ?? null, error: j.error, tcpFallback: j.tcpFallback})),
  };
}

function cameraJson(p) {
  const c = p.camera;
  return {armed: c.armed, busy: c.busy, recording: c.recording, selected: c.live ? null : c.selected, torch: c.torch, live: c.live,
    monitor: {id: c.monitor.id, recording: c.monitor.recording, width: c.monitor.id ? 640 : 0, height: c.monitor.id ? 480 : 0, fpsMin: c.monitor.id ? 5 : 0, fpsMax: c.monitor.id ? 30 : 0},
    error: c.error, microphone: {running: c.mic.running, recording: c.mic.recording, error: c.mic.error}};
}
function cameraState(p) {
  const c = p.camera;
  return {...cameraJson(p), cameras: c.cameras.map(x => ({...x, settings: c.settings[x.id]})),
    storage: {quotaMb: c.quotaMb, usedBytes: c.files.reduce((s, x) => s + x.bytes, 0)},
    files: [...c.files].sort((a, b) => b.name.localeCompare(a.name)).slice(0, 100)};
}

function updateJson(p) {
  const u = p.update;
  return {version: p.version, versionCode: 94, latest: u.latest, available: !!u.latest && u.latest.version !== p.version && newer(u.latest.version, p.version),
    checkError: null, checkedAt: u.checkedAt, state: u.state, error: u.error, done: u.done, total: u.total, allowed: true, silent: false};
}
const newer = (a, b) => { const x = a.split('.').map(Number), y = b.split('.').map(Number); for (let i = 0; i < 3; i++) if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) > (y[i] || 0); return false; };

// ---- Media: a moving test pattern (PNG) and a tone (WAV), made without dependencies -------------
const CRC = new Int32Array(256).map((_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c; });
function crc32(buf) { let c = -1; for (const b of buf) c = CRC[(c ^ b) & 0xff] ^ (c >>> 8); return (c ^ -1) >>> 0; }
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}
// 5x7 digits for the frame counter
const DIGITS = ['01110100011001110101110011000101110', '00100011000010000100001000010001110', '01110100010000100010001000100011111', '11111000100010000010000011000101110',
  '00010001100101010010111110001000010', '11111100001111000001000011000101110', '00110010001000011110100011000101110', '11111000010001000100010000100001000',
  '01110100011000101110100011000101110', '01110100011000101111000010001001100'];
/** A 320x240 PNG test card; [seq] moves the bar and is printed in the corner, [hue] tints it per camera. */
function frame(seq, hue = 0, w = 320, h = 240) {
  const raw = Buffer.alloc((w * 3 + 1) * h);
  const bar = (seq * 7) % w;
  const text = String(seq);
  for (let y = 0; y < h; y++) {
    const row = y * (w * 3 + 1);
    raw[row] = 0;
    for (let x = 0; x < w; x++) {
      let r = 30 + Math.round(90 * x / w), g = 40 + Math.round(80 * y / h), b = 90 + hue;
      if (Math.abs(x - bar) < 6) { r = 240; g = 240; b = 240; }
      if ((x % 40 === 0) || (y % 40 === 0)) { r += 25; g += 25; b += 25; }
      // frame number at (10,10), 3x scale
      const tx = Math.floor((x - 10) / 3), ty = Math.floor((y - 10) / 3);
      if (ty >= 0 && ty < 7 && tx >= 0 && tx < text.length * 6) {
        const di = Math.floor(tx / 6), cx = tx % 6;
        if (cx < 5 && DIGITS[+text[di]][ty * 5 + cx] === '1') { r = 255; g = 220; b = 60; }
      }
      const o = row + 1 + x * 3;
      raw[o] = Math.min(255, r); raw[o + 1] = Math.min(255, g); raw[o + 2] = Math.min(255, b);
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, {level: 1})), chunk('IEND', Buffer.alloc(0))]);
}
/** 16-bit mono PCM: a quiet two-tone chime, [offset] samples in. */
function pcm(samples, offset = 0, rate = 16000) {
  const b = Buffer.alloc(samples * 2);
  for (let i = 0; i < samples; i++) {
    const t = (offset + i) / rate, f0 = Math.floor(t) % 2 ? 660 : 440;
    b.writeInt16LE(Math.round(Math.sin(2 * Math.PI * f0 * t) * 2500 * (1 - (t % 1) * 0.8)), i * 2);
  }
  return b;
}
function wav(seconds = 2, rate = 16000) {
  const data = pcm(seconds * rate, 0, rate), h = Buffer.alloc(44);
  h.write('RIFF', 0); h.writeUInt32LE(36 + data.length, 4); h.write('WAVEfmt ', 8); h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(1, 22);
  h.writeUInt32LE(rate, 24); h.writeUInt32LE(rate * 2, 28); h.writeUInt16LE(2, 32); h.writeUInt16LE(16, 34); h.write('data', 36); h.writeUInt32LE(data.length, 40);
  return Buffer.concat([h, data]);
}

/** The phones: this one plus a cluster of two others (one online, one offline). */
function createCluster() {
  const self = createPhone({id: 'p7c1e0a2b9d4', name: 'Pixel 8 Pro', model: 'Google Pixel 8 Pro', ips: ['192.168.1.5'], variant: 'self'});
  const den = createPhone({id: 'q3f8d2c6e1a7', name: 'Den phone', model: 'Samsung SM-G991B', ips: ['192.168.1.31'], variant: 'member', android: '14', ramTotalMb: 7_680});
  const garage = createPhone({id: 'r9b4a7e3c2f5', name: 'Garage cam', model: 'Xiaomi Redmi Note 10', ips: ['192.168.1.44'], variant: 'member', online: false, android: '13', ramTotalMb: 3_840});
  den.version = VERSION;
  garage.version = '0.9.3';
  return {
    self, members: [den, garage],
    outgoing: null, discovering: false, discovered: [],
    pending: [],
  };
}

module.exports = {
  ASSETS, VERSION, CORE, AI_CATALOG, now, rand, pick, hex, clamp,
  createPhone, createCluster, startJob, installLines, bump, walk, treeSize,
  status, services, serviceList, daemon, modules, job, deploys, deployJson, settings, logs, tailscaleJson,
  aiJson, aiModels, aiAlias, routinesJson, nextRun, wolJson, networkJson, cameraJson, cameraState, updateJson,
  frame, pcm, wav, crc32, serviceNames,
};
