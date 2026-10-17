import { test, type TestContext } from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { WebSocket, WebSocketServer } from 'ws';
import { LiveSession, type DelegationBackend, type LiveOptions, type DelegatedTask } from '../src/live.js';

async function eventually(predicate: () => boolean) {
  const deadline=Date.now()+2000;
  while(!predicate()) { if(Date.now()>deadline)throw new Error('Expected protocol event did not arrive.'); await new Promise(resolve=>setTimeout(resolve,5)); }
}
async function fixture(t: TestContext, backend: DelegationBackend, options: LiveOptions = {}, acknowledgeClose = true) {
  const server=new WebSocketServer({port:0,host:'127.0.0.1'});await once(server,'listening');
  const address=server.address();assert(address&&typeof address==='object');
  const received:Record<string,any>[]=[];const events:Record<string,unknown>[]=[];
  const peerReady=new Promise<WebSocket>(resolve=>server.once('connection',peer=>{
    peer.on('message',raw=>{
      const event=JSON.parse(raw.toString());received.push(event);
      if(acknowledgeClose&&event.type==='session.close')peer.send(JSON.stringify({type:'session.closed',usage:{seconds:0.321}}));
    });resolve(peer);
  }));
  const wire=new WebSocket(`ws://127.0.0.1:${address.port}`,{maxPayload:1024*1024});
  const session=new LiveSession({execute:async()=>({contextKey:'image-and-region',selectionCount:899})},backend,event=>events.push(event),{maxSessionMs:5000,readyTimeoutMs:1000,closeTimeoutMs:100,...options});
  session.start(wire);const peer=await peerReady;
  t.after(async()=>{await session.close();for(const client of server.clients)client.terminate();await new Promise<void>(resolve=>server.close(()=>resolve()));});
  await eventually(()=>received.some(event=>event.type==='session.start'));
  async function ready(){peer.send(JSON.stringify({type:'session.started',session:{id:'test-live'}}));await eventually(()=>events.some(event=>event.type==='voice_state'&&event.state==='listening'));}
  function send(event:Record<string,unknown>){peer.send(JSON.stringify(event));}
  return {session,peer,wire,received,events,ready,send};
}

test('Live startup waits for session.started and carries complete PCM samples in both directions',async t=>{
  const f=await fixture(t,{run:async()=>{}});
  const start=f.received[0];assert.equal(start.session.model,'gpt-live-1');assert.equal(start.session.delegation.type,'client');
  assert.deepEqual(start.session.audio.format,{type:'audio/pcm',rate:24000});
  assert.throws(()=>f.session.audio(new Uint8Array([0,0])),/session.started/);
  await f.ready();assert.throws(()=>f.session.audio(new Uint8Array([0])),/complete PCM16/);
  const pcm=Buffer.from([0,0,255,127]);f.session.audio(pcm);
  await eventually(()=>f.received.some(event=>event.type==='session.input_audio.append'));
  assert.deepEqual(Buffer.from(f.received.find(event=>event.type==='session.input_audio.append')!.audio,'base64'),pcm);
  f.send({type:'session.output_audio.delta',delta:pcm.toString('base64')});
  await eventually(()=>f.events.some(event=>event.type==='audio'));
  assert.equal(f.events.find(event=>event.type==='audio')!.audio,pcm.toString('base64'));
  const closed=await f.session.close();assert.equal(closed.finalUsageReceived,true);assert.deepEqual(closed.usage,{seconds:0.321});
});

test('Live delegation uses saved timestamped conversation, deduplicates IDs and stays full duplex',async t=>{
  let release!:()=>void;const gate=new Promise<void>(resolve=>{release=resolve;});let task:DelegatedTask|undefined;let calls=0;
  const f=await fixture(t,{run:async current=>{calls++;task=current;await gate;
    const history=current.conversation();assert.equal(history.truncated,false);assert.equal(history.fragments.at(-1)!.text,'Actually, only highlight them.');
    current.update({content:'Display updated in QuPath.',spoken:true});
  }});
  await f.ready();
  f.send({type:'session.input_transcript.delta',delta:'Find the cells here.',start_ms:0,end_ms:400});
  f.send({type:'session.delegation.created',offset_ms:400,delegation:{id:'delegation-1',target:'client'}});
  f.send({type:'session.delegation.created',offset_ms:400,delegation:{id:'delegation-1',target:'client'}});
  await eventually(()=>task!==undefined);assert.equal(calls,1);assert.equal(task!.offsetMs,400);assert.equal(task!.context.selectionCount,899);
  f.send({type:'session.input_transcript.delta',delta:'Actually, only highlight them.',start_ms:450,end_ms:800});
  f.send({type:'session.output_audio.delta',delta:Buffer.alloc(4).toString('base64')});
  await eventually(()=>f.events.some(event=>event.type==='audio')&&task!.conversation().fragments.length===2);
  release();await eventually(()=>f.received.some(event=>event.type==='session.commentary.append'));
  assert.equal(f.received.find(event=>event.type==='session.commentary.append')!.delegation_id,'delegation-1');
  assert(!f.received.some(event=>event.type==='response.create'),'Client delegation returns updates rather than Realtime/Responses continuation events');
});

test('explicit Live interruption aborts backend work and stops local playback without guessing a transcript turn',async t=>{
  let started=false;let aborted=false;
  const f=await fixture(t,{run:async task=>{
    started=true;await new Promise<void>(resolve=>task.signal.addEventListener('abort',()=>{aborted=true;resolve();},{once:true}));
  }});
  await f.ready();f.send({type:'session.delegation.created',offset_ms:0,delegation:{id:'work',target:'client'}});
  await eventually(()=>started);f.session.interrupt();
  await eventually(()=>aborted&&f.received.some(event=>event.type==='session.instructions.append'));
  assert(f.events.some(event=>event.type==='interrupted'));assert(!f.events.some(event=>event.type==='backend_error'));
});

