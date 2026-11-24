// Run with node --test tests/dashboard-prefs.test.cjs (no dependencies).
// Covers the pure "Layout" script section (prefs schema, migration, widget layout, OLED care), the theme tokens in the
// stylesheet (WCAG AA), and the phone sync of GET/PUT /api/ui/prefs.
'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const html = fs.readFileSync('app/src/main/assets/dashboard.html', 'utf8');
const script = html.split('<script>')[1].split('</script>')[0];
const layout = script.slice(script.indexOf('// ---- Layout'), script.indexOf('// ---- end Layout'));
const L = vm.createContext({URL, console});
vm.runInContext(layout + '\nthis.api = {PREFS_V, PREFS_MAX_BYTES, SKINS, LAYOUTS, PRESETS, WIDGET_TYPES, SIZES, defaultPrefs, sanitizePrefs, migratePrefs, resolveSkin, resolveAccent, ensureContrast, contrast, mixHex, onColor, orderNav, moveItem, widgetSpan, reflow, moveWidget, moveWidgetTo, resizeWidget, removeWidget, addWidget, rotateOrder, shiftOffset, idleMinutes, idleStage, driftPosition, wantsWakeLock, formatTemp, formatClock, applyPreset, prefsBytes, safeUrl, FONT_UI, FONT_HEAD, FONT_MONO};', L);
const plain = x => x === undefined ? x : JSON.parse(JSON.stringify(x)); // results come from another realm: compare them as plain data
const A = Object.fromEntries(Object.entries(L.api).map(([k, v]) => [k, typeof v === 'function' ? (...a) => plain(v(...a)) : plain(v)]));

