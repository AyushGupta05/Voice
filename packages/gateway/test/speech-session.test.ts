import {test} from 'node:test';import assert from 'node:assert/strict';
import {AudioQueue,SpeechSession,spokenReply,isAddressedToLuma,addressed} from '../src/speech-session.js';
// Session-level tests exercise request handling without the wake word; the gate itself is tested directly below.
process.env.LUMA_WAKE_WORD='off';
import type {SpeechProvider,SpeechCallbacks} from '../src/aws-speech.js';
const tick=()=>new Promise<void>(resolve=>setImmediate(resolve));
function fixture(){let callbacks!:SpeechCallbacks;let signal!:AbortSignal;let stops=0;let closed=0;const submitted:string[]=[];const events:any[]=[];let finish!:()=>void;
 const provider:SpeechProvider={transcribe:async(_audio,s,c)=>{callbacks=c;signal=s;c.inputReady();await new Promise<void>(resolve=>{finish=resolve;s.addEventListener('abort',resolve as ()=>void,{once:true});});},speak:async()=>new Uint8Array([0,0,100,0])};
 const session=new SpeechSession(provider,{submit:async (text,spoken)=>{assert.equal(spoken,true);submitted.push(text);},stop:()=>{stops++;}},e=>events.push(e),()=>closed++);
 return{session,events,submitted,get callbacks(){return callbacks;},get signal(){return signal;},get stops(){return stops;},get closed(){return closed;},finish:()=>finish(),provider};}
