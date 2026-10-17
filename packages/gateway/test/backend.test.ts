import { test } from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { ResponsesBackend, type BackendClient, type BackendRequest } from '../src/backend.js';
import { Runtime, type Action } from '../src/runtime.js';
import { Knowledge } from '../src/knowledge.js';
import type { Adapter } from '../src/bridge.js';
import type { DelegatedTask, LiveUpdate } from '../src/live.js';

const annotate={type:'function_call',call_id:'call-1',name:'qupath_annotate',arguments:JSON.stringify({x:0,y:0,width:32,height:32})};
function task(signal=new AbortController().signal) {
  const updates:LiveUpdate[]=[];
  const value:DelegatedTask={id:'delegated-task',offsetMs:800,context:{contextKey:'fixture'},signal,
    conversation:()=>({fragments:[{role:'user',text:'Create a region here.',startMs:0,endMs:800}],truncated:false}),
    update:update=>updates.push(update)};
  return {value,updates};
}
async function runtimeFixture(adapter?:Adapter){
  const directory=await mkdtemp(join(tmpdir(),'luma-backend-'));let mutations=0;
  const runtime=new Runtime(adapter??{execute:async(operation)=>{
    if(operation==='context')return {contextKey:'fixture',image:{name:'Synthetic image'}};
    mutations++;return {created:true};
  }},new Knowledge(),directory);
  return {runtime,mutations:()=>mutations,clean:()=>rm(directory,{recursive:true,force:true})};
}

test('backend waits for the real human decision and continues with actual tool results',async()=>{
  const f=await runtimeFixture();const t=task();let modelCalls=0;const shown:string[]=[];
  const client:BackendClient={complete:async request=>{
    modelCalls++;assert.equal(request.store,false);assert.equal(request.tools.length,23);assert.equal(request.parallel_tool_calls,false);
    if(modelCalls===1)return {output:[annotate]};
    const result=request.input.find(item=>item.type==='function_call_output')!;
    assert.equal(JSON.parse(String(result.output)).status,'completed');assert.deepEqual(JSON.parse(String(result.output)).result,{created:true});
    return {output:[],output_text:'The region was created in QuPath.'};
  }};
  try{
    const pending=once(f.runtime,'action');
    const running=new ResponsesBackend(f.runtime,client,'test-model',(_id,text)=>shown.push(text)).run(t.value);
    const action=(await pending)[0] as Action;
    assert.equal(action.status,'pending');assert.equal(f.mutations(),0);assert.equal(modelCalls,1);
    await f.runtime.decide(action.id,true);await running;
    assert.equal(f.mutations(),1);assert.equal(shown.length,1);assert.equal(t.updates.at(-1)?.content,'The region was created in QuPath.');
  }finally{await f.clean();}
});

test('backend reports denial without executing the proposed operation',async()=>{
  const f=await runtimeFixture();const t=task();let modelCalls=0;
  const client:BackendClient={complete:async request=>{
    if(++modelCalls===1)return {output:[annotate]};
    assert.equal(JSON.parse(String(request.input.find(item=>item.type==='function_call_output')!.output)).status,'denied');
    return {output:[],output_text:'The proposed change was declined.'};
  }};
  try{
    const pending=once(f.runtime,'action');const running=new ResponsesBackend(f.runtime,client,'test-model',()=>{}).run(t.value);
    const action=(await pending)[0] as Action;await f.runtime.decide(action.id,false);await running;
    assert.equal(f.mutations(),0);
  }finally{await f.clean();}
});

