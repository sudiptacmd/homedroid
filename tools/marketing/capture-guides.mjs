// Records the dashboard setup-guide videos (site/media/guide-<slug>.webm + .webp poster) and the
// page screenshots of the dashboard tour (site/media/dashboard-<page>[-mobile].webp), all from the
// local dashboard mock (tests/dashboard-mock), never from a real phone. No ffmpeg: Chrome's CDP
// screencast gives the frames and a second page encodes them with canvas + MediaRecorder.
//
// Run (Node 22+, repository root):
//   /usr/bin/google-chrome --headless=new --no-sandbox --disable-dev-shm-usage \
//     --remote-debugging-port=9228 --user-data-dir=/tmp/hd-capture-chrome about:blank &
//   CDP_URL=http://127.0.0.1:9228 node tools/marketing/capture-guides.mjs [options]
//
// Options:
//   --videos / --shots    only the guide videos / only the page screenshots (default: both)
//   --only a,b,c          only these guide slugs (videos) or dashboard pages (screenshots)
//   --debug               also write a PNG after every step into $QA_DIR (default /tmp/hd-guides-qa)
//   --dump                print the visible buttons and fields after every step (for writing new steps)
// Env: CDP_URL (Chrome, default :9227), MOCK_PORT (default 8790; the script starts its own mock
// there for every guide so each recording begins from the same fresh demo state; password "demo").
//
// Each guide is DATA: a list of steps. A step is [action, ...args], optionally ending with an
// options object {pause: ms} to change how long the video lingers after it.
//   ['go', 'modules']                      open a dashboard page (location.hash)
//   ['click', css, 'text?']                move the cursor to the first visible match (whose text
//                                          contains 'text', case-insensitive) and click it
//   ['type', css, 'text']                  click the field, then type character by character
//   ['select', css, 'value or label']      choose an option of a <select>
//   ['scroll', css | y]                    scroll an element to the middle (or the page to y)
//   ['key', 'Enter']                       press a key
//   ['wait', ms]                           just let the video run
//   ['eval', js]                           run code in the page (setup tricks; not shown)
//   ['api', 'path', body?]                 POST to the mock through the page (setup or a stand-in
//                                          for something that happens on the phone)
//   ['until', js, ms?]                     wait until js is truthy (a job finishing)
//   ['poster']                             this moment is the poster (default: the end state)
// A guide may also have `setup` (steps run before recording starts, with no pauses) and `mobile`.
import {connect, sleep} from './cdp.mjs';
import {mkdir, writeFile, readFile} from 'node:fs/promises';
import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import path from 'node:path';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const OUT = path.join(ROOT, 'site/media');
const PORT = process.env.MOCK_PORT || '8790';
const BASE = `http://127.0.0.1:${PORT}`;
const QA = process.env.QA_DIR || '/tmp/hd-guides-qa';
const args = process.argv.slice(2);
const flag = f => args.includes(f);
const only = (args[args.indexOf('--only') + 1] || '').split(',').filter(Boolean);
const wantOnly = (flag('--only') && only.length) ? only : null;
const DEBUG = flag('--debug'), DUMP = flag('--dump');
const W = 1280, H = 800, FPS = 15;   // 15 fps keeps a 30 s video of a mostly still page under 2.5 MB

