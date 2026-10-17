/** Real credit-funded text inference, constrained to public-slide observation and exact focus.
 * No automated approval, data mutation, microphone, speaker or GPT-Live session.
 */
import assert from 'node:assert/strict';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { randomUUID } from 'node:crypto';
import { QuPathAdapter, type Context } from '../packages/gateway/src/bridge.js';
import { Knowledge } from '../packages/gateway/src/knowledge.js';
import { PublicFixtureRuntime, assertPublicFixture, publicFixtureContext } from './public-text-fixture.js';
import { ResponsesBackend } from '../packages/gateway/src/backend.js';
import { BedrockBackendClient } from '../packages/gateway/src/bedrock-client.js';
import { BEDROCK_MODEL } from '../packages/gateway/src/aws-credit-proof.js';
if(!process.argv.includes('--public-fixture'))throw new Error('Pass --public-fixture only for the real AWS-credit text inference / public OS-2 display test.');
const root=resolve(process.env.LUMA_ROOT??process.cwd());const state=resolve(root,'.local/state');const adapter=new QuPathAdapter(root);
const initial=await adapter.execute('context') as any;
assertPublicFixture(initial);
await adapter.execute('focus',{x:48000,y:24000,downsample:4},{expectedContext:initial.contextKey});
const arranged=await adapter.execute('context') as any;assertPublicFixture(arranged,initial.contextKey);assert.equal(arranged.viewer.centerX,48000);assert.equal(arranged.viewer.centerY,24000);
const runtime=new PublicFixtureRuntime(adapter,new Knowledge(),resolve(root,'.local/text-smoke'),initial);
const executed:string[]=[];runtime.on('tool',event=>executed.push(event.name));
const receipts=async()=>{try{return(await readFile(resolve(state,'credit-usage.jsonl'),'utf8')).trim().split('\n').filter(Boolean).map(line=>JSON.parse(line));}catch(error){if((error as NodeJS.ErrnoException).code==='ENOENT')return[];throw error;}};
const reservations=async()=>{try{return JSON.parse(await readFile(resolve(state,'credit-budget.json'),'utf8')).reservations as Array<{id:string}>;}catch(error){if((error as NodeJS.ErrnoException).code==='ENOENT')return[];throw error;}};
const previous=new Set((await reservations()).map(row=>row.id));
const displayed:string[]=[];
const backend=new ResponsesBackend(runtime,new BedrockBackendClient(state),BEDROCK_MODEL,(_id,text)=>displayed.push(text),4096);
const prompt='Read actual QuPath context. Focus the real viewer at full-resolution image pixel coordinates x=51100, y=27100, downsample=3. Confirm the actual returned center. Only use qupath_context and qupath_focus. Do not change selection, scientific data, files or settings. Keep the result short.';
const started=performance.now();
await backend.run({id:randomUUID(),offsetMs:0,context:publicFixtureContext(arranged,initial.contextKey),signal:AbortSignal.timeout(120_000),conversation:()=>({fragments:[{role:'user',text:prompt,startMs:0,endMs:0}],truncated:false}),update:()=>{}});
const final=await runtime.call('qupath_context',{}) as any;
assert.equal(final.viewer.centerX,51100);assert.equal(final.viewer.centerY,27100);assert.equal(final.viewer.downsample,3);assert.deepEqual(final.counts,initial.counts);assert.equal(runtime.actions.size,0);assert(executed.includes('qupath_focus'));assert.equal(displayed.length,1);
const attempts=(await reservations()).filter(row=>!previous.has(row.id));
const newIds=new Set(attempts.map(row=>row.id));const usage=(await receipts()).filter(row=>newIds.has(row.id));
const evidence={status:'passed',observedAt:new Date().toISOString(),provider:'Amazon Bedrock InvokeModel',model:BEDROCK_MODEL,inference:'actual OpenAI text model; not a scripted response',funding:'fresh authenticated eligible-credit/access/price checks before each request',application:'real QuPath',operation:'display-only exact focus; scientific data tools refused by fixture',center:[final.viewer.centerX,final.viewer.centerY],hierarchyUnchanged:true,approvalProposals:runtime.actions.size,executedTools:executed,answer:displayed[0],projectAttemptReserveDeltaUSD:attempts.length,estimatedTokenCostUSD:usage.length===attempts.length&&usage.every(row=>typeof row.estimatedUSD==='number'&&row.outcome==='completed')?usage.reduce((sum,row)=>sum+row.estimatedUSD,0):null,billingReconciled:false,elapsedMs:Math.round(performance.now()-started),voice:'not used; GPT-Live remains disabled',vision:'text-only; no image was sent'};
await mkdir(resolve(root,'.local/evidence'),{recursive:true});await writeFile(resolve(root,'.local/evidence/text-model.json'),JSON.stringify(evidence,null,2)+'\n');console.log(JSON.stringify(evidence,null,2));