test('cancelled human wait releases listeners without deciding or executing the proposal',async()=>{
  const f=await runtimeFixture();const cancellation=new AbortController();const t=task(cancellation.signal);
  try{
    const pending=once(f.runtime,'action');
    const running=new ResponsesBackend(f.runtime,{complete:async()=>({output:[annotate]})},'test-model',()=>{}).run(t.value);
    const rejected=assert.rejects(running,{name:'AbortError'});const action=(await pending)[0] as Action;
    await new Promise(resolve=>setImmediate(resolve));
    assert(f.runtime.listenerCount('action')>0,'Fixture must cancel an installed human-decision listener');
    cancellation.abort();await rejected;
    assert.equal(f.runtime.listenerCount('action'),0);assert.equal(f.runtime.actions.get(action.id)!.status,'pending');assert.equal(f.mutations(),0);
  }finally{await f.clean();}
});

test('duplicate backend call IDs never execute an operation twice',async()=>{
  const f=await runtimeFixture();const t=task();const call={type:'function_call',call_id:'duplicate',name:'qupath_overlays',arguments:'{"fill":true}'};
  try{
    await assert.rejects(new ResponsesBackend(f.runtime,{complete:async()=>({output:[call,call]})},'test-model',()=>{}).run(t.value),/repeated a function call ID/);
    assert.equal(f.mutations(),0);
  }finally{await f.clean();}
});

test('viewer snapshots become image input rather than base64 function-result text',async()=>{
  const image='iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=';
  const f=await runtimeFixture({execute:async operation=>operation==='context'?{contextKey:'fixture'}:{mimeType:'image/png',data:image}});
  const t=task();let modelCalls=0;
  const client:BackendClient={complete:async request=>{
    if(++modelCalls===1)return {output:[{type:'function_call',call_id:'viewer',name:'qupath_snapshot',arguments:'{}'}]};
    const output=String(request.input.find(item=>item.type==='function_call_output')!.output);assert(!output.includes(image));
    const imagePart=request.input.flatMap(item=>Array.isArray(item.content)?item.content:[]).find(part=>part.type==='input_image');
    assert.equal(imagePart.image_url,`data:image/png;base64,${image}`);
    return {output:[],output_text:'The viewer image is available for inspection.'};
  }};
  try{await new ResponsesBackend(f.runtime,client,'test-model',()=>{}).run(t.value);assert.equal(modelCalls,2);}finally{await f.clean();}
});

test('long backend results remain complete for display while spoken updates respect the byte limit',async()=>{
  const f=await runtimeFixture();const t=task();const text='Measured result details. '.repeat(60);let shown='';
  try{
    await new ResponsesBackend(f.runtime,{complete:async()=>({output:[],output_text:text})},'test-model',(_id,value)=>{shown=value;}).run(t.value);
    assert.equal(shown,text.trim());assert(Buffer.byteLength(t.updates[0].content)<=480);assert.match(t.updates[0].content,/detailed result/);
  }finally{await f.clean();}
});

test('follow-up delegation receives the complete prior result rather than only its spoken notice',async()=>{
  const f=await runtimeFixture();const long='Exact measured details for the prior result. '.repeat(30);let requests=0;
  const client:BackendClient={complete:async request=>{
    if(++requests===1)return {output:[],output_text:long};
    const observations=JSON.parse(String((request.input.at(-1)!.content as any[])[0].text));
    assert.equal(observations.previousTasks.tasks[0].answer,long.trim());
    assert.equal(observations.previousTasks.tasks[0].status,'completed');
    return {output:[],output_text:'Here is the requested detail from the retained result.'};
  }};
  try{
    const backend=new ResponsesBackend(f.runtime,client,'test-model',()=>{});
    await backend.run(task().value);const follow=task().value;follow.id='follow-up';await backend.run(follow);
    assert.equal(requests,2);
  }finally{await f.clean();}
});