test('bounded audio queue preserves order and close wakes a blocked consumer',async()=>{
 const queue=new AudioQueue();const reader=queue.stream();const waiting=reader.next();queue.push(new Uint8Array([1,2]));assert.deepEqual((await waiting).value,new Uint8Array([1,2]));
 const small=new AudioQueue(4);small.push(new Uint8Array([1,2]));small.push(new Uint8Array([3,4]));small.push(new Uint8Array([5,6]));assert.equal(small.dropped,2);assert.deepEqual((await small.stream().next()).value,new Uint8Array([3,4]));
 assert.throws(()=>queue.push(new Uint8Array([1])));const end=reader.next();queue.close();assert.equal((await end).done,true);
});
test('only ready audio is accepted; duplicate final transcripts cannot replay a task',async()=>{
 const f=fixture();f.session.acceptAudio('not audio');f.session.start();await tick();assert(f.events.some(e=>e.state==='ready_for_audio'));assert(!f.events.some(e=>e.state==='listening'));
 f.callbacks.listening();f.session.acceptAudio(Buffer.from([0,0]).toString('base64'));
 f.callbacks.transcript({id:'one',text:'Focus',partial:true});assert.equal(f.submitted.length,0);
 f.callbacks.transcript({id:'one',text:'Focus here',partial:false});f.callbacks.transcript({id:'one',text:'Focus here',partial:false});assert.deepEqual(f.submitted,['Focus here']);assert.equal(f.events.filter(e=>e.type==='interrupted').length,1);
 f.session.stop();await f.session.settled();assert(f.signal.aborted);assert.equal(f.closed,1);
 f.callbacks.transcript({id:'two',text:'Delete everything',partial:false});assert.equal(f.submitted.length,1);
});
test('late synthesized audio is suppressed after interruption and old sessions stay closed',async()=>{
 const f=fixture();let finish!:(data:Uint8Array)=>void;f.provider.speak=()=>new Promise(resolve=>{finish=resolve;});f.session.start();await tick();const speaking=f.session.speak('Done');f.session.interrupt();finish(new Uint8Array([0,0]));await speaking;assert(!f.events.some(e=>e.type==='audio'));
 f.session.stop();f.session.start();await f.session.settled();assert.equal(f.closed,1);
});
test('PCM output is explicitly24kHz, bounded and tied to one playback epoch/session',async()=>{
 const f=fixture();f.session.start();await tick();await f.session.speak('Focused the view.');const audio=f.events.find(e=>e.type==='audio');assert.equal(audio.sampleRate,24000);assert.equal(audio.sessionId,f.session.id);assert.equal(audio.final,true);assert.equal(Buffer.from(audio.audio,'base64').length,6);
 f.session.stop();await f.session.settled();assert.throws(()=>new SpeechSession(f.provider,{submit:async()=>{},stop:()=>{}},()=>{},()=>{},1_800_001));
 assert.doesNotThrow(()=>new SpeechSession(f.provider,{submit:async()=>{},stop:()=>{}},()=>{},()=>{},1_800_000).stop());
 assert.equal(spokenReply('x'.repeat(2000)),'The result is ready on screen.');
});
test('a queued final reply plays after its acknowledgment even when its synthesis finishes first; interruption cancels both',async()=>{
 const releases:Array<()=>void>=[];const events:any[]=[];
 const provider:SpeechProvider={transcribe:async(_a,s,c)=>{c.inputReady();await new Promise(r=>s.addEventListener('abort',r as ()=>void,{once:true}));},
  speak:async text=>{await new Promise<void>(r=>releases.push(r));return new Uint8Array(text.startsWith('Zoom')?[1,0]:[2,0]);}};
 const session=new SpeechSession(provider,{submit:async()=>{},stop:()=>{}},e=>events.push(e),()=>{});session.start();await tick();
 const ack=session.speak('Zooming in.');const final=session.speak('Now at ten times.',true);await tick();
 releases[1]();await tick();assert.equal(events.filter(e=>e.type==='audio').length,0);
 releases[0]();await ack;await final;
 const spoken=events.filter(e=>e.type==='voice_speaking');assert.deepEqual(spoken.map(e=>e.queued),[false,true]);
 const audio=events.filter(e=>e.type==='audio');assert.equal(audio.length,2);assert(audio[0].epoch<audio[1].epoch);
 const a=session.speak('Zooming out.');const b=session.speak('Done zooming.',true);await tick();session.interrupt();releases[2]();releases[3]();await a;await b;
 assert.equal(events.filter(e=>e.type==='audio').length,2);session.stop();
});
test('room chatter and one-word fragments are ignored while real requests and short commands pass',()=>{
 for(const chatter of ['OK.','Bye.','Really.','hello','Yeah','Hmm.'])assert.equal(isAddressedToLuma(chatter),false,chatter);
 for(const request of ['Zoom in a little.','Show me the positive cells','Focus here','undo that','Stop','zoom out','Hey Luma, what is this?','No','yes','save it','Add a tumour region on the right'])assert.equal(isAddressedToLuma(request),true,request);
});
test('with the wake word required, Luma acts on its name, short commands and attentive follow-ups only',()=>{
 const strict={wakeWord:true,attentive:false};
 assert.deepEqual(addressed('Hey Luma, analyse the cells here.',strict),{accept:true,request:'analyse the cells here.',reason:'wake word'});
 assert.deepEqual(addressed('Show me the positive cells, Luma',strict).request,'Show me the positive cells');
 assert.equal(addressed('Luma.',strict).request,'Yes?');
 assert.equal(addressed('What uh looking into the YouTube but we know he takes it.',strict).accept,false);
 assert.equal(addressed('zoom in',strict).accept,true);
 assert.equal(addressed('What percentage are positive?',{wakeWord:true,attentive:true}).accept,true);
 assert.equal(addressed('What percentage are positive?',{wakeWord:false,attentive:false}).accept,true);
 assert.equal(addressed('OK.',{wakeWord:false,attentive:true}).accept,false);
});
test('a session with the wake word required ignores overheard talk but engages after its name',async()=>{
 const submitted:string[]=[];let callbacks!:SpeechCallbacks;const events:any[]=[];
 const provider:SpeechProvider={transcribe:async(_a,s,c)=>{callbacks=c;c.inputReady();await new Promise(r=>s.addEventListener('abort',r as ()=>void,{once:true}));},speak:async()=>new Uint8Array([0,0])};
 const session=new SpeechSession(provider,{submit:async text=>{submitted.push(text);},stop:()=>{}},e=>events.push(e),()=>{},1_800_000,{wakeWord:true});
 session.start();await tick();
 callbacks.transcript({id:'a',text:'you talk to a few people and it is',partial:false});
 callbacks.transcript({id:'b',text:'Luma, zoom in a little',partial:false});
 callbacks.transcript({id:'c',text:'now show me the positive cells',partial:false});
 assert.deepEqual(submitted,['zoom in a little','now show me the positive cells']);
 assert.equal(events.filter(e=>e.type==='voice_ignored').length,1);
 session.stop();
});
