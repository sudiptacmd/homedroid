// Records site/media/guide-media-automation.webm: the media apps in the dashboard, a movie
// requested in Jellyseerr (Seerr), and the same movie arriving in Radarr. Uses a real Homedroid
// (phone or emulator) with Jellyfin, qBittorrent, Prowlarr, Radarr, Sonarr and Seerr installed and
// Seerr already set up, so everything on screen is genuine. Needs Chrome on :9227 and:
//   adb forward tcp:8800 tcp:8800; adb forward tcp:5055 tcp:5055; adb forward tcp:7878 tcp:7878
//   HD_PASSWORD=<dashboard> JELLYFIN_PASSWORD=<jellyfin admin> RADARR_PASSWORD=<radarr admin> \
//     node tools/marketing/capture-media-video.mjs
import {connect,sleep} from './cdp.mjs';
import {writeFile} from 'node:fs/promises';

const {HD_PASSWORD, JELLYFIN_PASSWORD, RADARR_PASSWORD} = process.env;
if (!HD_PASSWORD || !JELLYFIN_PASSWORD || !RADARR_PASSWORD) throw new Error('Set HD_PASSWORD, JELLYFIN_PASSWORD and RADARR_PASSWORD');
const W = 1280, H = 800;
const c = await connect('about:blank');
const encoder = await connect();

const go = async (url, wait = 2500) => { await c.send('Page.navigate', {url}); await sleep(wait); };
const js = expr => c.evaluate(expr);
async function waitFor(expr, ms = 20000) {
  for (let t = 0; t < ms; t += 250) { if (await js(`!!(${expr})`)) return true; await sleep(250); }
  throw new Error('Timed out waiting for ' + expr);
}
// Click the centre of the first (or last) visible element matching a CSS selector and containing text.
async function click(selector, text = '', last = false) {
  const box = await js(`(()=>{const el=[...document.querySelectorAll(${JSON.stringify(selector)})][${last ? "'findLast'" : "'find'"}](e=>e.offsetParent!==null&&e.textContent.trim().toLowerCase().includes(${JSON.stringify(text.toLowerCase())}));if(!el)return null;el.scrollIntoView({block:'center'});const r=el.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2};})()`);
  if (!box) throw new Error(`No ${selector} with "${text}"`);
  await sleep(250);
  for (const type of ['mouseMoved', 'mousePressed', 'mouseReleased']) {
    await c.send('Input.dispatchMouseEvent', {type, x: box.x, y: box.y, button: 'left', clickCount: 1});
  }
}
async function type(selector, text) {
  await js(`document.querySelector(${JSON.stringify(selector)}).focus()`);
  for (const ch of text) { await c.send('Input.insertText', {text: ch}); await sleep(60); }
}
const enter = async () => {
  await c.send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, text: '\r'});
  await c.send('Input.dispatchKeyEvent', {type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13});
};

