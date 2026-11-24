// Optional browser QA: screenshots of layouts, themes and fonts from the mock server (one Chrome at a time).
//   node tests/dashboard-mock/server.cjs &                   # password "demo"
//   PLAYWRIGHT_MODULE=/path/to/node_modules/playwright MOCK_URL=http://127.0.0.1:8787 node tests/dashboard-mock/shots.cjs [name,...]
// Each job is {name, width, height, hash, prefs}: prefs is a partial prefs object merged into the defaults by the page.
'use strict';
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const path = require('node:path');
const fs = require('node:fs');
const base = process.env.MOCK_URL || 'http://127.0.0.1:8787';
const out = path.resolve(__dirname, '../../docs/design/screens');
const D = {width: 1440, height: 900}, M = {width: 390, height: 844}, T = {width: 1024, height: 768};
const jobs = [
  {name: 'layout-sidebar-desktop', ...D, hash: 'overview', prefs: {}},
  {name: 'layout-rail-desktop', ...D, hash: 'overview', prefs: {layout: {shell: 'rail'}}},
  {name: 'layout-topbar-desktop', ...D, hash: 'overview', prefs: {layout: {shell: 'topbar'}}},
  {name: 'layout-split-desktop', ...D, hash: 'overview', prefs: {layout: {shell: 'split'}}},
  {name: 'layout-focus-desktop', ...D, hash: 'overview', prefs: {layout: {shell: 'focus', width: 'fixed'}}},
  {name: 'layout-kiosk-tablet', ...T, hash: 'overview', prefs: {layout: {shell: 'kiosk', overview: 'big'}, theme: {mode: 'fixed', skin: 'oled'}}},
  {name: 'overview-bento-desktop', ...D, hash: 'overview', prefs: {layout: {overview: 'bento'}}},
  {name: 'overview-list-desktop', ...D, hash: 'overview', prefs: {layout: {overview: 'list'}}},
  {name: 'overview-big-desktop', ...D, hash: 'overview', prefs: {layout: {overview: 'big'}}},
  {name: 'overview-mobile', ...M, hash: 'overview', prefs: {}},
  {name: 'overview-mobile-light', ...M, hash: 'overview', prefs: {theme: {mode: 'fixed', skin: 'homedroid-light'}}},
  {name: 'files-mobile-oled', ...M, hash: 'files', prefs: {theme: {mode: 'fixed', skin: 'oled'}}},
  {name: 'customize-layout-desktop', ...D, hash: 'overview', prefs: {}, open: 'layout'},
  {name: 'customize-theme-desktop', ...D, hash: 'overview', prefs: {}, open: 'theme'},
  {name: 'customize-fonts-desktop', ...D, hash: 'overview', prefs: {font: {ui: 'humanist', head: 'serif'}}, open: 'fonts'},
  {name: 'customize-widgets-desktop', ...D, hash: 'overview', prefs: {}, open: 'widgets'},
  {name: 'customize-nav-desktop', ...D, hash: 'overview', prefs: {}, open: 'nav'},
  {name: 'customize-theme-mobile', ...M, hash: 'overview', prefs: {}, open: 'theme'},
  {name: 'widgets-edit-desktop', ...D, hash: 'overview', prefs: {dash: {list: [{id: 'home', name: 'Home', widgets: ['health', 'metric', 'clock', 'wol', 'services', 'notes', 'links', 'actions', 'modules'].map(t => ({id: t, type: t, cfg: t === 'notes' ? {text: '# Plan\n- Replace the **router**\n- Back up `photos`'} : t === 'links' ? {links: [{label: 'Router', url: 'http://192.168.1.1'}, {label: 'AdGuard', url: 'http://192.168.1.5:3000'}]} : t === 'actions' ? {items: [{kind: 'restart', id: 'sshd', label: 'Restart SSH'}, {kind: 'wake', id: 'a1b2c3d4e5f6', label: 'Wake Gaming PC'}]} : {}, size: ['health', 'services'].includes(t) ? 'XL' : t === 'metric' || t === 'clock' ? 'S' : 'M'}))}, {id: 'media', name: 'Media', widgets: []}]}}, edit: true},
  {name: 'saver-kiosk', ...T, hash: 'overview', prefs: {layout: {shell: 'kiosk'}, oled: {idleMin: 1}}, saver: true},
  {name: 'settings-desktop', ...D, hash: 'settings', prefs: {}},
  {name: 'modules-desktop-nordic', ...D, hash: 'modules', prefs: {theme: {mode: 'fixed', skin: 'nordic'}}},
];
for (const s of ['homedroid-dark', 'homedroid-light', 'oled', 'midnight', 'forest', 'ember', 'nordic', 'terminal', 'contrast-dark', 'paper', 'solar', 'lavender', 'contrast-light']) {
  jobs.push({name: 'theme-' + s, ...D, height: 720, hash: 'overview', prefs: {theme: {mode: 'fixed', skin: s}}});
}
for (const f of ['humanist', 'geometric', 'rounded', 'serif', 'mono']) jobs.push({name: 'font-' + f, ...D, height: 720, hash: 'overview', prefs: {font: {ui: f}}});
(async () => {
  fs.mkdirSync(out, {recursive: true});
  const only = process.argv[2] ? process.argv[2].split(',') : null;
  const browser = await chromium.launch({executablePath: process.env.CHROME_PATH || '/usr/bin/google-chrome', headless: true, args: ['--no-sandbox']});
  const issues = [];
  try {
    for (const job of jobs.filter(j => !only || only.some(o => j.name.includes(o)))) {
      const context = await browser.newContext({viewport: {width: job.width, height: job.height}, colorScheme: 'dark', reducedMotion: 'reduce'});
      const page = await context.newPage();
      page.on('pageerror', e => issues.push(`${job.name}: ${e.message}`));
      page.on('console', m => { if (m.type() === 'error' && !/401|favicon/.test(m.text())) issues.push(`${job.name}: console ${m.text()}`); });
      await context.request.post(base + '/api/login', {data: {password: 'demo'}});
      const cookies = await context.request.storageState(); await context.addCookies(cookies.cookies);
      const merged = {v: 1, updated: 1, ...job.prefs, theme: job.prefs.theme, font: job.prefs.font, layout: job.prefs.layout, oled: job.prefs.oled, dash: job.prefs.dash};
      await page.addInitScript(p => { try { if (!localStorage.getItem('hd.seeded')) { localStorage.setItem('hd.prefs', JSON.stringify(p)); localStorage.setItem('hd.seeded', '1'); } } catch (e) {} }, merged);
      await page.route('**/api/ui/prefs', r => r.request().method() === 'GET' ? r.fulfill({status: 200, contentType: 'application/json', body: '{}'}) : r.fulfill({status: 200, contentType: 'application/json', body: JSON.stringify({updated: 2})}));
      await page.goto(base + '/#' + job.hash);
      await page.locator('#appView:not(.hidden)').waitFor({timeout: 8000});
      await page.waitForTimeout(job.hash === 'overview' ? 1400 : 700);
      if (job.edit) { await page.evaluate(() => ACT.wEdit()); await page.waitForTimeout(500); }
      if (job.open) { await page.evaluate(t => openCustomize(t), job.open); await page.waitForTimeout(400); }
      if (job.saver) { await page.evaluate(() => { lastInput = Date.now() - 3600000; setStage('saver'); }); await page.waitForTimeout(300); }
      const m = await page.evaluate(() => ({sw: document.documentElement.scrollWidth, w: innerWidth}));
      if (m.sw > m.w + 1) issues.push(`${job.name}: horizontal overflow ${m.sw} > ${m.w}`);
      await page.screenshot({path: path.join(out, job.name + '.png')});
      await context.close();
    }
  } finally { await browser.close(); }
  console.log(JSON.stringify({issues}, null, 2));
  process.exitCode = issues.length ? 1 : 0;
})().catch(e => { console.error(e); process.exitCode = 1; });
