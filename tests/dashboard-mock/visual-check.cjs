// Optional browser QA; not part of node --test. Set PLAYWRIGHT_MODULE if not installed locally.
'use strict';
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const base = process.env.MOCK_URL || 'http://127.0.0.1:8787';
const output = path.resolve(__dirname, '../../docs/design/screens');
(async () => {
  fs.mkdirSync(output, {recursive: true});
  const browser = await chromium.launch({executablePath: process.env.CHROME_PATH || '/usr/bin/google-chrome', headless: true, args: ['--no-sandbox']});
  const issues = [], measurements = [];
  try {
    const context = await browser.newContext({viewport: {width: 1440, height: 1000}, colorScheme: 'dark', reducedMotion: 'reduce'});
    const page = await context.newPage();
    page.on('pageerror', e => issues.push(e.message));
    page.on('response', r => { if (r.status() >= 400 && r.status() !== 401) issues.push(`${r.status()} ${r.url()}`); });
    await page.goto(base);
    await page.locator('#password').fill('demo'); await page.locator('#loginForm button[type=submit]').click();
    await page.locator('#ovServices .item').first().waitFor();
    // Generate a tiny, valid video fixture once, using Chrome's built-in encoder.
    const sample = path.join(__dirname, 'sample.webm');
    if (!fs.existsSync(sample)) {
      const data = await page.evaluate(async () => {
        const canvas = document.createElement('canvas'); canvas.width = 160; canvas.height = 120;
        const ctx = canvas.getContext('2d'), stream = canvas.captureStream(5), recorder = new MediaRecorder(stream, {mimeType: 'video/webm;codecs=vp8'}), chunks = [];
        const done = new Promise(resolve => { recorder.ondataavailable = e => chunks.push(e.data); recorder.onstop = async () => resolve(Array.from(new Uint8Array(await new Blob(chunks).arrayBuffer()))); });
        recorder.start();
        for (let i = 0; i < 10; i++) { ctx.fillStyle = '#18191e'; ctx.fillRect(0, 0, 160, 120); ctx.fillStyle = '#a6a2ff'; ctx.fillRect(i * 12, 40, 24, 24); ctx.font = '12px sans-serif'; ctx.fillText('Demo camera', 30, 100); await new Promise(r => setTimeout(r, 200)); }
        recorder.stop(); const data = await done; stream.getTracks().forEach(t => t.stop()); return data;
      }); fs.writeFileSync(sample, Buffer.from(data));
    }
    const pages = ['overview', 'modules', 'deploys', 'files', 'network', 'wol', 'camera', 'cameras', 'cluster', 'ai', 'ssh', 'settings'];
    for (const mobile of [false, true]) {
      await page.setViewportSize(mobile ? {width: 390, height: 844} : {width: 1440, height: 1000});
      for (const theme of ['dark', 'light']) {
        await page.evaluate(t => setThemeMode(t), theme);
        for (const tab of pages) {
          await page.evaluate(t => { location.hash = t; }, tab);
          await page.waitForTimeout(tab === 'cameras' ? 900 : 220);
          await page.locator(`[data-view="${tab}"]`).waitFor({state: 'visible'});
          const measure = await page.evaluate(() => ({width: innerWidth, scrollWidth: document.documentElement.scrollWidth, error: document.querySelector('#loadStatus').textContent}));
          measurements.push({tab, mobile, theme, ...measure});
          if (measure.scrollWidth > measure.width || measure.error) issues.push(JSON.stringify({tab, mobile, theme, ...measure}));
          // Representative screens; all pages still receive overflow/runtime checks above.
          if (['overview', 'modules', 'files', 'cluster', 'settings', 'ai', 'ssh'].includes(tab)) await page.screenshot({path: path.join(output, `${tab}-${mobile ? 'mobile' : 'desktop'}-${theme}.png`)});
        }
      }
    }
    await page.evaluate(() => { location.hash = 'overview'; setThemeMode('dark'); });
    await page.locator('#navToggle').click();
    assert.equal(await page.locator('#navToggle').getAttribute('aria-expanded'), 'true');
    await page.screenshot({path: path.join(output, 'navigation-mobile-dark.png')});
    await page.keyboard.press('Escape');
    await page.setViewportSize({width: 1440, height: 1000});
    await page.keyboard.press('Control+k'); await page.locator('#commandInput').fill('settings'); await page.screenshot({path: path.join(output, 'command-palette-dark.png')});
    await page.keyboard.press('Enter'); await page.waitForURL('**/#settings');
    await page.locator('#settingsSshPort').fill('2222'); await page.locator('#settingsAppsDns').fill('1.1.1.1, 9.9.9.9'); await page.locator('#saveSettings').click(); await page.locator('#settingsMessage').filter({hasText: 'Settings saved'}).waitFor();
    await page.evaluate(() => { location.hash = 'ssh'; }); await page.locator('#sshCmd').filter({hasText: '2222'}).waitFor();
    await page.locator('#termStart').click(); await page.locator('#termState').filter({hasText: 'Connected to the phone shell'}).waitFor(); await page.keyboard.type('whoami'); await page.keyboard.press('Enter');
    await page.screenshot({path: path.join(output, 'terminal-connected-dark.png')}); await page.locator('#termStop').click();
    await page.evaluate(() => { location.hash = 'modules'; }); await page.locator('[data-act=updateMod]').first().click(); await page.locator('#jobBox').filter({hasText: 'Updating'}).waitFor();
    await page.screenshot({path: path.join(output, 'module-update-dark.png')});
    // Reset settings after the interaction checks.
    await context.request.post(base + '/api/settings', {data: {sshPort: 8022, appsDns: ''}});
    fs.writeFileSync(path.join(output, 'browser-report.json'), JSON.stringify({issues, measurements}, null, 2) + '\n');
    console.log(JSON.stringify({screens: output, checks: measurements.length, issues}, null, 2));
    assert.deepEqual(issues, []);
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