// ---- The guides --------------------------------------------------------------------------------
const OK = 'dialog[open] .dlg-foot .btn.primary';       // the confirm button of the open dialog
const GUIDES = {
  apps: {
    setup: [['go', 'modules'], ['eval', `api('modules/jellyfin/remove', {method: 'POST', body: {deleteData: false, deleteLibrary: false}})`],
      ['until', `!modules.job.running && !modules.apps.find(a => a.id === 'jellyfin').installed`, 30000]],
    steps: [
      ['go', 'modules'],
      ['scroll', '[data-act=install][data-id=jellyfin]'],
      ['click', '[data-act=install][data-id=jellyfin]', {pause: 600}],
      ['until', `modules.apps.find(a => a.id === 'jellyfin').installed`, 40000],
      ['wait', 1800],
      ['scroll', '[data-act=toggleMod][data-id=jellyfin]'],
      ['wait', 1500],
      ['go', 'files'],
      ['select', '#fRoot', 'Media library'],
      ['click', '[data-act=cd]', 'Movies'],
      ['click', '[data-act=cd]', 'Big Buck Bunny'],
    ]},
  'remote-access': {steps: [
    ['go', 'modules'],
    ['scroll', '[data-act=tsSettings]'],
    ['click', '[data-act=tsSettings]'],
    ['select', 'dialog[open] [name=serve]', 'tailnet'],
    ['click', OK],
    ['wait', 1800],
    ['scroll', '[data-act=toggleMod][data-id=tunnel]'],
    ['click', '[data-act=guide][data-id=tunnel]'],
    ['click', '#guideNext'], ['click', '#guideNext'], ['click', '#guideNext'],
    ['key', 'Escape'],
    ['click', '[data-act=toggleMod][data-id=tunnel]'],
    ['type', 'dialog[open] input[type=password]', 'eyJhIjoiZGVtby10dW5uZWwifQ'],
    ['click', OK],
    ['until', `findMod('tunnel').enabled`, 15000],
    ['wait', 2200],
  ]},
  mail: {steps: [
    ['go', 'mail'],
    ['click', '[data-act=mailInstall]'],
    ['until', `document.querySelector('[data-f=domain]')`, 40000],
    ['type', '[data-f=domain]', 'example.com'],
    ['click', '[data-act=mailNext]'],
    ['click', '[data-act=mailMode][data-id=relay]'],
    ['click', '[data-act=mailNext]'],
    ['type', '[data-f=address]', '203.0.113.42'],
    ['type', '[data-f="relay.host"]', 'smtp.mailgun.org'],
    ['type', '[data-f="relay.port"]', '587'],
    ['type', '[data-f="relay.username"]', 'postmaster'],
    ['type', '[data-f="relay.password"]', 'demo-key'],
    ['type', '[data-f=spf]', 'spf.mailgun.org'],
    ['click', '[data-act=mailNext]'],
    ['click', '[data-act=mailSave]'],
    ['until', `document.querySelector('[data-act=mailTab]')`, 20000],
    ['click', '[data-act=mailTab][data-t=dns]'],
    ['click', '[data-act=mailTab][data-t=accounts]'],
    ['until', `document.querySelector('[data-act=mailAddAccount]:not([disabled])')`, 10000],
    ['click', '[data-act=mailAddAccount]:not([disabled])'],
    ['type', 'dialog[open] [name=name]', 'alice'],
    ['click', OK],
    ['wait', 1500],
    ['key', 'Escape'],
  ]},
  files: {steps: [
    ['go', 'files'],
    ['select', '#fRoot', 'Media library'],
    ['click', '[data-act=cd]', 'Movies'],
    ['click', '[data-act=cd]', 'Big Buck Bunny'],
    ['click', '[data-act=fileMenu]'],
    ['key', 'Escape'],
    ['click', '[data-act=pickFiles]', {pause: 300}],
    ['eval', `(() => { const dt = new DataTransfer(); dt.items.add(new File(['Great movie night!\\n'], 'movie-night.txt', {type: 'text/plain'})); const i = document.querySelector('#fPick'); i.files = dt.files; i.dispatchEvent(new Event('change', {bubbles: true})); })()`],
    ['wait', 2500],
    ['click', '[data-act=mkdir]'],
    ['type', 'dialog[open] input', 'Subtitles'],
    ['click', OK],
    ['wait', 1500],
  ]},
  deploy: {steps: [
    ['go', 'deploys'],
    ['click', '[data-act=newDeploy]'],
    ['type', 'dialog[open] [name=name]', 'portfolio'],
    ['type', 'dialog[open] [name=port]', '3300'],
    ['type', 'dialog[open] [name=repo]', 'https://github.com/example/portfolio'],
    ['click', 'dialog[open] [name=autoDeploy]'],
    ['click', OK],
    ['wait', 1200],
    ['click', OK],
    ['wait', 3000],
    ['click', '[role=tab]', 'Deploy hooks'],
    ['wait', 1500],
    ['click', '[role=tab]', 'Pipelines'],
  ]},
  domains: {
    setup: [['go', 'domains'], ['eval', `api('domains').then(d => Promise.all(d.domains.map(x => api('domains/' + x.id, {method: 'DELETE'}))))`], ['go', 'ddns'],
      ['eval', `api('ddns').then(d => Promise.all(d.entries.map(x => api('ddns/' + x.id, {method: 'DELETE'}))))`]],
    steps: [
      ['go', 'domains'],
      ['click', '[data-act=domEdit]', 'Add a domain'],
      ['type', 'dialog[open] [name=host]', 'blog.example.com'],
      ['click', OK],
      ['wait', 1500],
      ['click', '[data-act=domVerify]'],
      ['click', OK],
      ['wait', 2500],
      ['go', 'ddns'],
      ['click', '[data-act=ddnsEdit]', 'Add'],
      ['type', 'dialog[open] [name=domain]', 'myhome'],
      ['type', 'dialog[open] [name=token]', 'duck-token-1234'],
      ['click', OK],
      ['wait', 1800],
      ['go', 'firewall'],
      ['click', '[data-act=fwAdd]', 'Home network'],
      ['click', '#fwSave'],
      ['wait', 1500],
    ]},
  ai: {steps: [
    ['go', 'ai'],
    ['scroll', '[data-act=aiDownload][data-id="qwen2.5-0.5b"]'],
    ['click', '[data-act=aiDownload][data-id="qwen2.5-0.5b"]'],
    ['until', `document.querySelector('[data-act=aiDelete][data-file*="0.5b"]')`, 20000],
    ['wait', 1000],
    ['go', 'ai'],
    ['type', '#aiText', 'What can you do on this phone?'],
    ['click', '#aiSend'],
    ['wait', 4500],
    ['scroll', '#aiKeyField'],
    ['click', '[data-act=aiShowKey]'],
  ]},
  mcp: {steps: [
    ['go', 'mcp'],
    ['click', '[data-act=mcpEnable]', {pause: 1800}],
    ['click', '[data-act=mcpReveal]'],
    ['scroll', '[data-act=mcpClient]'],
    ['click', '[data-act=mcpClient]', 'Claude Code'],
    ['click', '[data-act=mcpClient]', 'Cursor'],
    ['click', '[data-act=mcpClient]', 'VS Code'],
    ['click', '[data-act=mcpWrite]', {pause: 1500}],
    ['key', 'Escape'],
    ['scroll', '#mcpText'],
    ['type', '#mcpText', 'Which services are running?'],
    ['click', '#mcpSend', {pause: 4500}],
  ]},
  desktop: {steps: [
    ['go', 'desktop'],
    ['click', '[data-act=dkPair]', {pause: 3500}],
    ['key', 'Escape'],
    ['scroll', '[data-act=mpSelect][data-id=b-5]'],
    ['click', '[data-act=mpSelect][data-id=b-5]', {pause: 1600}],
    ['click', '[data-act=mpSelect][data-id=b-10]', {pause: 1600}],
    ['click', '[data-act=mpPage]', 'Work', {pause: 1600}],
    ['click', '[data-act=mpProfile]', 'Streaming', {pause: 1600}],
    ['click', '[data-act=mpLookScope]', 'This profile', {pause: 1600}],
    ['click', '#mpSave', {pause: 1800}],
  ]},
  'wifi-repeater': {steps: [
    ['go', 'repeater'],
    ['click', '[data-act=rpToggle]', {pause: 2500}],
    ['click', '[data-act=rpReveal]', {pause: 1500}],
    ['click', '[data-act=rpBand]', '5 GHz', {pause: 1500}],
    ['scroll', '[data-act=rpOs]'],
    ['click', '[data-act=rpOs]', 'iPhone', {pause: 2000}],
    ['click', '[data-act=rpOs]', 'Windows 11', {pause: 2000}],
    ['click', '[data-act=rpOs]', 'macOS', {pause: 2000}],
  ]},
  adguard: {
    setup: [['go', 'modules'], ['eval', `api('modules/adguard/remove', {method: 'POST', body: {deleteData: false, deleteLibrary: false}})`],
      ['until', `!modules.job.running && !modules.apps.find(a => a.id === 'adguard').installed`, 30000]],
    steps: [
      ['go', 'modules'],
      ['scroll', '[data-act=install][data-id=adguard]'],
      ['click', '[data-act=install][data-id=adguard]', {pause: 600}],
      ['until', `modules.apps.find(a => a.id === 'adguard').installed`, 40000],
      ['wait', 1500],
      ['scroll', '[data-act=guide][data-id=adguard]'],
      ['click', '[data-act=guide][data-id=adguard]', {pause: 2200}],
      ['click', '#guideNext', {pause: 2200}], ['click', '#guideNext', {pause: 2200}], ['click', '#guideNext', {pause: 2200}],
      ['key', 'Escape'],
    ]},
  // The Customize dialog, in two videos (one would be too large): every layout and the presets,
  // then every theme and interface font.
  customize: {steps: [
    ['go', 'overview'],
    ['click', '[data-act=customize]', {pause: 1200}],
    ...['rail', 'topbar', 'split', 'focus', 'kiosk', 'sidebar'].map(v => ['click', `[data-set="layout.shell"][data-v="${v}"]`, {pause: 1600}]),
    ...['studio', 'terminal', 'paper', 'kiosk', 'minimal'].map(v => ['click', `[data-act=preset][data-id=${v}]`, {pause: 1800}]),
    ['click', '[data-act=custClose]', {pause: 2500}],
  ]},
  'customize-themes': {steps: [
    ['go', 'overview'],
    ['click', '[data-act=customize]', {pause: 900}],
    ['click', '#ct-theme', {pause: 900}],
    ...['homedroid-dark', 'homedroid-light', 'oled', 'midnight', 'ember', 'nordic', 'terminal', 'contrast-dark', 'paper', 'solar', 'lavender', 'contrast-light', 'forest']
      .map(v => ['click', `[data-skin-pick="${v}"]`, {pause: 1000}]),
    ['click', '#ct-fonts', {pause: 900}],
    ...['humanist', 'geometric', 'rounded', 'serif', 'mono', 'system'].map(v => ['select', 'select[data-pref="font.ui"]', v, {pause: 1000}]),
    ['click', '[data-act=custClose]', {pause: 2000}],
  ]},
};
// ---- Mock server -------------------------------------------------------------------------------
let mock = null;
async function startMock() {
  stopMock();
  mock = spawn('node', ['tests/dashboard-mock/server.cjs'], {cwd: ROOT, env: {...process.env, PORT}, stdio: 'ignore'});
  for (let i = 0; i < 50; i++) {
    try { if ((await fetch(BASE + '/')).ok) return; } catch {}
    await sleep(150);
  }
  throw new Error('The dashboard mock did not start');
}
function stopMock() { if (mock) { mock.kill(); mock = null; } }
process.on('exit', stopMock);
process.on('SIGINT', () => { stopMock(); process.exit(1); });

