import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { TextConversation } from '../src/text-session.js';
import { Runtime, type Action } from '../src/runtime.js';
import { Knowledge } from '../src/knowledge.js';
import type { BackendClient, BackendResponse } from '../src/backend.js';
import { fiji } from '../src/apps/fiji.js';
import type { AppProfile } from '../src/apps/profile.js';

async function eventually(predicate:()=>boolean){const deadline=Date.now()+2000;while(!predicate()){assert(Date.now()<deadline,'Expected text event');await new Promise(resolve=>setTimeout(resolve,5));}}
async function fixture(client:BackendClient){const root=await mkdtemp(join(tmpdir(),'luma-text-session-'));let mutations=0;const events:Record<string,unknown>[]=[];const runtime=new Runtime({execute:async operation=>{if(operation==='context')return{contextKey:'synthetic',image:{name:'Synthetic'}};mutations++;return{created:true};}},new Knowledge(),root);const conversation=new TextConversation(runtime,client,'synthetic',event=>events.push(event));return{conversation,runtime,events,mutations:()=>mutations,clean:()=>rm(root,{recursive:true,force:true})};}

test('text conversation emits a full result and preserves conversation for a follow-up',async()=>{
 let calls=0;
 const f=await fixture({complete:async request=>{
  calls++;assert.equal(request.max_output_tokens,4096);
  if(calls===2){const serialized=JSON.stringify(request.input);assert(serialized.includes('First result.'));assert(serialized.includes('What was the previous result?'));}
  return{output:[],output_text:calls===1?'First result.':'Retained result.'};
 }});
 try{await f.conversation.submit('Read this region.');await f.conversation.submit('What was the previous result?');assert.equal(f.events.filter(event=>event.type==='text_result').length,2);assert.equal(f.events.at(-1)!.state,'idle');assert.equal(f.mutations(),0);}finally{f.conversation.stop();await f.clean();}
});

test('Stop releases a model wait and ignores its late answer without claiming rollback',async()=>{
 let started=false;let release!:(value:BackendResponse)=>void;
 const f=await fixture({complete:async()=>{started=true;return new Promise(resolve=>{release=resolve;});}});
 try{const running=f.conversation.submit('Read this region.');await eventually(()=>started);f.conversation.stop();await running;release({output:[],output_text:'Late obsolete answer.'});await new Promise(resolve=>setImmediate(resolve));assert(!f.events.some(event=>event.type==='text_result'));const stopped=f.events.find(event=>event.reason)!;assert.match(String(stopped.reason),/completed actions and pending human decisions remain/);assert.equal(f.mutations(),0);}finally{f.conversation.stop();await f.clean();}
});

test('a new text request owns the answer and an old task cannot clear its busy state',async()=>{
 let requests=0;let firstStarted=false;let release!:(value:BackendResponse)=>void;
 const f=await fixture({complete:async()=>{if(++requests===1){firstStarted=true;return new Promise(resolve=>{release=resolve;});}return{output:[],output_text:'Corrected result.'};}});
 try{const old=f.conversation.submit('Original request.');await eventually(()=>firstStarted);const latest=f.conversation.submit('Actually, use this corrected request.');await Promise.all([old,latest]);release({output:[],output_text:'Obsolete result.'});await new Promise(resolve=>setImmediate(resolve));const results=f.events.filter(event=>event.type==='text_result');assert.equal(results.length,1);assert.equal(results[0].text,'Corrected result.');const states=f.events.filter(event=>event.type==='task_state');assert.equal(states.filter(event=>event.state==='idle').length,1);assert.equal(states.at(-1)!.taskId,results[0].taskId);}finally{f.conversation.stop();await f.clean();}
});

test('stopping a human-review wait leaves the exact proposal pending and executes nothing',async()=>{
 const f=await fixture({complete:async()=>({output:[{type:'function_call',call_id:'proposal',name:'qupath_annotate',arguments:'{"x":0,"y":0,"width":5,"height":5}'}]})});
 try{const running=f.conversation.submit('Create this synthetic region.');await eventually(()=>f.events.some(event=>event.type==='text_progress'));const action=[...f.runtime.actions.values()][0] as Action;assert.equal(action.status,'pending');f.conversation.stop();await running;assert.equal(action.status,'pending');assert.equal(f.runtime.listenerCount('action'),0);assert.equal(f.mutations(),0);}finally{f.conversation.stop();await f.clean();}
});

async function fastFixture(result:unknown,profile:AppProfile=fiji,client:BackendClient={complete:async()=>{throw new Error('No model call expected.');}}){
 const root=await mkdtemp(join(tmpdir(),'penny-fast-session-'));const events:Record<string,unknown>[]=[];const operations:string[]=[];
 const runtime=new Runtime({execute:async operation=>{
  operations.push(operation);
  if(operation==='context')return{contextKey:'synthetic',image:{name:'Synthetic',channels:1,calibration:{unit:'pixel'}},counts:{roiManager:12}};
  if(result instanceof Error)throw result;
  return result;
 }},new Knowledge(profile),root,{autoApprove:true,profile});
 const conversation=new TextConversation(runtime,client,'synthetic',event=>events.push(event));
 return{conversation,events,operations,runtime,clean:()=>rm(root,{recursive:true,force:true})};
}

