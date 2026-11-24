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
// Console pages: every page at desktop and mobile, dark and light. `steps` run in the page after it loads (clicks, tab choices).
const DB = 'databases/alpine/srv/my-blog/data/app.sqlite';
const consolePages = [
  {n: 'databases', hash: 'databases'}, {n: 'databases-schema', hash: DB}, {n: 'databases-data', hash: DB, steps: [`ACT.dbTab({dataset:{t:'data'}})`], wait: 900}, {n: 'databases-sql', hash: DB, steps: [`ACT.dbTab({dataset:{t:'sql'}})`, `ACT.dbQuick({dataset:{sql:'SELECT * FROM posts LIMIT 20;'}})`, `ACT.dbRun()`], wait: 900},
  {n: 'documents', hash: 'documents/notes'}, {n: 'domains', hash: 'domains'}, {n: 'request-logs', hash: 'request-logs'}, {n: 'ddns', hash: 'ddns'}, {n: 'firewall', hash: 'firewall'},
  {n: 'mcp', hash: 'mcp', steps: [`mcpAsk('Restart sshd')`], wait: 900}, {n: 'deploys', hash: 'deploys'}, {n: 'deploy-overview', hash: 'deploys/my-blog'}, {n: 'deploy-settings', hash: 'deploys/my-blog/settings'},
  {n: 'deploy-hooks', hash: 'deploys/my-blog/hooks', steps: [`dep.secrets['my-blog'] = {token: 'hd_demo_token_shown_once'}; dep.force = true; refresh()`], wait: 600}, {n: 'deploy-upload', hash: 'deploys/docs-site/upload'},
  {n: 'deploy-pipelines', hash: 'deploys/my-blog/pipelines'}, {n: 'deploy-run', hash: 'deploys/my-blog/pipelines/__RUN__'}, {n: 'desktop', hash: 'desktop', steps: [`ACT.dkPair()`, `ACT.mpSelect({dataset:{id:'b-4'}})`], wait: 900},
];
// Mail and Wi-Fi repeater: `pre` puts the mock in a state first (mock-only routes, see extras.cjs); `steps` walk the wizard and tabs.
const MAIL_READY = [['mail/_mock', {phase: 'ready'}]];
const wiz = (n, extra = []) => [`mail.form.domain = 'example.com'; mail.form.hostname = 'mail.example.com'`, ...(n > 0 ? [`ACT.mailNext()`] : []), ...(n > 1 ? [`ACT.mailMode({dataset: {id: 'relay'}}); ACT.mailNext()`] : []),
  ...(n > 2 ? [`Object.assign(mail.form, {address: '203.0.113.42', spf: 'spf.mailgun.org', report: 'postmaster@example.com'}); Object.assign(mail.form.relay, {host: 'smtp.mailgun.org', username: 'postmaster@mg.example.com', password: 'key-demo'}); ACT.mailNext()`] : []), ...extra];
