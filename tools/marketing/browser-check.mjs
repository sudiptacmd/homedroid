import assert from 'node:assert/strict';
import {mkdir,writeFile,stat} from 'node:fs/promises';
import {connect,sleep} from './cdp.mjs';
const base=process.env.SITE_URL||'http://127.0.0.1:8765';
const out=process.env.QA_DIR||'/tmp/homedroid-site-qa';await mkdir(out,{recursive:true});
const c=await connect();const errors=[];const checks=[];
c.on('Runtime.exceptionThrown',e=>errors.push(e.exceptionDetails.text));
await c.send('Network.enable');c.on('Network.responseReceived',e=>{if(e.response.status>=400)errors.push(`${e.response.status} ${e.response.url}`);});
async function visit(route){await c.send('Page.navigate',{url:base+route});await sleep(180);await c.evaluate(`Promise.all([...document.images].map(i=>{i.loading='eager';return i.decode()}))`);}
async function screenshot(name,full=false){const p={format:'png',captureBeyondViewport:full};if(full){const {cssContentSize:r}=await c.send('Page.getLayoutMetrics');p.clip={x:0,y:0,width:r.width,height:r.height,scale:1};}await writeFile(`${out}/${name}.png`,Buffer.from((await c.send('Page.captureScreenshot',p)).data,'base64'));}
try{
for(const width of [1440,390,320]){
 await c.send('Emulation.setDeviceMetricsOverride',{width,height:width===1440?900:844,deviceScaleFactor:1,mobile:width<500});
 for(const route of ['/','/desktop/','/macropads/','/dock/','/compare/','/faq/','/404.html']){
  await visit(route);
  const result=await c.evaluate(`({width:innerWidth,scroll:document.documentElement.scrollWidth,h1:document.querySelectorAll('h1').length,broken:[...document.images].filter(i=>!i.naturalWidth).map(i=>i.src),fonts:performance.getEntriesByType('resource').filter(e=>/googleapis|gstatic/.test(e.name)).length})`);
  assert.ok(result.scroll<=result.width,`${width} ${route} horizontal overflow`);assert.equal(result.h1,1);assert.equal(result.broken.length,0);assert.equal(result.fonts,0);
  checks.push({width,route,...result});
  if(width!==320)await screenshot((route==='/'?'home':route.replaceAll('/',''))+'-'+width,route==='/'&&width===1440);
 }
}
await c.send('Emulation.setDeviceMetricsOverride',{width:1440,height:900,deviceScaleFactor:1,mobile:false});await visit('/');
await c.evaluate(`document.querySelector('[role=tab]').focus()`);
await c.send('Input.dispatchKeyEvent',{type:'keyDown',key:'ArrowRight',code:'ArrowRight',windowsVirtualKeyCode:39});
assert.equal(await c.evaluate(`document.activeElement.dataset.tab`),'layouts');
assert.equal(await c.evaluate(`document.querySelector('[data-panel=layouts]').hidden`),false);
await c.evaluate(`document.querySelector('[data-tab=apps]').click()`);
assert.equal(await c.evaluate(`document.querySelector('[data-panel=apps]').hidden`),false);
await c.evaluate(`document.querySelector('summary').click()`);assert.equal(await c.evaluate(`document.querySelector('details').open`),true);
await c.send('Emulation.setEmulatedMedia',{features:[{name:'prefers-reduced-motion',value:'reduce'}]});
assert.equal(await c.evaluate(`getComputedStyle(document.documentElement).scrollBehavior`),'auto');
assert.equal(await c.evaluate(`document.querySelectorAll('video[autoplay]').length`),0);
await c.send('Emulation.setScriptExecutionDisabled',{value:true});await visit('/');
assert.equal(await c.evaluate(`document.querySelectorAll('[data-panel]:not([hidden])').length`),6);
assert.equal(await c.evaluate(`getComputedStyle(document.querySelector('.tablist')).display`),'none');
assert.equal(await c.evaluate(`!!document.querySelector('#download a[href$="arm64-v8a.apk"]')`),true);
await c.send('Emulation.setScriptExecutionDisabled',{value:false});
for(const [platform,ua,expected] of [['Win32','Windows NT','windows'],['MacIntel','Macintosh','macos'],['Linux x86_64','Linux','linux'],['Linux armv8l','Android','']]){
 const {identifier}=await c.send('Page.addScriptToEvaluateOnNewDocument',{source:`Object.defineProperty(navigator,'userAgentData',{value:undefined});Object.defineProperty(navigator,'platform',{value:${JSON.stringify(platform)}});Object.defineProperty(navigator,'userAgent',{value:${JSON.stringify(ua)}})`});
 await visit('/desktop/');const selected=await c.evaluate(`[...document.querySelectorAll('.download.detected')].map(a=>a.dataset.os)`);assert.ok(selected.every(x=>x===expected));assert.equal(selected.length,expected==='linux'?2:expected?1:0);await c.send('Page.removeScriptToEvaluateOnNewDocument',{identifier});
}
await visit('/');await c.send('Page.bringToFront');
const videoInfo=[];
for(const file of ['dashboard-tour.webm','dashboard-layouts.webm']){
 const info=await c.evaluate(`(async()=>{const v=document.createElement('video');v.src='/media/${file}';v.muted=true;v.preload='auto';document.body.replaceChildren(v);await new Promise((r,j)=>{v.onloadeddata=r;v.onerror=j});const canvas=document.createElement('canvas');canvas.width=160;canvas.height=100;const ctx=canvas.getContext('2d');const frames=[];await v.play();for(let i=0;i<3;i++){await new Promise(r=>setTimeout(r,1600));await new Promise(r=>v.requestVideoFrameCallback(r));ctx.drawImage(v,0,0,160,100);frames.push(canvas.toDataURL());}v.pause();return {duration:v.duration,width:v.videoWidth,height:v.videoHeight,distinct:new Set(frames).size}})()`);
 assert.ok(info.duration>=5&&info.duration<=15,`${file} duration ${info.duration}`);assert.ok(info.distinct>=2,`${file} must change frames`);assert.equal(info.width,1280);assert.ok((await stat('site/media/'+file)).size<3_000_000);videoInfo.push({file,...info});await screenshot(file.replace('.webm','')+'-frame');
}
assert.deepEqual(errors,[]);
await writeFile(`${out}/results.json`,JSON.stringify({checks,videoInfo,keyboardTabs:'passed',noJavaScript:'passed',osDetection:'passed',reducedMotion:'passed',runtimeErrors:errors},null,2));console.log(`PASS: ${checks.length} responsive page checks; keyboard tabs, FAQ, OS detection, reduced motion, no-JS content, video decode/duration/changing frames. Screenshots: ${out}`);console.log(videoInfo);
}finally{await c.close();}
