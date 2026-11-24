// Static checks for the exact directory uploaded by the Pages workflow.
import fs from 'node:fs';
import path from 'node:path';
const root=path.resolve('site'), origin='https://homedroid.seltiv.com';
const walk=dir=>fs.readdirSync(dir,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(path.join(dir,e.name)):[path.join(dir,e.name)]);
const files=walk(root), pages=files.filter(f=>f.endsWith('.html'));
const errors=[];let schemas=0,links=0;
const contents=new Map(pages.map(p=>[p,fs.readFileSync(p,'utf8')]));
const ids=new Map([...contents].map(([p,s])=>[p,new Set([...s.matchAll(/\bid="([^"]+)"/g)].map(m=>m[1]))]));
const fail=(file,msg)=>errors.push(`${path.relative(root,file)}: ${msg}`);
function resolveLink(file,raw){
 if(!raw||/^(?:mailto|tel|data|javascript):/.test(raw))return;
 const base=origin+'/'+path.relative(root,file).replaceAll(path.sep,'/');
 const url=new URL(raw.replaceAll('&amp;','&'),base);
 if(url.origin!==origin)return;
 links++;
 let target=path.join(root,decodeURIComponent(url.pathname));
 if(fs.existsSync(target)&&fs.statSync(target).isDirectory())target=path.join(target,'index.html');
 if(!fs.existsSync(target))return fail(file,`missing internal link ${raw}`);
 if(url.hash&&ids.has(target)&&!ids.get(target).has(decodeURIComponent(url.hash.slice(1))))fail(file,`missing fragment ${raw}`);
}
for(const [file,s] of contents){
 if((s.match(/<h1\b/g)||[]).length!==1)fail(file,'must have one H1');
 if(!/<html lang="en">/.test(s))fail(file,'missing document language');
 if(!/<title>[^<]+<\/title>/.test(s))fail(file,'missing title');
 const allIds=[...s.matchAll(/\bid="([^"]+)"/g)].map(m=>m[1]);
 if(new Set(allIds).size!==allIds.length)fail(file,'duplicate IDs');
 if(!file.endsWith('404.html')){
  const route='/'+path.relative(root,file).replaceAll(path.sep,'/').replace(/index\.html$/,'');
  if(!s.includes(`<link rel="canonical" href="${origin+route}">`))fail(file,'wrong canonical');
  if(!/<meta name="description" content="[^"]+">/.test(s))fail(file,'missing description');
 }
 for(const m of s.matchAll(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/g)){
  try{const data=JSON.parse(m[1]);schemas++;if(!data['@context']||!data['@type'])fail(file,'incomplete schema');if(data['@type']==='SoftwareApplication'&&data.softwareVersion!=='0.9.5-beta')fail(file,'old software version');}catch(e){fail(file,'invalid JSON-LD '+e.message);}
 }
 for(const m of s.matchAll(/\b(?:href|src|srcset|poster)="([^"]+)"/g))resolveLink(file,m[1]);
 for(const m of s.matchAll(/<img\b[^>]*>/g))if(!/\balt="[^"]*"/.test(m[0])||!m[0].includes('width=')||!m[0].includes('height='))fail(file,'image missing alt/dimensions');
 for(const m of s.matchAll(/<video\b[^>]*>/g))if(!m[0].includes('preload="none"')||m[0].includes('autoplay'))fail(file,'media must load on request');
 if(/fonts\.googleapis|fonts\.gstatic/.test(s))fail(file,'third-party fonts');
}
const sitemap=fs.readFileSync(path.join(root,'sitemap.xml'),'utf8');
for(const m of sitemap.matchAll(/<loc>([^<]+)<\/loc>/g))resolveLink(path.join(root,'sitemap.xml'),m[1]);
if((sitemap.match(/<lastmod>/g)||[]).length!==pages.length-1)errors.push('Sitemap must date every indexable HTML page');
for(const name of ['site.webmanifest','media/capture-notes.json'])JSON.parse(fs.readFileSync(path.join(root,name),'utf8'));
for(const icon of JSON.parse(fs.readFileSync(path.join(root,'site.webmanifest'),'utf8')).icons)resolveLink(path.join(root,'site.webmanifest'),icon.src);
if(fs.readFileSync(path.join(root,'CNAME'),'utf8').trim()!=='homedroid.seltiv.com')errors.push('wrong CNAME');
const banned=/\b(?:unleash\w*|unlock the power|seamless\w*|effortless\w*|elevate|supercharge\w*|revolutionize\w*|game.changer|cutting.edge|robust|leverage|empower\w*|harness|dive in|deep dive|in today.s (?:world|digital age)|whether you.re|look no further|the possibilities are endless|it.s not just|say goodbye to|transform your|next.level|all.in.one solution|at your fingertips|peace of mind)\b/gi;
for(const file of [...pages,path.join(root,'llms.txt'),path.join(root,'llms-full.txt'),path.resolve('README.md')]){
 const s=fs.readFileSync(file,'utf8');
 if(s.includes('sudiptacmd.github.io/homedroid'))fail(file,'old website origin');
 const text=s.replace(/<script[\s\S]*?<\/script>/g,'').replace(/<[^>]*>/g,' ');
 const hits=text.match(banned);if(hits)fail(file,'banned copy: '+hits.join(', '));
}
// README local file targets (Markdown links and HTML screenshots).
const readme=fs.readFileSync('README.md','utf8');
for(const m of readme.matchAll(/(?:\]\(([^)]+)\)|src="([^"]+)")/g)){
 const raw=m[1]||m[2];if(/^(?:https?:|#)/.test(raw))continue;
 if(!fs.existsSync(raw.split('#')[0]))errors.push('README missing local target: '+raw);
}
for(const f of files.filter(f=>/dashboard-.*\.webm$/.test(f)))if(fs.statSync(f).size>=3_000_000)fail(f,'video exceeds 3 MB');
if(errors.length){console.error(errors.join('\n'));process.exit(1)}
console.log(`PASS: ${pages.length} HTML pages, ${schemas} JSON-LD blocks parsed, ${links} local links/assets/fragments; metadata, README targets, media budgets and copy checks.`);
