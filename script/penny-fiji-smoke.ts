/** Real gateway/Fiji conversation check on the disposable PipelineContractTest fixture.
 * Uses the credit-guarded text model, with spoken-style turns but no microphone, Transcribe or Polly.
 * Usage: node --import tsx script/penny-fiji-smoke.ts --authorized-aws-text --fixture-folder=/absolute/path
 * Fixture: exactly three identical 240×160 TIFFs, 0.5 µm pixels, from PipelineContractTest.nuclei().
 * The run changes only the fixture's open image/objects and writes its normal macro/CSV plus JSON evidence.
 */
import assert from 'node:assert/strict';
import {createHash,randomUUID} from 'node:crypto';
import {mkdir,readFile,readdir,realpath,writeFile} from 'node:fs/promises';
import {basename,isAbsolute,join,resolve} from 'node:path';
import {WebSocket} from 'ws';

const usage='Usage: node --import tsx script/penny-fiji-smoke.ts --authorized-aws-text --fixture-folder=/absolute/path [--output=/absolute/evidence.json]';
if(process.argv.includes('--help')){console.log(usage);process.exit(0);}
if(!process.argv.includes('--authorized-aws-text'))throw new Error(`${usage}\n--authorized-aws-text explicitly authorizes credit-covered model requests and analysis of the disposable Fiji fixture.`);
const folderArg=process.argv.find(value=>value.startsWith('--fixture-folder='))?.slice('--fixture-folder='.length);
assert(folderArg&&isAbsolute(folderArg),'Pass --fixture-folder with an absolute path to the disposable PipelineContractTest fixture.');
const folder=await realpath(folderArg);
const entries=await readdir(folder,{withFileTypes:true});
const images=entries.filter(entry=>entry.isFile()&&/\.tiff?$/i.test(entry.name)).map(entry=>entry.name).sort();
assert.equal(images.length,3,'The fixture folder must contain exactly three TIFF images.');
assert(!entries.some(entry=>entry.isFile()&&!/\.tiff?$/i.test(entry.name)&&!entry.name.startsWith('.')),'Keep the fixture folder limited to its three TIFFs.');
const hashes=await Promise.all(images.map(async name=>createHash('sha256').update(await readFile(join(folder,name))).digest('hex')));
assert.equal(new Set(hashes).size,1,'All three fixture TIFFs must be identical.');

