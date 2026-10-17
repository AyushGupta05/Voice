import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { PublicFixtureRuntime } from '../../../script/public-text-fixture.js';
import { Knowledge } from '../src/knowledge.js';
import { ResponsesBackend } from '../src/backend.js';
import type { Context } from '../src/bridge.js';
const baseline=():Context=>({contextKey:'public-fixture-identity',image:{name:'OS-2.ndpi',width:126976,height:73728} as any,counts:{},selection:[],selectionCount:0,viewer:{centerX:48000,centerY:24000,downsample:4}});

test('a switch away from the public fixture prevents the next model input and any focus dispatch',async()=>{
 const directory=await mkdtemp(join(tmpdir(),'luma-public-model-'));let current=baseline();let requests=0;let focuses=0;
 const runtime=new PublicFixtureRuntime({execute:async operation=>{if(operation==='context')return structuredClone(current);focuses++;return{};}},new Knowledge(),directory,baseline());
 const backend=new ResponsesBackend(runtime,{complete:async request=>{
  requests++;assert(!JSON.stringify(request).includes('Private research'));
  current={...baseline(),contextKey:'private-image',image:{name:'Private research'}};
  return{output:[{type:'function_call',call_id:'focus',name:'qupath_focus',arguments:'{"x":51100,"y":27100,"downsample":3}'}]};
 }},'synthetic',()=>{});
 try{
  await assert.rejects(backend.run({id:'public-fixture',offsetMs:0,context:baseline(),signal:new AbortController().signal,conversation:()=>({fragments:[],truncated:false}),update:()=>{}}),/public fixture/);
  assert.equal(requests,1);assert.equal(focuses,0);assert.equal(runtime.actions.size,0);
 }finally{await rm(directory,{recursive:true,force:true});}
});

test('same metadata on a different image identity is refused before transmitting context',async()=>{
 const directory=await mkdtemp(join(tmpdir(),'luma-public-identity-'));
 const runtime=new PublicFixtureRuntime({execute:async()=>({...baseline(),contextKey:'another-open-copy'})},new Knowledge(),directory,baseline());
 try{await assert.rejects(runtime.call('qupath_context',{}),/public fixture/);await assert.rejects(runtime.call('qupath_annotate',{x:0,y:0,width:1,height:1}),/context and exact focus only/);assert.equal(runtime.actions.size,0);}finally{await rm(directory,{recursive:true,force:true});}
});

test('public model requests exclude private checkpoints, project state, dispatch errors and unknown fields',async()=>{
 const directory=await mkdtemp(join(tmpdir(),'luma-public-allowlist-'));let requests=0;
 const raw={...baseline(),project:{name:'PRIVATE_PROJECT',uri:'file:///PRIVATE_PATH'},checkpoints:[{label:'PRIVATE_CHECKPOINT',counts:{'class:PRIVATE_MARKER':12}}],lastDispatch:{error:'PRIVATE_ERROR'},extra:'PRIVATE_UNKNOWN'};
 const {publicFixtureContext}=await import('../../../script/public-text-fixture.js');
 const runtime=new PublicFixtureRuntime({execute:async operation=>operation==='context'?structuredClone(raw):{x:51100,y:27100,downsample:3,pointer:{label:'PRIVATE_POINTER'},extra:'PRIVATE_FOCUS'}},new Knowledge(),directory,raw);
 const backend=new ResponsesBackend(runtime,{complete:async request=>{
  requests++;assert(!JSON.stringify(request).includes('PRIVATE_'),'No session-wide metadata may reach the model');
  if(requests===1)return{output:[{type:'function_call',call_id:'public-focus',name:'qupath_focus',arguments:'{"x":51100,"y":27100,"downsample":3}'}]};
  return{output:[],output_text:'Focused the public fixture.'};
 }},'synthetic',()=>{});
 try{
  await backend.run({id:'allowlisted-public-fixture',offsetMs:0,context:publicFixtureContext(raw),signal:new AbortController().signal,conversation:()=>({fragments:[],truncated:false}),update:()=>{}});
  assert.equal(requests,2);assert.equal(runtime.actions.size,0);
 }finally{await rm(directory,{recursive:true,force:true});}
});
