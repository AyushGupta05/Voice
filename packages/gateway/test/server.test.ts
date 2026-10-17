import { tools as toolDefinitions } from '../src/tools.js';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';
import WebSocket from 'ws';
import { startServer } from '../src/server.js';

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
