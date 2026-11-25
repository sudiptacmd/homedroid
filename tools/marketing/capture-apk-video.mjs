// Records site/media/dashboard-alpine-packages.webm: installing Alpine packages from the
// dashboard's terminal, on a real Homedroid phone or emulator (not the mock), so the apk output
// is genuine. Needs Chrome on :9227 (see README.md in this folder) and the dashboard reachable:
//   adb forward tcp:8800 tcp:8800
//   HD_PASSWORD=<dashboard password> node tools/marketing/capture-apk-video.mjs
import {connect,sleep} from './cdp.mjs';
import {writeFile} from 'node:fs/promises';

const base = process.env.HD_URL || 'http://127.0.0.1:8800';
const password = process.env.HD_PASSWORD;
if (!password) throw new Error('Set HD_PASSWORD to the dashboard password');
const W = 1280, H = 800;
const c = await connect(base);
const encoder = await connect();
try {
  await c.send('Emulation.setDeviceMetricsOverride', {width: W, height: H, deviceScaleFactor: 1, mobile: false});
  await sleep(1200);
  await c.evaluate(`document.querySelector('#password').value=${JSON.stringify(password)};document.querySelector('#loginForm').requestSubmit()`);
  await sleep(1500);
  await c.evaluate(`prefs.layout.shell='sidebar';prefs.theme.mode='fixed';prefs.theme.skin='forest';savePrefs();location.hash='ssh';window.scrollTo(0,0)`);
  await sleep(1200);

  // Start recording before anything moves.
  await encoder.evaluate(`window.frames=[]`);
  await c.send('Page.bringToFront');
  let queue = Promise.resolve();
  c.on('Page.screencastFrame', p => {
    c.send('Page.screencastFrameAck', {sessionId: p.sessionId});
    queue = queue.then(() => encoder.evaluate(`frames.push({data:${JSON.stringify(p.data)},time:${p.metadata.timestamp}})`));
  });
  await c.send('Page.startScreencast', {format: 'jpeg', quality: 80, maxWidth: W, maxHeight: H, everyNthFrame: 1});
  await sleep(900);
  await c.evaluate(`document.querySelector('[data-act="termKind"][data-kind="alpine"]').click()`);
  await sleep(700);
  await c.evaluate(`document.querySelector('#termStart').click()`);
  await sleep(2500);
  // Typed one key at a time, so it reads like someone typing.
  async function type(text) {
    for (const ch of text) { await c.send('Input.insertText', {text: ch}); await sleep(55); }
    await sleep(250);
    await c.send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, text: '\r'});
    await c.send('Input.dispatchKeyEvent', {type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13});
  }
  await type('apk add git htop');
  // Wait for apk to finish ("OK: ... packages" is its last line).
  for (let i = 0; i < 120; i++) {
    const done = await c.evaluate(`[...document.querySelectorAll('#termBox .xterm-rows > div')].some(r => /^OK: /.test(r.textContent.trim()) || /ERROR/.test(r.textContent))`);
    if (done) break;
    await sleep(500);
  }
  await sleep(1200);
  await type('git --version');
  await sleep(1500);
  await type('apk info -e git htop');
  await sleep(2600);
  await c.send('Page.stopScreencast');
  await queue;
  const unique = await encoder.evaluate('new Set(frames.map(f=>f.data)).size');
  console.log('frames', await encoder.evaluate('frames.length'), 'distinct', unique);
  if (unique < 8) throw new Error('Too few distinct screencast frames');

  // Encode the real frames at their original timing with MediaRecorder (no ffmpeg).
  await encoder.send('Page.bringToFront');
  const data = await encoder.evaluate(`(async()=>{
    const canvas=document.createElement('canvas');canvas.width=${W};canvas.height=${H};document.body.append(canvas);
    const ctx=canvas.getContext('2d');
    const decoded=await Promise.all(frames.map(async f=>{const img=new Image();img.src='data:image/jpeg;base64,'+f.data;await img.decode();return {...f,img};}));
    const stream=canvas.captureStream(30),chunks=[],rec=new MediaRecorder(stream,{mimeType:'video/webm;codecs=vp9',videoBitsPerSecond:900000});
    const done=new Promise(r=>{rec.ondataavailable=e=>chunks.push(e.data);rec.onstop=()=>{const reader=new FileReader();reader.onload=()=>r(reader.result.split(',')[1]);reader.readAsDataURL(new Blob(chunks,{type:'video/webm'}));};});
    const start=decoded[0].time;ctx.drawImage(decoded[0].img,0,0,${W},${H});rec.start();const t=performance.now();
    const duration=(decoded.at(-1).time-start+0.8)*1000;
    await new Promise(resolve=>{const timer=setInterval(()=>{const elapsed=performance.now()-t;const frame=decoded.findLast(f=>(f.time-start)*1000<=elapsed)||decoded[0];ctx.drawImage(frame.img,0,0,${W},${H});if(elapsed>=duration){clearInterval(timer);resolve();}},1000/30);});
    rec.stop();const result=await done;stream.getTracks().forEach(t=>t.stop());return result;})()`);
  await writeFile('site/media/dashboard-alpine-packages.webm', Buffer.from(data, 'base64'));
  console.log('video', Buffer.from(data, 'base64').length, 'bytes');
  // Poster: the finished terminal.
  await c.send('Page.bringToFront');
  const {data: png} = await c.send('Page.captureScreenshot', {format: 'webp', quality: 82});
  await writeFile('site/media/dashboard-alpine-packages.webp', Buffer.from(png, 'base64'));
} finally {
  await c.close();
  await encoder.close();
}