// ---- Browser helpers ---------------------------------------------------------------------------
const J = JSON.stringify;
async function openDashboard(c, {width = W, height = H, mobile = false} = {}) {
  await c.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
  await c.send('Page.navigate', {url: BASE + '/'});
  await sleep(1200);
  await c.evaluate(`document.querySelector('#password').value='demo';document.querySelector('#loginForm').requestSubmit()`);
  await sleep(1300);
  await c.evaluate(`prefs.layout.shell='sidebar';prefs.theme.mode='fixed';prefs.theme.skin='forest';savePrefs()`);
  await sleep(300);
}

// A visible pointer, so a viewer sees what is being clicked. A popover lives in the top layer, and
// is re-shown before every move so it also stays above modal dialogs.
const CURSOR_JS = `(() => {
  let c = document.getElementById('hd-cursor');
  if (c) return;
  c = document.createElement('div'); c.id = 'hd-cursor'; c.setAttribute('popover', 'manual');
  c.style.cssText = 'position:fixed;inset:auto;margin:0;padding:0;border:0;background:transparent;overflow:visible;pointer-events:none;width:0;height:0;left:0;top:0;transition:transform .55s cubic-bezier(.3,.1,.2,1)';
  c.innerHTML = '<svg width="26" height="26" viewBox="0 0 24 24" style="position:absolute;left:-3px;top:-2px;filter:drop-shadow(0 2px 3px rgba(0,0,0,.55))"><path d="M4 2l15 9-6.5 1.6L9.5 19z" fill="#fff" stroke="#111" stroke-width="1.4" stroke-linejoin="round"/></svg><div id="hd-ripple" style="position:absolute;left:-18px;top:-18px;width:36px;height:36px;border-radius:50%;border:3px solid #5be08a;opacity:0;transform:scale(.3)"></div>';
  document.body.append(c); c.style.transform = 'translate(640px,420px)'; c.showPopover();
})()`;
const showCursor = () => `(()=>{const c=document.getElementById('hd-cursor');if(c){try{c.hidePopover();c.showPopover();}catch{}}})()`;

