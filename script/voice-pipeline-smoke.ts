/** Real end-to-end voice check through the running gateway, without a microphone.
 * Polly synthesizes a spoken view request; it is streamed as companion microphone PCM over the authenticated
 * /events socket, so AWS Transcribe, the OpenAI Bedrock agent, actual QuPath and the Polly reply all run for real.
 * The request is a view-only focus; any approval request fails the check instead of being approved. */
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {randomUUID} from 'node:crypto';
import {resolve,join} from 'node:path';
import {WebSocket} from 'ws';
import {AWSSpeechProvider,resampleSpeech} from '../packages/gateway/src/aws-speech.js';
if(!process.argv.includes('--authorized-aws-speech'))throw new Error('Pass --authorized-aws-speech: makes small credit-covered speech and model requests and moves the real QuPath viewer.');
const root=resolve(process.env.LUMA_ROOT??process.cwd()),state=join(root,'.local/state');
const phrase=process.argv.find(a=>a.startsWith('--say='))?.slice(6)??'Zoom in on the centre of this slide.';
const pcm=resampleSpeech(await new AWSSpeechProvider(state).speak(phrase,AbortSignal.timeout(20000)));
const token=(await readFile(join(state,'companion-token'),'utf8')).trim();
const ws=new WebSocket('ws://127.0.0.1:49555/events',{headers:{Authorization:`Bearer ${token}`}});
const requestId=randomUUID();const timeline:Record<string,number>={};const t0=Date.now();const mark=(k:string)=>{timeline[k]??=Date.now()-t0;};
const allowApproval=process.argv.includes('--allow-approval');const decisions:Record<string,unknown>[]=[];
let sessionId:string|undefined;let transcript='';let reply='';let ack='';let finals=0;let audioBytes=0;const tools:string[]=[];
const done=new Promise<void>((resolveDone,reject)=>{
 const timer=setTimeout(()=>reject(new Error('Voice pipeline timed out.')),allowApproval?240000:60000);
 // Done once the answer arrived and no further (queued) reply audio follows for 2.5 s.
 let quiet:ReturnType<typeof setTimeout>|undefined;const settle=()=>{if(!reply||!finals)return;clearTimeout(quiet);quiet=setTimeout(()=>{clearTimeout(timer);resolveDone();},2500);};
 ws.on('message',raw=>{const event=JSON.parse(raw.toString());
  if(event.type==='connected')ws.send(JSON.stringify({type:'start_voice',requestId}));
  else if(event.type==='voice_state'&&event.requestId===requestId){
   if(event.state==='connecting')sessionId=event.sessionId;
   if(event.state==='ready_for_audio'){mark('readyForAudio');void stream();}
   if(event.state==='listening')mark('listening');
  }
  else if(event.type==='voice_transcript'&&!event.partial){transcript=event.text;mark('finalTranscript');}
  else if(event.type==='voice_transcript')mark('firstPartial');
  else if(event.type==='tool'&&event.name){tools.push(event.name);if(event.name!=='qupath_context')mark('firstAction');}
  else if(event.type==='action'&&event.action?.status==='pending'&&Date.parse(event.action.createdAt)>=t0){
   if(!allowApproval){clearTimeout(timer);reject(new Error('Unexpected approval request; this check never approves.'));}
   else{mark('approvalRequested');console.error(`APPROVAL NEEDED in Luma: ${event.action.tool} ${JSON.stringify(event.action.arguments)}`);}
  }
  else if(event.type==='action'&&['completed','failed','denied','expired'].includes(event.action?.status)&&Date.parse(event.action.createdAt)>=t0){mark('approvalDecided:'+event.action.status);decisions.push({tool:event.action.tool,status:event.action.status,error:event.action.error});}
  else if(event.type==='text_ack'){ack=event.text;mark('acknowledged');}
  else if(event.type==='text_result'){reply=event.text;mark('agentReply');settle();}
  else if(event.type==='voice_speaking'){mark('speaking');if(event.queued)mark('queuedSpeaking');}
  else if(event.type==='audio'&&event.requestId===requestId){audioBytes+=Buffer.from(event.audio,'base64').length;if(event.final){finals++;mark('firstAudioComplete');settle();}}
  else if(event.type==='voice_error'||event.type==='error'||event.type==='text_error'){clearTimeout(timer);reject(new Error(event.message));}
 });
 ws.on('error',reject);
});
async function stream(){
 const frame=4800,silence=Buffer.alloc(frame);const send=(b:Buffer)=>ws.send(JSON.stringify({type:'audio',sessionId,audio:b.toString('base64')}));
 const pace=()=>new Promise(r=>setTimeout(r,100));
 for(let i=0;i<5;i++){send(silence);await pace();}
 for(let o=0;o<pcm.length;o+=frame){send(pcm.subarray(o,o+frame));await pace();}
 mark('speechSent');
 while(!reply&&ws.readyState===WebSocket.OPEN&&Date.now()-t0<(allowApproval?235000:55000)){send(silence);await pace();}
}
try{await done;}finally{if(ws.readyState===WebSocket.OPEN){ws.send(JSON.stringify({type:'stop_voice',requestId}));await new Promise(r=>setTimeout(r,300));ws.close();}}
const result={phrase,transcript,acknowledgment:ack,reply,decisions,tools,replyAudioSeconds:+(audioBytes/48000).toFixed(2),timelineMs:timeline,
 speechEndToReplyAudioMs:timeline.speaking!==undefined&&timeline.speechSent!==undefined?timeline.speaking-timeline.speechSent:undefined};
console.log(JSON.stringify(result,null,1));
assert(transcript,'No final transcript.');assert(reply,'No agent reply.');assert(audioBytes>0,'No spoken reply audio.');
