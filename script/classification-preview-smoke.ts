/** Read-only actual-app refusal check. Never approves a data action or invokes a model. */
import assert from 'node:assert/strict';
import {mkdir,writeFile} from 'node:fs/promises';
import {randomUUID} from 'node:crypto';
import {resolve} from 'node:path';
import {QuPathAdapter,type Context} from '../packages/gateway/src/bridge.js';
import {Runtime} from '../packages/gateway/src/runtime.js';
import {Knowledge} from '../packages/gateway/src/knowledge.js';
import {assertAnalysisBaseline} from './public-analysis-fixture.js';
if(!process.argv.includes('--public-fixture'))throw new Error('Pass --public-fixture for the empty public OS-2 read-only preview check.');
const root=resolve(process.env.LUMA_ROOT??process.cwd());const adapter=new QuPathAdapter(root);
const baseline=await adapter.execute('context');assertAnalysisBaseline(baseline);
const runtime=new Runtime(adapter,new Knowledge(),resolve(root,'.local/classification-preview-smoke',randomUUID()));
await assert.rejects(runtime.call('qupath_classify',{scope:'all',kind:'cell',classification:'Positive',measurement:'Nucleus: DAB OD mean',threshold:0.2},undefined,context=>{
  if(context){assertAnalysisBaseline(context);assert.equal(context.contextKey,baseline.contextKey);}
}),/classification filters CURRENT labels/);
assert.equal(runtime.actions.size,0);
const after=await adapter.execute('context') as Context;assertAnalysisBaseline(after);assert.equal(after.contextKey,baseline.contextKey);
const evidence={status:'passed',observedAt:new Date().toISOString(),flow:'Current Runtime -> actual QuPath context and matching-object preflight',
  fixture:'Empty public OS-2 image, H-DAB, no project, empty selection/hierarchy',matchingCandidates:0,approvalProposals:0,
  contextKeyUnchanged:true,providerCalls:0,scientificMutationExecuted:false,
  boundary:'Actual empty-scope refusal only; nonempty preview/binding tested with synthetic adapters. No native foreground review or live voice.'};
await mkdir(resolve(root,'.local/evidence'),{recursive:true});await writeFile(resolve(root,'.local/evidence/classification-preview.json'),JSON.stringify(evidence,null,2)+'\n');
console.log(JSON.stringify(evidence,null,2));
