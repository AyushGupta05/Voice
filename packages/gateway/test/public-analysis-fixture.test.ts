import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,rm,writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import type {Adapter} from '../src/bridge.js';
import {Knowledge} from '../src/knowledge.js';
import {ResponsesBackend} from '../src/backend.js';
import {PublicAnalysisAdapter,PublicAnalysisRuntime,assertAnalysisBaseline} from '../../../script/public-analysis-fixture.js';

class SyntheticPublicAdapter implements Adapter {
  revision=0;mutations=0;failDetection=false;restores=0;changeDuringDispatch=false;failContext=false;
  context:any={application:'QuPath',contextKey:'fixture-0',project:null,
    image:{name:'OS-2.ndpi',width:126976,height:73728,type:'BRIGHTFIELD_H_DAB',privatePath:'PRIVATE_SOURCE_PATH'},
    viewer:{centerX:51100,centerY:27100,downsample:3,z:0,t:0},counts:{},selection:[],selectionCount:0,primarySelectedId:null,
    checkpoints:[{currentImage:true,id:'PRIVATE_UNRELATED_CHECKPOINT'}],lastDispatch:{secret:'PRIVATE_DISPATCH'},analysisRunning:false,
    measurementNames:[]};
  async execute(operation:string,args:Record<string,unknown>={},options?:{approved?:boolean;expectedContext?:string}) {
    if(operation==='context'){if(this.failContext)throw new Error('PRIVATE_CONTEXT_PATH');return structuredClone(this.context);}
    if(this.changeDuringDispatch){this.changeDuringDispatch=false;this.context.contextKey='independent-dispatch';this.context.counts.annotations=2;}
    assert.equal(options?.expectedContext,this.context.contextKey);
    if(operation==='objects')return{total:this.context.counts.cells??0,objects:[{secret:'PRIVATE_OBJECT_DETAILS'}]};
    if(operation==='compare')return{baseline:{},current:structuredClone(this.context.counts),delta:structuredClone(this.context.counts)};
    assert.equal(options?.approved,true);this.mutations++;
    let result:any={result:{}};
    if(operation==='annotate') {
      const object={id:'owned-roi',name:'Public sample integration ROI',kind:'annotation',roi:{x:50000,y:26000,width:2200,height:2200,areaPixels:4840000,z:0,t:0}};
      this.context.counts={annotations:1};this.context.selection=[object];this.context.selectionCount=1;this.context.primarySelectedId=object.id;
      result={result:{id:object.id}};
    } else if(operation==='detect') {
      this.context.counts={annotations:1,cells:this.failDetection?898:899};
      this.context.measurementNames=['Nucleus: DAB OD mean'];result={result:{executionFinished:true}};
    } else if(operation==='classify') {
      this.context.counts={annotations:1,cells:899,'class:Positive':49,'class:Negative':850};result={result:{positive:49,negative:850}};
    } else if(operation==='restore') {
      this.restores++;this.context.counts={};this.context.selection=[];this.context.selectionCount=0;this.context.primarySelectedId=null;
      result={restored:args.checkpointId};
    } else assert.equal(operation,'image_type');
    this.context.contextKey='fixture-'+ ++this.revision;
    if(operation==='detect'&&this.failDetection)throw new Error('PRIVATE_NATIVE_DIAGNOSTIC');
    return {...result,contextKey:this.context.contextKey,privateDiagnostic:'PRIVATE_OPERATION_RESULT'};
  }
}
async function fixture(){
  const directory=await mkdtemp(join(tmpdir(),'luma-analysis-contract-'));
  const raw=new SyntheticPublicAdapter();const adapter=new PublicAnalysisAdapter(raw,raw.context,'owned-baseline');
  const runtime=new PublicAnalysisRuntime(adapter,new Knowledge(),directory);
  return {raw,adapter,runtime,clean:()=>rm(directory,{recursive:true,force:true})};
}
async function next(f:Awaited<ReturnType<typeof fixture>>,index:number) {
  const step=f.adapter.steps[index];
  const proposed=await f.runtime.call(step.name,structuredClone(step.args),undefined,()=>{}) as any;
  if(proposed.status==='approval_required') {
    assert.equal(f.raw.mutations,index===0?0:index===1?1:index===2?2:index===3?3:4);
    const action=await f.runtime.decide(proposed.actionId,true);
    assert.equal(action.status,'completed',action.error);return action.result;
  }
  return proposed;
}