test('new user correction during inference prevents the stale selection call and stale answer',async()=>{
  const operations:string[]=[];
  const f=await runtimeFixture({execute:async operation=>{operations.push(operation);return operation==='context'?{contextKey:'fixture'}:{updated:true};}});
  const t=task();const fragments=t.value.conversation().fragments;
  t.value.conversation=()=>({fragments:structuredClone(fragments),truncated:false});let turns=0;const shown:string[]=[];
  const client:BackendClient={complete:async()=>{
    turns++;
    if(turns===1){fragments.push({role:'user',text:'Actually, only highlight them.',startMs:900,endMs:1200});return {output:[{type:'function_call',call_id:'stale-select',name:'qupath_select',arguments:'{}'}]};}
    if(turns===2)return {output:[{type:'function_call',call_id:'corrected-highlight',name:'qupath_overlays',arguments:'{"fill":true}'}]};
    if(turns===3){fragments.push({role:'user',text:'Use outlines instead.',startMs:1300,endMs:1500});return {output:[],output_text:'Stale answer: cells are filled.'};}
    if(turns===4)return {output:[{type:'function_call',call_id:'outlines',name:'qupath_overlays',arguments:'{"fill":false}'}]};
    return {output:[],output_text:'Cell outlines are shown.'};
  }};
  try{
    await new ResponsesBackend(f.runtime,client,'test-model',(_id,text)=>shown.push(text)).run(t.value);
    assert(!operations.includes('select'));assert.equal(operations.filter(operation=>operation==='overlays').length,2);
    assert.deepEqual(shown,['Cell outlines are shown.']);
  }finally{await f.clean();}
});

test('session result history stays bounded and exposes omissions to follow-up requests',async()=>{
  const f=await runtimeFixture();let turns=0;
  const client:BackendClient={complete:async request=>{
    const observations=JSON.parse(String((request.input.at(-1)!.content as any[])[0].text));
    if(turns>=8){assert.equal(observations.previousTasks.truncated,true);assert(observations.previousTasks.tasks.length<=7);}
    return {output:[],output_text:`Result ${turns++}`};
  }};
  try{
    const backend=new ResponsesBackend(f.runtime,client,'test-model',()=>{});
    for(let index=0;index<10;index++){const current=task().value;current.id=`task-${index}`;await backend.run(current);}
    assert.equal(turns,10);
  }finally{await f.clean();}
});

test('follow-up reconciles a human decision made after the prior backend wait was interrupted',async()=>{
  const f=await runtimeFixture();const cancellation=new AbortController();let turns=0;
  const client:BackendClient={complete:async request=>{
    if(++turns===1)return {output:[annotate]};
    const observations=JSON.parse(String((request.input.at(-1)!.content as any[])[0].text));
    const previous=observations.previousTasks.tasks[0];assert.equal(previous.status,'interrupted');assert.equal(previous.calls[0].output.status,'completed');
    return {output:[],output_text:'The researcher approved it and the region was created.'};
  }};
  try{
    const backend=new ResponsesBackend(f.runtime,client,'test-model',()=>{});
    const pending=once(f.runtime,'action');const running=backend.run(task(cancellation.signal).value);const rejected=assert.rejects(running,{name:'AbortError'});
    const action=(await pending)[0] as Action;await new Promise(resolve=>setImmediate(resolve));cancellation.abort();await rejected;
    await f.runtime.decide(action.id,true);
    const follow=task().value;follow.id='new-delegation';await backend.run(follow);assert.equal(f.mutations(),1);
  }finally{await f.clean();}
});

test('a correction invalidates a tool that was already waiting in the executor queue',async()=>{
  let release!:()=>void;let entered!:()=>void;let returned!:()=>void;
  const gate=new Promise<void>(resolve=>{release=resolve;});const blocked=new Promise<void>(resolve=>{entered=resolve;});const queued=new Promise<void>(resolve=>{returned=resolve;});
  const operations:string[]=[];
  const f=await runtimeFixture({execute:async operation=>{operations.push(operation);if(operation==='focus'){entered();await gate;}return operation==='context'?{contextKey:'fixture'}:{updated:true};}});
  const t=task();const fragments=t.value.conversation().fragments;t.value.conversation=()=>({fragments:structuredClone(fragments),truncated:false});
  let turns=0;let blocker:Promise<unknown>|undefined;
  const client:BackendClient={complete:async()=>{
    if(++turns===1){blocker=f.runtime.call('qupath_focus',{x:1,y:1});await blocked;returned();return {output:[{type:'function_call',call_id:'queued-selection',name:'qupath_select',arguments:'{}'}]};}
    return {output:[],output_text:'The corrected request needs no selection change.'};
  }};
  try{
    const running=new ResponsesBackend(f.runtime,client,'test-model',()=>{}).run(t.value);
    await queued;await new Promise(resolve=>setImmediate(resolve));fragments.push({role:'user',text:'Do not change the selection.',startMs:900,endMs:1200});release();
    await blocker;await running;assert(!operations.includes('select'));assert.equal(turns,2);
  }finally{release();await f.clean();}
});

