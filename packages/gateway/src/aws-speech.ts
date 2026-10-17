import {appendFile} from 'node:fs/promises';
import {join} from 'node:path';
import {TranscribeStreamingClient,StartStreamTranscriptionCommand} from '@aws-sdk/client-transcribe-streaming';
import {PollyClient,SynthesizeSpeechCommand} from '@aws-sdk/client-polly';
import {CreditBudget,sharedObservation} from './credit-budget.js';
import {loadSpeechConfig} from './speech-config.js';
import {reusableClient} from './aws-clients.js';
import {observeSpeechCredits,SPEECH_REGION,MAX_VOICE_SECONDS,MAX_SPOKEN_CHARACTERS,VOICE,type SpeechProof} from './aws-speech-proof.js';

export type SpeechTranscript={id:string;text:string;partial:boolean};
export type SpeechCallbacks={inputReady:()=>void;listening:()=>void;transcript:(value:SpeechTranscript)=>void};
export interface SpeechProvider{
 transcribe(audio:AsyncIterable<Uint8Array>,signal:AbortSignal,callbacks:SpeechCallbacks):Promise<void>;
 speak(text:string,signal:AbortSignal):Promise<Uint8Array>;
 /** Optional read-only warm-up while the agent works; never sends speech or reserves credit. */
 prepare?(signal:AbortSignal):Promise<void>;
 /** Optional: pre-synthesize short acknowledgment phrases. */
 warmPhrases?(signal:AbortSignal):Promise<void>;
}
export type SpeechDependencies={observe:(signal:AbortSignal)=>Promise<SpeechProof>;
 transcribe:(proof:SpeechProof,audio:AsyncIterable<Uint8Array>,signal:AbortSignal,onReady:()=>void,onTranscript:(value:SpeechTranscript)=>void)=>Promise<void>;
 synthesize:(proof:SpeechProof,text:string,signal:AbortSignal)=>Promise<Uint8Array>};
