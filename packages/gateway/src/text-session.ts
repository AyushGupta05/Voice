import { randomUUID } from 'node:crypto';
import { ResponsesBackend, type BackendClient } from './backend.js';
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
  this.backend=new ResponsesBackend(runtime,client,model,(id,text)=>{
   const active=this.active;if(!active||active.id!==id||active.controller.signal.aborted)return;
   active.answered=true;this.append('assistant',text);this.emit({type:'text_result',taskId:id,text});
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
   const context=await this.runtime.call(`${profile.toolPrefix}_context`,{},active.controller.signal) as Context;
   active.controller.signal.throwIfAborted();
   // Spoken display-only commands with one unambiguous meaning skip the model entirely; a data route (undo) only
   // when policy executes it at once, since a review card needs the model's conversation to carry it.
   let route=spoken?profile.fastRoute(text,context):undefined;
   if(route&&!this.runtime.autoApprove){const risk=profile.tools.find(tool=>tool.name===route!.tool)?.risk;if(risk==='data'||risk==='code')route=undefined;}
   if(route){
    const result=await this.runtime.call(route.tool,route.arguments,active.controller.signal) as Record<string,unknown>|undefined;
    active.controller.signal.throwIfAborted();
    // View tools answer with the bridge payload; data tools with a Runtime envelope whose status must be completed.
    const failed=!result||typeof result!=='object'||result.error!=null||('status' in result&&result.status!=='completed');
    const reply=failed?`That did not work; check ${profile.displayName}.`:route.reply;
    active.answered=true;this.append('assistant',`${reply} (${route.tool} ${JSON.stringify(route.arguments)})`);
    this.emit({type:'text_result',taskId:active.id,text:reply,fastPath:true});
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
