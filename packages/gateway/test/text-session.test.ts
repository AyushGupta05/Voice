import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { TextConversation } from '../src/text-session.js';
import { Runtime, type Action } from '../src/runtime.js';
import { Knowledge } from '../src/knowledge.js';
import type { BackendClient, BackendResponse } from '../src/backend.js';

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
