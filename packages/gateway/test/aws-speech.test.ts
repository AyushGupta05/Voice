import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,writeFile,rm} from 'node:fs/promises';
import {join} from 'node:path';import {tmpdir} from 'node:os';
import {AWSSpeechProvider,resampleSpeech,type SpeechDependencies} from '../src/aws-speech.js';
import {speechRates,selectVoice,type SpeechProof} from '../src/aws-speech-proof.js';
import {eligibleCreditUSD} from '../src/aws-credit-proof.js';

const product=(service:string,usage:string,unit:string,price:string,operation?:string)=>({product:{attributes:{regionCode:'us-east-1',servicecode:service,usagetype:usage,operation}},terms:{OnDemand:{standard:{effectiveDate:'2026-01-01T00:00:00Z',priceDimensions:{one:{unit,beginRange:'0',endRange:'Inf',pricePerUnit:{USD:price}}}}}}});
const stt=()=>product('transcribe','USE1-StreamingAudio','second','0.0001667','StreamingAudio');
const tts=()=>product('AmazonPolly','USE1-SynthesizeSpeechNeural-Characters','Characters','0.000016');
const proof=():SpeechProof=>({credentials:{accessKeyId:'synthetic',secretAccessKey:'synthetic'},accountHash:'a'.repeat(64),observedAt:Date.now(),availableCreditUSD:5000,maximumRequestUSD:0.03,rates:{transcribePerSecondUSD:0.0001667,pollyPerCharacterUSD:0.000016}});
async function fixture(){const state=await mkdtemp(join(tmpdir(),'luma-speech-'));const expiresAt=new Date(Date.now()+3600000).toISOString();await writeFile(join(state,'text-funding.json'),JSON.stringify({version:1,accountHash:'a'.repeat(64),expiresAt,model:'openai.gpt-oss-120b-1:0',maximumReservedUSD:200}));await writeFile(join(state,'speech-funding.json'),JSON.stringify({version:1,route:'aws-transcribe-polly-openai',accountHash:'a'.repeat(64),expiresAt,maximumReservedUSD:200,maximumSessionSeconds:180}));return{state,clean:()=>rm(state,{recursive:true,force:true})};}