test('defaults survive sanitizing unchanged and are versioned', () => {
  const d = A.defaultPrefs();
  assert.equal(d.v, 1);
  assert.deepEqual(A.sanitizePrefs(JSON.parse(JSON.stringify(d))), d);
  assert.deepEqual(d.dash.list[0].widgets.map(w => w.type), ['health', 'vitals', 'reach', 'services']);
  assert.ok(A.prefsBytes(d) < A.PREFS_MAX_BYTES / 20);
});
test('migration: nothing, garbage, the legacy theme key and unversioned prefs', () => {
  assert.deepEqual(A.migratePrefs(null), A.defaultPrefs());
  assert.deepEqual(A.migratePrefs([1, 2]), A.defaultPrefs());
  assert.deepEqual(A.migratePrefs('x'), A.defaultPrefs());
  const legacy = A.migratePrefs(undefined, 'light');
  assert.equal(legacy.theme.mode, 'fixed'); assert.equal(legacy.theme.skin, 'homedroid-light');
  assert.equal(A.migratePrefs({}, 'auto').theme.mode, 'system');
  const v0 = A.migratePrefs({theme: 'dark', layout: {shell: 'rail'}});
  assert.equal(v0.v, 1); assert.equal(v0.theme.skin, 'homedroid-dark'); assert.equal(v0.layout.shell, 'rail');
  const future = A.migratePrefs({v: 7, layout: {shell: 'kiosk'}, somethingNew: {a: 1}});
  assert.equal(future.v, 1); assert.equal(future.layout.shell, 'kiosk'); assert.ok(!('somethingNew' in future));
});
test('validation clamps every field to its allowed values', () => {
  const p = A.sanitizePrefs({theme: {mode: 'x', skin: 'nope', accent: 'url(javascript:1)', radius: 'round', density: 9, shadow: {}, bg: 'sparkle'},
    font: {ui: 'comic', scale: 400, lh: 'huge', hw: 123, uiCustom: 'Evil"; background:url(x)', tnum: 0}, layout: {shell: 'x', width: 'x', overview: 'x', sidebar: 'x'},
    oled: {idleMin: 9999, shift: 'yes'}, units: {clock: '99', temp: 'k', bytes: 'z'}, motion: 'x', nav: {title: 'x'.repeat(200), hidden: 'a', links: [{label: 'x', url: 'javascript:alert(1)'}, {label: 'ok', url: 'https://example.com/a'}]}});
  const d = A.defaultPrefs();
  assert.equal(p.theme.skin, d.theme.skin); assert.equal(p.theme.accent, ''); assert.equal(p.theme.radius, 'rounded');
  assert.equal(p.font.ui, 'system'); assert.equal(p.font.scale, 125); assert.equal(p.font.hw, 650); assert.equal(p.font.uiCustom, 'Evil backgroundurlx'); // no quotes, colons, parentheses or semicolons survive
  assert.equal(p.font.tnum, false); assert.equal(p.layout.shell, 'sidebar'); assert.equal(p.oled.idleMin, 120); assert.equal(p.oled.shift, true);
  assert.equal(p.units.clock, '24'); assert.equal(p.units.bytes, 'legacy'); assert.equal(p.nav.title.length, 40);
  assert.deepEqual(p.nav.hidden, []); assert.deepEqual(p.nav.links.map(l => l.url), ['https://example.com/a']);
  assert.equal(A.sanitizePrefs({theme: {accent: '#ABCDEF'}}).theme.accent, '#abcdef');
});
test('unknown widgets and malformed dashboards never crash and ids stay unique', () => {
  const p = A.sanitizePrefs({dash: {active: 'gone', list: [{id: 'a', name: 'A', widgets: [{type: 'hologram', id: 'h'}, {type: 'clock', id: 'c'}, {type: 'clock', id: 'c'}, null, 5, {type: ''}]}, 'x', {id: 'a', name: 'Again', widgets: []}]}});
  assert.equal(p.dash.list.length, 2); assert.notEqual(p.dash.list[0].id, p.dash.list[1].id); assert.equal(p.dash.active, p.dash.list[0].id);
  const ws = p.dash.list[0].widgets;
  assert.deepEqual(ws.map(w => w.type), ['hologram', 'clock', 'clock']); assert.equal(new Set(ws.map(w => w.id)).size, 3);
  assert.equal(A.sanitizePrefs({dash: {list: []}}).dash.list.length, 1);
  const many = A.sanitizePrefs({dash: {list: Array.from({length: 30}, (_, i) => ({id: 'd' + i, widgets: Array.from({length: 99}, (_, j) => ({type: 'clock', id: 'w' + j}))}))}});
  assert.equal(many.dash.list.length, 8); assert.equal(many.dash.list[0].widgets.length, 40);
});
test('widget settings are sanitized per type: links, embeds, notes, actions', () => {
  const p = A.sanitizePrefs({dash: {list: [{id: 'h', widgets: [
    {id: 'l', type: 'links', cfg: {links: [{label: 'ok', url: 'http://192.168.1.1'}, {label: 'bad', url: 'javascript:alert(1)'}, {label: 'file', url: 'file:///etc/passwd'}]}},
    {id: 'e', type: 'embed', cfg: {url: 'ftp://x', height: 99999}}, {id: 'e2', type: 'embed', cfg: {url: 'http://pc:8123', height: 10}},
    {id: 'n', type: 'notes', cfg: {text: 'x'.repeat(20000)}}, {id: 'm', type: 'metric', cfg: {metric: 'nope'}},
    {id: 'a', type: 'actions', cfg: {items: [{kind: 'hack', id: 'x'}, {kind: 'wake', id: ''}]}}]}]}});
  const by = Object.fromEntries(p.dash.list[0].widgets.map(w => [w.id, w.cfg]));
  assert.deepEqual(by.l.links.map(x => x.url), ['http://192.168.1.1/']);
  assert.equal(by.e.url, ''); assert.equal(by.e.height, 900); assert.equal(by.e2.url, 'http://pc:8123/'); assert.equal(by.e2.height, 160);
  assert.equal(by.n.text.length, 8000); assert.equal(by.m.metric, 'cpu'); assert.deepEqual(by.a.items, [{kind: 'restart', id: 'x', label: ''}]);
});
test('system mode follows the light/dark pair; fixed mode ignores the device', () => {
  const p = A.defaultPrefs(); p.theme.light = 'paper'; p.theme.dark = 'midnight';
  assert.equal(A.resolveSkin(p, true), 'midnight'); assert.equal(A.resolveSkin(p, false), 'paper');
  p.theme.mode = 'fixed'; p.theme.skin = 'oled';
  assert.equal(A.resolveSkin(p, false), 'oled');
  assert.equal(A.sanitizePrefs({theme: {light: 'oled', dark: 'paper'}}).theme.light, 'homedroid-light'); // pairs keep their nature
});
test('accent colours are nudged to AA on the theme surface and get a readable foreground', () => {
  for (const skin of A.SKINS) for (const hex of ['#ffff00', '#0000ff', '#808080', '#ff00ff', '#6366f1', '#14b8a6', '#f59e0b']) {
    const p = A.defaultPrefs(); p.theme.accent = hex; p.theme.mode = 'fixed'; p.theme.skin = skin.id;
    const r = A.resolveAccent(p, skin);
    assert.ok(A.contrast(r.accent, skin.surface) >= 4.5 - 0.01, `${skin.id} ${hex} -> ${r.accent}`);
    assert.ok(A.contrast(r.onAccent, r.accent) >= 4.5, `${skin.id} ${hex} on-accent`);
  }
  assert.equal(A.resolveAccent(A.defaultPrefs(), A.SKINS[0]), null);
});
test('widget spans form a 12-column grid and collapse on narrow grids', () => {
  assert.deepEqual(['S', 'M', 'L', 'XL'].map(s => A.widgetSpan(s)), [4, 6, 8, 12]);
  assert.deepEqual(['S', 'M', 'L', 'XL'].map(s => A.widgetSpan(s, 2)), [1, 2, 2, 2]);
  assert.equal(A.widgetSpan('XL', 4), 4); assert.equal(A.widgetSpan('bogus'), 6);
});
test('reflow wraps rows without overlap', () => {
  const w = ['S', 'S', 'S', 'M', 'L', 'S', 'XL'].map((size, i) => ({id: 'w' + i, size}));
  const r = A.reflow(w);
  assert.deepEqual(r.map(x => [x.row, x.col, x.span]), [[0, 0, 4], [0, 4, 4], [0, 8, 4], [1, 0, 6], [2, 0, 8], [2, 8, 4], [3, 0, 12]]);
  for (const row of new Set(r.map(x => x.row))) { const cells = r.filter(x => x.row === row).sort((a, b) => a.col - b.col); cells.forEach((c, i) => { assert.ok(c.col + c.span <= 12); if (i) assert.ok(cells[i - 1].col + cells[i - 1].span <= c.col); }); }
  assert.deepEqual(A.reflow(w, 2).map(x => x.row), [0, 0, 1, 2, 3, 4, 5]);
  assert.deepEqual(A.reflow([]), []);
});
test('moving, resizing, adding and removing widgets', () => {
  const list = A.defaultPrefs().dash.list[0].widgets, ids = l => l.map(w => w.id);
  assert.deepEqual(ids(A.moveWidget(list, 'vitals', -1)), ['vitals', 'health', 'reach', 'services']);
  assert.deepEqual(ids(A.moveWidget(list, 'health', -1)), ids(list)); assert.deepEqual(ids(A.moveWidget(list, 'services', 1)), ids(list));
  assert.deepEqual(ids(A.moveWidgetTo(list, 'services', 0)), ['services', 'health', 'vitals', 'reach']);
  assert.deepEqual(ids(A.moveWidgetTo(list, 'health', 99)), ['vitals', 'reach', 'services', 'health']);
  assert.deepEqual(ids(A.moveWidgetTo(list, 'nope', 1)), ids(list));
  assert.equal(A.resizeWidget(list, 'health', 'S')[0].size, 'S'); assert.equal(A.resizeWidget(list, 'health', 'Q')[0].size, 'XL');
  assert.equal(list[0].size, 'XL'); // inputs are never mutated
  assert.equal(A.removeWidget(list, 'reach').length, 3);
  let more = A.addWidget(list, 'clock'); more = A.addWidget(more, 'clock'); more = A.addWidget(more, 'health');
  assert.deepEqual(ids(more).slice(4), ['clock', 'clock-2']); // singletons are added once
  assert.deepEqual(ids(A.addWidget(list, 'hologram')), ids(list));
  let full = list; for (let i = 0; i < 60; i++) full = A.addWidget(full, 'clock');
  assert.equal(full.length, 40);
});
test('navigation order keeps unknown pages at the end and moves one step at a time', () => {
  assert.deepEqual(A.orderNav(['a', 'b', 'c'], ['c', 'x', 'a']), ['c', 'a', 'b']);
  assert.deepEqual(A.orderNav(['a', 'b'], undefined), ['a', 'b']);
  assert.deepEqual(A.moveItem(['a', 'b', 'c'], 'c', -1), ['a', 'c', 'b']); assert.deepEqual(A.moveItem(['a', 'b'], 'a', -1), ['a', 'b']);
});
test('presets combine layout, theme and font and always validate', () => {
  assert.deepEqual(A.PRESETS.map(p => p.id), ['studio', 'terminal', 'minimal', 'kiosk', 'paper']);
  for (const preset of A.PRESETS) {
    const p = A.applyPreset(A.defaultPrefs(), preset.id);
    assert.deepEqual(A.sanitizePrefs(p), p); assert.notDeepEqual(p.layout, A.defaultPrefs().layout);
  }
  const kiosk = A.applyPreset(A.defaultPrefs(), 'kiosk');
  assert.equal(kiosk.layout.shell, 'kiosk'); assert.equal(kiosk.theme.skin, 'oled'); assert.equal(kiosk.oled.shift, true); assert.equal(kiosk.oled.idleMin, 5);
  assert.equal(A.applyPreset(A.defaultPrefs(), 'nope').layout.shell, 'sidebar');
});
test('OLED pixel shift walks a small ring, one step a minute, and returns to the start', () => {
  const pts = Array.from({length: 8}, (_, m) => A.shiftOffset(m));
  assert.equal(new Set(pts.map(p => p.x + ',' + p.y)).size, 8);
  for (const p of pts) assert.ok(Math.abs(p.x) <= 3 && Math.abs(p.y) <= 3);
  assert.deepEqual(A.shiftOffset(8), pts[0]); assert.deepEqual(A.shiftOffset(-1), pts[7]); assert.deepEqual(A.shiftOffset(3.9), pts[3]);
  assert.deepEqual(A.shiftOffset(1, 6), {x: 4, y: 2}); assert.deepEqual(A.shiftOffset(1, 0), {x: 0, y: 0});
});
test('screensaver: 5 minutes in Kiosk, off elsewhere, and dimming comes first', () => {
  const p = A.defaultPrefs();
  assert.equal(A.idleMinutes(p), 0); assert.equal(A.idleStage(3600e3, p), 'active');
  p.layout.shell = 'kiosk';
  assert.equal(A.idleMinutes(p), 5); assert.equal(A.idleStage(299e3, p), 'active'); assert.equal(A.idleStage(300e3, p), 'saver');
  p.oled.dim = true;
  assert.equal(A.idleStage(30e3, p), 'active'); assert.equal(A.idleStage(60e3, p), 'dim'); assert.equal(A.idleStage(299e3, p), 'dim'); assert.equal(A.idleStage(300e3, p), 'saver');
  p.oled.idleMin = 1; assert.equal(A.idleStage(30e3, p), 'dim'); assert.equal(A.idleStage(60e3, p), 'saver');
  p.oled.idleMin = 0; assert.equal(A.idleStage(9e6, p), 'dim'); // explicit off beats the Kiosk default
  p.layout.shell = 'sidebar'; p.oled.idleMin = 2; assert.equal(A.idleStage(120e3, p), 'saver');
  p.oled.dim = false; assert.equal(A.idleStage(60e3, p), 'active');
});
test('the screensaver clock drifts to new spots inside the screen', () => {
  const seen = new Set();
  for (let i = 0; i < 40; i++) { const p = A.driftPosition(i); assert.ok(p.x >= 10 && p.x <= 90 && p.y >= 15 && p.y <= 85); seen.add(p.x + ',' + p.y); }
  assert.ok(seen.size > 30);
});
test('wake lock is wanted only in Kiosk, when enabled, and while visible', () => {
  const p = A.defaultPrefs(); p.oled.keepOn = true;
  assert.equal(A.wantsWakeLock(p, true), false); p.layout.shell = 'kiosk';
  assert.equal(A.wantsWakeLock(p, true), true); assert.equal(A.wantsWakeLock(p, false), false);
  p.oled.keepOn = false; assert.equal(A.wantsWakeLock(p, true), false);
});
test('rotating widgets is a permutation; units and clock format', () => {
  assert.deepEqual(A.rotateOrder(4, 1), [1, 2, 3, 0]); assert.deepEqual(A.rotateOrder(4, 5), [1, 2, 3, 0]); assert.deepEqual(A.rotateOrder(3, -1), [2, 0, 1]); assert.deepEqual(A.rotateOrder(0, 3), []);
  assert.match(A.formatTemp(40, 'f'), /104\.0/); assert.match(A.formatTemp(40, 'c'), /40\.0/);
  assert.equal(A.formatClock(new Date(2026, 0, 1, 15, 4), '24'), '15:04'); assert.equal(A.formatClock(new Date(2026, 0, 1, 15, 4), '12'), '3:04 PM');
  assert.equal(A.formatClock(new Date(2026, 0, 1, 0, 5, 9), '12', true), '12:05:09 AM');
});

