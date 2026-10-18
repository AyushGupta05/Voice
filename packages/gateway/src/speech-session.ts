import {randomUUID} from 'node:crypto';
import {resampleSpeech,type SpeechProvider,type SpeechTranscript} from './aws-speech.js';

/** Bounded producer/consumer queue; close discards unsent microphone audio and wakes readers.
 * Transcribe can take a second or more to accept the stream while the microphone keeps producing, so the
 * buffer holds ten seconds and drops the oldest audio instead of failing the session. */
export class AudioQueue{
 private chunks:Uint8Array[]=[];private bytes=0;private wake?:()=>void;private closed=false;
 dropped=0;
 constructor(private maximumBytes=48000*10){}
 push(data:Uint8Array){if(this.closed)return;if(!data.length||data.length%2)throw new Error('Microphone PCM must contain complete signed 16-bit samples.');
  while(this.chunks.length&&this.bytes+data.length>this.maximumBytes){const old=this.chunks.shift()!;this.bytes-=old.length;this.dropped+=old.length;}
  this.chunks.push(data.slice());this.bytes+=data.length;this.wake?.();this.wake=undefined;}
 close(){this.closed=true;this.chunks=[];this.bytes=0;this.wake?.();this.wake=undefined;}
 async *stream(){while(!this.closed){const data=this.chunks.shift();if(data){this.bytes-=data.length;yield data;}else await new Promise<void>(resolve=>{this.wake=resolve;});}}
}
export function spokenReply(text:string){
 const clean=text.replace(/https?:\/\/\S+/g,'').replace(/[`*_#]/g,'').trim();
 if(Array.from(clean).length<=240)return clean||'The result is on screen.';
 const sentence=clean.match(/^.{1,200}?[.!?](?:\s|$)/u)?.[0]?.trim();
 return sentence?sentence+' More details are on screen.':'The result is ready on screen.';
}
export type VoiceConversation={submit:(text:string,spoken?:boolean)=>Promise<void>;stop:()=>void;
 /** A stop word after its barge-in, for a profile whose instant routes act on it (Fidelis: "cancel" discards a waiting plan). Never reaches the model. */
 control?:(text:string)=>Promise<void>};
const wordCount=(text:string)=>text.toLowerCase().replace(/[^a-z0-9' ]+/g,' ').split(/\s+/).filter(Boolean).length;
/** Short commands worth acting on even as one or two words; anything else that short is treated as room chatter. */
const SHORT_COMMANDS=/^(?:(?:hey |ok |okay )?(?:penny|luma)\b.*|(?:stop|cancel)(?: (?:that|it))?|undo(?: that)?|go back|redo|rido|re do|yes|no|confirm|proceed|stricter|tighter|looser|brighter|lighter|darker|zoom (?:in|out)|(?:show|hide) (?:cells|annotations|detections)|save(?: it)?|next|previous|close(?: it)?|apply|again|repeat(?: that)?)$/;
const STOP_COMMAND=/^(?:please )?(?:stop|cancel)(?: (?:that|it))?(?: please)?[.!?]*$/i;
/** The microphone stays open for 30 minutes, so fragments like "OK." or "Bye." must not become model requests. */
export function isAddressedToLuma(text:string){
 const clean=text.toLowerCase().replace(/[^a-z0-9' ]+/g,' ').replace(/\s+/g,' ').trim();
 if(!clean)return false;
 if(SHORT_COMMANDS.test(clean))return true;
 // Single stray words ("OK.", "Bye.", "Really.") are chatter; two or more words are treated as a request.
 return wordCount(clean)>=2;
}
/** "Hey Penny", "Penny," or "…, Penny" anywhere in the sentence; the match is stripped from the request. */
const WAKE_WORD=/(?:^|[\s,.;:!?-])(?:hey |ok |okay |hi )?(?:penny|pennie|penne|penni|luma|looma|lumo|luna)\b[,.:!?]?\s*/i;
/** How long after Penny last heard a request or spoke that follow-ups need no wake word. */
export const ATTENTION_MS=30_000;
/** Off by default: the researcher expects to just talk. LUMA_WAKE_WORD=on requires "Penny, …" (busy rooms); stray single words are always ignored. */
export const wakeWordRequired=()=>process.env.LUMA_WAKE_WORD==='on';
/**
 * Decide whether a final transcript is for Penny. With the wake word required (default, for rooms with other
 * people talking), a sentence is accepted when it names Penny, is a short command, or arrives while Penny is
 * attentive (within ATTENTION_MS of the last request or reply). Returns the request text with the wake word removed.
 */
export function addressed(text:string,options:{wakeWord:boolean;attentive:boolean}):{accept:boolean;request:string;reason:string}{
 const trimmed=text.trim();
 const named=WAKE_WORD.test(trimmed);
 const request=named?trimmed.replace(WAKE_WORD,' ').replace(/\s+/g,' ').replace(/^[\s,;:]+|[\s,;:]+$/g,''):trimmed;
 if(named){return request&&wordCount(request)>=1?{accept:true,request,reason:'wake word'}:{accept:true,request:'Yes?',reason:'wake word only'};}
 if(!isAddressedToLuma(trimmed))return{accept:false,request,reason:'fragment'};
 if(!options.wakeWord)return{accept:true,request,reason:'wake word disabled'};
 const clean=trimmed.toLowerCase().replace(/[^a-z0-9' ]+/g,' ').replace(/\s+/g,' ').trim();
 if(SHORT_COMMANDS.test(clean))return{accept:true,request,reason:'short command'};
 if(options.attentive)return{accept:true,request,reason:'attentive'};
 return{accept:false,request,reason:'not addressed to Penny (say "Penny, …")'};
}
export class SpeechSession{
 readonly id=randomUUID();private controller=new AbortController();private input=new AudioQueue();
 private inputReady=false;private seen=new Set<string>();private hearing=false;private turns=0;private replies=0;private bytes=0;
 private started=Date.now();private timer?:ReturnType<typeof setTimeout>;private speeches=new Set<AbortController>();private epoch=0;
 private running?:Promise<void>;private closed=false;private interrupts=0;private tail:Promise<void>=Promise.resolve();
 private droppedBeforeReady=0;private loggedBytes=0;
 private attentiveUntil=0;private readonly wakeWord:boolean;
 constructor(private provider:SpeechProvider,private conversation:VoiceConversation,private emit:(event:Record<string,unknown>)=>void,private onClosed:()=>void,private maximumMs=1_800_000,options:{wakeWord?:boolean}={}){
  if(!Number.isInteger(maximumMs)||maximumMs<1||maximumMs>1_800_000)throw new Error('Voice lifetime must not exceed 30 minutes.');
  this.wakeWord=options.wakeWord??wakeWordRequired();
 }
 /** Penny is attentive right after a request or its own reply, so natural follow-ups need no wake word. */
 private attentive(){return Date.now()<this.attentiveUntil;}
 private engage(){this.attentiveUntil=Date.now()+ATTENTION_MS;}
 private event(event:Record<string,unknown>){if(!this.closed)this.emit({...event,sessionId:this.id});}
 start(){
  if(this.running||this.closed)return;
  this.event({type:'voice_state',state:'connecting'});
  this.timer=setTimeout(()=>this.stop('Voice session reached its time limit.'),this.maximumMs);this.timer.unref();
  this.running=Promise.resolve().then(()=>this.provider.transcribe(this.input.stream(),this.controller.signal,{
   inputReady:()=>{if(!this.closed){this.inputReady=true;this.log('ready for microphone audio');this.event({type:'voice_state',state:'ready_for_audio'});}},
   listening:()=>{this.log(`transcriber listening (${this.input.dropped} bytes dropped while connecting)`);this.event({type:'voice_state',state:'listening'});void this.provider.warmPhrases?.(this.controller.signal);},
   transcript:value=>this.transcript(value),
  })).catch(error=>{const message=error instanceof Error?error.message:'AWS speech stopped.';this.log(`error: ${message}`);if(!this.closed)this.event({type:'voice_error',message});})
   .finally(()=>this.stop('Speech stream closed.'));
 }
 private log(message:string){console.error(`[voice ${new Date().toISOString()} ${this.id.slice(0,8)}] ${message}`);}
 acceptAudio(value:unknown){
  if(this.closed)return;
  if(!this.inputReady){if(!this.droppedBeforeReady++)this.log('microphone audio arrived before the transcriber was ready; dropping until ready');return;}
  if(typeof value!=='string'||value.length>64000)throw new Error('Invalid microphone audio frame.');
  const data=Buffer.from(value,'base64');if(data.toString('base64')!==value||!data.length||data.length%2)throw new Error('Microphone PCM must contain complete signed16-bit samples.');
  const total=this.bytes+data.length;
  if(total>this.maximumMs*48||total>(Date.now()-this.started+1000)*48)throw new Error('Microphone stream exceeded its duration/rate bound.');
  this.input.push(data);this.bytes=total;
  if(this.bytes-this.loggedBytes>=48000*5||this.loggedBytes===0){this.loggedBytes=this.bytes;this.log(`${this.bytes} bytes of microphone audio received (${(this.bytes/48000).toFixed(1)} s)`);}
 }
 private transcript(value:SpeechTranscript){
  if(this.closed||this.seen.has(value.id))return;
  const text=value.text.trim();if(!text)return;if(text.length>4000){this.stop('Spoken request is too long.');return;}
  if(!value.partial)this.log(`heard: ${JSON.stringify(text)}`);
  // Use the same gate for interruption and submission: an unaddressed fragment cannot stop a real task,
  // while one-word controls such as "stop" must work even during Penny's reply or in wake-word mode.
  const decision=addressed(text,{wakeWord:this.wakeWord,attentive:this.attentive()});
  const forLuma=decision.accept;
  if(!this.hearing&&forLuma){this.hearing=true;this.interrupt();}
  this.event({type:'voice_transcript',text,partial:value.partial,addressed:forLuma});
  if(value.partial)return;
  this.hearing=false;this.seen.add(value.id);
  if(!decision.accept){this.log(`ignored (${decision.reason}): ${JSON.stringify(text)}`);this.event({type:'voice_ignored',text,reason:decision.reason});return;}
  if(++this.turns>200){this.stop('Voice turn limit reached.');return;}
  this.engage();
  // Stop is a transport control, not a scientific request. Interruption above already stopped the task
  // and playback; sending it to the model could start fresh work or synthesize an obsolete result.
  if(STOP_COMMAND.test(decision.request)){void this.conversation.control?.(decision.request).catch(error=>{if(!this.closed)this.event({type:'voice_error',message:error instanceof Error?error.message:'Voice request failed.'});});return;}
  void this.provider.prepare?.(this.controller.signal).catch(()=>undefined);
  void this.conversation.submit(decision.request,true).catch(error=>{if(!this.closed)this.event({type:'voice_error',message:error instanceof Error?error.message:'Voice request failed.'});});
 }
 private cancelSpeech(){for(const speech of this.speeches)speech.abort();this.speeches.clear();this.tail=Promise.resolve();}
 interrupt(){if(this.closed)return;this.interrupts++;this.epoch++;this.cancelSpeech();this.conversation.stop();this.event({type:'interrupted',epoch:this.epoch});}
 /** queued=true plays after the previous reply (an acknowledgment) instead of replacing it. Interruption cancels both. */
 async speak(text:string,queued=false){
  if(this.closed)return;if(++this.replies>400){this.stop('Spoken reply limit reached.');return;}
  this.engage();
  if(!queued)this.cancelSpeech();
  const speech=new AbortController();const interrupts=this.interrupts;const prior=queued?this.tail:Promise.resolve();
  // Include queued synthesis in cancellation ownership before it starts. A new reply must cancel both
  // the previous acknowledgment and its final answer, even if the provider finishes them out of order.
  this.speeches.add(speech);
  const run=(async()=>{
   // Synthesis starts at once; only playback waits for the previous reply to finish sending.
   const pending=this.provider.speak(spokenReply(text),AbortSignal.any([this.controller.signal,speech.signal]));
   try{
    const pcm=await pending;await prior.catch(()=>undefined);
    if(this.closed||speech.signal.aborted||this.interrupts!==interrupts)return;
    const output=resampleSpeech(pcm);const epoch=++this.epoch;this.event({type:'voice_speaking',epoch,queued});
    for(let offset=0;offset<output.length;offset+=48000){this.event({type:'audio',audio:output.subarray(offset,offset+48000).toString('base64'),sampleRate:24000,epoch,final:offset+48000>=output.length});}
   }catch(error){if(!this.closed&&!speech.signal.aborted&&this.interrupts===interrupts)this.event({type:'voice_error',message:error instanceof Error?error.message:'Spoken response unavailable.'});}
   finally{this.speeches.delete(speech);}
  })();
  this.tail=run;return run;
 }
 stop(reason='Voice stopped.'){
  if(this.closed)return;this.log(`stopped: ${reason} after ${(this.bytes/48000).toFixed(1)} s of audio, ${this.turns} turns`);this.closed=true;clearTimeout(this.timer);this.controller.abort();this.cancelSpeech();this.input.close();this.conversation.stop();
  this.emit({type:'voice_state',state:'idle',sessionId:this.id,reason});this.onClosed();
 }
 async settled(){await this.running;}
}
