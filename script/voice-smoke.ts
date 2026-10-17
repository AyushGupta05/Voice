/** GPT-Live protocol integration fixture: synthetic audio and scripted model responses.
 * Performs only read/view operations on public OS-2 in the real application.
 * This is NOT live AI, speech recognition, microphone or provider verification.
 */
import assert from 'node:assert/strict';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { once } from 'node:events';
import { WebSocket, WebSocketServer } from 'ws';
import { Runtime } from '../packages/gateway/src/runtime.js';
import { tools } from '../packages/gateway/src/tools.js';
import { Knowledge } from '../packages/gateway/src/knowledge.js';
import { LiveSession } from '../packages/gateway/src/live.js';
import { ResponsesBackend, type BackendClient } from '../packages/gateway/src/backend.js';
import type { Adapter } from '../packages/gateway/src/bridge.js';

if(!process.argv.includes('--public-fixture'))throw new Error('Pass --public-fixture for the scripted, display-only public-slide protocol test.');
const token=(await readFile('.local/state/gateway-token','utf8')).trim();
const adapter:Adapter={execute:async(operation,args={})=>{
  const tool=tools.find(tool=>tool.operation===operation&&['read','view'].includes(tool.risk));
  if(!tool)throw new Error('This protocol smoke fixture permits read/view operations only.');
  const response=await fetch('http://127.0.0.1:49555/tools/call',{method:'POST',headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},body:JSON.stringify({name:tool.name,arguments:args}),signal:AbortSignal.timeout(10000)});
  const value=await response.json() as any;if(!response.ok)throw new Error(value.error);return value.result;
}};
const initial=await adapter.execute('context') as any;
assert.equal(initial.image?.name,'OS-2.ndpi');assert.equal(initial.image?.width,126976);assert.equal(initial.image?.height,73728);
await adapter.execute('focus',{x:48000,y:24000,downsample:4});
const arranged=await adapter.execute('context') as any;assert.equal(arranged.viewer.centerX,48000);assert.equal(arranged.viewer.centerY,24000);
const started=performance.now();let modelTurns=0;let audioChunks=0;const displayed:string[]=[];
const runtime=new Runtime(adapter,new Knowledge(),'.local/voice-smoke');
const client:BackendClient={complete:async()=>{
  if(++modelTurns===1)return {output:[{type:'function_call',call_id:'focus-public-slide',name:'qupath_focus',arguments:JSON.stringify({x:51100,y:27100,downsample:3})}]};
  const actual=await adapter.execute('context') as any;
  assert.equal(actual.viewer.centerX,51100);assert.equal(actual.viewer.centerY,27100);
  return {output:[],output_text:'Focused the real QuPath viewer on the public-slide test coordinates.'};
}};
const backend=new ResponsesBackend(runtime,client,'scripted-local-model',(_id,text)=>displayed.push(text));
const server=new WebSocketServer({host:'127.0.0.1',port:0});await once(server,'listening');
const address=server.address();assert(address&&typeof address==='object');
let succeeded!:()=>void;let failed!:(error:Error)=>void;
const complete=new Promise<void>((resolve,reject)=>{succeeded=resolve;failed=reject;});
server.on('connection',peer=>peer.on('message',raw=>{
  const event=JSON.parse(raw.toString());
  if(event.type==='session.start'){
    peer.send(JSON.stringify({type:'session.started',session:{id:'local-protocol-fixture'}}));
    peer.send(JSON.stringify({type:'session.input_transcript.delta',delta:'Focus on the public-slide test region.',start_ms:0,end_ms:400}));
    peer.send(JSON.stringify({type:'session.delegation.created',offset_ms:400,delegation:{id:'local-delegation',target:'client'}}));
    peer.send(JSON.stringify({type:'session.output_audio.delta',delta:Buffer.alloc(4800).toString('base64')}));
  }
  if(event.type==='session.commentary.append')succeeded();
  if(event.type==='session.close')peer.send(JSON.stringify({type:'session.closed',usage:{synthetic_fixture:true,seconds:0}}));
}));
const session=new LiveSession(adapter,backend,event=>{
  if(event.type==='audio')audioChunks++;
  if(event.type==='backend_error'||event.type==='error')failed(new Error(String(event.message)));
},{maxSessionMs:10000,closeTimeoutMs:1000});
const wire=new WebSocket(`ws://127.0.0.1:${address.port}`,{maxPayload:1024*1024});session.start(wire);
try{
  await Promise.race([complete,new Promise<never>((_resolve,reject)=>{const timer=setTimeout(()=>reject(new Error('Local protocol fixture timed out.')),8000);timer.unref();})]);
  assert.equal(modelTurns,2);assert.equal(audioChunks,1);assert.equal(displayed.length,1);
  const final=await adapter.execute('context') as any;assert.deepEqual(final.counts,initial.counts);
  const report=await session.close();assert.equal(report.finalUsageReceived,true);
  const evidence={protocol:'GPT-Live client delegation',provider:'local WebSocket fixture only',backend:'scripted local response; no AI inference',audio:'synthetic PCM; no microphone or speaker',application:'real QuPath',operation:'display-only focus',center:[final.viewer.centerX,final.viewer.centerY],hierarchyUnchanged:true,modelTurns,audioChunks,elapsedFixtureMs:Math.round(performance.now()-started),timingBoundary:'local glue only; not model or voice latency',status:'passed'};
  await mkdir('.local/evidence',{recursive:true});await writeFile('.local/evidence/voice-protocol.json',JSON.stringify(evidence,null,2)+'\n');console.log(JSON.stringify(evidence,null,2));
}finally{await session.close();for(const peer of server.clients)peer.terminate();await new Promise<void>(resolve=>server.close(()=>resolve()));}
