import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { randomBytes, timingSafeEqual } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { WebSocketServer, WebSocket } from 'ws';
import { QuPathAdapter } from './bridge.js';
import { Knowledge } from './knowledge.js';
import { Runtime } from './runtime.js';
import { tools, type Arguments } from './tools.js';
import { fundingStatus } from './funding.js';

async function tokenFile(path:string) {
  try {return (await readFile(path,'utf8')).trim();}
  catch {const token=randomBytes(32).toString('hex');await writeFile(path,token+'\n',{mode:0o600,flag:'wx'});return token;}
}
function equal(value:string|undefined,token:string) {
  const actual=Buffer.from(value??'');const expected=Buffer.from(`Bearer ${token}`);
  return actual.length===expected.length && timingSafeEqual(actual,expected);
}
async function body(req:IncomingMessage) {
  const chunks:Buffer[]=[];let length=0;
  for await (const chunk of req) {length+=chunk.length;if(length>256*1024) throw new Error('Request exceeds 256 KiB.');chunks.push(chunk);}
  return JSON.parse(Buffer.concat(chunks).toString('utf8')) as Record<string,unknown>;
}
function json(res:ServerResponse,status:number,value:unknown) {res.writeHead(status,{'Content-Type':'application/json','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});res.end(JSON.stringify(value));}
export async function startServer(root:string,port=49555) {
  const state=join(root,'.local/state');await mkdir(state,{recursive:true,mode:0o700});
  const gatewayToken=await tokenFile(join(state,'gateway-token'));const companionToken=await tokenFile(join(state,'companion-token'));
  const knowledge=new Knowledge();await knowledge.load(root);
  const runtime=new Runtime(new QuPathAdapter(root),knowledge,state);
  const peers=new Set<WebSocket>();
  const emit=(event:Record<string,unknown>)=>{for(const peer of peers) if(peer.readyState===WebSocket.OPEN)peer.send(JSON.stringify(event));};
  runtime.on('action',action=>emit({type:'action',action}));
  runtime.on('tool',event=>emit({type:'tool',...event}));
  const server=createServer(async(req,res)=>{
    try {
      if(req.headers.origin){json(res,403,{error:'Browser-origin requests are not permitted.'});return;}
      const host=req.headers.host?.split(':')[0];
      if(!['127.0.0.1','localhost'].includes(host??'')){json(res,403,{error:'Loopback host required.'});return;}
      const companion=equal(req.headers.authorization,companionToken);
      if(!companion&&!equal(req.headers.authorization,gatewayToken)){json(res,401,{error:'Unauthorized'});return;}
      const url=new URL(req.url??'/','http://127.0.0.1');
      if(req.method==='GET'&&url.pathname==='/health') {let context;let bridgeError;try{context=await runtime.adapter.execute('context');}catch(error){bridgeError=String(error);}json(res,200,{application:'Luma',tools:tools.length,voice:await fundingStatus(root),context,bridgeError,actions:[...runtime.actions.values()].slice(-30)});return;}
      if(req.method==='GET'&&url.pathname==='/tools'){json(res,200,{tools});return;}
      if(req.method==='POST'&&url.pathname==='/tools/call'){const request=await body(req);if(typeof request.name!=='string'||!request.arguments||typeof request.arguments!=='object'||Array.isArray(request.arguments))throw new Error('Expected name and arguments object.');json(res,200,{result:await runtime.call(request.name,request.arguments as Arguments)});return;}
      if(req.method==='POST'&&/^\/approvals\/[a-f0-9-]+$/.test(url.pathname)){
        if(!companion){json(res,403,{error:'Only the human companion can decide approvals.'});return;}
        const request=await body(req);if(typeof request.approved!=='boolean')throw new Error('Expected approved boolean.');
        json(res,200,{action:await runtime.decide(url.pathname.split('/').pop()!,request.approved)});return;
      }
      json(res,404,{error:'Not found'});
    }catch(error){json(res,400,{error:error instanceof Error?error.message:String(error)});}
  });
  const wss=new WebSocketServer({noServer:true,maxPayload:256*1024});
  server.on('upgrade',(req,socket,head)=>{
    if(req.headers.origin || req.url!=='/events' || !equal(req.headers.authorization,companionToken)) {socket.write('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n');socket.destroy();return;}
    wss.handleUpgrade(req,socket,head,ws=>wss.emit('connection',ws,req));
  });
  wss.on('connection',ws=>{
    peers.add(ws);
    ws.on('message',raw=>{
      void (async()=>{
        const event=JSON.parse(raw.toString());
        if(event.type==='start_voice') ws.send(JSON.stringify({type:'error',message:(await fundingStatus(root)).reason}));
        else if(event.type==='text')ws.send(JSON.stringify({type:'error',message:'Conversational execution needs a funded voice/model connection. Quick actions and MCP tools work locally.'}));
        else if(event.type==='stop_voice')ws.send(JSON.stringify({type:'voice_state',state:'idle'}));
      })().catch(error=>ws.send(JSON.stringify({type:'error',message:error.message})));
    });
    ws.on('error',()=>peers.delete(ws));ws.on('close',()=>peers.delete(ws));
    ws.send(JSON.stringify({type:'connected',tools:tools.length}));
  });
  await new Promise<void>((resolve,reject)=>{server.once('error',reject);server.listen(port,'127.0.0.1',()=>resolve());});
  return {server,runtime,close:async()=>{for(const peer of peers)peer.close();wss.close();await new Promise<void>((resolve,reject)=>server.close(error=>error?reject(error):resolve()));}};
}
if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href) {
  const root=resolve(process.env.LUMA_ROOT??process.cwd());
  startServer(root).then(()=>console.log('Luma gateway listening on http://127.0.0.1:49555 (authenticated, local only)')).catch(error=>{console.error(error.message);process.exitCode=1;});
}