// ---- Themes: every theme defines the whole token set and meets WCAG AA ----
const cssOf = id => { const m = new RegExp(`(?:^|[\\n,]\\s*)(?::root, )?\\[data-skin="${id}"\\] \\{ ([^}]*)\\}`).exec(html.split('</style>')[0]); return m && Object.fromEntries([...m[1].matchAll(/(--)?([\w-]+): ([^;]+);/g)].map(x => [x[2], x[3].trim()])); };
test('13 original themes, each with the full token set and matching preview colours', () => {
  assert.ok(A.SKINS.length >= 12); assert.equal(new Set(A.SKINS.map(s => s.id)).size, A.SKINS.length);
  for (const id of ['homedroid-dark', 'homedroid-light', 'oled', 'midnight', 'forest', 'ember', 'nordic', 'paper', 'solar', 'contrast-dark', 'contrast-light', 'terminal', 'lavender']) assert.ok(A.SKINS.some(s => s.id === id), id);
  for (const s of A.SKINS) {
    const t = cssOf(s.id); assert.ok(t, 'css for ' + s.id);
    for (const k of ['bg', 'surface', 'surface-2', 'line', 'line-2', 'text', 'text-2', 'muted', 'accent', 'on-accent', 'ok', 'warn', 'bad', 'on-danger', 'code', 'term-bg', 'term-fg', 'color-scheme']) assert.ok(t[k], `${s.id} --${k}`);
    assert.equal(t['color-scheme'], s.dark ? 'dark' : 'light');
    assert.deepEqual([t.bg, t.surface, t.text, t.accent], [s.bg, s.surface, s.text, s.accent], s.id + ' preview colours');
  }
  assert.equal(A.SKINS.filter(s => s.dark).length >= 7 && A.SKINS.filter(s => !s.dark).length >= 5, true);
});
test('every theme passes WCAG AA for text, accents, status colours, borders and the terminal', () => {
  for (const s of A.SKINS) {
    const t = cssOf(s.id), fail = [], need = (label, a, b, min = 4.5) => { const c = A.contrast(a, b); if (c < min) fail.push(`${label} ${c.toFixed(2)}`); };
    const soft = (c, p) => A.mixHex(c, t.surface, p);
    for (const bg of [t.bg, t.surface, t['surface-2'], t.code]) { need('text', t.text, bg); need('text-2', t['text-2'], bg); need('muted', t.muted, bg); need('accent', t.accent, bg); }
    need('accent on accent-soft', t.accent, soft(t.accent, .14)); need('text on accent-soft', t.text, soft(t.accent, .14));
    need('on-accent', t['on-accent'], t.accent); need('on-accent hover', t['on-accent'], A.mixHex(t.accent, t.text, .8));
    for (const k of ['ok', 'warn', 'bad']) { need(k, t[k], t.surface); need(k + ' on soft', t[k], soft(t[k], .12)); need(k + ' on bg', t[k], t.bg); }
    need('on-danger', t['on-danger'], t.bad); need('line-2 on surface', t['line-2'], t.surface, 3); need('line-2 on bg', t['line-2'], t.bg, 3);
    need('terminal', t['term-fg'], t['term-bg']); const hover = A.mixHex(t.text, t.surface, .08); need('text on hover', t.text, hover); need('muted on hover', t.muted, hover);
    assert.deepEqual(fail, [], s.id);
  }
});

