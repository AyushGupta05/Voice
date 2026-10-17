import { tools as toolDefinitions } from '../src/tools.js';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';
import WebSocket from 'ws';
import { startServer } from '../src/server.js';

test('a competing voice start reports its own request ID so the bar can leave Connecting', async () => {
  const root = await mkdtemp(join(tmpdir(), 'luma-voice-owner-'));
  const gateway = await startServer(root, 0, { speechProvider: {
    transcribe: async (_audio, signal, callbacks) => { callbacks.inputReady(); await new Promise<void>(resolve => signal.addEventListener('abort', () => resolve(), { once: true })); },
    speak: async () => new Uint8Array([0, 0]),
  } });
  const state = join(root, '.local/state'), expiresAt = new Date(Date.now() + 60_000).toISOString(), accountHash = 'a'.repeat(64);
  const { BEDROCK_MODEL } = await import('../src/aws-credit-proof.js');
  await writeFile(join(state, 'text-funding.json'), JSON.stringify({ version: 1, accountHash, expiresAt, model: BEDROCK_MODEL, maximumReservedUSD: 200 }));
  await writeFile(join(state, 'speech-funding.json'), JSON.stringify({ version: 1, route: 'aws-transcribe-polly-openai', accountHash, expiresAt, maximumReservedUSD: 200, maximumSessionSeconds: 180 }));
  const address = gateway.server.address(); assert(address && typeof address === 'object');
  const token = (await readFile(join(state, 'companion-token'), 'utf8')).trim();
  const connect = () => { const ws = new WebSocket(`ws://127.0.0.1:${address.port}/events`, { headers: { Authorization: `Bearer ${token}` } }); const events: any[] = []; ws.on('message', raw => events.push(JSON.parse(raw.toString()))); return { ws, events }; };
  const first = connect(), second = connect();
  const until = async (events: any[], type: string) => { const deadline = Date.now() + 2000; while (!events.some(event => event.type === type)) { assert(Date.now() < deadline, `Expected ${type}`); await new Promise(resolve => setTimeout(resolve, 5)); } return events.find(event => event.type === type); };
  try {
    await Promise.all([once(first.ws, 'open'), once(second.ws, 'open')]);
    first.ws.send(JSON.stringify({ type: 'start_voice', requestId: '11111111-1111-4111-8111-111111111111' }));
    await until(first.events, 'voice_state');
    const requestId = '22222222-2222-4222-8222-222222222222';
    second.ws.send(JSON.stringify({ type: 'start_voice', requestId }));
    const error = await until(second.events, 'voice_error');
    assert.equal(error.requestId, requestId); assert.match(error.message, /already active/);
  } finally { first.ws.terminate(); second.ws.terminate(); await gateway.close(); await rm(root, { recursive: true, force: true }); }
});

test('loopback gateway separates model tools from human approval and blocks browser origins',async()=>{
  const root=await mkdtemp(join(tmpdir(),'luma-server-'));const gateway=await startServer(root,0);
  const address=gateway.server.address();assert(address&&typeof address==='object');
  const base=`http://127.0.0.1:${address.port}`;
  const model=(await readFile(join(root,'.local/state/gateway-token'),'utf8')).trim();
  const human=(await readFile(join(root,'.local/state/companion-token'),'utf8')).trim();
  try {
    assert.equal((await fetch(base+'/tools')).status,401);
    assert.equal((await fetch(base+'/tools',{headers:{Authorization:`Bearer ${model}`,Origin:'https://attacker.example'}})).status,403);
    const tools=await fetch(base+'/tools',{headers:{Authorization:`Bearer ${model}`}});assert.equal(tools.status,200);
    assert.deepEqual((await tools.json()).tools.map((tool:{name:string})=>tool.name),toolDefinitions.map(tool=>tool.name));
    const denied=await fetch(base+'/approvals/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',{method:'POST',headers:{Authorization:`Bearer ${model}`,'Content-Type':'application/json'},body:'{"approved":true}'});assert.equal(denied.status,403);
    const ws=new WebSocket(`ws://127.0.0.1:${address.port}/events`,{headers:{Authorization:`Bearer ${human}`}});
    await once(ws,'open');
    const result=once(ws,'message');ws.send(JSON.stringify({type:'start_voice',requestId:'11111111-1111-4111-8111-111111111111'}));
    const [raw]=await result;const event=JSON.parse(raw.toString());
    // The connection greeting may race with the start request; wait for the funding response explicitly.
    const message=event.type==='error'?event:JSON.parse((await once(ws,'message'))[0].toString());
    assert.equal(message.type,'error');assert.match(message.message,/AWS-credit-covered/);
    ws.close();await once(ws,'close');
  }finally{await gateway.close();await rm(root,{recursive:true,force:true});}
});