test('a correction during proposal context observation does not publish the stale proposal',async()=>{
  let release!:()=>void;let entered!:()=>void;const gate=new Promise<void>(resolve=>{release=resolve;});const observed=new Promise<void>(resolve=>{entered=resolve;});let contexts=0;
  const f=await runtimeFixture({execute:async operation=>{if(operation==='context'&&++contexts===2){entered();await gate;}return {contextKey:'fixture'};}});
  const t=task();const fragments=t.value.conversation().fragments;t.value.conversation=()=>({fragments:structuredClone(fragments),truncated:false});let turns=0;
  const client:BackendClient={complete:async()=>++turns===1?{output:[annotate]}:{output:[],output_text:'The creation request was corrected before review.'}};
  try{
    const running=new ResponsesBackend(f.runtime,client,'test-model',()=>{}).run(t.value);await observed;
    fragments.push({role:'user',text:'Actually, do not create a region.',startMs:900,endMs:1200});release();await running;
    assert.equal(f.runtime.actions.size,0);assert.equal(turns,2);
  }finally{release();await f.clean();}
});

test('history can trim a large approval result even if memory pressure already omitted its arguments',async()=>{
  const large='x'.repeat(9*1024*1024);
  const f=await runtimeFixture({execute:async operation=>operation==='context'?{contextKey:'fixture'}:{value:large}});
  let releaseA!:()=>void;let releaseB!:()=>void;let readyA!:()=>void;let readyB!:()=>void;
  const gateA=new Promise<void>(resolve=>{releaseA=resolve;});const gateB=new Promise<void>(resolve=>{releaseB=resolve;});
  const blockedA=new Promise<void>(resolve=>{readyA=resolve;});const blockedB=new Promise<void>(resolve=>{readyB=resolve;});
  const turns=new Map<string,number>();let checked=false;
  const client:BackendClient={complete:async request=>{
    const observed=JSON.parse(String((request.input.at(-1)!.content as any[])[0].text));const id=observed.delegation.id;
    const turn=(turns.get(id)??0)+1;turns.set(id,turn);
    if(id==='A'&&turn===1)return {output:[{type:'function_call',call_id:'large-script',name:'qupath_run_script',arguments:'{"source":"return 1","reason":"Synthetic memory fixture"}'}]};
    if(id==='B'&&turn===1)return {output:[{type:'function_call',call_id:'large-read',name:'qupath_measurements',arguments:'{"limit":1}'}]};
    if(id==='A'){readyA();await gateA;return {output:[],output_text:'A completed.'};}
    if(id==='B'){readyB();await gateB;return {output:[],output_text:'B completed.'};}
    assert(Buffer.byteLength(JSON.stringify(observed.previousTasks))<=8*1024*1024,'Repopulated approval output must remain trimmable');
    assert.equal(observed.previousTasks.truncated,true);checked=true;return {output:[],output_text:'Bounded follow-up context.'};
  }};
  const backend=new ResponsesBackend(f.runtime,client,'test-model',()=>{});
  let runningA:Promise<void>|undefined;let runningB:Promise<void>|undefined;
  try{
    const a=task().value;a.id='A';const pending=once(f.runtime,'action');runningA=backend.run(a);const action=(await pending)[0] as Action;
    const b=task().value;b.id='B';runningB=backend.run(b);await blockedB;
    await f.runtime.decide(action.id,true);await blockedA;
    const c=task().value;c.id='C';await backend.run(c);assert.equal(checked,true);
  }finally{releaseA();releaseB();await Promise.allSettled([runningA,runningB]);await f.clean();}
});

