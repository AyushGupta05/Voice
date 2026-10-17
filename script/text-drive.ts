/** Drive the running gateway's agent with a typed request over the authenticated companion socket and print
 * what happened: tools called, acknowledgment, final sentence, timings. Spoken style is requested so the run
 * matches a voice turn, without needing the microphone slot. Usage: text-drive.ts "Luma, analyse the cells here" */
import {readFile} from 'node:fs/promises';
import {randomUUID} from 'node:crypto';
import {resolve,join} from 'node:path';
import {WebSocket} from 'ws';
const request=process.argv.slice(2).filter(a=>!a.startsWith('--')).join(' ').trim();
if(!request)throw new Error('Give the request text as the argument.');
const root=resolve(process.env.LUMA_ROOT??process.cwd()),state=join(root,'.local/state');
const token=(await readFile(join(state,'companion-token'),'utf8')).trim();
const ws=new WebSocket('ws://127.0.0.1:49555/events',{headers:{Authorization:`Bearer ${token}`}});
const t0=Date.now();const tools:string[]=[];const highlights:string[]=[];let ack='';let reply='';let error='';
const done=new Promise<void>((resolveDone,reject)=>{
 const timer=setTimeout(()=>{error='timed out after 180 s';resolveDone();},180_000);
 ws.on('open',()=>{ws.send(JSON.stringify({type:'hello',clientId:`text-drive-${randomUUID().slice(0,8)}`}));ws.send(JSON.stringify({type:'text',text:request,spoken:true}));});
 ws.on('message',raw=>{const e=JSON.parse(raw.toString());
  if(e.type==='tool'&&e.name&&!/_context$/.test(e.name)){tools.push(`${e.name}@${Date.now()-t0}ms`);const r=e.result?.result??e.result;if(r&&typeof r==='object'&&('highlight' in r||'pointer' in r))highlights.push(e.name);}
  else if(e.type==='text_ack')ack=`${e.text}@${Date.now()-t0}ms`;
  else if(e.type==='text_result'){reply=`${e.text}@${Date.now()-t0}ms`;}
  else if(e.type==='text_error'||e.type==='error'){error=e.message;}
  else if(e.type==='task_state'&&e.state==='idle'&&(reply||error)){clearTimeout(timer);resolveDone();}
 });
 ws.on('error',reject);
});
await done;ws.close();
console.log(JSON.stringify({request,tools,cursorMoves:highlights,ack,reply,error},null,1));