const FIND = (css, text) => `(() => {
  const want = ${J(text || '')}.toLowerCase();
  const vis = e => { const r = e.getBoundingClientRect(); const s = getComputedStyle(e); return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none'; };
  const all = [...document.querySelectorAll(${J(css)})].filter(vis);
  const dlg = all.filter(e => e.closest('dialog[open]'));
  const pool = (dlg.length ? dlg : all);
  const norm = e => ((e.innerText || e.value || e.getAttribute('aria-label') || e.title || '') + '').replace(/\\s+/g, ' ').toLowerCase();
  const hit = want ? pool.find(e => norm(e).includes(want)) : pool[0];
  return hit || null;
})()`;

class Rec {
  constructor(c, encoder, slug) { this.c = c; this.encoder = encoder; this.slug = slug; this.poster = null; this.step = 0; this.frames = false; }
  async eval(js) { return this.c.evaluate(js); }
  async el(css, text) {
    for (let i = 0; i < 40; i++) {
      const ok = await this.c.evaluate(`window.__el = ${FIND(css, text)}; !!window.__el`);
      if (ok) return;
      await sleep(250);
    }
    throw new Error(`[${this.slug}] no visible element for ${css} ${text || ''}`);
  }
  async center() {
    await this.c.evaluate(`(() => { const e = window.__el; const r = e.getBoundingClientRect(); if (r.top < 70 || r.bottom > innerHeight - 30) { e.scrollIntoView({block: 'center'}); } })()`);
    await sleep(120);
    const [x, y] = await this.c.evaluate(`(() => { const r = window.__el.getBoundingClientRect(); return [Math.round(r.left + Math.min(r.width / 2, 120)), Math.round(r.top + r.height / 2)]; })()`);
    return [x, y];
  }
  async moveTo(x, y) {
    await this.c.evaluate(CURSOR_JS);
    await this.c.evaluate(showCursor());
    await this.c.evaluate(`document.getElementById('hd-cursor').style.transform='translate(${x}px,${y}px)'`);
    await sleep(650);
    await this.c.send('Input.dispatchMouseEvent', {type: 'mouseMoved', x, y});
  }
  async ripple() {
    await this.c.evaluate(`(() => { const r = document.getElementById('hd-ripple'); if (!r) return; r.animate([{opacity:.9,transform:'scale(.3)'},{opacity:0,transform:'scale(1.4)'}], {duration: 450}); })()`);
  }
  async click(css, text) {
    await this.el(css, text);
    const [x, y] = await this.center();
    await this.moveTo(x, y);
    await this.ripple();
    // A real mouse click lands on whatever is on top; a covered control fails loudly instead.
    const onTop = await this.c.evaluate(`(() => { const e = window.__el, t = document.elementFromPoint(${x}, ${y}); return !!t && (e === t || e.contains(t) || t.contains(e)); })()`);
    if (!onTop) { await this.c.evaluate(`window.__el.click()`); }
    else {
      await this.c.send('Input.dispatchMouseEvent', {type: 'mousePressed', x, y, button: 'left', clickCount: 1});
      await this.c.send('Input.dispatchMouseEvent', {type: 'mouseReleased', x, y, button: 'left', clickCount: 1});
    }
  }
  async type(css, text) {
    await this.click(css);
    await this.c.evaluate(`(() => { const e = window.__el; e.focus(); if (e.select && e.value) e.select(); })()`);
    await sleep(250);
    if (await this.c.evaluate(`!!window.__el.value`)) { await this.c.send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Backspace', code: 'Backspace', windowsVirtualKeyCode: 8}); await this.c.send('Input.dispatchKeyEvent', {type: 'keyUp', key: 'Backspace', code: 'Backspace', windowsVirtualKeyCode: 8}); }
    for (const ch of text) { await this.c.send('Input.insertText', {text: ch}); await sleep(ch === ' ' ? 70 : 38 + Math.random() * 28); }
  }
  async select(css, value) {
    await this.el(css);
    const [x, y] = await this.center();
    await this.moveTo(x, y);
    await this.ripple();
    await sleep(350);
    const okv = await this.c.evaluate(`(() => { const s = window.__el, v = ${J(value)}.toLowerCase(); const o = [...s.options].find(o => o.value.toLowerCase() === v || o.textContent.trim().toLowerCase().startsWith(v)); if (!o) return false; s.value = o.value; s.dispatchEvent(new Event('input', {bubbles: true})); s.dispatchEvent(new Event('change', {bubbles: true})); return true; })()`);
    if (!okv) throw new Error(`[${this.slug}] no option ${value} in ${css}`);
  }
  async key(k) {
    const code = {Enter: 13, Escape: 27, Tab: 9}[k] || 0;
    await this.c.send('Input.dispatchKeyEvent', {type: 'keyDown', key: k, code: k, windowsVirtualKeyCode: code, text: k === 'Enter' ? '\r' : undefined});
    await this.c.send('Input.dispatchKeyEvent', {type: 'keyUp', key: k, code: k, windowsVirtualKeyCode: code});
  }
  async scroll(target) {
    if (typeof target === 'number') await this.c.evaluate(`window.scrollTo({top: ${target}, behavior: 'smooth'})`);
    else { await this.el(target); await this.c.evaluate(`window.__el.scrollIntoView({block: 'center', behavior: 'smooth'})`); }
    await sleep(900);
  }
  async until(js, ms = 20000) {
    const t = Date.now();
    while (Date.now() - t < ms) { if (await this.c.evaluate(`!!(${js})`)) return; await sleep(250); }
    throw new Error(`[${this.slug}] timed out waiting for ${js}`);
  }
  async shot(file, quality = 82) {
    const {data} = await this.c.send('Page.captureScreenshot', {format: 'webp', quality});
    await writeFile(file, Buffer.from(data, 'base64'));
  }
  async dump() {
    console.log(await this.c.evaluate(`[...document.querySelectorAll('dialog[open] *, main *')].filter(e => e.matches('button, a, input, select, textarea, summary, [role=tab], [role=switch]') && e.offsetParent !== null).map(e => (e.tagName[0] + ':' + (e.id ? '#' + e.id : '') + (e.name ? '[name=' + e.name + ']' : '') + (e.dataset.act ? '[act=' + e.dataset.act + (e.dataset.id ? ' id=' + e.dataset.id : '') + ']' : '') + ' ' + (e.innerText || e.placeholder || e.value || '').trim().replace(/\\s+/g, ' ').slice(0, 40))).join('\\n')`));
  }
  async run(step, quiet) {
    const [name, ...rest] = step;
    let opt = {};
    if (rest.length && typeof rest.at(-1) === 'object' && rest.at(-1) !== null && !Array.isArray(rest.at(-1)) && name !== 'api') opt = rest.pop();
    if (name === 'api' && rest.length > 2) opt = rest.pop();
    const pause = quiet ? 0 : opt.pause;
    switch (name) {
      case 'go': await this.c.evaluate(`location.hash=${J(rest[0])};window.scrollTo(0,0)`); await sleep(quiet ? 700 : 900); break;
      case 'click': await this.click(rest[0], rest[1]); break;
      case 'type': await this.type(rest[0], rest[1]); break;
      case 'select': await this.select(rest[0], rest[1]); break;
      case 'scroll': await this.scroll(rest[0]); break;
      case 'key': await this.key(rest[0]); break;
      case 'wait': await sleep(rest[0]); break;
      case 'eval': await this.c.evaluate(rest[0]); await sleep(150); break;
      case 'api': await this.c.evaluate(`api(${J(rest[0])}, {method: 'POST', body: ${J(rest[1] || {})}})`); await sleep(300); break;
      case 'until': await this.until(rest[0], rest[1]); break;
      case 'poster': await sleep(300); await this.shot(path.join(OUT, `guide-${this.slug}.webp`)); this.poster = true; break;
      default: throw new Error('Unknown step ' + name);
    }
    this.step++;
    if (DEBUG) { await mkdir(QA, {recursive: true}); const {data} = await this.c.send('Page.captureScreenshot', {format: 'png'}); await writeFile(path.join(QA, `${this.slug}-${String(this.step).padStart(2, '0')}-${name}.png`), Buffer.from(data, 'base64')); }
    if (DUMP) { console.log('--', J(step)); await this.dump(); }
    const defaults = {go: 1500, click: 1100, type: 700, select: 1100, scroll: 700, key: 800, wait: 0, eval: 0, api: 0, until: 700, poster: 0};
    await sleep(pause ?? (quiet ? 100 : defaults[name]));
  }
}

// ---- Video recording ---------------------------------------------------------------------------
async function encode(encoder, startWall, stopWall, bitrate) {
  await encoder.send('Page.bringToFront');
  return encoder.evaluate(`(async () => {
    const canvas = document.createElement('canvas'); canvas.width = ${W}; canvas.height = ${H}; document.body.append(canvas);
    const ctx = canvas.getContext('2d');
    const decoded = await Promise.all(frames.map(async f => { const img = new Image(); img.src = 'data:image/jpeg;base64,' + f.data; await img.decode(); return {time: f.time, img}; }));
    const stream = canvas.captureStream(${FPS}), chunks = [];
    const rec = new MediaRecorder(stream, {mimeType: 'video/webm;codecs=vp9', videoBitsPerSecond: ${bitrate}});
    const done = new Promise(r => { rec.ondataavailable = e => chunks.push(e.data); rec.onstop = () => { const fr = new FileReader(); fr.onload = () => r(fr.result.split(',')[1]); fr.readAsDataURL(new Blob(chunks, {type: 'video/webm'})); }; });
    const start = decoded[0].time; ctx.drawImage(decoded[0].img, 0, 0, ${W}, ${H}); rec.start(); const t0 = performance.now();
    const length = Math.max(decoded.at(-1).time - start + 0.6, ${(stopWall - startWall) / 1000}) * 1000;
    await new Promise(resolve => { const timer = setInterval(() => { const el = performance.now() - t0; const f = decoded.findLast(f => (f.time - start) * 1000 <= el) || decoded[0]; ctx.drawImage(f.img, 0, 0, ${W}, ${H}); if (el >= length) { clearInterval(timer); resolve(); } }, 1000 / ${FPS}); });
    rec.stop(); const out = await done; stream.getTracks().forEach(t => t.stop()); canvas.remove(); return out; })()`);
}

async function recordGuide(slug, g) {
  console.log('== guide', slug);
  await startMock();
  const c = await connect(BASE);
  const encoder = await connect();
  try {
    await c.send('Page.bringToFront');
    await openDashboard(c);
    const r = new Rec(c, encoder, slug);
    for (const s of g.setup || []) await r.run(s, true);
    await c.evaluate(CURSOR_JS);
    await encoder.evaluate(`window.frames = []`);
    await c.send('Page.bringToFront');
    let queue = Promise.resolve();
    c.on('Page.screencastFrame', p => {
      c.send('Page.screencastFrameAck', {sessionId: p.sessionId});
      queue = queue.then(() => encoder.evaluate(`frames.push({data: ${J(p.data)}, time: ${p.metadata.timestamp}})`));
    });
    await c.send('Page.startScreencast', {format: 'jpeg', quality: 72, maxWidth: W, maxHeight: H, everyNthFrame: 2});
    const startWall = Date.now();
    await sleep(1200);
    for (const s of g.steps) await r.run(s);
    await sleep(2200);
    if (!r.poster) await r.shot(path.join(OUT, `guide-${slug}.webp`));
    const stopWall = Date.now();
    await c.send('Page.stopScreencast');
    await queue;
    const n = await encoder.evaluate('frames.length'), uniq = await encoder.evaluate('new Set(frames.map(f => f.data)).size');
    console.log(`   frames ${n}, distinct ${uniq}, ${((stopWall - startWall) / 1000).toFixed(1)} s recorded`);
    if (uniq < 8) throw new Error('Too few distinct screencast frames');
    let bitrate = g.bitrate || 600000, buf;
    for (let i = 0; i < 4; i++) {
      buf = Buffer.from(await encode(encoder, startWall, stopWall, bitrate), 'base64');
      console.log(`   try ${bitrate} bps -> ${(buf.length / 1024).toFixed(0)} KB`);
      if (buf.length < 2.3 * 1024 * 1024) break;
      bitrate = Math.round(bitrate * 0.7);
    }
    await writeFile(path.join(OUT, `guide-${slug}.webm`), buf);
    console.log(`   guide-${slug}.webm ${(buf.length / 1024).toFixed(0)} KB at ${bitrate} bps`);
  } finally { await c.close(); await encoder.close(); stopMock(); }
}

// ---- Page screenshots --------------------------------------------------------------------------
const TOUR_PAGE_FILE = {ssh: 'terminal'};
// Per page: steps that make the page show real demo content before the screenshot (desktop, mobile).
const SHOT_PREP = {
  ssh: [['eval', `document.querySelector('#termStart')?.click()`], ['wait', 900]],
};
async function shootPages() {
  const meta = JSON.parse(await readFile(path.join(ROOT, 'tools/marketing/guides.json'), 'utf8'));
  const pages = meta.tourPages.filter(p => !wantOnly || wantOnly.includes(p));
  await startMock();
  const c = await connect(BASE);
  try {
    for (const mobile of [false, true]) {
      await openDashboard(c, {width: mobile ? 390 : 1440, height: mobile ? 844 : 900, mobile});
      const r = new Rec(c, null, 'tour');
      for (const p of pages) {
        const file = (TOUR_PAGE_FILE[p] || p) + (mobile ? '-mobile' : '');
        await c.evaluate(`location.hash=${J(p)};window.scrollTo(0,0)`);
        await sleep(1500);
        for (const s of SHOT_PREP[p] || []) await r.run(s, true);
        await sleep(600);
        await r.shot(path.join(OUT, `dashboard-${file}.webp`), 82);
        console.log('shot', file);
      }
    }
  } finally { await c.close(); stopMock(); }
}

await mkdir(OUT, {recursive: true});
if (!flag('--shots')) for (const [slug, g] of Object.entries(GUIDES)) { if (slug.startsWith('__') || (wantOnly && !wantOnly.includes(slug))) continue; await recordGuide(slug, g); }
if (!flag('--videos')) await shootPages();
stopMock();