test('authenticated companion text uses the shared Runtime and returns its actual view result over WebSocket',async()=>{
  const {Runtime}=await import('../src/runtime.js');const {Knowledge}=await import('../src/knowledge.js');
  const root=await mkdtemp(join(tmpdir(),'luma-text-server-'));let modelCalls=0;let focused=false;
  const runtime=new Runtime({execute:async operation=>operation==='context'?{contextKey:'fixture',image:{name:'Synthetic'}}:(focused=true,{x:10,y:20})},new Knowledge(),join(root,'.local/state'));
  const gateway=await startServer(root,0,{runtime,textModel:'synthetic',textClient:{complete:async request=>{
    assert.equal(request.model,'synthetic');
    if(++modelCalls===1)return{output:[{type:'function_call',call_id:'focus',name:'qupath_focus',arguments:'{"x":10,"y":20}'}]};
    assert(focused);const result=request.input.find(item=>item.type==='function_call_output')!;
    assert.deepEqual(JSON.parse(String(result.output)),{x:10,y:20});
    return{output:[],output_text:'The actual synthetic view result is ready.'};
  }}});
  const address=gateway.server.address();assert(address&&typeof address==='object');
  const human=(await readFile(join(root,'.local/state/companion-token'),'utf8')).trim();
  const ws=new WebSocket(`ws://127.0.0.1:${address.port}/events`,{headers:{Authorization:`Bearer ${human}`}});const events:any[]=[];ws.on('message',raw=>events.push(JSON.parse(raw.toString())));
  try{
    await once(ws,'open');ws.send(JSON.stringify({type:'text',text:'Focus this synthetic view.',model:'untrusted-provider-choice'}));
    const deadline=Date.now()+2000;while(!events.some(event=>event.type==='text_result')){assert(Date.now()<deadline);await new Promise(resolve=>setTimeout(resolve,5));}
    assert.equal(events.find(event=>event.type==='text_result').text,'The actual synthetic view result is ready.');assert.equal(modelCalls,2);assert.equal(runtime.actions.size,0);
    ws.close();await once(ws,'close');
  }finally{ws.terminate();await gateway.close();await rm(root,{recursive:true,force:true});}
});


test('authenticated health settles idle proposals without executing a scientific operation',async()=>{
  const {Runtime}=await import('../src/runtime.js');const {Knowledge}=await import('../src/knowledge.js');
  const root=await mkdtemp(join(tmpdir(),'luma-idle-health-'));let mutations=0;
  const runtime=new Runtime({execute:async operation=>{
    if(operation==='context')return{contextKey:'fixture',image:{name:'Synthetic'}};
    mutations++;throw new Error('No operation should execute');
  }},new Knowledge(),join(root,'.local/state'));
  const gateway=await startServer(root,0,{runtime});
  const address=gateway.server.address();assert(address&&typeof address==='object');
  try{
    const proposal=await runtime.call('qupath_annotate',{x:0,y:0,width:20,height:20}) as {actionId:string};
    runtime.actions.get(proposal.actionId)!.expiresAt=new Date(0).toISOString();
    const token=(await readFile(join(root,'.local/state/gateway-token'),'utf8')).trim();
    const response=await fetch(`http://127.0.0.1:${address.port}/health`,{headers:{Authorization:`Bearer ${token}`}});
    assert.equal(response.status,200);const health=await response.json();
    assert.equal(health.actions[0].id,proposal.actionId);assert.equal(health.actions[0].status,'expired');
    assert.equal(health.actions.filter((action:{status:string})=>action.status==='pending').length,0);
    assert.equal(mutations,0);assert.equal(health.voice.enabled,false);
  }finally{await gateway.close();await rm(root,{recursive:true,force:true});}
});