test('speech prices require exact region/SKU/unit/positive current unambiguous bounded terms',()=>{
 assert.deepEqual(speechRates([stt()],[tts()],'neural'),proof().rates);
 for(const bad of [[],[stt(),stt()],[product('transcribe','USE1-MedicalStreamingAudio','second','0.0001','MedicalStreamingAudio')],[product('transcribe','USE1-StreamingAudio','minutes','0.0001667','StreamingAudio')],[product('transcribe','USE1-StreamingAudio','second','0','StreamingAudio')],[product('transcribe','USE1-StreamingAudio','second','1','StreamingAudio')]])assert.throws(()=>speechRates(bad,[tts()],'neural'));
 const future=stt();future.terms.OnDemand.standard.effectiveDate='2099-01-01';assert.throws(()=>speechRates([future],[tts()],'neural'));
});
test('speech credit proof requires the same qualifying grant to cover all requested products',()=>{
 const now=Date.now();const record:any={accountId:'synthetic',creditId:'one',creditStatus:'ENABLED',creditSharingType:'DEFAULT',startDate:new Date(now-1000),endDate:new Date(now+86400000),remainingAmount:{currencyCode:'USD',currencyAmount:'1000'},estimatedAmount:{currencyCode:'USD',currencyAmount:'900'},applicableProductNames:['AmazonBedrockFoundationModels','Amazon Transcribe']};
 assert.equal(eligibleCreditUSD([record],'synthetic',now,['Amazon Transcribe','Amazon Polly']),0);
 record.applicableProductNames.push('Amazon Polly');assert.equal(eligibleCreditUSD([record],'synthetic',now,['Amazon Transcribe','Amazon Polly']),900);
});
test('real shared reservations precede speech dispatch and successful speech does not refund them',async()=>{
 const f=await fixture();let invokes=0;let ready=false;const controller=new AbortController();
 const dependencies:SpeechDependencies={observe:async()=>proof(),transcribe:async(_p,audio,_s,onReady,onTranscript)=>{assert(ready);assert.equal(JSON.parse(await readFile(join(f.state,'credit-budget.json'),'utf8')).reservations.length,1);for await(const chunk of audio)assert.equal(chunk.length,2);invokes++;onReady();onTranscript({id:'one',text:'Focus here',partial:false});},synthesize:async()=>{assert.equal(JSON.parse(await readFile(join(f.state,'credit-budget.json'),'utf8')).reservations.length,2);invokes++;return Buffer.from([0,0,1,0]);}};
 try{const provider=new AWSSpeechProvider(f.state,dependencies);async function* audio(){yield Buffer.from([0,0]);}
 await provider.transcribe(audio(),controller.signal,{inputReady:()=>{ready=true;},listening:()=>{},transcript:()=>{}});await provider.speak('Done.',controller.signal);
 assert.equal(invokes,2);assert.equal(JSON.parse(await readFile(join(f.state,'credit-budget.json'),'utf8')).reservations.length,2);
 const receipts=(await readFile(join(f.state,'speech-usage.jsonl'),'utf8')).trim().split('\n').map(x=>JSON.parse(x));assert.deepEqual(receipts.map(r=>r.service),['transcribe','polly']);assert(receipts.every(r=>r.outcome==='completed'&&!r.billingReconciled));
 }finally{await f.clean();}
});
test('funding failure and pre-cancellation prevent dispatch; cancellation after reserve retains the attempt',async()=>{
 const f=await fixture();let invoked=0;let observed=0;const dependencies:SpeechDependencies={observe:async()=>{observed++;return proof();},transcribe:async()=>{invoked++;},synthesize:async()=>{invoked++;return new Uint8Array([0,0]);}};
 try{const provider=new AWSSpeechProvider(f.state,dependencies);const stopped=new AbortController();stopped.abort();await assert.rejects(provider.speak('Hello',stopped.signal));assert.equal(observed,0);
 const active=new AbortController();async function* audio(){yield new Uint8Array([0,0]);}
 await assert.rejects(provider.transcribe(audio(),active.signal,{inputReady:()=>active.abort(),listening:()=>{},transcript:()=>{}}));assert.equal(invoked,0);assert.equal(JSON.parse(await readFile(join(f.state,'credit-budget.json'),'utf8')).reservations.length,1);
 await assert.rejects(new AWSSpeechProvider(f.state,{...dependencies,observe:async()=>({...proof(),accountHash:'b'.repeat(64)})}).speak('Hello',new AbortController().signal),/identity/);assert.equal(invoked,0);
 }finally{await f.clean();}
});
test('speech resampling preserves duration and signed sample levels without relabeling16kHz as24kHz',()=>{
 const input=Buffer.alloc(32000);for(let i=0;i<16000;i++)input.writeInt16LE(i%2===0?1000:-1000,i*2);
 const output=resampleSpeech(input);assert.equal(output.length,48000);assert.equal(output.readInt16LE(0),1000);assert.equal(output.readInt16LE(6),1000);
 assert.throws(()=>resampleSpeech(new Uint8Array([1])));assert.throws(()=>resampleSpeech(new Uint8Array(2_000_002)));
});

test('voice selection defaults to a natural generative voice and rejects unknown settings',()=>{
 assert.deepEqual(selectVoice(undefined),{id:'Danielle',language:'en-US',engine:'generative'});
 assert.deepEqual(selectVoice('Amy:neural'),{id:'Amy',language:'en-GB',engine:'neural'});
 assert.throws(()=>selectVoice('Nobody'));assert.throws(()=>selectVoice('Amy:standard'));
 assert.throws(()=>speechRates([stt()],[tts()],'generative'));
});
