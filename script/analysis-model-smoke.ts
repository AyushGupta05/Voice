/** Actual funded model, fixed scientific operations, test-only simulated approvals.
 * Requires the user's explicit OS-2 fixture authorization. Never a production command.
 */
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {mkdir,open,readFile,unlink,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {QuPathAdapter} from '../packages/gateway/src/bridge.js';
import {Knowledge} from '../packages/gateway/src/knowledge.js';
import {ResponsesBackend,type BackendClient} from '../packages/gateway/src/backend.js';
import {BedrockBackendClient,loadTextConfig} from '../packages/gateway/src/bedrock-client.js';
import {BEDROCK_MODEL} from '../packages/gateway/src/aws-credit-proof.js';
import type {Action} from '../packages/gateway/src/runtime.js';
import {PublicAnalysisAdapter,PublicAnalysisRuntime,assertAnalysisBaseline} from './public-analysis-fixture.js';

if(!process.argv.includes('--authorized-os2-analysis-fixture'))throw new Error('Explicit fixed OS-2 mutation-test authorization and --authorized-os2-analysis-fixture are required. No test was started.');
const root=resolve(process.env.LUMA_ROOT??process.cwd());const state=resolve(root,'.local/state');
await loadTextConfig(state);
const ledger=async()=>{
  try{return JSON.parse(await readFile(resolve(state,'credit-budget.json'),'utf8')).reservations as Array<{id:string;amountUSD:number}>;}
  catch(error){if((error as NodeJS.ErrnoException).code==='ENOENT')return [];throw error;}
};
const originalReservations=new Set((await ledger()).map(row=>row.id));
const evidenceDirectory=resolve(root,'.local/evidence');await mkdir(evidenceDirectory,{recursive:true});
const nonce=randomUUID();const lockPath=resolve(state,'analysis-model-fixture.lock');
const lock=await open(lockPath,'wx',0o600);await lock.writeFile(nonce);
const raw=new QuPathAdapter(root);const cancellation=new AbortController();
const interrupt=()=>cancellation.abort(new Error('The fixed public fixture was interrupted.'));
process.once('SIGINT',interrupt);process.once('SIGTERM',interrupt);
const timer=setTimeout(()=>cancellation.abort(new Error('Fixed scientific model fixture timed out.')),240_000);
let fixture:PublicAnalysisAdapter|undefined;let runtime:PublicAnalysisRuntime|undefined;
let modelCalls=0;let answer='';let error:unknown;let cleanup:unknown;
const decisions=new Set<Promise<unknown>>();const approvalErrors:string[]=[];const approvedIds=new Set<string>();
const started=performance.now();
const onAction=(action:Action)=>{
  if(action.status!=='pending'||approvedIds.has(action.id))return;
  approvedIds.add(action.id);
  const decision=(async()=>{
    try {
      fixture!.assertTool(action.tool,action.arguments);
      const result=await runtime!.decide(action.id,true);
      if(result.status!=='completed')throw new Error('Fixed fixture action failed.');
      console.error('Authorized disposable fixture step completed: '+action.tool);
    } catch {
      approvalErrors.push('Fixed fixture approval or execution failed.');
      if(action.status==='pending')await runtime!.decide(action.id,false);
      cancellation.abort(new Error('Fixed fixture step failed.'));
    }
  })();
  decisions.add(decision);void decision.finally(()=>decisions.delete(decision)).catch(()=>undefined);
};
try {
  const gatewayToken=(await readFile(resolve(state,'gateway-token'),'utf8')).trim();
  const healthResponse=await fetch('http://127.0.0.1:49555/health',{headers:{Authorization:`Bearer ${gatewayToken}`},signal:AbortSignal.timeout(6000)});
  assert(healthResponse.ok);const health=await healthResponse.json() as any;
  assert.equal(health.application,'Luma');assert(!health.bridgeError);
  assert(!(health.actions??[]).some((action:any)=>['pending','running'].includes(action.status)),'Do not run alongside researcher actions.');
  const baseline=await raw.execute('context');assertAnalysisBaseline(baseline);
  const checkpoint=await raw.execute('checkpoint',{label:'Authorized model OS-2 empty baseline '+nonce},{expectedContext:baseline.contextKey}) as {id:string};
  fixture=new PublicAnalysisAdapter(raw,baseline,checkpoint.id);
  runtime=new PublicAnalysisRuntime(fixture,new Knowledge(),resolve(root,'.local/analysis-model-fixture',nonce));
  runtime.on('action',onAction);
  const allowed=new Set(['qupath_context',...fixture.steps.map(step=>step.name)]);
  const funded=new BedrockBackendClient(state);
  const client:BackendClient={supportsVision:false,complete:async(request,signal)=>{
    modelCalls++;return funded.complete({...request,tools:request.tools.filter(tool=>allowed.has(String(tool.name)))},signal);
  }};
  const user='Execute the already-authorized fixed public OS-2 disposable test, not a new scientific experiment. '
    +'The current image is H-DAB; use only these exact steps in this order: '+JSON.stringify(fixture.steps.map(({name,args})=>({tool:name,arguments:args})))
    +'. The host supplies fresh qupathObservation. The annotation selects the ROI automatically. Do not create another checkpoint, select other objects, repeat a started step, use additional tools, write files or choose different parameters. '
    +'The trusted test harness simulates approval only for these exact fixed steps; you cannot approve an action. After actual comparison and restoration, return only JSON with numeric detectedCells, positiveCells, negativeCells and boolean baselineRestored. These are software-operation counts, not biological or clinical validation.';
  const initial=await runtime.call('qupath_context',{}) as any;
  const backend=new ResponsesBackend(runtime,client,BEDROCK_MODEL,(_id,text)=>{answer=text;},2048);
  await backend.run({id:nonce,offsetMs:0,context:initial,signal:cancellation.signal,
    conversation:()=>({fragments:[{role:'user',text:user,startMs:0,endMs:0}],truncated:false}),update:()=>{}});
  assert(fixture.finished,'The model did not complete the entire exact sequence.');
  assert.deepEqual(JSON.parse(answer),{detectedCells:899,positiveCells:49,negativeCells:850,baselineRestored:true});
  assert.equal(approvalErrors.length,0);assert.equal(approvedIds.size,5);
}catch(failure){error=failure;}
finally {
  clearTimeout(timer);cancellation.abort();
  if(runtime)runtime.off('action',onAction);
  await Promise.allSettled([...decisions]);
  try {if(fixture&&runtime)cleanup=await fixture.restoreOwnedBaseline(runtime);}
  catch{cleanup={restored:false,requiresInspection:true};error??=new Error('Public fixture cleanup requires inspection.');}
  await lock.close();
  if((await readFile(lockPath,'utf8'))===nonce)await unlink(lockPath);
  process.off('SIGINT',interrupt);process.off('SIGTERM',interrupt);
}
const attempts=(await ledger()).filter(row=>!originalReservations.has(row.id));const ids=new Set(attempts.map(row=>row.id));
const receipts=(await readFile(resolve(state,'credit-usage.jsonl'),'utf8')).split('\n').filter(Boolean).map(row=>JSON.parse(row)).filter(row=>ids.has(row.id));
const accountingComplete=receipts.length===attempts.length&&attempts.length===modelCalls&&receipts.every(row=>row.outcome==='completed'&&typeof row.estimatedUSD==='number');
if(!accountingComplete)error??=new Error('Actual inference accounting is unconfirmed.');
const evidence={status:error?'failed':'passed',observedAt:new Date().toISOString(),model:BEDROCK_MODEL,
  flow:'Actual AWS-credit OpenAI backend -> shared Runtime -> fixed public QuPath analysis -> comparison -> empty hierarchy restore',
  authorization:'Only the explicitly authorized fixed disposable OS-2 sequence; approvals simulated solely in this private fixture Runtime. Ordinary production approvals are unchanged.',
  toolSequence:fixture?.executed??[],attemptedSteps:[...(fixture?.attempted??[])],counts:fixture?.observedCounts??{},
  answer,baselineRestoration:cleanup,simulatedApprovals:[...(runtime?.actions.values()??[])].filter(action=>['running','completed','failed'].includes(action.status)).length,backendCompletions:modelCalls,
  projectAttemptReserveDeltaUSD:attempts.reduce((sum,row)=>sum+row.amountUSD,0),
  estimatedTokenCostUSD:accountingComplete?receipts.reduce((sum,row)=>sum+row.estimatedUSD,0):null,
  billingReconciled:false,elapsedMs:Math.round(performance.now()-started),
  scientificValidity:'Not established; fixed-fixture counts do not validate segmentation or biological interpretation.',
  nativeUI:'No foreground interaction or hardware audio verified.',voice:'Not used; production GPT-Live disabled.',
  ...(error?{error:'The fixed fixture did not pass. Inspect private diagnostics before retrying.'}:{})};
await writeFile(resolve(evidenceDirectory,'analysis-model-'+nonce+'.json'),JSON.stringify({...evidence,...(fixture?.privateDiagnostics.length?{privateDiagnostics:fixture.privateDiagnostics}:{}),...(error?{privateFailure:String(error)}:{})},null,2)+'\n',{mode:0o600});
console.log(JSON.stringify(evidence,null,2));
if(error)process.exitCode=1;