test('long-running instant analysis acknowledges before dispatch and finishes with the measured result',async()=>{
 const f=await fastFixture({count:12});let ackAtDispatch=false;
 f.runtime.on('action',()=>{ackAtDispatch=f.events.some(event=>event.type==='text_ack');});
 try{
  await f.conversation.submit('find all the nuclei',true);
  const spoken=f.events.filter(event=>event.type==='text_ack'||event.type==='text_result');
  assert.deepEqual(spoken.map(event=>event.type),['text_ack','text_result']);assert(ackAtDispatch);
  assert.equal(spoken[0].text,'Finding the nuclei.');assert.equal(spoken[1].text,'Found 12 nuclei.');assert.equal(spoken[1].outcome,undefined);
 }finally{f.conversation.stop();await f.clean();}
});

test('a failed long-running instant action reports failure after its acknowledgment',async()=>{
 const f=await fastFixture(new Error('No usable nuclear channel.'));
 try{
  await f.conversation.submit('find all the nuclei',true);
  const spoken=f.events.filter(event=>event.type==='text_ack'||event.type==='text_result');
  assert.deepEqual(spoken.map(event=>event.type),['text_ack','text_result']);
  assert.equal(spoken[1].outcome,'failed');assert.equal(spoken[1].text,'That did not work: No usable nuclear channel.');
 }finally{f.conversation.stop();await f.clean();}
});

test('instant view refusals are marked as failures and do not claim the view changed',async()=>{
 for(const result of [new Error('No image canvas.'),{ok:false,message:'No image canvas.'}]){
  const f=await fastFixture(result);
  try{
   await f.conversation.submit('zoom in',true);
   const final=f.events.find(event=>event.type==='text_result');assert(final);assert.equal(final.outcome,'failed');
   assert.match(String(final.text),/No image canvas/);assert(!String(final.text).includes('Zooming in'));
   assert(!f.events.some(event=>event.type==='text_ack'));
  }finally{f.conversation.stop();await f.clean();}
 }
});

test('a deterministic fast-route refusal emits failure without acknowledging or dispatching an action',async()=>{
 const profile:AppProfile={...fiji,fastRoute:()=>({tool:'fiji_filter_objects',arguments:{minArea:20},reply:'Removing small objects.',refusal:'This image is not calibrated in microns.'})};
 const f=await fastFixture({ok:true},profile);
 try{
  await f.conversation.submit('remove anything smaller than 20 square microns',true);
  assert.deepEqual(f.operations,['context']);assert.equal(f.runtime.actions.size,0);assert(!f.events.some(event=>event.type==='text_ack'));
  const final=f.events.find(event=>event.type==='text_result');assert.equal(final?.outcome,'failed');assert.match(String(final?.text),/not calibrated/);
  assert.equal(f.events.at(-1)?.state,'idle');
 }finally{f.conversation.stop();await f.clean();}
});

test('model-assisted failures retain failure metadata whether the model admits the failure or claims success',async()=>{
 for(const finalText of ['Done.','That did not work: Nuclear channel is blank.']){
  let calls=0;
  const f=await fastFixture({ok:false,message:'Nuclear channel is blank.'},fiji,{complete:async()=>++calls===1?
   {output:[{type:'function_call',call_id:'nuclei',name:'fiji_segment_cells',arguments:'{}'}]}:{output:[],output_text:finalText}});
  try{
   await f.conversation.submit('Analyse the sample.');
   const final=f.events.find(event=>event.type==='text_result');assert.equal(final?.outcome,'failed');
   assert.match(String(final?.text),/Nuclear channel is blank/);
  }finally{f.conversation.stop();await f.clean();}
 }
});

test('instant numeric results speak only the measured outcome without repeating an action phrase',async()=>{
 const cases=[
  {request:'How many cells are left?',result:{n:10,area:{mean:80,median:78}},expected:'10 objects.'},
  {request:'How many cells are left?',result:{n:0,area:{mean:0}},expected:'0 detected objects in this image.'},
  {request:'Remove the tiny ones.',result:{removed:3,kept:10},expected:'Removed 3 tiny ones; 10 cells left.'},
 ];
 for(const example of cases){
  const f=await fastFixture(example.result);
  try{
   await f.conversation.submit(example.request,true);
   const spoken=f.events.filter(event=>event.type==='text_ack'||event.type==='text_result');assert.equal(spoken.length,1);
   assert.equal(spoken[0].text,example.expected);assert.equal(spoken[0].outcome,undefined);assert.equal(spoken[0].fastPath,true);
  }finally{f.conversation.stop();await f.clean();}
 }
});

test('a numeric-looking fast result cannot mask its recorded failure',async()=>{
 const f=await fastFixture({ok:false,message:'ROI ownership changed before filtering.',removed:3,kept:10});
 try{
  await f.conversation.submit('Remove the tiny ones.',true);
  const final=f.events.find(event=>event.type==='text_result');assert.equal(final?.outcome,'failed');
  assert.equal(final?.text,'That did not work: ROI ownership changed before filtering.');
  assert(!String(final?.text).includes('10 cells'));
 }finally{f.conversation.stop();await f.clean();}
});