test('broadcast tool events stay under the companion bound and keep the rendered fields',async()=>{
  const {compactToolEvent,MAX_TOOL_EVENT_BYTES}=await import('../src/server.js');
  const small={type:'tool',name:'qupath_focus',arguments:{x:1},result:{pointer:{x:1,y:2}}};
  assert.deepEqual(compactToolEvent(small),small);
  const controls=Array.from({length:3000},(_,i)=>({nodeId:`node-${i}`,text:'x'.repeat(40)}));
  const big=compactToolEvent({type:'tool',name:'qupath_ui',arguments:{},result:{windows:controls,highlight:{label:'Apply'},total:3000}});
  assert.equal(big.truncated,true);assert(Buffer.byteLength(JSON.stringify(big))<=MAX_TOOL_EVENT_BYTES);
  assert.deepEqual(big.result,{highlight:{label:'Apply'},total:3000});assert.equal(big.name,'qupath_ui');
  const envelope=compactToolEvent({type:'tool',name:'qupath_detect_cells',arguments:{},result:{status:'completed',result:{counts:{cells:294},log:'y'.repeat(70_000)},error:undefined}});
  assert.deepEqual(envelope.result,{status:'completed',result:{counts:{cells:294}}});
});

test('an identified companion that drops mid-task gets its acknowledgment and result on reconnect',async()=>{
  const {Runtime}=await import('../src/runtime.js');const {Knowledge}=await import('../src/knowledge.js');
  const root=await mkdtemp(join(tmpdir(),'luma-reattach-'));let release!:()=>void;const gate=new Promise<void>(resolve=>{release=resolve;});let modelCalls=0;
  const runtime=new Runtime({execute:async operation=>operation==='context'?{contextKey:'fixture',image:{name:'Synthetic'}}:{x:10,y:20}},new Knowledge(),join(root,'.local/state'));
  const gateway=await startServer(root,0,{runtime,textModel:'synthetic',detachedGraceMs:2000,textClient:{complete:async()=>{
    if(++modelCalls===1)return{output:[{type:'function_call',call_id:'focus',name:'qupath_focus',arguments:'{"x":10,"y":20}'}]};
    await gate;return{output:[],output_text:'The view moved while you were away.'};
  }}});
  const address=gateway.server.address();assert(address&&typeof address==='object');
  const human=(await readFile(join(root,'.local/state/companion-token'),'utf8')).trim();
  const connect=()=>{const ws=new WebSocket(`ws://127.0.0.1:${address.port}/events`,{headers:{Authorization:`Bearer ${human}`}});const events:any[]=[];ws.on('message',raw=>events.push(JSON.parse(raw.toString())));return{ws,events};};
  const until=async(events:any[],type:string)=>{const deadline=Date.now()+3000;while(!events.some(event=>event.type===type)){assert(Date.now()<deadline,`Expected ${type}`);await new Promise(resolve=>setTimeout(resolve,5));}};
  const first=connect();let second:ReturnType<typeof connect>|undefined;
  try{
    await once(first.ws,'open');first.ws.send(JSON.stringify({type:'hello',clientId:'companion-run-0001'}));
    first.ws.send(JSON.stringify({type:'text',text:'Focus this synthetic view.'}));
    await until(first.events,'tool');first.ws.terminate();await once(first.ws,'close');
    await new Promise(resolve=>setTimeout(resolve,50));release();await new Promise(resolve=>setTimeout(resolve,50));
    second=connect();await once(second.ws,'open');second.ws.send(JSON.stringify({type:'hello',clientId:'companion-run-0001'}));
    await until(second.events,'text_result');
    assert.equal(second.events.find(event=>event.type==='reattached').replayed>=1,true);
    assert.equal(second.events.find(event=>event.type==='text_result').text,'The view moved while you were away.');
    assert.equal(second.events.filter(event=>event.type==='task_state'&&event.state==='idle').length,1);
    // A stranger's clientId never receives the buffered conversation.
    const third=connect();await once(third.ws,'open');third.ws.send(JSON.stringify({type:'hello',clientId:'companion-run-0002'}));
    await new Promise(resolve=>setTimeout(resolve,50));assert(!third.events.some(event=>event.type==='reattached'||event.type==='text_result'));third.ws.close();await once(third.ws,'close');
    second.ws.close();await once(second.ws,'close');
  }finally{release();first.ws.terminate();second?.ws.terminate();await gateway.close();await rm(root,{recursive:true,force:true});}
});