// ---- Fonts, layouts and structure ----
test('font choices, layouts and appearance attributes all have CSS behind them', () => {
  for (const f of A.FONT_UI.filter(x => !['system', 'custom'].includes(x))) assert.match(html, new RegExp(`html\\[data-font-ui="${f}"\\]`), f);
  for (const f of A.FONT_HEAD.filter(x => !['inherit', 'custom'].includes(x))) assert.match(html, new RegExp(`html\\[data-font-head="${f}"\\]`), f);
  assert.match(html, /html\[data-font-mono="system"\]/); assert.match(html, /JetBrainsMono-Regular\.woff2/);
  for (const l of ['rail', 'topbar', 'split', 'focus', 'kiosk']) assert.match(html, new RegExp(`html\\[data-layout="${l}"\\]`), l);
  for (const o of ['list', 'big', 'bento']) assert.match(html, new RegExp(`html\\[data-ov="${o}"\\]`), o);
  for (const [k, vals] of Object.entries({radius: ['sharp', 'pill'], density: ['compact', 'spacious'], shadow: ['flat', 'elevated'], bg: ['gradient', 'mesh', 'grain'], width: ['fixed', 'full']})) for (const v of vals) assert.match(html, new RegExp(`html\\[data-${k}="${v}"\\]`), k + v);
  assert.match(html, /--fs: var\(--fscale\)/);
  assert.doesNotMatch(html.split('</style>')[0], /url\(['"]?(?:https?:|data:|\/\/)/); // no external or data: assets: the CSP forbids them
});
test('navigation groups and reserved slots are in place for new pages', () => {
  const nav = html.match(/<nav class="nav"[\s\S]*?<\/nav>/)[0];
  assert.deepEqual([...nav.matchAll(/class="ng(?: solo)?" data-group="(\w+)"/g)].map(m => m[1]), ['overview', 'apps', 'data', 'network', 'devices', 'intelligence', 'system']);
  for (const slot of ['pipelines', 'Databases', 'Documents', 'Domains', 'Request logs', 'Dynamic DNS', 'Firewall', 'Desktop', 'MCP']) assert.ok(nav.includes(slot), 'slot ' + slot);
  assert.match(script, /How to add a page/); assert.match(html, /id="ovGrid"/);
  for (const id of ['ovHealth', 'ovStats', 'ovReach', 'ovSvcHead', 'ovServices', 'ovSub']) assert.ok(html.includes(`id="${id}"`), id);
  assert.match(html, /id="customize"/); assert.match(html, /id="saver"/); assert.match(html, /id="inspector"/);
});

// ---- Phone sync: GET/PUT /api/ui/prefs, debounced, tolerant of older phones ----
function syncSetup({getStatus = 200, remote = {}, putStatus = 200} = {}) {
  const requests = [], timers = [], cache = {}, calls = {apply: 0, login: 0};
  const context = vm.createContext({URL, console, JSON, Date, Object,
    fetch: async (url, o) => {
      requests.push({url, method: o.method, headers: o.headers, body: o.body && JSON.parse(o.body)});
      const status = o.method === 'GET' ? getStatus : putStatus;
      return {status, ok: status < 400, json: async () => status >= 400 ? {error: 'nope'} : o.method === 'GET' ? remote : {...JSON.parse(o.body), updated: 5000}};
    },
    setTimeout: fn => timers.push(fn) && timers.length, clearTimeout() {}, store: (k, v) => { cache[k] = v; }, stored: k => cache[k] ?? null, authed: true,
    matchMedia: () => ({matches: false}), showLogin() { calls.login++; }, applyAppearance() { calls.apply++; }, renderDash() {}, drawCustomize() {}, $: () => ({open: false}), toast() {}, syncWakeLock() {}, document: {documentElement: {}, addEventListener() {}}});
  vm.runInContext(layout + script.slice(script.indexOf("const PREFS_KEY = 'hd.prefs';"), script.indexOf('// ---- Applying the look')), context);
  return {context, requests, timers, cache, calls, run: s => vm.runInContext(s, context)};
}
test('changes are saved with PUT, the request header and a debounce, and cached for the next load', async () => {
  const t = syncSetup();
  t.run("setPref('layout.shell', 'rail'); setPref('theme.radius', 'pill');");
  assert.equal(t.requests.length, 0); assert.equal(t.timers.length, 2); // one pending timer per change, only the last one runs
  assert.equal(JSON.parse(t.cache['hd.prefs']).layout.shell, 'rail');
  await t.timers.at(-1)();
  assert.equal(t.requests.length, 1);
  const r = t.requests[0];
  assert.equal(r.method, 'PUT'); assert.equal(r.url, '/api/ui/prefs'); assert.equal(r.headers['X-Homedroid-Request'], '1'); assert.equal(r.body.v, 1);
  assert.equal(r.body.layout.shell, 'rail'); assert.equal(r.body.theme.radius, 'pill');
  assert.equal(t.run('prefs.updated'), 5000); // the phone's timestamp wins
});
test('an older phone without the endpoint keeps working from the cache and is not asked again', async () => {
  const t = syncSetup({getStatus: 404, putStatus: 404});
  await t.run('syncPrefs()'); assert.equal(t.run('prefsRemote'), false);
  t.run("setPref('layout.shell', 'split')"); assert.equal(t.timers.length, 0); assert.equal(t.requests.length, 1);
  assert.equal(JSON.parse(t.cache['hd.prefs']).layout.shell, 'split');
});
test('after login newer prefs from the phone are adopted; older ones are replaced by ours', async () => {
  const newer = {v: 1, updated: 9e12, layout: {shell: 'topbar'}, theme: {mode: 'fixed', skin: 'forest'}};
  const t = syncSetup({remote: newer}); await t.run('syncPrefs()');
  assert.equal(t.run('prefs.layout.shell'), 'topbar'); assert.equal(t.run('prefs.theme.skin'), 'forest'); assert.equal(t.calls.apply, 1);
  const t2 = syncSetup({remote: {v: 1, updated: 5, layout: {shell: 'rail'}}}); t2.run("prefs.updated = 100; prefs.layout.shell = 'focus'");
  await t2.run('syncPrefs()'); assert.equal(t2.run('prefs.layout.shell'), 'focus'); assert.equal(t2.timers.length, 1); // queued to send ours
  const t3 = syncSetup({remote: {}}); await t3.run('syncPrefs()'); assert.equal(t3.timers.length, 0); // nothing customized, nothing to send
});
test('oversized prefs are not sent to the phone', async () => {
  const t = syncSetup();
  t.run(`prefs = sanitizePrefs({dash: {list: Array.from({length: 8}, (_, i) => ({id: 'd' + i, widgets: Array.from({length: 40}, (_, j) => ({id: 'n' + j, type: 'notes', cfg: {text: 'x'.repeat(8000)}}))}))}})`);
  assert.ok(t.run('prefsBytes(prefs)') > 256 * 1024);
  await t.run('pushPrefs()'); assert.equal(t.requests.length, 0);
});