const mailPages = [
  {n: 'mail-install', pre: [['mail/_mock', {phase: 'not_installed'}]]},
  {n: 'mail-wizard-1-domain', pre: [['mail/_mock', {phase: 'needs_setup'}]], steps: [`mail.form.domain = 'example.com'; drawMailWizard()`]},
  {n: 'mail-wizard-1-errors', pre: [['mail/_mock', {phase: 'needs_setup'}]], steps: [`mail.form.domain = 'example'; ACT.mailNext()`]},
  {n: 'mail-wizard-2-mode', pre: [['mail/_mock', {phase: 'needs_setup'}]], steps: wiz(1, [`ACT.mailMode({dataset: {id: 'relay'}}); drawMailWizard()`])},
  {n: 'mail-wizard-3-details', pre: [['mail/_mock', {phase: 'needs_setup'}]], steps: wiz(2)},
  {n: 'mail-wizard-4-review', pre: [['mail/_mock', {phase: 'needs_setup'}]], steps: wiz(3)},
  {n: 'mail-starting', pre: [['mail/_mock', {phase: 'starting'}]]},
  {n: 'mail-ready', pre: MAIL_READY, wait: 700, steps: [`mail.reveal = true; drawMailPane()`]},
  {n: 'mail-dns', pre: MAIL_READY, steps: [`ACT.mailTab({dataset: {t: 'dns'}})`], wait: 900},
  {n: 'mail-health', pre: MAIL_READY, steps: [`ACT.mailTab({dataset: {t: 'health'}}); ACT.mailCheck()`], wait: 1800},
  {n: 'mail-accounts', pre: MAIL_READY, steps: [`ACT.mailTab({dataset: {t: 'accounts'}})`], wait: 900},
  {n: 'mail-account-created', pre: MAIL_READY, steps: [`ACT.mailTab({dataset: {t: 'accounts'}})`, `mailShowPassword('Account created', 'carol@example.com', 'q8Wm3RtYb5HkVz2N', true)`], wait: 700},
  {n: 'mail-apps', pre: MAIL_READY, steps: [`ACT.mailTab({dataset: {t: 'apps'}})`], wait: 1200},
  {n: 'mail-cert', pre: MAIL_READY, steps: [`ACT.mailTab({dataset: {t: 'cert'}})`], wait: 600},
];
const RP_OK = {allowLan: false, supported: true, permission: true, stationKept: true};
const repeaterPages = [
  {n: 'repeater-off', pre: [['repeater/_mock', {...RP_OK, state: 'off'}]]},
  {n: 'repeater-running', pre: [['repeater/_mock', {...RP_OK, state: 'running'}]], wait: 4200, steps: [`rp.reveal = true; drawRepeater()`]},
  {n: 'repeater-lan', pre: [['repeater/_mock', {...RP_OK, state: 'running', allowLan: true, stationKept: false}]], wait: 600, steps: [`rp.os = 2; drawRepeater()`]},
  {n: 'repeater-permission', pre: [['repeater/_mock', {...RP_OK, state: 'needs_permission', permission: false}]]},
  {n: 'repeater-unsupported', pre: [['repeater/_mock', {...RP_OK, state: 'unsupported', supported: false}]]},
  {n: 'repeater-error', pre: [['repeater/_mock', {...RP_OK, state: 'error'}]]},
];
for (const pg of [...consolePages, ...mailPages.map(x => ({...x, hash: 'mail'})), ...repeaterPages.map(x => ({...x, hash: 'repeater'}))]) for (const [vp, w, h] of [['desktop', 1440, 900], ['mobile', 390, 844]]) for (const [tn, skin] of [['dark', 'homedroid-dark'], ['light', 'homedroid-light']])
  jobs.push({name: `console-${pg.n}-${vp}-${tn}`, width: w, height: h, hash: pg.hash, prefs: {theme: {mode: 'fixed', skin}}, steps: pg.steps, wait: pg.wait, pre: pg.pre, full: true});
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
      for (const [route, data] of job.pre || []) { const r = await context.request.post(base + '/api/' + route, {data, headers: {'X-Homedroid-Request': '1'}}); if (!r.ok()) issues.push(`${job.name}: pre ${route} ${r.status()}`); }
      const merged = {v: 1, updated: 1, ...job.prefs, theme: job.prefs.theme, font: job.prefs.font, layout: job.prefs.layout, oled: job.prefs.oled, dash: job.prefs.dash};
      await page.addInitScript(p => { try { if (!localStorage.getItem('hd.seeded')) { localStorage.setItem('hd.prefs', JSON.stringify(p)); localStorage.setItem('hd.seeded', '1'); } } catch (e) {} }, merged);
      await page.route('**/api/ui/prefs', r => r.request().method() === 'GET' ? r.fulfill({status: 200, contentType: 'application/json', body: '{}'}) : r.fulfill({status: 200, contentType: 'application/json', body: JSON.stringify({updated: 2})}));
      let hash = job.hash;
      if (hash.includes('__RUN__')) hash = hash.replace('__RUN__', (await (await context.request.get(base + '/api/deploys/my-blog/runs')).json()).runs[1].id);
      await page.goto(base + '/#' + hash);
      await page.locator('#appView:not(.hidden)').waitFor({timeout: 8000});
      await page.waitForTimeout(job.hash === 'overview' ? 1400 : 900);
      for (const step of job.steps || []) { await page.evaluate(step).catch(e => issues.push(`${job.name}: step ${step}: ${e.message}`)); await page.waitForTimeout(job.wait || 400); }
      if (job.edit) { await page.evaluate(() => ACT.wEdit()); await page.waitForTimeout(500); }
      if (job.open) { await page.evaluate(t => openCustomize(t), job.open); await page.waitForTimeout(400); }
      if (job.saver) { await page.evaluate(() => { lastInput = Date.now() - 3600000; setStage('saver'); }); await page.waitForTimeout(300); }
      const m = await page.evaluate(() => ({sw: document.documentElement.scrollWidth, w: innerWidth}));
      if (m.sw > m.w + 1) issues.push(`${job.name}: horizontal overflow ${m.sw} > ${m.w}`);
      await page.screenshot({path: path.join(out, job.name + '.png'), fullPage: !!job.full});
      await context.close();
    }
  } finally { await browser.close(); }
  console.log(JSON.stringify({issues}, null, 2));
  process.exitCode = issues.length ? 1 : 0;
})().catch(e => { console.error(e); process.exitCode = 1; });
