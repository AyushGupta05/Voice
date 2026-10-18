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
import { fidelis } from '../src/apps/fidelis.js';
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

async function objectWordsFixture(){
 const root=await mkdtemp(join(tmpdir(),'penny-object-words-'));const events:Record<string,unknown>[]=[];
 let image={id:1,title:'Image A',channels:1,calibration:{unit:'pixel'}},segmentationFails=false;
 const profile:AppProfile={...fiji,settleDialogs:undefined,localTool:input=>input.name==='find_images'?Promise.resolve({matches:[{kind:'image',path:'/synthetic/hela-cells.tif'}]}):fiji.localTool?.(input)};
 const runtime=new Runtime({execute:async operation=>{
  if(operation==='context')return{contextKey:`image-${image.id}`,image,counts:{roiManager:12,roiManagerForImage:12}};
  if(operation==='segment_cells'){if(segmentationFails)throw new Error('No nuclear signal.');return{count:12};}
  if(operation==='object_stats')return{n:4};
  if(operation==='open_path'){image={...image,id:3,title:'hela-cells.tif'};return{image:{...image,width:672,height:512}};}
  throw new Error(`Unexpected operation ${operation}.`);
 }},new Knowledge(profile),root,{autoApprove:true,profile});
 const conversation=new TextConversation(runtime,{complete:async()=>{throw new Error('No model round expected.');}},'synthetic',event=>events.push(event));
 return{conversation,events,setImage:(id:number)=>{image={...image,id,title:`Image ${id}`};},failSegmentation:()=>{segmentationFails=true;},last:()=>events.filter(event=>event.type==='text_result').at(-1),clean:async()=>{conversation.stop();await rm(root,{recursive:true,force:true});}};
}

test('nuclei terminology follows the image that was segmented and resets after an independent image switch',async()=>{
 const f=await objectWordsFixture();
 try{
  await f.conversation.submit('Find all the nuclei.',true);assert.equal(f.last()?.text,'Found 12 nuclei.');
  await f.conversation.submit('How many are left?',true);assert.equal(f.last()?.text,'4 nuclei.');
  f.setImage(2);await f.conversation.submit('How many are left?',true);assert.equal(f.last()?.text,'4 objects.');
 }finally{await f.clean();}
});

test('a failed nuclei request cannot rename the current image objects in later answers',async()=>{
 const f=await objectWordsFixture();
 try{
  f.failSegmentation();await f.conversation.submit('Find all the nuclei.',true);assert.equal(f.last()?.outcome,'failed');
  await f.conversation.submit('How many are left?',true);assert.equal(f.last()?.text,'4 objects.');
 }finally{await f.clean();}
});

test('opening another image preserves its real friendly name and resets the previous object terminology',async()=>{
 const f=await objectWordsFixture();
 try{
  await f.conversation.submit('Find all the nuclei.',true);
  await f.conversation.submit('Open the nuclei image.',true);assert.equal(f.last()?.text,'Opened hela cells, 672 by 512.');
  await f.conversation.submit('How many are left?',true);assert.equal(f.last()?.text,'4 objects.');
 }finally{await f.clean();}
});

test('"apply this analysis to the whole folder" reads the folder macro for the focused document\u2019s folder and hands it to run_macro, which waits',async()=>{
 const root=await mkdtemp(join(tmpdir(),'penny-folder-session-'));const events:Record<string,unknown>[]=[];const calls:Array<[string,Record<string,unknown>]>=[];
 const runtime=new Runtime({execute:async(operation,args)=>{
  calls.push([operation,args as Record<string,unknown>]);
  if(operation==='context')return{contextKey:'k1',application:'Fidelis',document:{id:'doc-1',title:'a-squares.png',path:'/data/set/a-squares.png',width:64,height:64},counts:{rois:2},adapter:{operations:['context','folder_macro','run_macro']}};
  if(operation==='folder_macro'){assert.equal((args as {folder?:unknown}).folder,'/data/set');return{macro:"// Penny: the session's steps applied to every image of a folder (2 images in set)\nsetBatchMode(true);",folder:'/data/set',images:2,output:'/data/set/penny-folder-results.csv',stepsPerImage:2,writes:['/data/set/penny-folder-results.csv']};}
  if(operation==='run_macro')return{status:'awaiting_confirmation',planId:'plan-1',operation:'run_macro',steps:[{label:'Run macro (3 lines)'}],reasons:['macro code runs with full privileges'],contextKey:'k2'};
  throw new Error('unexpected operation '+operation);
 }},new Knowledge(fidelis),root,{autoApprove:true,profile:fidelis});
 const conversation=new TextConversation(runtime,{complete:async()=>{throw new Error('No model call expected.');}},'synthetic',event=>events.push(event));
 try{
  await conversation.submit('Apply this analysis to the whole folder.',true);
  const spoken=events.filter(event=>event.type==='text_ack'||event.type==='text_result');
  assert.deepEqual(spoken.map(event=>event.type),['text_result']);
  assert.equal(spoken[0].text,'Ready to run this on 2 images in set; say yes to go ahead.');assert.equal(spoken[0].outcome,undefined);
  const run=calls.find(([operation])=>operation==='run_macro');assert(run);assert.match(String(run![1].code),/every image of a folder/);
  assert(!calls.some(([operation])=>operation==='find_images'),'no folder was named, so none was searched');
  // a named folder that cannot be found stops the request: never this image's folder instead
  const before=calls.length;await conversation.submit('Run this on the Zzqx folder.',true);
  const last=events.filter(event=>event.type==='text_result').at(-1)!;
  assert.match(String(last.text),/could not find a folder called zzqx/);assert.equal(last.outcome,'failed');
  assert(!calls.slice(before).some(([operation])=>operation==='folder_macro'||operation==='run_macro'));
 }finally{conversation.stop();await rm(root,{recursive:true,force:true});}
});

