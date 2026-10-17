import {PublicFixtureRuntime,publicFixtureContext} from './public-text-fixture.js';
import {validateTool} from '../packages/gateway/src/tools.js';
import {Runtime} from '../packages/gateway/src/runtime.js';
import type {Adapter,Context} from '../packages/gateway/src/bridge.js';
import type {Knowledge} from '../packages/gateway/src/knowledge.js';
/** Model-test authority restricted to public context and committed public API reference. No view/data/code actions. */
export class PublicApiFixtureRuntime extends Runtime {
 private readonly publicContext:PublicFixtureRuntime;
 constructor(adapter:Adapter,knowledge:Knowledge,directory:string,baseline:Context){
  super(adapter,knowledge,directory);this.publicContext=new PublicFixtureRuntime(adapter,knowledge,directory,baseline);
 }
 override async call(...args:Parameters<Runtime['call']>){
  const [name,parameters,signal,guard]=args;
  if(name==='qupath_context')return this.publicContext.call(...args);
  if(!['qupath_api_search','qupath_api_read'].includes(name))throw new Error('This fixture permits only public context and pinned API references. No application action or approval is available.');
  validateTool(name,parameters);signal?.throwIfAborted();
  const context=publicFixtureContext(await this.publicContext.call('qupath_context',{},signal));guard?.(context);
  const result=name==='qupath_api_search'?this.knowledge.api.search(parameters.query as string,parameters.limit as number|undefined):this.knowledge.api.read(parameters.source as string,parameters.startLine as number|undefined,parameters.lineCount as number|undefined);
  signal?.throwIfAborted();const current=publicFixtureContext(await this.publicContext.call('qupath_context',{},signal));guard?.(current);
  this.emit('tool',{name,arguments:parameters,result});return result;
 }
}
