import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { BEDROCK_MODEL, type AWSProof } from '../src/aws-credit-proof.js';
import { BedrockBackendClient, chatPayload, parseChat } from '../src/bedrock-client.js';
import type { BackendRequest } from '../src/backend.js';
const request=():BackendRequest=>({model:BEDROCK_MODEL,instructions:'Use actual measurements.',input:[{role:'user',content:[{type:'input_text',text:'Focus this region.'}]}],tools:[{type:'function',name:'qupath_focus',parameters:{type:'object',properties:{}}}],store:false,parallel_tool_calls:false,max_output_tokens:4096});
const proof=():AWSProof=>({credentials:{accessKeyId:'synthetic',secretAccessKey:'synthetic'},rates:{inputPerTokenUSD:0.00000015,outputPerTokenUSD:0.0000006},accountHash:'a'.repeat(64),observedAt:Date.now(),availableCreditUSD:5000,maximumRequestUSD:0.03});
const response=()=>Buffer.from(JSON.stringify({choices:[{finish_reason:'stop',message:{role:'assistant',content:'Ready.'}}],usage:{prompt_tokens:100,completion_tokens:20}}));
async function fixture(){const root=await mkdtemp(join(tmpdir(),'luma-bedrock-'));await writeFile(join(root,'text-funding.json'),JSON.stringify({version:1,accountHash:'a'.repeat(64),expiresAt:new Date(Date.now()+3600_000).toISOString(),model:BEDROCK_MODEL,maximumReservedUSD:200}));return{root,clean:()=>rm(root,{recursive:true,force:true})};}

test('Responses-shaped history maps tool calls and results into a native text-only chat request',()=>{
 const value=request();value.input.push({type:'function_call',call_id:'call-1',name:'qupath_focus',arguments:'{}'},{type:'function_call_output',call_id:'call-1',output:'{"focused":true}'},{role:'user',content:[{type:'input_text',text:'Current app state'}]});
 const chat=JSON.parse(chatPayload(value).toString());
 assert.equal(chat.max_completion_tokens,4096);assert.equal(chat.stream,false);assert.equal(chat.parallel_tool_calls,false);
 assert.equal(chat.messages[2].tool_calls[0].id,'call-1');assert.equal(chat.messages[3].tool_call_id,'call-1');
 assert.equal(chat.tools[0].function.name,'qupath_focus');
 const image=request();image.input.push({role:'user',content:[{type:'input_image',image_url:'data:image/png;base64,test'}]});assert.throws(()=>chatPayload(image),/text only/);
 assert.throws(()=>chatPayload({...request(),model:'other-provider'}),/Unsupported/);
});

test('model parser refuses incomplete results and never publishes private reasoning tags',()=>{
 const raw={choices:[{finish_reason:'stop',message:{role:'assistant',content:'<reasoning>Private scratch work</reasoning> Actual result.'}}]};
 assert.equal(parseChat(Buffer.from(JSON.stringify(raw))).response.output_text,'Actual result.');
 raw.choices[0].message.content='<reasoning>unfinished';assert.throws(()=>parseChat(Buffer.from(JSON.stringify(raw))),/private-reasoning/);
 raw.choices[0].finish_reason='length';assert.throws(()=>parseChat(Buffer.from(JSON.stringify(raw))),/incomplete/);
});

test('a verified AWS attempt is reserved before invocation and its small usage estimate never refunds that reserve',async()=>{
 const f=await fixture();let invokes=0;
 try{
  const client=new BedrockBackendClient(f.root,{observe:async()=>proof(),invoke:async()=>{
   invokes++;const ledger=JSON.parse(await readFile(join(f.root,'credit-budget.json'),'utf8'));assert.equal(ledger.reservations.length,invokes);return response();
  }});
  assert.equal((await client.complete(request(),new AbortController().signal)).output_text,'Ready.');
  await client.complete(request(),new AbortController().signal);
  const ledger=JSON.parse(await readFile(join(f.root,'credit-budget.json'),'utf8'));assert.equal(ledger.reservations.length,2);
  const receipts=(await readFile(join(f.root,'credit-usage.jsonl'),'utf8')).trim().split('\n').map(value=>JSON.parse(value));
  assert.equal(receipts.length,2);assert(receipts[0].estimatedUSD<0.001);assert.equal(receipts[0].outcome,'completed');
 }finally{await f.clean();}
});

test('wrong account, missing credits and missing configuration never invoke the model',async()=>{
 const f=await fixture();let invokes=0;let observed=0;
 try{
  for(const observation of [{...proof(),accountHash:'b'.repeat(64)},{...proof(),availableCreditUSD:0}]){
   const client=new BedrockBackendClient(f.root,{observe:async()=>{observed++;return observation;},invoke:async()=>{invokes++;return response();}});
   await assert.rejects(client.complete(request(),new AbortController().signal));
  }
  await rm(join(f.root,'text-funding.json'));const client=new BedrockBackendClient(f.root,{observe:async()=>{observed++;return proof();},invoke:async()=>{invokes++;return response();}});
  await assert.rejects(client.complete(request(),new AbortController().signal),/not configured/);
  assert.equal(invokes,0);assert.equal(observed,2);
 }finally{await f.clean();}
});

test('an aborted in-flight request retains the durable reserve and cannot return tool output',async()=>{
 const f=await fixture();const controller=new AbortController();
 try{
  const client=new BedrockBackendClient(f.root,{observe:async()=>proof(),invoke:async()=>{controller.abort();return response();}});
  await assert.rejects(client.complete(request(),controller.signal),{name:'AbortError'});
  assert.equal(JSON.parse(await readFile(join(f.root,'credit-budget.json'),'utf8')).reservations.length,1);
  assert.equal(JSON.parse((await readFile(join(f.root,'credit-usage.jsonl'),'utf8')).trim()).outcome,'unconfirmed');
 }finally{await f.clean();}
});

test('configuration expiring during metadata preflight blocks inference before reserving',async()=>{
 const f=await fixture();let invokes=0;
 try{
  const config=JSON.parse(await readFile(join(f.root,'text-funding.json'),'utf8'));config.expiresAt=new Date(Date.now()+500).toISOString();await writeFile(join(f.root,'text-funding.json'),JSON.stringify(config));
  const client=new BedrockBackendClient(f.root,{observe:async()=>{await new Promise(resolve=>setTimeout(resolve,600));return proof();},invoke:async()=>{invokes++;return response();}});
  await assert.rejects(client.complete(request(),new AbortController().signal),/expired/);assert.equal(invokes,0);
  await assert.rejects(readFile(join(f.root,'credit-budget.json')),{code:'ENOENT'});
 }finally{await f.clean();}
});