type Json=Record<string,any>;
type Turn={request:string;expectedTool:string;taskId?:string;ack?:string;ackMs?:number;reply?:string;replyMs?:number;durationMs?:number;outcome?:string;tools:Array<{name:string;arguments:Json;result:unknown;atMs:number}>;observation?:Json};
type Pending={turn:Turn;started:number;resolve:()=>void;reject:(error:Error)=>void;timer:ReturnType<typeof setTimeout>};
const root=resolve(process.env.LUMA_ROOT??process.cwd()),state=join(root,'.local/state');
const port=Number(process.env.LUMA_GATEWAY_PORT??49555);
assert(Number.isInteger(port)&&port>=1024&&port<=65535,'LUMA_GATEWAY_PORT must be a local unprivileged port.');
const gatewayToken=(await readFile(join(state,'gateway-token'),'utf8')).trim();
const companionToken=(await readFile(join(state,'companion-token'),'utf8')).trim();
assert(gatewayToken&&companionToken,'Gateway credentials are empty.');
const started=Date.now(),deadline=AbortSignal.timeout(10*60_000);
const clientId=`penny-fiji-smoke-${randomUUID()}`;
const evidence:Json={at:new Date().toISOString(),fixture:{folder,images,sha256:hashes[0],width:240,height:160,pixelSizeMicrons:0.5},clientId,route:'spoken-style text over /events; no speech synthesis or microphone',passed:false,directTools:[],turns:[]};
const output=resolve(process.argv.find(value=>value.startsWith('--output='))?.slice('--output='.length)??join(root,'.local/evidence/penny-fiji-smoke.json'));
let pending:Pending|undefined,ws:WebSocket|undefined,sourceId:unknown,taskActive=false;
const object=(value:unknown,label:string):Json=>{assert(value&&typeof value==='object'&&!Array.isArray(value),`${label} was not an object.`);return value as Json;};
function payload(value:unknown):Json{
 let result=object(value,'Tool result');
 assert(result.error==null,`Tool failed: ${String(result.error)}`);
 if(typeof result.status==='string'){assert.equal(result.status,'completed',`Tool ended ${result.status}.`);result=object(result.result,'Completed tool payload');}
 if(result.result&&typeof result.result==='object')result=object(result.result,'Adapter payload');
 assert(result.error==null&&result.ok!==false,`Tool reported failure: ${String(result.error??result.message)}`);
 return result;
}
async function api(path:string,body?:Json):Promise<Json>{
 const response=await fetch(`http://127.0.0.1:${port}${path}`,{method:body?'POST':'GET',headers:{Authorization:`Bearer ${gatewayToken}`,...(body?{'Content-Type':'application/json'}:{})},...(body?{body:JSON.stringify(body)}:{}),signal:AbortSignal.any([deadline,AbortSignal.timeout(150_000)])});
 const value=object(await response.json(),'Gateway response');assert(response.ok,`Gateway HTTP ${response.status}: ${String(value.error??'request failed')}`);return value;
}
async function tool(name:string,args:Json={}):Promise<Json>{
 const at=Date.now();const response=await api('/tools/call',{name,arguments:args});const result=payload(response.result);
 evidence.directTools.push({name,arguments:args,atMs:at-started,durationMs:Date.now()-at,result});return result;
}
async function observe(expected:number|undefined):Promise<Json>{
 // Sequential reads deliberately avoid racing Fiji's event thread or the live conversation.
 const context=await tool('fiji_context'),manager=await tool('fiji_roi_manager',{limit:100});
 const image=object(context.image,'Active image'),calibration=object(image.calibration,'Calibration');
 assert.equal(image.width,240);assert.equal(image.height,160);assert.equal(calibration.pixelWidth,0.5);assert.equal(calibration.pixelHeight,0.5);
 assert(/^(?:microns?|micrometers?|micrometres?|µm|μm|um)$/i.test(String(calibration.unit)),`Unexpected fixture unit: ${String(calibration.unit)}`);
 assert.equal(image.id,sourceId,'The conversation switched away from the fixture image.');
 assert.equal(await realpath(String(image.path)),join(folder,images[0]),'The active image is not the exact first fixture TIFF.');
 const stats=expected===undefined||expected===0?undefined:await tool('fiji_object_stats',{plot:false,scope:'image'});
 if(expected!==undefined){assert.equal(context.counts?.roiManagerForImage,expected,'Context object count differs.');assert.equal(manager.forImage,expected,'ROI Manager image-owned count differs.');if(stats)assert.equal(stats.n,expected,'Independent measured object count differs.');}
 return{context,manager,stats};
}
function failPending(error:Error){const current=pending;if(!current)return;clearTimeout(current.timer);pending=undefined;current.reject(error);}
const onDeadline=()=>failPending(new Error('Smoke exceeded its ten-minute total time limit.'));
deadline.addEventListener('abort',onDeadline,{once:true});
function onEvent(event:Json){
 const current=pending;if(!current)return;
 if(event.type==='error'){failPending(new Error(String(event.message??'Gateway error.')));return;}
 if(event.type==='task_state'&&event.state==='working'&&!current.turn.taskId){current.turn.taskId=String(event.taskId);return;}
 if(event.taskId!==current.turn.taskId)return; // Other clients share global tool broadcasts.
 const elapsed=Date.now()-current.started;
 if(event.type==='text_error'){failPending(new Error(String(event.message??'Text task failed.')));return;}
 if(event.type==='tool'&&typeof event.name==='string'){
  assert(current.turn.tools.length<64,'Too many tool events in one fixture turn.');
  current.turn.tools.push({name:event.name,arguments:event.arguments??{},result:event.result,atMs:elapsed});
 }else if(event.type==='text_ack'){current.turn.ack=String(event.text);current.turn.ackMs??=elapsed;}
 else if(event.type==='text_result'){current.turn.reply=String(event.text);current.turn.replyMs=elapsed;current.turn.outcome=event.outcome;}
 else if(event.type==='task_state'&&event.state==='idle'){
  current.turn.durationMs=elapsed;clearTimeout(current.timer);pending=undefined;
  taskActive=false;
  if(!current.turn.reply)current.reject(new Error('Task finished without a result.'));
  else if(current.turn.outcome==='failed')current.reject(new Error(`Task reported failure: ${current.turn.reply}`));
  else current.resolve();
 }
}
async function turn(request:string,expectedTool:string,expectedCount:number):Promise<Turn>{
 deadline.throwIfAborted();
 assert(ws?.readyState===WebSocket.OPEN,'Companion socket is not open.');assert(!pending,'A previous turn is still active.');
 const result:Turn={request,expectedTool,tools:[]};evidence.turns.push(result);
 await new Promise<void>((resolveTurn,reject)=>{
  const timer=setTimeout(()=>failPending(new Error(`Turn timed out after 180 s: ${request}`)),180_000);
  pending={turn:result,started:Date.now(),resolve:resolveTurn,reject,timer};
  taskActive=true;
  ws!.send(JSON.stringify({type:'text',text:request,spoken:true}));
 });
 assert(result.taskId,'Gateway did not attribute this turn to a task.');
 assert(result.tools.some(event=>event.name===expectedTool),`Expected ${expectedTool}; observed ${result.tools.map(event=>event.name).join(', ')||'no attributed tool events'}. Reload the current gateway if taskId attribution is missing.`);
 if(result.ackMs!==undefined)assert(result.ackMs<=result.tools.find(event=>event.name===expectedTool)!.atMs,'The acknowledgment arrived after the action completed.');
 if(expectedTool!=='fiji_segment_cells')assert(!result.tools.some(event=>event.name==='fiji_segment_cells'),'A correction unexpectedly re-segmented the image.');
 for(const event of result.tools)payload(event.result);
 result.observation=await observe(expectedCount);return result;
}
function sanitize(value:unknown,key=''):unknown{
 if(/token|authorization|credential|secret|api.?key/i.test(key))return'[redacted]';
 if(typeof value==='string')return value.replaceAll(gatewayToken,'[redacted]').replaceAll(companionToken,'[redacted]').replace(/Bearer\s+[A-Za-z0-9._-]+/gi,'Bearer [redacted]');
 if(Array.isArray(value))return value.map(item=>sanitize(item));
 if(value&&typeof value==='object')return Object.fromEntries(Object.entries(value).map(([name,item])=>[name,sanitize(item,name)]));
 return value;
}
try{
 const health=await api('/health');assert.equal(health.app?.id,'fiji','Start the gateway with the Fiji profile.');assert.equal(health.approvals,'auto','This fixture requires the recorded automatic policy.');assert.equal(health.text?.configured,true,'The credit-checked text route must be configured.');assert(!health.bridgeError,`Fiji bridge unavailable: ${String(health.bridgeError)}`);
 assert.equal(health.context?.adapterReadiness?.status,'matches_build','Reload the completed bridge build before verifying its behavior.');
 ws=new WebSocket(`ws://127.0.0.1:${port}/events`,{headers:{Authorization:`Bearer ${companionToken}`}});
 ws.on('message',raw=>{try{onEvent(object(JSON.parse(raw.toString()),'Socket event'));}catch(error){failPending(error instanceof Error?error:new Error(String(error)));}});
 ws.on('error',error=>failPending(error));ws.on('close',()=>failPending(new Error('Companion socket closed before the task finished.')));
 await new Promise<void>((resolveOpen,reject)=>{const timer=setTimeout(()=>reject(new Error('Companion socket connection timed out.')),10_000);ws!.once('open',()=>{clearTimeout(timer);resolveOpen();});ws!.once('error',error=>{clearTimeout(timer);reject(error);});});
 ws.send(JSON.stringify({type:'hello',clientId}));
 const opened=await tool('fiji_open_path',{path:join(folder,images[0])});evidence.opened=opened;
 const openedContext=await tool('fiji_context');
 sourceId=openedContext.image?.id;assert(sourceId!==undefined,'Fiji did not report an open fixture image.');
 evidence.baseline=await observe(0);
 const segment=await turn('Find nuclei without watershed and keep the small artifacts for now.','fiji_segment_cells',12);
 const segmentCall=segment.tools.find(event=>event.name==='fiji_segment_cells')!;
 assert.equal(segmentCall.arguments.watershed,false,'Segmentation must preserve the requested watershed-off setting.');assert.equal(segmentCall.arguments.keepArtifacts,true,'The explicit later size correction requires keeping artifacts initially.');
 assert(segment.ack&&segment.ackMs!==undefined,'Segmentation did not acknowledge that it was starting.');
 await tool('fiji_select_roi',{type:'rectangle',x:28,y:118,width:42,height:24,name:'Disposable touching-pair fixture'});
 try{await turn('Separate the touching cells.','fiji_split_objects',13);}finally{await tool('fiji_select_roi',{type:'none'});}
 await turn('Remove anything smaller than twenty square microns.','fiji_filter_objects',10);
 await turn('Show me the size distribution.','fiji_object_stats',10);
 const applied=await turn('Apply this analysis to the entire folder.','fiji_apply_analysis',10);
 assert(applied.ack&&applied.ackMs!==undefined,'Folder replay did not acknowledge that it was starting.');
 const replayCall=applied.tools.findLast(event=>event.name==='fiji_apply_analysis')!;
 const replay=object(payload(replayCall.result).run,'Folder replay');
 assert.equal(replay.processed,3);assert.equal(replay.matched,3);assert.equal(replay.timedOut,false);assert.deepEqual(replay.failed,[]);
 assert(Array.isArray(replay.perImage)&&replay.perImage.length===3,'Replay must return each of the three measured image results.');
 assert.deepEqual(replay.perImage.map((row:Json)=>row.image).sort(),images);
 for(const row of replay.perImage){assert.equal(row.ok,true,`Replay failed for ${String(row.image)}.`);assert.equal(row.resultsRows,10,`Replay count differs for ${String(row.image)}.`);}
 evidence.replay={processed:replay.processed,perImage:replay.perImage.map((row:Json)=>({image:row.image,resultsRows:row.resultsRows,ok:row.ok}))};
 evidence.passed=true;
}catch(error){evidence.error=error instanceof Error?error.message:String(error);throw error;}
finally{
 deadline.removeEventListener('abort',onDeadline);
 if(taskActive&&ws?.readyState===WebSocket.OPEN)ws.send(JSON.stringify({type:'stop_task'}));
 if(pending){clearTimeout(pending.timer);pending=undefined;}
 if(ws&&ws.readyState!==WebSocket.CLOSED){await new Promise<void>(resolveClose=>{const timer=setTimeout(()=>{ws!.terminate();resolveClose();},1000);ws!.once('close',()=>{clearTimeout(timer);resolveClose();});ws!.close();});}
 evidence.durationMs=Date.now()-started;await mkdir(resolve(output,'..'),{recursive:true});await writeFile(output,JSON.stringify(sanitize(evidence),null,2)+'\n',{mode:0o600});
 console.log(JSON.stringify({passed:evidence.passed,evidence:output,durationMs:evidence.durationMs,turns:evidence.turns.map((item:Turn)=>({request:item.request,ackMs:item.ackMs,replyMs:item.replyMs,reply:item.reply,taskId:item.taskId})),error:evidence.error},null,2));
}