test('analysis fixture refuses nonempty/research/project/foreign-checkpoint baselines',()=>{
  const raw=new SyntheticPublicAdapter();assert.doesNotThrow(()=>assertAnalysisBaseline(raw.context));
  for(const change of [{image:{...raw.context.image,name:'Research.ndpi'}},{project:{name:'Research'}},{counts:{cells:1}},
    {checkpoints:[{currentImage:false}]},{analysisRunning:true},{viewer:{...raw.context.viewer,z:1}}])
    assert.throws(()=>assertAnalysisBaseline({...structuredClone(raw.context),...change}));
});
test('fixed analysis permits only ordered exact steps and never exposes unrelated session state',async()=>{
  const f=await fixture();try{
    await assert.rejects(f.runtime.call('qupath_run_script',{source:'anything',reason:'fixture'}),/exact step/);
    await assert.rejects(f.runtime.call('qupath_annotate',f.adapter.steps[1].args),/exact step/);
    for(let index=0;index<6;index++) {
      const context=await f.runtime.call('qupath_context',{});
      assert(!JSON.stringify(context).includes('PRIVATE_'));
      const result=await next(f,index);assert(!JSON.stringify(result).includes('PRIVATE_'));
      if(index===0)await assert.rejects(f.runtime.call('qupath_annotate',{...f.adapter.steps[1].args,width:2201}),/exact step/);
      if(index===1)await assert.rejects(f.runtime.call('qupath_classify',{...f.adapter.steps[3].args,threshold:0.3}),/exact step/);
      if(index===2)assert.equal((result as any).completionVerified,false,'Counts are not segmentation validity');
      if(index===3)await assert.rejects(f.runtime.call('qupath_compare',{checkpointId:'unrelated-checkpoint'}),/exact step/);
    }
    assert.equal(f.adapter.finished,true);assert.equal(f.raw.mutations,5);assert.equal(f.raw.restores,1);
    assert.deepEqual(f.raw.context.counts,{});assert.equal(f.raw.context.selectionCount,0);
    await assert.rejects(f.runtime.call('qupath_image_type',{type:'BRIGHTFIELD_H_DAB'}),/exact step/);
  }finally{await f.clean();}
});
test('uncertain partial native failure is not adopted and cleanup refuses speculative restoration',async()=>{
  const f=await fixture();try{
    await next(f,0);await next(f,1);f.raw.failDetection=true;
    const step=f.adapter.steps[2];const pending=await f.runtime.call(step.name,step.args) as any;
    const action=await f.runtime.decide(pending.actionId,true);
    assert.equal(action.status,'failed');assert(!JSON.stringify(action).includes('PRIVATE_NATIVE_DIAGNOSTIC'));
    assert.equal(f.adapter.finished,false);assert.equal(f.raw.context.counts.cells,898);
    await assert.rejects(f.runtime.call(step.name,step.args),/exact step/,'A failed native step must not be replayed');
    assert.equal(f.raw.mutations,3);
    await assert.rejects(f.adapter.restoreOwnedBaseline(f.runtime),/independently/);
    assert.equal(f.raw.context.counts.cells,898);assert.equal(f.raw.restores,0);
  }finally{await f.clean();}
});
test('an independently changed hierarchy rejected at native dispatch is never adopted for cleanup',async()=>{
  const f=await fixture();try{
    await next(f,0);await next(f,1);f.raw.changeDuringDispatch=true;
    const step=f.adapter.steps[2];const pending=await f.runtime.call(step.name,step.args) as any;
    const action=await f.runtime.decide(pending.actionId,true);assert.equal(action.status,'failed');
    await assert.rejects(f.adapter.restoreOwnedBaseline(f.runtime),/independently/);
    assert.equal(f.raw.mutations,2);assert.equal(f.raw.restores,0);assert.equal(f.raw.context.counts.annotations,2);
  }finally{await f.clean();}
});
test('a known successful fixture state can be restored after model failure through the owned Runtime path',async()=>{
  const f=await fixture();try{
    await next(f,0);await next(f,1);await next(f,2);
    const restored=await f.adapter.restoreOwnedBaseline(f.runtime);
    assert.equal(restored.restored,true);assert.equal(f.raw.restores,1);assert.deepEqual(f.raw.context.counts,{});
  }finally{await f.clean();}
});
test('context and audit failures remain private rather than appearing in a later model request',async()=>{
  const f=await fixture();try{
    f.raw.failContext=true;
    await assert.rejects(f.runtime.call('qupath_context',{}),error=>!String(error).includes('PRIVATE_'));
    assert(f.adapter.privateDiagnostics.some(text=>text.includes('PRIVATE_CONTEXT_PATH')));
    f.raw.failContext=false;
    const directory=await mkdtemp(join(tmpdir(),'luma-private-audit-'));try{
      const invalid=join(directory,'PRIVATE_AUDIT_PATH');await writeFile(invalid,'a file, not an audit directory');
      const runtime=new PublicAnalysisRuntime(f.adapter,new Knowledge(),invalid);let requests=0;
      const backend=new ResponsesBackend(runtime,{complete:async request=>{
        assert(!JSON.stringify(request).includes('PRIVATE_'));
        if(++requests===1)return{output:[{type:'function_call',call_id:'exact',name:'qupath_image_type',arguments:'{"type":"BRIGHTFIELD_H_DAB"}'}]};
        assert(String(request.input.find(item=>item.type==='function_call_output')?.output).includes('No session diagnostics'));
        return{output:[],output_text:'The fixture could not prepare the reviewed action.'};
      }},'synthetic',()=>{});
      await backend.run({id:'audit-fixture',offsetMs:0,context:await runtime.call('qupath_context',{}) as any,
        signal:new AbortController().signal,conversation:()=>({fragments:[],truncated:false}),update:()=>{}});
      assert.equal(requests,2);assert.equal(f.raw.mutations,0);
      assert(f.adapter.privateDiagnostics.some(text=>text.includes('PRIVATE_AUDIT_PATH')));
    }finally{await rm(directory,{recursive:true,force:true});}
  }finally{await f.clean();}
});
test('a model can correct rejected arguments using only the public canonical next-step error',async()=>{
  const f=await fixture();let requests=0;
  const decide=(action:any)=>{if(action.status==='pending')void f.runtime.decide(action.id,true);};
  f.runtime.on('action',decide);
  try{
    const backend=new ResponsesBackend(f.runtime,{complete:async request=>{
      assert(!JSON.stringify(request).includes('PRIVATE_'));
      if(++requests===1)return{output:[{type:'function_call',call_id:'wrong',name:'qupath_image_type',arguments:'{"type":"FLUORESCENCE"}'}]};
      if(requests===2){
        const result=JSON.parse(String(request.input.find(item=>item.type==='function_call_output')!.output));
        assert(result.error.includes('BRIGHTFIELD_H_DAB'));assert(result.error.includes('No operation executed'));
        return{output:[{type:'function_call',call_id:'correct',name:'qupath_image_type',arguments:'{"type":"BRIGHTFIELD_H_DAB"}'}]};
      }
      return{output:[],output_text:'The exact image-type step completed.'};
    }},'synthetic',()=>{});
    await backend.run({id:'correction-fixture',offsetMs:0,context:await f.runtime.call('qupath_context',{}) as any,
      signal:new AbortController().signal,conversation:()=>({fragments:[],truncated:false}),update:()=>{}});
    assert.equal(requests,3);assert.equal(f.raw.mutations,1);assert.deepEqual(f.adapter.executed,['qupath_image_type']);
    assert(f.adapter.privateDiagnostics.some(text=>text.includes('FLUORESCENCE')));
  }finally{f.runtime.off('action',decide);await f.clean();}
});
test('independent state changes block cleanup and leave the changed hierarchy untouched',async()=>{
  const f=await fixture();try{
    await next(f,0);await next(f,1);
    f.raw.context.contextKey='independently-changed';f.raw.context.counts.annotations=2;
    await assert.rejects(f.adapter.restoreOwnedBaseline(f.runtime),/independently/);
    assert.equal(f.raw.restores,0);assert.equal(f.raw.context.counts.annotations,2);
  }finally{await f.clean();}
});