test('Act 2 by instant routes against a stateful Fidelis stub: open, nuclei, go back, run this macro, yes \u2014 no model, confirm bound to the pending plan',async()=>{
 const root=await mkdtemp(join(tmpdir(),'penny-act2-session-'));const events:Record<string,unknown>[]=[];const calls:string[]=[];
 const state={open:false,rois:0,rows:0,history:0,pending:null as null|{planId:string},version:0};
 const runtime=new Runtime({execute:async(operation,args)=>{
  calls.push(operation);
  if(operation==='context')return{contextKey:'k'+state.version,application:'Fidelis',...(state.open?{document:{id:'doc-1',title:'blobs.gif',width:256,height:254,unit:'pixel',path:'/data/blobs.gif'},documents:[{id:'doc-1',title:'blobs.gif',focused:true}]}:{documents:[]}),counts:{rois:state.rois,resultsRows:state.rows,history:state.history},pending:state.pending,running:null};
  // fidelis_find_images is the gateway's own search (Penny's data folder, fixtures), not a bridge operation: any blobs.gif it finds will do
  if(operation==='open_path'){if(!/blobs\.gif$/.test(String((args as {path?:unknown}).path)))throw new Error('open_path args '+JSON.stringify(args));state.open=true;state.history++;state.version++;return{status:'completed',planId:'plan-open',opened:true,document:{id:'doc-1',title:'blobs.gif',width:256,height:254}};}
  if(operation==='segment_cells'){state.rois=67;state.rows=67;state.history+=9;state.version++;return{status:'completed',planId:'plan-seg',count:67,averageSize:327,unit:'pixel',undoSteps:1};}
  if(operation==='undo'){state.rois=0;state.rows=0;state.history++;state.version++;return{status:'completed',planId:'plan-undo',step:{label:'Undo'},undoneActionIds:['act-9'],restored:{rois:{before:67,after:0},resultsRows:{before:67,after:0}}};}
  if(operation==='macro')return{macro:'open("/data/blobs.gif");\nid1 = getImageID();\nrun("Gaussian Blur...", "sigma=2");',steps:state.history};
  if(operation==='run_macro'){state.pending={planId:'plan-run'};state.version++;return{status:'awaiting_confirmation',planId:'plan-run',operation:'run_macro',steps:[{label:'Run macro (3 lines)'}],reasons:['macro code runs with full privileges']};}
  if(operation==='confirm'){assert.equal((args as {planId?:unknown}).planId,'plan-run','the assent is bound to the plan that was pending');state.pending=null;state.rois=67;state.rows=67;state.version++;return{status:'completed',planId:'plan-run',step:{label:'Run macro (3 lines)'},resultsRowsAdded:67,documentsAdded:['doc-2']};}
  throw new Error('unexpected operation '+operation);
 }},new Knowledge(fidelis),root,{autoApprove:true,profile:fidelis});
 const conversation=new TextConversation(runtime,{complete:async()=>{throw new Error('No model call expected in Act 2.');}},'synthetic',event=>events.push(event));
 const spokenSince=(from:number)=>events.slice(from).filter(event=>event.type==='text_ack'||event.type==='text_result').map(event=>[event.type,event.text]);
 try{
  let at=events.length;await conversation.submit('Open the blobs image.',true);
  assert.deepEqual(spokenSince(at),[['text_ack','Opening it.'],['text_result','Opened blobs, 256 by 254.']]);
  at=events.length;await conversation.submit('Find all the nuclei.',true);
  assert.deepEqual(spokenSince(at),[['text_ack','Finding the nuclei.'],['text_result','Found 67 nuclei, mean area 327 square pixels.']]);
  at=events.length;await conversation.submit('Go back.',true);
  assert.deepEqual(spokenSince(at),[['text_result','Undid: 67 nuclei \u2192 0, Results cleared.']]);
  at=events.length;await conversation.submit('Yes.',true);
  assert.deepEqual(spokenSince(at),[],'a "yes" with nothing pending is not a request (nothing is run, nothing is said)');
  at=events.length;await conversation.submit('Run this macro.',true);
  assert.deepEqual(spokenSince(at),[['text_result','Ready to run 1 step; say yes to go ahead.']]);
  assert.equal(state.pending?.planId,'plan-run');
  at=events.length;await conversation.submit('Yes.',true);
  assert.deepEqual(spokenSince(at),[['text_ack','Running it.'],['text_result','Ran the macro: 67 result rows, 1 new image.']]);
  assert.equal(state.pending,null);assert.equal(state.rois,67);
  assert(!events.some(event=>event.type==='text_result'&&event.outcome==='failed'),JSON.stringify(events.filter(e=>e.type==='text_result')));
  assert.deepEqual(calls.filter(c=>c!=='context'),['open_path','segment_cells','undo','macro','run_macro','confirm']);
 }finally{conversation.stop();await rm(root,{recursive:true,force:true});}
});

