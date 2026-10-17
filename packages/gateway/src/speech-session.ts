import {randomUUID} from 'node:crypto';
import {resampleSpeech,type SpeechProvider,type SpeechTranscript} from './aws-speech.js';

/** Small bounded producer/consumer queue; close discards unsent microphone audio and wakes readers. */
export class AudioQueue{
 private chunks:Uint8Array[]=[];private bytes=0;private wake?:()=>void;private closed=false;
 push(data:Uint8Array){if(this.closed)return;if(!data.length||data.length%2||this.bytes+data.length>48000)throw new Error('Microphone audio exceeded its one-second send buffer.');this.chunks.push(data.slice());this.bytes+=data.length;this.wake?.();this.wake=undefined;}
 close(){this.closed=true;this.chunks=[];this.bytes=0;this.wake?.();this.wake=undefined;}
 async *stream(){while(!this.closed){const data=this.chunks.shift();if(data){this.bytes-=data.length;yield data;}else await new Promise<void>(resolve=>{this.wake=resolve;});}}
}
export function spokenReply(text:string){
 const clean=text.replace(/https?:\/\/\S+/g,'').replace(/[`*_#]/g,'').trim();
 if(Array.from(clean).length<=240)return clean||'The result is on screen.';
 const sentence=clean.match(/^.{1,200}?[.!?](?:\s|$)/u)?.[0]?.trim();
 return sentence?sentence+' More details are on screen.':'The result is ready on screen.';
}
export type VoiceConversation={submit:(text:string,spoken?:boolean)=>Promise<void>;stop:()=>void};
export class SpeechSession{
 readonly id=randomUUID();private controller=new AbortController();private input=new AudioQueue();
 private inputReady=false;private seen=new Set<string>();private hearing=false;private turns=0;private replies=0;private bytes=0;
 private started=Date.now();private timer?:ReturnType<typeof setTimeout>;private speech?:AbortController;private epoch=0;
 private running?:Promise<void>;private closed=false;
 constructor(private provider:SpeechProvider,private conversation:VoiceConversation,private emit:(event:Record<string,unknown>)=>void,private onClosed:()=>void,private maximumMs=180000){
  if(!Number.isInteger(maximumMs)||maximumMs<1||maximumMs>180000)throw new Error('Voice lifetime must not exceed180seconds.');
 }
 private event(event:Record<string,unknown>){if(!this.closed)this.emit({...event,sessionId:this.id});}
 start(){
  if(this.running||this.closed)return;
  this.event({type:'voice_state',state:'connecting'});
  this.timer=setTimeout(()=>this.stop('Voice session reached its time limit.'),this.maximumMs);this.timer.unref();
  this.running=Promise.resolve().then(()=>this.provider.transcribe(this.input.stream(),this.controller.signal,{
   inputReady:()=>{if(!this.closed){this.inputReady=true;this.event({type:'voice_state',state:'ready_for_audio'});}},
   listening:()=>this.event({type:'voice_state',state:'listening'}),
   transcript:value=>this.transcript(value),
  })).catch(error=>{if(!this.closed)this.event({type:'voice_error',message:error instanceof Error?error.message:'AWS speech stopped.'});})
   .finally(()=>this.stop('Speech stream closed.'));
 }
 acceptAudio(value:unknown){
  if(this.closed||!this.inputReady)return;
  if(typeof value!=='string'||value.length>64000)throw new Error('Invalid microphone audio frame.');
  const data=Buffer.from(value,'base64');if(data.toString('base64')!==value||!data.length||data.length%2)throw new Error('Microphone PCM must contain complete signed16-bit samples.');
  const total=this.bytes+data.length;
  if(total>this.maximumMs*48||total>(Date.now()-this.started+1000)*48)throw new Error('Microphone stream exceeded its duration/rate bound.');
  this.input.push(data);this.bytes=total;
 }
 private transcript(value:SpeechTranscript){
  if(this.closed||this.seen.has(value.id))return;
  const text=value.text.trim();if(!text)return;if(text.length>4000){this.stop('Spoken request is too long.');return;}
  if(!this.hearing){this.hearing=true;this.interrupt();}
  this.event({type:'voice_transcript',text,partial:value.partial});
  if(value.partial)return;
  this.hearing=false;this.seen.add(value.id);if(++this.turns>16){this.stop('Voice turn limit reached.');return;}
  void this.provider.prepare?.(this.controller.signal).catch(()=>undefined);
  void this.conversation.submit(text,true).catch(error=>{if(!this.closed)this.event({type:'voice_error',message:error instanceof Error?error.message:'Voice request failed.'});});
 }
 interrupt(){if(this.closed)return;this.epoch++;this.speech?.abort();this.speech=undefined;this.conversation.stop();this.event({type:'interrupted',epoch:this.epoch});}
 async speak(text:string){
  if(this.closed)return;if(++this.replies>8){this.stop('Spoken reply limit reached.');return;}
  this.speech?.abort();const speech=new AbortController();this.speech=speech;const epoch=++this.epoch;
  try{
   const pcm=await this.provider.speak(spokenReply(text),AbortSignal.any([this.controller.signal,speech.signal]));
   if(this.closed||speech.signal.aborted||this.speech!==speech)return;
   const output=resampleSpeech(pcm);this.event({type:'voice_speaking',epoch});
   for(let offset=0;offset<output.length;offset+=48000){this.event({type:'audio',audio:output.subarray(offset,offset+48000).toString('base64'),sampleRate:24000,epoch,final:offset+48000>=output.length});}
  }catch(error){if(!this.closed&&!speech.signal.aborted)this.event({type:'voice_error',message:error instanceof Error?error.message:'Spoken response unavailable.'});}
 }
 stop(reason='Voice stopped.'){
  if(this.closed)return;this.closed=true;clearTimeout(this.timer);this.controller.abort();this.speech?.abort();this.input.close();this.conversation.stop();
  this.emit({type:'voice_state',state:'idle',sessionId:this.id,reason});this.onClosed();
 }
 async settled(){await this.running;}
}
