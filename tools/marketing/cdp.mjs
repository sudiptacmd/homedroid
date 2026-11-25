// Dependency-free Chrome DevTools Protocol client (Node 22+).
export const sleep = ms => new Promise(r => setTimeout(r, ms));
export async function connect(url = 'about:blank') {
  const endpoint = process.env.CDP_URL || 'http://127.0.0.1:9227';
  const target = await (await fetch(`${endpoint}/json/new?${encodeURIComponent(url)}`, {method:'PUT'})).json();
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve,reject) => { ws.onopen=resolve; ws.onerror=reject; });
  let id=0; const pending=new Map(), events=new Map();
  ws.onmessage=({data})=>{const m=JSON.parse(data); if(m.id){const p=pending.get(m.id); pending.delete(m.id); m.error?p?.reject(new Error(JSON.stringify(m.error))):p?.resolve(m.result);}else events.get(m.method)?.(m.params);};
  const send=(method,params={})=>new Promise((resolve,reject)=>{pending.set(++id,{resolve,reject});ws.send(JSON.stringify({id,method,params}));});
  const evaluate=async expression=>{const r=await send('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});if(r.exceptionDetails)throw new Error(JSON.stringify(r.exceptionDetails));return r.result.value;};
  await send('Page.enable'); await send('Runtime.enable');
  return {send,evaluate,on:(event,fn)=>events.set(event,fn),close:async()=>{ws.close(); await fetch(`${endpoint}/json/close/${target.id}`);}};
}
