// Run the dashboard mock and Chrome on :9227 first. See tools/marketing/README.md.
import {connect,sleep} from './cdp.mjs';
import {mkdir,writeFile} from 'node:fs/promises';
const out='site/media'; await mkdir(out,{recursive:true});
const c=await connect('http://127.0.0.1:8787');
try {
await c.send('Emulation.setDeviceMetricsOverride',{width:1440,height:900,deviceScaleFactor:1,mobile:false});
await sleep(700);await c.evaluate(`document.querySelector('#password').value='demo';document.querySelector('#loginForm').requestSubmit()`);await sleep(1000);
const available=await c.evaluate(`[...document.querySelectorAll('[data-view]')].map(e=>e.dataset.view)`);
const missing=['pipelines','databases','domains','mcp'].filter(p=>!available.includes(p));
console.log('Pages absent in this checkout:',missing.join(', '));
async function look(layout,skin){await c.evaluate(`prefs.layout.shell=${JSON.stringify(layout)};prefs.theme.mode='fixed';prefs.theme.skin=${JSON.stringify(skin)};savePrefs()`);await sleep(200);}
async function page(id){await c.evaluate(`location.hash=${JSON.stringify(id)};window.scrollTo(0,0)`);await sleep(550);}
async function shot(name){const {data}=await c.send('Page.captureScreenshot',{format:'png'});await writeFile(`${out}/dashboard-${name}.png`,Buffer.from(data,'base64'));}
if (!process.argv.includes('--videos-only')) for(const mobile of [false,true]){
 await c.send('Emulation.setDeviceMetricsOverride',{width:mobile?390:1440,height:mobile?844:900,deviceScaleFactor:1,mobile});
 const suffix=mobile?'-mobile':'';
 await look('sidebar','forest');await page('overview');await shot('overview'+suffix);
 await look('topbar','homedroid-light');await shot('overview-light'+suffix);
 await look('rail','midnight');await shot('overview-rail'+suffix);
 await look('sidebar','forest');
 for(const p of ['modules','deploys','files','ssh','ai','cluster']){await page(p);if(p==='ssh'){await c.evaluate(`document.querySelector('#termStart').click()`);await sleep(350);}await shot((p==='ssh'?'terminal':p)+suffix);}
 await page('overview');await c.evaluate('openCustomize()');await sleep(200);await shot('customize'+suffix);await c.evaluate(`document.querySelector('#customize').close()`);
}
// Capture actual composited dashboard frames. Encode in a separate browser canvas,
// with MediaRecorder at 30 fps; no ffmpeg or synthetic replacement UI.
await c.send('Emulation.setDeviceMetricsOverride',{width:1280,height:800,deviceScaleFactor:1,mobile:false});
const encoder=await connect();
async function record(name,steps){
 await page('overview');await look('sidebar','forest');
 await encoder.evaluate(`window.frames=[]`);
 await c.send('Page.bringToFront');
 let queue=Promise.resolve();
 c.on('Page.screencastFrame',p=>{c.send('Page.screencastFrameAck',{sessionId:p.sessionId});queue=queue.then(()=>encoder.evaluate(`frames.push({data:${JSON.stringify(p.data)},time:${p.metadata.timestamp}})`));});
 await c.send('Page.startScreencast',{format:'jpeg',quality:76,maxWidth:1280,maxHeight:800,everyNthFrame:1});
 for(const step of steps){await c.evaluate(step);await sleep(1600);}
 await c.send('Page.stopScreencast');await queue;
 console.log(name, await encoder.evaluate('({frames:frames.length,unique:new Set(frames.map(f=>f.data)).size})'));
 if(await encoder.evaluate('new Set(frames.map(f=>f.data)).size')<4) throw new Error('Too few distinct screencast frames');
 await encoder.send('Page.bringToFront');
 const data=await encoder.evaluate(`(async()=>{
 const canvas=document.createElement('canvas');canvas.width=1280;canvas.height=800;
 document.body.append(canvas);const ctx=canvas.getContext('2d');const decoded=await Promise.all(frames.map(async f=>{const img=new Image();img.src='data:image/jpeg;base64,'+f.data;await img.decode();return {...f,img};}));
 const stream=canvas.captureStream(30), chunks=[],rec=new MediaRecorder(stream,{mimeType:'video/webm;codecs=vp9',videoBitsPerSecond:1100000});
 const done=new Promise(r=>{rec.ondataavailable=e=>chunks.push(e.data);rec.onstop=()=>{const reader=new FileReader();reader.onload=()=>r(reader.result.split(',')[1]);reader.readAsDataURL(new Blob(chunks,{type:'video/webm'}));};});
 const start=decoded[0].time;ctx.drawImage(decoded[0].img,0,0,1280,800);rec.start();const t=performance.now();
 const duration=Math.max(6,decoded.at(-1).time-start+0.5)*1000;
 await new Promise(resolve=>{const timer=setInterval(()=>{const elapsed=performance.now()-t;const frame=decoded.findLast(f=>(f.time-start)*1000<=elapsed)||decoded[0];ctx.drawImage(frame.img,0,0,1280,800);if(elapsed>=duration){clearInterval(timer);resolve();}},1000/30);});rec.stop();const result=await done;stream.getTracks().forEach(t=>t.stop());return result;})()`);
 await writeFile(`${out}/dashboard-${name}.webm`,Buffer.from(data,'base64'));console.log(name,Buffer.from(data,'base64').length,'bytes');
}
await record('tour',[`location.hash='overview'`,`location.hash='modules'`,`location.hash='deploys'`,`location.hash='files'`,`location.hash='ssh'`]);
await record('layouts',[`location.hash='overview'`,`prefs.layout.shell='rail';prefs.theme.skin='midnight';savePrefs()`,`prefs.layout.shell='topbar';prefs.theme.skin='homedroid-light';savePrefs()`,`openCustomize()`]);
await encoder.close();
await writeFile('site/media/capture-notes.json',JSON.stringify({captured:'2026-11-25',source:'Local dashboard mock; demo data, not hardware measurements',sizes:['1440x900','390x844'],missingDashboardPages:missing,videos:'Actual CDP screencast frames encoded by Chrome MediaRecorder at 1280x800, 30 fps'},null,2)+'\n');
} finally {await c.close();}