const pollyClient=reusableClient(credentials=>new PollyClient({region:SPEECH_REGION,credentials,maxAttempts:1,endpoint:'https://polly.us-east-1.amazonaws.com'}));
const production:SpeechDependencies={observe:sharedObservation(observeSpeechCredits),
 transcribe:async(proof,audio,signal,onReady,onTranscript)=>{
  const client=new TranscribeStreamingClient({region:SPEECH_REGION,credentials:proof.credentials,maxAttempts:1,endpoint:'https://transcribestreaming.us-east-1.amazonaws.com'});
  try{
   async function* stream(){for await(const AudioChunk of audio)yield{AudioEvent:{AudioChunk}};}
   const connecting=Date.now();
   console.error(`[voice ${new Date().toISOString()}] connecting to Amazon Transcribe`);
   const result=await client.send(new StartStreamTranscriptionCommand({LanguageCode:'en-GB',MediaEncoding:'pcm',MediaSampleRateHertz:24000,EnablePartialResultsStabilization:true,PartialResultsStability:'high',AudioStream:stream()}),{abortSignal:signal});
   console.error(`[voice ${new Date().toISOString()}] Transcribe stream accepted after ${Date.now()-connecting} ms`);
   signal.throwIfAborted();onReady();if(!result.TranscriptResultStream)throw new Error('AWS returned no transcript stream.');
   for await(const event of result.TranscriptResultStream){signal.throwIfAborted();
    if(!event.TranscriptEvent)throw new Error('AWS transcription stopped with a service error.');
    for(const value of event.TranscriptEvent.Transcript?.Results??[]){const text=value.Alternatives?.[0]?.Transcript;
     if(value.ResultId&&text)onTranscript({id:value.ResultId,text,partial:value.IsPartial!==false});}
   }
  }finally{client.destroy();}
 },
 synthesize:async(proof,text,signal)=>{
  const client=pollyClient(proof.credentials);
  {const result=await client.send(new SynthesizeSpeechCommand({Engine:VOICE.engine,VoiceId:VOICE.id as any,LanguageCode:VOICE.language as any,OutputFormat:'pcm',SampleRate:'16000',TextType:'text',Text:text}),{abortSignal:signal});
   if(!result.AudioStream||result.ContentType!=='audio/pcm')throw new Error('AWS returned no supported speech audio.');
   const chunks:Uint8Array[]=[];let bytes=0;
   for await(const chunk of result.AudioStream as AsyncIterable<Uint8Array>){signal.throwIfAborted();bytes+=chunk.length;if(bytes>2_000_000)throw new Error('Speech audio exceeded its bounded size.');chunks.push(chunk);}
   const data=Buffer.concat(chunks);if(!data.length||data.length%2)throw new Error('AWS returned incomplete PCM samples.');return data;
  }
 }
};
/** Process-wide cache of short spoken phrases (acknowledgments), so repeats play with no synthesis delay. */
const phraseAudio=new Map<string,Uint8Array>();
export const ACKNOWLEDGMENT_PHRASES=['Zooming in.','Zooming out.','Moving there.','Taking you there.','Right here.','Checking.','Updating the view.','Selecting them.'];
/** Separate speech receipts; all attempts still reserve on the unchanged shared $200 ledger. */
export class AWSSpeechProvider implements SpeechProvider{
 private budget:CreditBudget;
 constructor(private state:string,private dependencies:SpeechDependencies=production){this.budget=new CreditBudget(state);}
 private async proof(signal:AbortSignal){
  signal.throwIfAborted();
  const config=await loadSpeechConfig(this.state);const proof=await this.dependencies.observe(AbortSignal.any([signal,AbortSignal.timeout(25000)]));signal.throwIfAborted();
  if(proof.accountHash!==config.accountHash||Date.parse(config.expiresAt)<=Date.now())throw new Error('Speech credit identity or expiry changed before dispatch.');return{...proof,expiresAt:config.expiresAt};
 }
 private async receipt(value:Record<string,unknown>){await appendFile(join(this.state,'speech-usage.jsonl'),JSON.stringify({at:new Date().toISOString(),...value,billingReconciled:false})+'\n',{mode:0o600});}
 async transcribe(audio:AsyncIterable<Uint8Array>,external:AbortSignal,callbacks:SpeechCallbacks){
  const signal=AbortSignal.any([external,AbortSignal.timeout(MAX_VOICE_SECONDS*1000)]);
  const proof=await this.proof(signal);const reservation=await this.budget.reserve({...proof,maximumRequestUSD:MAX_VOICE_SECONDS*proof.rates.transcribePerSecondUSD},signal);
  const started=Date.now();let complete=false;
  try{signal.throwIfAborted();if(Date.parse(proof.expiresAt)<=Date.now())throw new Error('Speech funding expired before dispatch.');callbacks.inputReady();signal.throwIfAborted();await this.dependencies.transcribe(proof,audio,signal,callbacks.listening,callbacks.transcript);complete=true;}
  finally{const seconds=Math.min(MAX_VOICE_SECONDS,Math.ceil((Date.now()-started)/1000));await this.receipt({id:reservation.id,service:'transcribe',outcome:complete?'completed':'unconfirmed',boundedElapsedSeconds:seconds,estimatedUSD:seconds*proof.rates.transcribePerSecondUSD}).catch(()=>undefined);}
 }
 async prepare(signal:AbortSignal){await this.dependencies.observe(signal);}
 async speak(text:string,external:AbortSignal){
  const cached=phraseAudio.get(text);if(cached){external.throwIfAborted();return cached;}
  const audio=await this.synthesizeFresh(text,external);
  if(Array.from(text).length<=40&&phraseAudio.size<64)phraseAudio.set(text,audio);
  return audio;
 }
 /** Pre-synthesize acknowledgment phrases once per gateway; each is a small credit-guarded Polly request. */
 async warmPhrases(signal:AbortSignal){for(const phrase of ACKNOWLEDGMENT_PHRASES){if(signal.aborted)return;if(!phraseAudio.has(phrase))await this.speak(phrase,signal).catch(()=>undefined);}}
 private async synthesizeFresh(text:string,external:AbortSignal){
  const characters=Array.from(text).length;if(!characters||characters>MAX_SPOKEN_CHARACTERS)throw new Error('Spoken reply exceeds the approved character bound.');
  const signal=AbortSignal.any([external,AbortSignal.timeout(20000)]);const proof=await this.proof(signal);
  const reservation=await this.budget.reserve({...proof,maximumRequestUSD:characters*proof.rates.pollyPerCharacterUSD},signal);let complete=false;
  try{signal.throwIfAborted();if(Date.parse(proof.expiresAt)<=Date.now())throw new Error('Speech funding expired before dispatch.');const data=await this.dependencies.synthesize(proof,text,signal);signal.throwIfAborted();complete=true;return data;}
  finally{await this.receipt({id:reservation.id,service:'polly',outcome:complete?'completed':'unconfirmed',characters,estimatedUSD:characters*proof.rates.pollyPerCharacterUSD}).catch(()=>undefined);}
 }
}

/** Polly PCM is16kHz; preserve duration/pitch at the native24kHz boundary. */
export function resampleSpeech(pcm:Uint8Array):Buffer{
 if(!pcm.length||pcm.length%2||pcm.length>2_000_000)throw new Error('Invalid16kHz speech PCM.');
 const input=Buffer.from(pcm),count=input.length/2,out=Buffer.alloc(Math.ceil(count*1.5)*2);
 for(let i=0;i<out.length/2;i++){const position=i*2/3,left=Math.min(count-1,Math.floor(position)),right=Math.min(count-1,left+1),fraction=position-left;
  out.writeInt16LE(Math.round(input.readInt16LE(left*2)*(1-fraction)+input.readInt16LE(right*2)*fraction),i*2);}
 return out;
}
