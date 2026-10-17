/** Explicit real AWS speech check without a microphone: Polly speaks a fixed phrase, Transcribe hears it.
 * Uses the credit-guarded provider (shared reserve ledger, receipts). Makes two small speech requests only. */
import {resolve,join} from 'node:path';
import {AWSSpeechProvider,resampleSpeech} from '../packages/gateway/src/aws-speech.js';
if(!process.argv.includes('--authorized-aws-speech'))throw new Error('Pass --authorized-aws-speech to make two small credit-covered AWS speech requests.');
const state=join(resolve(process.env.LUMA_ROOT??process.cwd()),'.local/state');
const phrase='Show me the detected cells in this region.';
const provider=new AWSSpeechProvider(state);
const started=Date.now();
const pcm=resampleSpeech(await provider.speak(phrase,AbortSignal.timeout(20000)));
const synthesizedMs=Date.now()-started;
/** Real-time paced 100 ms frames, then 1.5 s of silence so Transcribe finalizes the utterance. */
async function* audio(){
 const frame=4800;
 for(let offset=0;offset<pcm.length;offset+=frame){yield pcm.subarray(offset,offset+frame);await new Promise(r=>setTimeout(r,100));}
 for(let i=0;i<15;i++){yield Buffer.alloc(frame);await new Promise(r=>setTimeout(r,100));}
}
const controller=new AbortController();let finalText='';let firstPartialMs:number|undefined;const heardAt=Date.now();
const done=provider.transcribe(audio(),controller.signal,{inputReady:()=>{},listening:()=>{},transcript:value=>{
 firstPartialMs??=Date.now()-heardAt;
 if(!value.partial){finalText=value.text;controller.abort();}
}}).catch(error=>{if(!controller.signal.aborted)throw error;});
await Promise.race([done,new Promise(r=>setTimeout(r,20000))]);controller.abort();
const words=(s:string)=>s.toLowerCase().replace(/[^a-z ]/g,'').split(/\s+/).filter(Boolean);
const expected=words(phrase),heard=new Set(words(finalText));
const matched=expected.filter(w=>heard.has(w)).length;
const result={phrase,finalText,wordMatch:`${matched}/${expected.length}`,synthesizedMs,firstPartialMs,audioSeconds:+(pcm.length/48000).toFixed(2),passed:matched>=expected.length-1};
console.log(JSON.stringify(result));
if(!result.passed)process.exitCode=1;
