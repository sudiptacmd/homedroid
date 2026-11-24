import {readFile,writeFile} from 'node:fs/promises';
import {connect} from './cdp.mjs';
const base=process.env.SITE_URL||'http://127.0.0.1:8765';
const c=await connect(base);
try {
 await c.send('Emulation.setDeviceMetricsOverride',{width:1280,height:640,deviceScaleFactor:1,mobile:false});
 const {frameTree}=await c.send('Page.getFrameTree');
 await c.send('Page.setDocumentContent',{frameId:frameTree.frame.id,html:await readFile(new URL('./social.html',import.meta.url),'utf8')});
 await c.evaluate(`Promise.all([...document.images].map(i=>i.decode()))`);
 const {data}=await c.send('Page.captureScreenshot',{format:'png'});
 await writeFile('site/img/social-preview.png',Buffer.from(data,'base64'));
 console.log('Rendered site/img/social-preview.png (1280×640)');
} finally {await c.close();}