test('an unidentified companion still loses its conversation on disconnect and a detached one expires',async()=>{
  const {Runtime}=await import('../src/runtime.js');const {Knowledge}=await import('../src/knowledge.js');
  const root=await mkdtemp(join(tmpdir(),'luma-detach-expiry-'));let started=0;let release!:()=>void;const gate=new Promise<void>(resolve=>{release=resolve;});
  const runtime=new Runtime({execute:async()=>({contextKey:'fixture',image:{name:'Synthetic'}})},new Knowledge(),join(root,'.local/state'));
  const gateway=await startServer(root,0,{runtime,textModel:'synthetic',detachedGraceMs:100,textClient:{complete:async(_request,signal)=>{started++;await Promise.race([gate,new Promise(resolve=>signal.addEventListener('abort',resolve,{once:true}))]);signal.throwIfAborted();return{output:[],output_text:'Late.'};}}});
  const address=gateway.server.address();assert(address&&typeof address==='object');
  const human=(await readFile(join(root,'.local/state/companion-token'),'utf8')).trim();
  try{
    const anonymous=new WebSocket(`ws://127.0.0.1:${address.port}/events`,{headers:{Authorization:`Bearer ${human}`}});await once(anonymous,'open');
    anonymous.send(JSON.stringify({type:'text',text:'Anonymous request.'}));const deadline=Date.now()+2000;while(started<1){assert(Date.now()<deadline);await new Promise(resolve=>setTimeout(resolve,5));}
    anonymous.terminate();await once(anonymous,'close');await new Promise(resolve=>setTimeout(resolve,50));
    const identified=new WebSocket(`ws://127.0.0.1:${address.port}/events`,{headers:{Authorization:`Bearer ${human}`}});await once(identified,'open');
    identified.send(JSON.stringify({type:'hello',clientId:'companion-run-0003'}));identified.send(JSON.stringify({type:'text',text:'Identified request.'}));
    while(started<2){assert(Date.now()<deadline+2000);await new Promise(resolve=>setTimeout(resolve,5));}
    identified.terminate();await once(identified,'close');await new Promise(resolve=>setTimeout(resolve,250));
    release();await new Promise(resolve=>setTimeout(resolve,50));
    const late=new WebSocket(`ws://127.0.0.1:${address.port}/events`,{headers:{Authorization:`Bearer ${human}`}});const events:any[]=[];late.on('message',raw=>events.push(JSON.parse(raw.toString())));await once(late,'open');
    late.send(JSON.stringify({type:'hello',clientId:'companion-run-0003'}));await new Promise(resolve=>setTimeout(resolve,100));
    assert(!events.some(event=>event.type==='reattached'),'An expired detached conversation is not reattached');
    late.close();await once(late,'close');
  }finally{release();await gateway.close();await rm(root,{recursive:true,force:true});}
});

test('health reports the application profile and /tools lists that profile\'s tools',async()=>{
  const {Runtime}=await import('../src/runtime.js');const {Knowledge}=await import('../src/knowledge.js');const {profiles}=await import('../src/apps/index.js');
  for(const profile of [profiles.qupath,profiles.fiji]){
    const root=await mkdtemp(join(tmpdir(),`luma-health-${profile.id}-`));
    const runtime=new Runtime({execute:async operation=>operation==='context'?{contextKey:'fixture',application:profile.displayName}:{}},new Knowledge(profile),join(root,'.local/state'),{profile});
    const gateway=await startServer(root,0,{runtime});
    const address=gateway.server.address();assert(address&&typeof address==='object');
    try{
      const token=(await readFile(join(root,'.local/state/gateway-token'),'utf8')).trim();
      const health=await(await fetch(`http://127.0.0.1:${address.port}/health`,{headers:{Authorization:`Bearer ${token}`}})).json();
      assert.deepEqual(health.app,{id:profile.id,displayName:profile.displayName,bundleId:profile.bundleId,launcherHint:profile.launcherScript});
      assert.equal(health.tools,profile.tools.length);assert.equal(health.context.application,profile.displayName);
      const listed=await(await fetch(`http://127.0.0.1:${address.port}/tools`,{headers:{Authorization:`Bearer ${token}`}})).json();
      assert.deepEqual(listed.tools.map((tool:{name:string})=>tool.name),profile.tools.map(tool=>tool.name));
    }finally{await gateway.close();await rm(root,{recursive:true,force:true});}
  }
});