test('corrections and the folder run as one conversation on the Fidelis stub: split, explain, apply to the folder, yes',async()=>{
 const root=await mkdtemp(join(tmpdir(),'penny-corrections-session-'));const events:Record<string,unknown>[]=[];const calls:string[]=[];
 const state={rois:67,pending:null as null|{planId:string},version:0};
 const runtime=new Runtime({execute:async(operation,args)=>{
  calls.push(operation);
  if(operation==='context')return{contextKey:'k'+state.version,application:'Fidelis',document:{id:'doc-1',title:'blobs.gif',width:256,height:254,unit:'pixel',path:'/data/imagej/blobs.gif'},documents:[{id:'doc-1',title:'blobs.gif',focused:true}],counts:{rois:state.rois,resultsRows:67,history:12},pending:state.pending,running:null};
  if(operation==='split_object'){state.rois=68;state.version++;return{status:'completed',planId:'plan-split',index:27,by:'pointer',pieces:2,pieceAreas:[340,334],unit:'pixel',total:68,undoSteps:3,highlight:{}};}
  if(operation==='explain_object')return{index:27,by:'pointer',area:340,unit:'pixel',areaRatio:1.02,circularity:0.81,verdict:'typical',thresholdMethod:'Otsu',sizeFloor:0,total:68,highlight:{}};
  if(operation==='folder_macro'){if((args as {folder?:unknown}).folder!=='/data/imagej')throw new Error('folder '+JSON.stringify(args));return{macro:"// Penny: the session's steps applied to every image of a folder (5 images in imagej)\nsetBatchMode(true);",folder:'/data/imagej',images:5,output:'/data/imagej/penny-folder-results.csv',stepsPerImage:9,literalOutlinesSkipped:67,writes:['/data/imagej/penny-folder-results.csv']};}
  if(operation==='run_macro'){state.pending={planId:'plan-folder'};state.version++;return{status:'awaiting_confirmation',planId:'plan-folder',operation:'run_macro',steps:[{label:'Run macro (40 lines)'}],reasons:['macro code runs with full privileges']};}
  if(operation==='confirm'){if((args as {planId?:unknown}).planId!=='plan-folder')throw new Error('confirm '+JSON.stringify(args));state.pending=null;state.version++;return{status:'completed',planId:'plan-folder',step:{label:'Run macro (40 lines)'},resultsRowsAdded:0,documentsAdded:[],log:['PENNY_FOLDER images=5 csv=/data/imagej/penny-folder-results.csv']};}
  throw new Error('unexpected operation '+operation);
 }},new Knowledge(fidelis),root,{autoApprove:true,profile:fidelis});
 const conversation=new TextConversation(runtime,{complete:async()=>{throw new Error('No model call expected.');}},'synthetic',event=>events.push(event));
 const spokenSince=(from:number)=>events.slice(from).filter(event=>event.type==='text_ack'||event.type==='text_result').map(event=>[event.type,event.text]);
 try{
  let at=events.length;await conversation.submit('Those are actually two separate cells.',true);
  assert.deepEqual(spokenSince(at),[['text_result','Split object 28 into 2 (340 square pixels and 334 square pixels); 68 objects now.']]);
  at=events.length;await conversation.submit('Why did you count this as a cell?',true);
  assert.deepEqual(spokenSince(at),[['text_result','Object 28: 340 square pixels, about the median size, circularity 0.81. Otsu threshold, no size floor.']]);
  at=events.length;await conversation.submit('Apply this analysis to the entire folder.',true);
  assert.deepEqual(spokenSince(at),[['text_result','Ready to run this on 5 images in imagej; say yes to go ahead.']]);
  at=events.length;await conversation.submit('Yes.',true);
  assert.deepEqual(spokenSince(at),[['text_ack','Running it.'],['text_result','Ran it on 5 images; the results are in penny-folder-results.csv.']]);
  assert(!events.some(event=>event.type==='text_result'&&event.outcome==='failed'),JSON.stringify(events.filter(e=>e.type==='text_result')));
  assert.deepEqual(calls.filter(c=>c!=='context'),['split_object','explain_object','folder_macro','run_macro','confirm']);
 }finally{conversation.stop();await rm(root,{recursive:true,force:true});}
});
