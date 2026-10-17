import { randomUUID } from 'node:crypto';
import { ResponsesBackend, failureSentence, toolTask, type BackendClient } from './backend.js';
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
 async submit(text:unknown,spoken=false){
  if(typeof text!=='string'||!text.trim()||text.length>4000)throw new Error('Enter a message of 1–4000 characters.');
  this.active?.controller.abort();
  const active:Active={id:randomUUID(),controller:new AbortController(),answered:false};this.active=active;
  this.append('user',text.trim());this.emit({type:'task_state',state:'working',taskId:active.id});
  try{
   const {profile}=this.runtime;
   const context=await toolTask.run(active.id,()=>this.runtime.call(`${profile.toolPrefix}_context`,{},active.controller.signal)) as Context;
   active.controller.signal.throwIfAborted();
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
    try{result=await toolTask.run(active.id,()=>this.runtime.call(route!.tool,route!.arguments,active.controller.signal)) as Record<string,unknown>|undefined;}
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
    // Echo the researcher's word: "Finding the nuclei." is answered with nuclei, not cells.
    const echo=(sentence:string)=>/\bnuclei\b/i.test(route!.reply)?sentence.replace(/\bcells\b/g,'nuclei').replace(/\bcell\b/g,'nucleus'):sentence;
    // A data route says why it did not work ("This image has no folder on disk") or that it achieved nothing (an undo
    // with no checkpoint), never its success phrase.
    const reason=failureReason(profile,route.tool,route.arguments,result);
    if(reason){
     active.answered=true;this.append('assistant',`${failureSentence(reason)} (${route.tool} ${JSON.stringify(route.arguments)})`);
     this.emit({type:'text_result',taskId:active.id,text:failureSentence(reason),outcome:'failed',fastPath:true});
     return;
    }
    // An instant route still reports the real outcome (e.g. the new cell count) when the profile can phrase it.
    const phrased=failed?'':echo(profile.localSentenceFor?.(route.tool,route.arguments,result)??'');
    // Long-running routes already spoke the action start; their final sentence carries only the real result.
    // Instant routes use the measured numeric outcome directly instead of repeating an action-start phrase.
    const detail=/\d/.test(phrased)?phrased:'';
    const reply=failed?`That did not work; check ${profile.displayName}.`:acknowledged?(phrased||'Done.'):(detail||route.reply);
    active.answered=true;this.append('assistant',`${reply} (${route.tool} ${JSON.stringify(route.arguments)})`);
    this.emit({type:'text_result',taskId:active.id,text:reply,fastPath:true,...(failed?{outcome:'failed'}:{})});
    return;
   }
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
