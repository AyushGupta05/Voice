import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Runtime, type Action } from '../src/runtime.js';
import { Knowledge } from '../src/knowledge.js';
import type { Adapter } from '../src/bridge.js';
import { validateTool } from '../src/tools.js';

class FakeAdapter implements Adapter {
  key='image:1:region-a';
  mutations=0;
  async execute(operation:string,args:Record<string,unknown>={},options?:{expectedContext?:string;approved?:boolean}) {
    if(operation==='context')return {contextKey:this.key,image:{name:'Test slide'}};
    if(options?.expectedContext!==undefined&&options.expectedContext!==this.key)throw new Error('Application context changed.');
    if(operation==='annotate'){assert.equal(options?.approved,true);this.mutations++;return {created:args.name};}
    return {updated:true};
  }
}
async function fixture() {
  const directory=await mkdtemp(join(tmpdir(),'luma-test-'));
  const adapter=new FakeAdapter();const runtime=new Runtime(adapter,new Knowledge(),directory);
  return {directory,adapter,runtime,clean:()=>rm(directory,{recursive:true,force:true})};
}
const annotation={x:0,y:0,width:100,height:100,name:'Test ROI'};

test('data changes are proposals until the human decides; double approval executes once',async()=>{
  const f=await fixture();try {
    const pending=await f.runtime.call('qupath_annotate',annotation) as {actionId:string;status:string};
    assert.equal(pending.status,'approval_required');assert.equal(f.adapter.mutations,0);
    const results=await Promise.allSettled([f.runtime.decide(pending.actionId,true),f.runtime.decide(pending.actionId,true)]);
    assert.equal(results.filter(r=>r.status==='fulfilled').length,1);assert.equal(f.adapter.mutations,1);
    assert.equal(f.runtime.actions.get(pending.actionId)?.status,'completed');
  }finally{await f.clean();}
});
test('a proposal owns an immutable copy of its reviewed parameters',async()=>{
  const f=await fixture();try {
    const args={...annotation};const pending=await f.runtime.call('qupath_annotate',args) as {actionId:string};
    args.name='Unreviewed replacement';await f.runtime.decide(pending.actionId,true);
    assert.deepEqual(f.runtime.actions.get(pending.actionId)?.result,{created:'Test ROI'});
  }finally{await f.clean();}
});
test('selection changes invalidate approval instead of targeting the new region',async()=>{
  const f=await fixture();try {
    const pending=await f.runtime.call('qupath_annotate',annotation) as {actionId:string};f.adapter.key='image:1:region-b';
    const action=await f.runtime.decide(pending.actionId,true);
    assert.equal(action.status,'failed');assert.match(action.error??'',/context changed/);assert.equal(f.adapter.mutations,0);
  }finally{await f.clean();}
});
test('denial and expiry never execute a mutation',async()=>{
  const f=await fixture();try {
    const denied=await f.runtime.call('qupath_annotate',annotation) as {actionId:string};
    assert.equal((await f.runtime.decide(denied.actionId,false)).status,'denied');
    const expired=await f.runtime.call('qupath_annotate',annotation) as {actionId:string};
    f.runtime.actions.get(expired.actionId)!.expiresAt=new Date(0).toISOString();
    await assert.rejects(f.runtime.decide(expired.actionId,true),/expired/);assert.equal(f.adapter.mutations,0);
  }finally{await f.clean();}
});
test('display-only operations execute immediately',async()=>{
  const f=await fixture();try {assert.deepEqual(await f.runtime.call('qupath_overlays',{fill:true}),{updated:true});assert.equal(f.runtime.actions.size,0);}finally{await f.clean();}
});
test('tool validation rejects hidden approval flags, NaN, unknown tools and invalid scopes',()=>{
  assert.throws(()=>validateTool('qupath_run_script',{source:'print 1',reason:'test',approved:true}),/Invalid arguments/);
  assert.throws(()=>validateTool('qupath_annotate',{...annotation,width:NaN}),/Invalid arguments/);
  assert.throws(()=>validateTool('qupath_delete_selected',{scope:'all'}),/Invalid arguments/);
  assert.throws(()=>validateTool('qupath_approve',{}),/Unknown tool/);
});

test('native-control approval includes the exact observed dialog fingerprint',async()=>{
  const directory=await mkdtemp(join(tmpdir(),'luma-ui-test-'));
  let observed:unknown;
  const adapter:Adapter={execute:async(operation,_args,options)=>{
    if(operation==='context')return {contextKey:'test-image'};
    if(operation==='ui')return {windows:[{title:'Test dialog',fingerprint:'dialog-state-1',controls:[{id:'apply-1',type:'Button',text:'Apply',disabled:false}]}]};
    observed=options?.expectedControl;return {dispatched:true};
  }};
  try{
    const runtime=new Runtime(adapter,new Knowledge(),directory);
    const pending=await runtime.call('qupath_ui_action',{nodeId:'apply-1',action:'fire'}) as {actionId:string};
    const action=await runtime.decide(pending.actionId,true);
    assert.equal(action.status,'completed');
    assert.deepEqual(observed,{window:'Test dialog',windowFingerprint:'dialog-state-1',id:'apply-1',type:'Button',text:'Apply',disabled:false});
  }finally{await rm(directory,{recursive:true,force:true});}
});