test('interruption releases an in-flight model wait even if a custom client ignores abort',async()=>{
  const f=await runtimeFixture();const cancellation=new AbortController();let entered!:()=>void;const started=new Promise<void>(resolve=>{entered=resolve;});
  const backend=new ResponsesBackend(f.runtime,{complete:async()=>{entered();return new Promise(()=>{});}},'test-model',()=>{});
  try{
    const running=backend.run(task(cancellation.signal).value);const rejected=assert.rejects(running,{name:'AbortError'});
    await started;cancellation.abort();await rejected;assert.doesNotThrow(()=>backend.clearHistory());
  }finally{await f.clean();}
});

test('an application change after inference prevents stale selection and supplies the new context for replanning',async()=>{
  let key='fixture';let selections=0;let requests=0;
  const f=await runtimeFixture({execute:async(operation,_args,options)=>{
    if(operation==='context')return {contextKey:key};
    selections++;assert.equal(options?.expectedContext,key);return {selected:1};
  }});
  const client:BackendClient={complete:async request=>{
    if(++requests===1){key='different-image';return {output:[{type:'function_call',call_id:'stale',name:'qupath_select',arguments:'{"ids":["old-object"]}'}]};}
    const observation=JSON.parse(String((request.input.filter(item=>item.role==='user').at(-1)!.content as any[])[0].text));
    assert.equal(observation.qupathObservation.contextKey,'different-image');
    assert.equal(observation.contextChangedSinceDelegation,true);
    return {output:[],output_text:'The application changed. Which region should I use?'};
  }};
  try{await new ResponsesBackend(f.runtime,client,'test-model',()=>{}).run(task().value);assert.equal(selections,0);assert.equal(requests,2);}finally{await f.clean();}
});

test('guarded display dispatch binds the observed application context at the adapter boundary',async()=>{
  let operations=0;let requests=0;
  const f=await runtimeFixture({execute:async(operation,_args,options)=>{
    if(operation==='context')return {contextKey:'fixture'};
    assert.equal(options?.expectedContext,'fixture');operations++;return {selected:1};
  }});
  const client:BackendClient={complete:async()=>++requests===1?{output:[{type:'function_call',call_id:'current',name:'qupath_select',arguments:'{"ids":["current-object"]}'}]}:{output:[],output_text:'Selected the requested object.'}};
  try{await new ResponsesBackend(f.runtime,client,'test-model',()=>{}).run(task().value);assert.equal(operations,1);}finally{await f.clean();}
});


test('a text-only backend cannot request viewer vision or receive image input',async()=>{
  const f=await runtimeFixture();let calls=0;
  const client:BackendClient={supportsVision:false,complete:async request=>{
    assert(!request.tools.some(tool=>tool.name==='qupath_snapshot'));
    if(++calls===1)return {output:[{type:'function_call',call_id:'unsupported-viewer',name:'qupath_snapshot',arguments:'{}'}]};
    const result=request.input.find(item=>item.type==='function_call_output')!;
    assert.match(String(result.output),/vision is unavailable/);
    assert(!request.input.flatMap(item=>Array.isArray(item.content)?item.content:[]).some(part=>part.type==='input_image'));
    return {output:[],output_text:'I can use the measured metadata and focus the real viewer, but this backend has no image input.'};
  }};
  try{await new ResponsesBackend(f.runtime,client,'text-only',()=>{},4096).run(task().value);assert.equal(f.mutations(),0);}finally{await f.clean();}
});