test('Live closing reports missing terminal usage and cannot reconnect or send more audio',async t=>{
  const f=await fixture(t,{run:async()=>{}},{closeTimeoutMs:20},false);await f.ready();
  const result=await f.session.close();assert.equal(result.finalUsageReceived,false);
  assert.throws(()=>f.session.audio(Buffer.alloc(4)),/session.started/);
  assert.throws(()=>f.session.start(f.wire),/already started/);
});

test('Live duration cap closes the session and collects a single final usage payload',async t=>{
  const f=await fixture(t,{run:async()=>{}},{maxSessionMs:300});await f.ready();
  await eventually(()=>f.events.some(event=>event.type==='voice_state'&&event.state==='idle'));
  const result=await f.session.close();assert.equal(result.finalUsageReceived,true);assert.match(result.reason,/duration limit/);
  assert.equal(f.events.filter(event=>event.type==='voice_state'&&event.state==='idle').length,1);
});

test('interrupted output stays suppressed until the application supplies the current recovery epoch',async t=>{
  const f=await fixture(t,{run:async()=>{}});await f.ready();
  const first=f.session.interrupt();
  f.send({type:'session.output_audio.delta',delta:Buffer.alloc(4).toString('base64')});
  f.send({type:'session.output_transcript.delta',delta:'Late blocked speech',start_ms:0,end_ms:100});
  await eventually(()=>f.events.some(event=>event.type==='transcript_delta'));
  assert.equal(f.events.filter(event=>event.type==='audio').length,0);
  const second=f.session.interrupt();assert.throws(()=>f.session.resumePlayback(first),/Stale playback/);
  f.session.resumePlayback(second);
  f.send({type:'session.output_audio.delta',delta:Buffer.alloc(4).toString('base64')});
  await eventually(()=>f.events.some(event=>event.type==='audio'));
  assert.equal(f.events.find(event=>event.type==='audio')!.epoch,second);
});

test('a new delegation supersedes the previous owner before both can execute the corrected request',async t=>{
  let release!:()=>void;const gate=new Promise<void>(resolve=>{release=resolve;});let oldStarted=false;let oldAborted=false;const executions:string[]=[];
  const f=await fixture(t,{run:async task=>{
    if(task.id==='old'){oldStarted=true;task.signal.addEventListener('abort',()=>{oldAborted=true;},{once:true});await gate;if(!task.signal.aborted)executions.push(task.id);}
    else {assert.equal(oldAborted,true);executions.push(task.id);task.update({spoken:true,content:'The corrected request was handled once.'});}
  }});
  await f.ready();f.send({type:'session.delegation.created',offset_ms:0,delegation:{id:'old',target:'client'}});await eventually(()=>oldStarted);
  f.send({type:'session.input_transcript.delta',delta:'Actually, only highlight them.',start_ms:50,end_ms:100});
  f.send({type:'session.delegation.created',offset_ms:100,delegation:{id:'new',target:'client'}});
  await eventually(()=>f.received.some(event=>event.type==='session.commentary.append'));release();await new Promise(resolve=>setImmediate(resolve));
  assert.deepEqual(executions,['new']);assert(!f.events.some(event=>event.type==='backend_error'));assert.equal(f.received.find(event=>event.type==='session.commentary.append')!.delegation_id,'new');
});

test('interruption blocks in-flight delegations until the host reconciles an exact new request',async t=>{
  const executed:string[]=[];
  const f=await fixture(t,{run:async task=>{executed.push(task.id);}});await f.ready();
  f.send({type:'session.input_transcript.delta',delta:'Select these cells.',start_ms:0,end_ms:100});
  await eventually(()=>f.events.some(event=>event.type==='transcript_delta'));
  const epoch=f.session.interrupt();
  f.send({type:'session.delegation.created',offset_ms:100,delegation:{id:'late-old-request',target:'client'}});
  await eventually(()=>f.events.some(event=>event.type==='delegation_blocked'));
  assert.deepEqual(executed,[]);
  f.session.resumePlayback(epoch);
  f.send({type:'session.input_transcript.delta',delta:'Now focus here instead.',start_ms:200,end_ms:300});
  f.send({type:'session.delegation.created',offset_ms:300,delegation:{id:'new-request',target:'client'}});
  await eventually(()=>f.events.filter(event=>event.type==='delegation_blocked').length===2);
  assert.deepEqual(executed,[],'Neither a transcript nor playback recovery authorizes queued work');
  assert.throws(()=>f.session.resumeDelegation('new-request',epoch-1),/Stale delegation/);
  f.session.resumeDelegation('new-request',epoch);
  await eventually(()=>executed.length===1);
  assert.deepEqual(executed,['new-request']);
  assert.throws(()=>f.session.resumeDelegation('new-request',epoch),/No blocked delegation/);
  f.send({type:'session.delegation.created',offset_ms:100,delegation:{id:'late-old-request',target:'client'}});
  f.send({type:'session.delegation.created',offset_ms:400,delegation:{id:'another-request',target:'client'}});
  await eventually(()=>f.events.filter(event=>event.type==='delegation_blocked').length===3);
  assert.deepEqual(executed,['new-request'],'Unreconciled work stays blocked after one exact-ID recovery');
});
