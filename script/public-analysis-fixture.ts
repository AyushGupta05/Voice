/** Test-only authority for the user's already-authorized, fixed OS-2 sequence.
 * No import, file, script, command, arbitrary ROI or general approval capability.
 */
import { isDeepStrictEqual } from 'node:util';
import type { Adapter, Context } from '../packages/gateway/src/bridge.js';
import { Runtime } from '../packages/gateway/src/runtime.js';
import type { Arguments } from '../packages/gateway/src/tools.js';
import type { Knowledge } from '../packages/gateway/src/knowledge.js';
import { assertPublicFixture } from './public-text-fixture.js';

export const analysisROI = { x:50000,y:26000,width:2200,height:2200,name:'Public sample integration ROI' };
export const analysisParameters = {
  detectionImageBrightfield:'Hematoxylin OD',requestedPixelSizeMicrons:0.5,backgroundRadiusMicrons:8,
  medianRadiusMicrons:0,sigmaMicrons:1.5,minAreaMicrons:10,maxAreaMicrons:400,threshold:0.1,
  watershedPostProcess:true,cellExpansionMicrons:5,includeNuclei:true,smoothBoundaries:true,makeMeasurements:true,
};
export const analysisClassification = { scope:'region',kind:'cell',measurement:'Nucleus: DAB OD mean',threshold:0.2 };
type FixtureContext = Context & { image: {name:string;width:number;height:number;type:string;pixelSizeMicrons?:unknown};
  counts:Record<string,number>;selection:Array<{id:string;name?:string;kind?:string;roi?:Record<string,unknown>}>;
  selectionCount:number;primarySelectedId?:string|null;measurementNames?:string[];viewer:Record<string,number>;
  checkpoints?:Array<{currentImage?:boolean}>;analysisRunning?:boolean;project?:unknown };
function identity(value:unknown): asserts value is FixtureContext {
  const c=value as FixtureContext;
  if (!c || c.image?.name!=='OS-2.ndpi' || c.image.width!==126976 || c.image.height!==73728
      || c.image.type!=='BRIGHTFIELD_H_DAB' || c.project!=null || !c.contextKey
      || !c.viewer || c.viewer.z!==0 || c.viewer.t!==0 || c.analysisRunning)
    throw new Error('The dedicated public OS-2 fixture identity/plane/state changed. No further model action is allowed.');
}
export function assertAnalysisBaseline(value:unknown): asserts value is FixtureContext {
  assertPublicFixture(value);identity(value);
  if (!Array.isArray(value.checkpoints) || value.checkpoints.some(item=>item.currentImage!==true))
    throw new Error('Fixture requires no checkpoints belonging to other images; no cache eviction is authorized there.');
}
const detected={annotations:1,cells:899};
const classified={...detected,'class:Positive':49,'class:Negative':850};
class FixtureError extends Error {
  constructor(message='The fixed public fixture call failed or was outside the next exact step. No session diagnostics are sent.'){super(message);}
}

