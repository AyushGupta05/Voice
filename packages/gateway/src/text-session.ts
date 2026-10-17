import { randomUUID } from 'node:crypto';
import { ResponsesBackend, failureSentence, toolTask, type BackendClient } from './backend.js';
import { toolUtterance, type ToolUtterance } from './tool-request.js';
import { failureReason } from './apps/profile.js';
import type { Runtime } from './runtime.js';
import type { Context } from './bridge.js';
import type { TranscriptFragment } from './live.js';

type Active = { id: string; controller: AbortController; answered: boolean };
/** One researcher-owned text conversation. No provider requests occur until submit. */
export class TextConversation {
 private active?: Active;
 private readonly backend: ResponsesBackend;
 private readonly fragments: TranscriptFragment[]=[];
 private truncated=false;
 /** The researcher's word for the current objects ("nuclei" after "find the nuclei"), echoed in later instant answers. */
 private objectWord:'cells'|'nuclei'='cells';
 private objectImage?:string;
 private readonly startedAt=Date.now();
 constructor(private runtime:Runtime, client:BackendClient, model:string,private emit:(event:Record<string,unknown>)=>void){
  this.backend=new ResponsesBackend(runtime,client,model,(id,text,outcome?:'failed')=>{
   const active=this.active;if(!active||active.id!==id||active.controller.signal.aborted)return;
   active.answered=true;this.append('assistant',text);this.emit({type:'text_result',taskId:id,text,...(outcome?{outcome}:{})});
  },4096);
 }
 private append(role:'user'|'assistant',text:string){
  const offset=Date.now()-this.startedAt;this.fragments.push({role,text,startMs:offset,endMs:offset});
  while(this.fragments.length>128||this.fragments.reduce((sum,item)=>sum+item.text.length,0)>24_000){this.fragments.shift();this.truncated=true;}
 }
 /** A spoken stop word the voice transport consumed as barge-in ("stop", "cancel that"): offered to the profile's instant
  * routes when it declares `controlRoutes` (Fidelis cancels a waiting plan), never to the model. */
 async control(text:string){if(this.runtime.profile.controlRoutes)await this.submit(text,true,{instantOnly:true});}
 async submit(text:unknown,spoken=false,options:{instantOnly?:boolean}={}){
  if(typeof text!=='string'||!text.trim()||text.length>4000)throw new Error('Enter a message of 1–4000 characters.');
  this.active?.controller.abort();
  const active:Active={id:randomUUID(),controller:new AbortController(),answered:false};this.active=active;
  this.append('user',text.trim());this.emit({type:'task_state',state:'working',taskId:active.id});
  try{
   const {profile}=this.runtime;
   // The conversation envelope (task, spoken, these words) for bridges that record it (Fidelis); the backend sets its own.
   let observed:Context|undefined;
   const request:ToolUtterance={spoken,utterance:()=>text.trim(),observed:()=>observed};
   const context=await toolTask.run(active.id,()=>toolUtterance.run(request,()=>this.runtime.call(`${profile.toolPrefix}_context`,{},active.controller.signal))) as Context;
   observed=context;
   active.controller.signal.throwIfAborted();
   // QuPath and Fiji report the open image as `image`, Fidelis as `document`.
   const image=(context.image??context.document) as {id?:unknown;path?:unknown;name?:unknown;title?:unknown}|undefined;
   const identity=image?['id','path','name','title'].map(key=>[key,image[key as keyof typeof image]] as const).find(([,value])=>typeof value==='string'||typeof value==='number'):undefined;
   const imageKey=identity?`${profile.id}:${identity[0]}:${String(identity[1])}`:undefined;
   if(imageKey!==this.objectImage){this.objectWord='cells';this.objectImage=imageKey;}
   // Spoken display-only commands with one unambiguous meaning skip the model entirely; a data route (undo) only
   // when policy executes it at once, since a review card needs the model's conversation to carry it.
   let route=spoken?profile.fastRoute(text,context):undefined;
   if(route?.refusal){
    const reply=failureSentence(route.refusal);active.answered=true;this.append('assistant',reply);
    this.emit({type:'text_result',taskId:active.id,text:reply,outcome:'failed',fastPath:true});return;
   }
   if(route&&!this.runtime.autoApprove){const risk=profile.tools.find(tool=>tool.name===route!.tool)?.risk;if(risk==='data'||risk==='code')route=undefined;}
   if(route?.imageQuery){
    // "Open the X image" in one step: resolve the name locally and open the best image match, no model round.
    const found=await this.runtime.call(`${profile.toolPrefix}_find_images`,{query:route.imageQuery,limit:5},active.controller.signal).catch(()=>undefined) as {matches?:Array<{kind?:unknown;path?:unknown;name?:unknown}>}|undefined;
    active.controller.signal.throwIfAborted();
    const image=found?.matches?.find(match=>match.kind!=='folder'&&match.kind!=='project'&&typeof match.path==='string');
    const {imageQuery:_image,...rest}=route;
    route=image?{...rest,arguments:{...rest.arguments,path:image.path}}:undefined;
   }
   if(route?.folderQuery){
    // A folder the utterance names is resolved locally; the first matching folder replaces the default (the active image's).
    const found=await this.runtime.call(`${profile.toolPrefix}_find_images`,{query:route.folderQuery},active.controller.signal).catch(()=>undefined) as {matches?:Array<{kind?:unknown;path?:unknown;name?:unknown}>}|undefined;
    active.controller.signal.throwIfAborted();
    const folder=found?.matches?.find(match=>match.kind==='folder'&&typeof match.path==='string');
    const {folderQuery:_query,...rest}=route;
    route=folder?{...rest,arguments:{...rest.arguments,folder:folder.path},reply:`Running it on the ${String(folder.name)} folder.`}:rest;
   }
   if(route){
    const risk=profile.tools.find(tool=>tool.name===route!.tool)?.risk;
    const acknowledged=(risk==='data'||risk==='code')&&profile.longRunningTools.has(route.tool);
    if(acknowledged)this.emit({type:'text_ack',taskId:active.id,text:route.reply});
    let result:Record<string,unknown>|undefined;
    // A profile that binds instant routes refuses one whose application state changed since it was chosen.
    const bound=profile.bindInstantRoutes?(current?:Context)=>{if(current&&current.contextKey!==context.contextKey)throw new Error(`${profile.displayName} changed before that ran; nothing was done. Say it again.`);}:undefined;
    try{result=await toolTask.run(active.id,()=>toolUtterance.run(request,()=>this.runtime.call(route!.tool,route!.arguments,active.controller.signal,bound))) as Record<string,unknown>|undefined;}
    catch(error){
     active.controller.signal.throwIfAborted();
     // The bridge's refusals are short, speakable sentences ("No cell under the pointer; point at it."): say them.
     const message=error instanceof Error?error.message.trim():'';
     if(!message||message.length>160||/[{}\[\]<>]|\bat \w+\./.test(message))throw error;
     active.answered=true;this.append('assistant',`${message} (${route.tool} ${JSON.stringify(route.arguments)})`);
     this.emit({type:'text_result',taskId:active.id,text:message,outcome:'failed',fastPath:true});
     return;
    }
    active.controller.signal.throwIfAborted();
    // View tools answer with the bridge payload; data tools with a Runtime envelope whose status must be completed.
    const failed=!result||typeof result!=='object'||result.error!=null||result.ok===false||('status' in result&&result.status!=='completed');
    // A data route says why it did not work ("This image has no folder on disk") or that it achieved nothing (an undo
    // with no checkpoint), never its success phrase.
    const reason=failureReason(profile,route.tool,route.arguments,result);
    if(reason){
     active.answered=true;this.append('assistant',`${failureSentence(reason)} (${route.tool} ${JSON.stringify(route.arguments)})`);
     this.emit({type:'text_result',taskId:active.id,text:failureSentence(reason),outcome:'failed',fastPath:true});
     return;
    }
    // An instant step can leave work only the model can finish (a command's original Fiji dialog waiting): the request
    // continues with the backend, which sees what the step returned, instead of finishing as done.
    if(!failed&&profile.needsModel?.(route.tool,result,context)){
     this.append('assistant',`(${route.tool} ${JSON.stringify(route.arguments)} returned ${String(JSON.stringify(result)).slice(0,2000)})`);
    }else{
    // Object terminology belongs to this image and a successful request. Opening another image must also
    // preserve its real name, e.g. "hela cells" must never be spoken as "hela nuclei".
    if(!failed){
     if(/_(?:open_path|activate_image)$/.test(route.tool))this.objectWord='cells';
     else if(/\b(?:segment|find|detect|count)/i.test(text)||route.tool.endsWith('_segment_cells'))this.objectWord=/\bnucle/i.test(text)||/\bnuclei\b/i.test(route.reply)?'nuclei':'cells';
    }
    const echoObjects=!/_(?:open_path|activate_image|context)$/.test(route.tool);
    const echo=(sentence:string)=>echoObjects&&(this.objectWord==='nuclei'||/\bnucle(?:i|us|ar)\b/i.test(text))?sentence.replace(/\b(?:cells|objects)\b/g,'nuclei').replace(/\b(?:cell|object)\b/g,'nucleus'):sentence;
    // An instant route still reports the real outcome (e.g. the new cell count) when the profile can phrase it.
    const phrased=failed?'':echo(profile.localSentenceFor?.(route.tool,route.arguments,result,context)??'');
    // Long-running routes already spoke the action start; their final sentence carries only the real result.
    // Instant routes use the measured numeric outcome directly instead of repeating an action-start phrase.
    const detail=/\d/.test(phrased)?phrased:'';
    const reply=failed?`That did not work; check ${profile.displayName}.`:acknowledged?(phrased||'Done.'):(detail||route.reply);
    active.answered=true;this.append('assistant',`${reply} (${route.tool} ${JSON.stringify(route.arguments)})`);
    this.emit({type:'text_result',taskId:active.id,text:reply,fastPath:true,...(failed?{outcome:'failed'}:{})});
    return;
    }
   }
   // A control word without an instant route ends here: nothing is sent to the model.
   if(options.instantOnly)return;
   await this.backend.run({id:active.id,spoken,
    acknowledge:text=>{if(this.active===active&&!active.controller.signal.aborted)this.emit({type:'text_ack',taskId:active.id,text});},offsetMs:Date.now()-this.startedAt,context,signal:active.controller.signal,
    conversation:()=>({fragments:structuredClone(this.fragments),truncated:this.truncated}),
    update:update=>{if(this.active===active&&!active.controller.signal.aborted&&!active.answered)this.emit({type:'text_progress',taskId:active.id,text:update.content});},
   });
  }catch(error){
   if(this.active===active&&!active.controller.signal.aborted)this.emit({type:'text_error',taskId:active.id,message:error instanceof Error?error.message:'Text task failed.'});
  }finally{if(this.active===active){this.active=undefined;this.emit({type:'task_state',state:'idle',taskId:active.id});}}
 }
 stop(){
  const active=this.active;this.active=undefined;active?.controller.abort();
  if(active)this.emit({type:'task_state',state:'idle',taskId:active.id,reason:'Interrupted; completed actions and pending human decisions remain.'});
 }
}