try {
  await c.send('Emulation.setDeviceMetricsOverride', {width: W, height: H, deviceScaleFactor: 1, mobile: false});
  // Start signed out everywhere, so the sign-ins are on camera.
  await c.send('Network.enable');
  await c.send('Network.clearBrowserCookies');
  // Dashboard login happens off camera.
  await go('http://127.0.0.1:8800/', 1500);
  await js(`document.querySelector('#password').value=${JSON.stringify(HD_PASSWORD)};document.querySelector('#loginForm').requestSubmit()`);
  await sleep(1500);
  await js(`prefs.layout.shell='sidebar';prefs.theme.mode='fixed';prefs.theme.skin='forest';savePrefs();location.hash='modules'`);
  await sleep(2500);
  await js(`[...document.querySelectorAll('.mod .title')].find(e=>e.textContent==='Prowlarr').closest('.mod').scrollIntoView({block:'start'});window.scrollBy(0,-90)`);
  await sleep(800);

  await encoder.evaluate(`window.frames=[]`);
  let queue = Promise.resolve();
  c.on('Page.screencastFrame', p => {
    c.send('Page.screencastFrameAck', {sessionId: p.sessionId});
    queue = queue.then(() => encoder.evaluate(`frames.push({data:${JSON.stringify(p.data)},time:${p.metadata.timestamp}})`));
  });
  // The recorded tab must be in front: headless Chrome throttles background tabs.
  const start = async () => { await c.send('Page.bringToFront'); await c.send('Page.startScreencast', {format: 'jpeg', quality: 78, maxWidth: W, maxHeight: H, everyNthFrame: 1}); };
  await start();
  await sleep(2200);
  for (let i = 0; i < 3; i++) { await js(`window.scrollBy({top:260,behavior:'smooth'})`); await sleep(1100); }
  await sleep(800);

  // Jellyseerr: sign in with the Jellyfin account, find the movie and request it.
  await go('http://127.0.0.1:5055/login', 3000); // the screencast carries on across page loads
  await waitFor(`document.querySelector('#username')`);
  await type('#username', 'admin');
  await type('#password', JELLYFIN_PASSWORD);
  await sleep(400);
  await click('button[type=submit]', 'sign in');
  await waitFor(`location.pathname==='/'`);
  await sleep(2500);
  // Popular movies: open the first one that can still be requested.
  await click('a', 'movies');
  await waitFor(`location.pathname.startsWith('/discover/movies')`);
  // Posters are cards, not links: hover one and click it.
  const posters = `[...document.querySelectorAll('main img, img')].filter(i=>i.offsetParent&&i.getBoundingClientRect().width>100&&i.getBoundingClientRect().top>100)`;
  await waitFor(`${posters}.length > 4`);
  await sleep(2000);
  let title = null, tmdb = null;
  for (let i = 0; i < 8 && !title; i++) {
    const p = await js(`(()=>{const el=${posters}[${i}];el.scrollIntoView({block:'center'});const r=el.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2};})()`);
    await c.send('Input.dispatchMouseEvent', {type: 'mouseMoved', x: p.x, y: p.y});
    await sleep(700);
    for (const type of ['mousePressed', 'mouseReleased']) await c.send('Input.dispatchMouseEvent', {type, x: p.x, y: p.y + 40, button: 'left', clickCount: 1});
    await waitFor(`location.pathname.startsWith('/movie/') && document.querySelector('h1')`);
    await sleep(2500);
    const requestable = await js(`[...document.querySelectorAll('button')].some(b=>b.offsetParent&&b.textContent.trim()==='Request')`);
    if (requestable) tmdb = await js(`location.pathname.split('/')[2]`);
    if (requestable) title = await js(`document.querySelector('h1').textContent.replace(/\\s*\\(\\d{4}\\)\\s*$/, '').trim()`);
    else { await c.send('Page.navigate', {url: 'http://127.0.0.1:5055/discover/movies'}); await sleep(3500); }
  }
  if (!title) throw new Error('No requestable movie in Popular');
  console.log('requesting', title);
  await click('button', 'request');
  await sleep(1800);
  // The confirm button is in the dialog, which comes last in the page.
  await click('button', 'request', true);
  await sleep(3500);

  // Radarr: the movie is there, monitored, waiting for a release.
  await go('http://127.0.0.1:7878/login', 2500);
  await waitFor(`document.querySelector('input[name=username]')`);
  await type('input[name=username]', 'admin');
  await type('input[name=password]', RADARR_PASSWORD);
  await enter();
  await waitFor(`!location.pathname.startsWith('/login')`);
  await sleep(4500);
  // Radarr links films by TMDB id, the same id as in Seerr's address.
  await waitFor(`document.querySelector('a[href="/movie/${tmdb}"]')`);
  await sleep(1500);
  await click(`a[href="/movie/${tmdb}"]`, '');
  await sleep(5000);
  await c.send('Page.stopScreencast');
  await queue;

  const n = await encoder.evaluate('new Set(frames.map(f=>f.data)).size');
  console.log('frames', await encoder.evaluate('frames.length'), 'distinct', n);
  await encoder.send('Page.bringToFront');
  const data = await encoder.evaluate(`(async()=>{
    const canvas=document.createElement('canvas');canvas.width=${W};canvas.height=${H};document.body.append(canvas);const ctx=canvas.getContext('2d');
    const decoded=await Promise.all(frames.map(async f=>{const img=new Image();img.src='data:image/jpeg;base64,'+f.data;await img.decode();return {...f,img};}));
    // Page loads between apps are cut to at most 0.6 s.
    let shift=0;for(let i=1;i<decoded.length;i++){const gap=decoded[i].time-decoded[i-1].time;if(gap>0.6)shift+=gap-0.6;decoded[i].t=decoded[i].time-shift;}decoded[0].t=decoded[0].time;
    const stream=canvas.captureStream(30),chunks=[],rec=new MediaRecorder(stream,{mimeType:'video/webm;codecs=vp9',videoBitsPerSecond:240000});
    const done=new Promise(r=>{rec.ondataavailable=e=>chunks.push(e.data);rec.onstop=()=>{const reader=new FileReader();reader.onload=()=>r(reader.result.split(',')[1]);reader.readAsDataURL(new Blob(chunks,{type:'video/webm'}));};});
    const s=decoded[0].t;ctx.drawImage(decoded[0].img,0,0,${W},${H});rec.start();const t0=performance.now();const duration=(decoded.at(-1).t-s+3.5)*1000;
    await new Promise(resolve=>{const timer=setInterval(()=>{const el=performance.now()-t0;const f=decoded.findLast(f=>(f.t-s)*1000<=el)||decoded[0];ctx.drawImage(f.img,0,0,${W},${H});if(el>=duration){clearInterval(timer);resolve();}},1000/30);});
    rec.stop();const r=await done;stream.getTracks().forEach(t=>t.stop());return r;})()`);
  await writeFile('site/media/guide-media-automation.webm', Buffer.from(data, 'base64'));
  console.log('video', Buffer.from(data, 'base64').length, 'bytes');
  await c.send('Page.bringToFront');
  const {data: poster} = await c.send('Page.captureScreenshot', {format: 'webp', quality: 82});
  await writeFile('site/media/guide-media-automation.webp', Buffer.from(poster, 'base64'));
} finally {
  await c.close();
  await encoder.close();
}