export class PublicAnalysisAdapter implements Adapter {
  private phase=0;
  private key:string;
  private counts:Record<string,number>={};
  private roiId?:string;
  private cleanup=false;
  readonly executed:string[]=[];
  readonly attempted=new Set<string>();
  readonly observedCounts:Record<string,Record<string,number>>={};
  readonly privateDiagnostics:string[]=[];
  recordPrivate(error:unknown){if(!(error instanceof FixtureError)){this.privateDiagnostics.push(String(error).slice(0,4000));if(this.privateDiagnostics.length>32)this.privateDiagnostics.shift();}}
  constructor(private raw:Adapter, baseline:unknown,readonly baselineId:string) {
    assertAnalysisBaseline(baseline);
    if (!baselineId)throw new Error('An owned baseline checkpoint is required.');
    this.key=baseline.contextKey;
  }
  get finished(){return this.phase===6;}
  get steps(){return [
    {name:'qupath_image_type',operation:'image_type',args:{type:'BRIGHTFIELD_H_DAB'}},
    {name:'qupath_annotate',operation:'annotate',args:analysisROI},
    {name:'qupath_detect_cells',operation:'detect',args:{parameters:analysisParameters}},
    {name:'qupath_classify',operation:'classify',args:analysisClassification},
    {name:'qupath_compare',operation:'compare',args:{checkpointId:this.baselineId}},
    {name:'qupath_restore',operation:'restore',args:{checkpointId:this.baselineId}},
  ];}
  assertTool(name:string,args:Arguments) {
    if(name==='qupath_context' && Object.keys(args).length===0)return;
    const expected=this.cleanup?this.steps[5]:this.steps[this.phase];
    const normalized=name==='qupath_annotate'&&args.shape==='rectangle'?Object.fromEntries(Object.entries(args).filter(([key])=>key!=='shape')):args;
    if(!expected || name!==expected.name || this.attempted.has(name) || !isDeepStrictEqual(normalized,expected.args)) {
      this.recordPrivate(new Error('Rejected fixture request: '+JSON.stringify({name,args}).slice(0,3500)));
      const guidance=expected&&!this.attempted.has(expected.name)
        ?'The next exact step is '+JSON.stringify({tool:expected.name,arguments:expected.args})+'. Submit those exact arguments.'
        :'No next exact step is executable; started steps cannot be retried. Report the incomplete outcome.';
      throw new FixtureError('No operation executed. '+guidance+' No session diagnostics are sent.');
    }
  }
  private async current() {
    const value=await this.raw.execute('context');identity(value);
    if(value.contextKey!==this.key || !isDeepStrictEqual(value.counts,this.counts))
      throw new Error('Fixture state changed independently. Stop and inspect; no automatic retargeting or restoration.');
    this.assertSelection(value,this.counts,this.roiId);
    return value;
  }
  private assertSelection(value:FixtureContext,counts:Record<string,number>,roiId:string|undefined) {
    if(Object.keys(counts).length) {
      const selection=value.selection?.[0];const roi=selection?.roi;
      if(!roiId || value.selectionCount!==1 || selection?.id!==roiId || value.primarySelectedId!==roiId
          || !roi || roi.x!==50000 || roi.y!==26000 || roi.width!==2200 || roi.height!==2200
          || roi.areaPixels!==4840000 || roi.z!==0 || roi.t!==0)
        throw new Error('The exact authorized ROI/selection changed. No further action is allowed.');
    } else if(value.selectionCount!==0 || value.selection?.length!==0)throw new Error('Empty fixture selection changed.');
  }
  private publicContext(c:FixtureContext):Context {
    return {application:'QuPath',contextKey:c.contextKey,project:null,
      image:{name:c.image.name,width:c.image.width,height:c.image.height,type:c.image.type,pixelSizeMicrons:c.image.pixelSizeMicrons} as Context['image'],
      counts:structuredClone(c.counts),selectionCount:c.selectionCount,selectionTruncated:false,
      primarySelectedId:this.roiId&&c.selectionCount?this.roiId:null,
      selection:this.roiId&&c.selectionCount?[{id:this.roiId,kind:'annotation',name:analysisROI.name,roi:{...analysisROI,z:0,t:0}}]:[],
      measurementNames:(c.measurementNames??[]).slice(0,100),viewer:{...c.viewer},
      note:'Authorized fixed public OS-2 analysis fixture; other session state deliberately excluded.'};
  }
  async execute(operation:string,args:Arguments={},options:Parameters<Adapter['execute']>[2]={}):Promise<unknown> {
    try{return await this.executeChecked(operation,args,options);}
    catch(error){this.recordPrivate(error);throw error instanceof FixtureError?error:new FixtureError();}
  }
  private async executeChecked(operation:string,args:Arguments,options:NonNullable<Parameters<Adapter['execute']>[2]>):Promise<unknown> {
    const before=await this.current();
    if(operation==='context')return this.publicContext(before);
    const step=this.cleanup?this.steps[5]:this.steps[this.phase];
    if(!step || step.operation!==operation)throw new Error('Operation is outside the fixed fixture sequence.');
    this.assertTool(step.name,args);
    if(options.expectedContext!==this.key || (operation!=='compare'&&options.approved!==true))
      throw new Error('Fixture requires the actual context-bound Runtime approval path.');
    this.attempted.add(step.name);
    // A rejected/failed dispatch never transfers ownership to its later state.
    // Unknown partial effects require inspection, not a speculative undo.
    const result=await this.raw.execute(operation,args,options) as any;
    const after=await this.raw.execute('context');identity(after);
    const expected:Record<string,number>=operation==='image_type'||operation==='restore'?{}:operation==='annotate'?{annotations:1}:operation==='detect'?detected:classified;
    if(!isDeepStrictEqual(after.counts,expected))throw new Error('Actual fixed-fixture counts differ; do not claim a successful scientific test.');
    const roiId=operation==='annotate'?result?.result?.id:this.roiId;
    this.assertSelection(after,expected,roiId);
    if(operation==='compare'?after.contextKey!==this.key:operation!=='restore'&&result?.contextKey!==after.contextKey)
      throw new Error('Context changed between native completion and observation; ownership was not transferred.');
    if(operation==='detect' && result?.result?.executionFinished!==true)throw new Error('Native detection did not report execution finished.');
    if(operation==='classify' && (result?.result?.positive!==49 || result?.result?.negative!==850))throw new Error('Actual DAB classification result differs.');
    if(operation==='compare' && (!isDeepStrictEqual(result?.baseline,{}) || !isDeepStrictEqual(result?.current,classified) || !isDeepStrictEqual(result?.delta,classified)))throw new Error('Actual baseline comparison differs.');
    if(operation==='restore' && result?.restored!==this.baselineId)throw new Error('The owned baseline was not restored.');
    this.key=after.contextKey;this.counts=structuredClone(after.counts);this.roiId=roiId;
    this.executed.push(step.name);this.observedCounts[operation]=structuredClone(after.counts);
    this.phase=this.cleanup?6:this.phase+1;
    return operation==='compare'?{baseline:{},current:structuredClone(after.counts),delta:structuredClone(result.delta)}:
      {context:this.publicContext(after),...(operation==='detect'?{executionFinished:true,completionVerified:false,scientificValidation:'not established'}:{}),
        ...(operation==='restore'?{restored:this.baselineId}:{})};
  }
  async restoreOwnedBaseline(runtime:Runtime) {
    const current=await this.current();
    if(Object.keys(current.counts).length===0)return {restored:true,alreadyEmpty:true};
    this.cleanup=true;
    try {
      const pending=await runtime.call('qupath_restore',{checkpointId:this.baselineId}) as {actionId:string};
      const action=await runtime.decide(pending.actionId,true);
      if(action.status!=='completed')throw new Error('Owned cleanup failed; inspect the public test instance before further work.');
      return {restored:true,alreadyEmpty:false};
    } finally {this.cleanup=false;}
  }
}

export class PublicAnalysisRuntime extends Runtime {
  constructor(readonly fixture:PublicAnalysisAdapter,knowledge:Knowledge,directory:string) {super(fixture,knowledge,directory);}
  override async call(...args:Parameters<Runtime['call']>) {
    try {
      this.fixture.assertTool(args[0],args[1]);
      return await super.call(...args);
    }catch(error){args[2]?.throwIfAborted();this.fixture.recordPrivate(error);throw error instanceof FixtureError?error:new FixtureError();}
  }
}
