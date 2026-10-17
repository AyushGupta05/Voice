/** Explicit tiny latency comparison of Bedrock OpenAI models on one realistic voice-turn request.
 * Reserves each call on the shared credit ledger like production. */
import {BedrockRuntimeClient,InvokeModelCommand} from '@aws-sdk/client-bedrock-runtime';
import {resolve,join} from 'node:path';
import {chatPayload} from '../packages/gateway/src/bedrock-client.js';
import {tools} from '../packages/gateway/src/tools.js';
import {instructions} from '../packages/gateway/src/instructions.js';
import {spokenStyle} from '../packages/gateway/src/backend.js';
import {observeAWSCredits} from '../packages/gateway/src/aws-credit-proof.js';
import {CreditBudget} from '../packages/gateway/src/credit-budget.js';
import {bedrockSigV4Only} from '../packages/gateway/src/aws-auth.js';
if(!process.argv.includes('--authorized-model-latency'))throw new Error('Pass --authorized-model-latency for a few tiny credit-covered model calls.');
const models=['openai.gpt-oss-120b-1:0','openai.gpt-oss-20b-1:0'];
const state=join(resolve(process.env.LUMA_ROOT??process.cwd()),'.local/state');
const context={image:{name:'OS-2.ndpi',width:126976,height:73728},viewer:{centerX:63488,centerY:36864,downsample:20},selectionCount:0,counts:{}};
const body=chatPayload({model:'openai.gpt-oss-120b-1:0',instructions:instructions+spokenStyle,store:false,parallel_tool_calls:false,max_output_tokens:1024,
 tools:tools.filter(t=>t.name!=='qupath_context').map(t=>({type:'function',name:t.name,description:t.description,parameters:t.inputSchema,strict:false})),
 input:[{role:'user',content:[{type:'input_text',text:JSON.stringify({conversation:{fragments:[{role:'user',text:'Zoom in a little.'}]},qupathObservation:context})}]}]});
const proof=await observeAWSCredits(AbortSignal.timeout(20000));const budget=new CreditBudget(state);
const client=new BedrockRuntimeClient({region:'us-east-1',credentials:proof.credentials,maxAttempts:1,...bedrockSigV4Only(),endpoint:'https://bedrock-runtime.us-east-1.amazonaws.com'});
for(const model of models)for(let i=0;i<3;i++){
 await budget.reserve({...proof,observedAt:Date.now()},AbortSignal.timeout(5000));
 const t=Date.now();const r=await client.send(new InvokeModelCommand({modelId:model,body:new TextEncoder().encode(JSON.stringify({...JSON.parse(new TextDecoder().decode(body)),model})),contentType:'application/json',accept:'application/json'}));
 const d=JSON.parse(new TextDecoder().decode(r.body));const m=d.choices?.[0]?.message;
 console.log(JSON.stringify({model,ms:Date.now()-t,usage:d.usage,tool:m?.tool_calls?.[0]?.function?.name,args:m?.tool_calls?.[0]?.function?.arguments}));
}
client.destroy();
