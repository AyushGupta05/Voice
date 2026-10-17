/** Explicitly authorized disposable OS-2 fixture. Never run on research data.
 * Simulates human decisions only for this fixed test sequence, through the companion channel.
 */
import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
if(!process.argv.includes('--public-fixture'))throw new Error('Pass --public-fixture only for the authorized disposable public OS-2 test.');
const model=(await readFile('.local/state/gateway-token','utf8')).trim();
const human=(await readFile('.local/state/companion-token','utf8')).trim();
async function request(path:string,body:unknown,token=model):Promise<any>{
 const response=await fetch(`http://127.0.0.1:49555${path}`,{method:'POST',headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},body:JSON.stringify(body),signal:AbortSignal.timeout(130000)});
 const value=await response.json() as any;if(!response.ok)throw new Error(value.error);return value;
}
async function call(name:string,args:Record<string,unknown>={}):Promise<any>{return (await request('/tools/call',{name,arguments:args})).result;}
async function approveFixture(name:string,args:Record<string,unknown>={}):Promise<any>{
 const proposal=await call(name,args);assert.equal(proposal.status,'approval_required');
 const {action}=await request(`/approvals/${proposal.actionId}`,{approved:true},human);
 assert.equal(action.status,'completed',action.error);return action.result;
}
async function waitFor(observe:()=>Promise<any>,predicate:(value:any)=>boolean,timeout=10000):Promise<any>{
 const deadline=Date.now()+timeout;
 do{const value=await observe();if(predicate(value))return value;await new Promise(resolve=>setTimeout(resolve,150));}while(Date.now()<deadline);
 throw new Error('Application did not reach the expected verified state.');
}
const start=await call('qupath_context');
assert.equal(start.image?.name,'OS-2.ndpi');assert.equal(start.image?.width,126976);assert.equal(start.image?.height,73728);assert.deepEqual(start.counts,{},'Fixture requires an empty disposable session.');
const observations:Record<string,unknown>={startedAt:new Date().toISOString(),image:start.image};
let ui=await call('qupath_ui');let dialog=ui.windows.find((window:any)=>window.title==='Set image type');
if(dialog){
 const hdab=dialog.controls.find((control:any)=>control.text==='Brightfield\nH-DAB');assert(hdab&&!hdab.disabled);
 await approveFixture('qupath_ui_action',{nodeId:hdab.id,action:'fire'});
 ui=await waitFor(()=>call('qupath_ui'),ui=>ui.windows.some((w:any)=>w.title==='Set image type'&&w.controls.some((c:any)=>c.text==='Brightfield\nH-DAB'&&c.selected)));
 dialog=ui.windows.find((w:any)=>w.title==='Set image type');const apply=dialog.controls.find((c:any)=>c.text==='Apply');assert(apply&&!apply.disabled);
 await approveFixture('qupath_ui_action',{nodeId:apply.id,action:'fire'});
 await waitFor(()=>call('qupath_ui'),ui=>!ui.windows.some((w:any)=>w.title==='Set image type'));
}
let context=await call('qupath_context');
if(context.image.type!=='BRIGHTFIELD_H_DAB')await approveFixture('qupath_image_type',{type:'BRIGHTFIELD_H_DAB'});
const baseline=await call('qupath_checkpoint',{label:'Integration baseline: no objects'});
await approveFixture('qupath_annotate',{x:50000,y:26000,width:2200,height:2200,name:'Public sample integration ROI'});
context=await call('qupath_context');assert.equal(context.counts.annotations,1);assert.equal(context.selectionCount,1);assert.equal(context.selection[0].name,'Public sample integration ROI');
const focus=await call('qupath_focus',{downsample:3});context=await call('qupath_context');
assert.equal(context.viewer.centerX,51100);assert.equal(context.viewer.centerY,27100);assert.equal(context.viewer.downsample,3);assert(Number.isFinite(focus.pointer.screenX));
observations.focus={requested:[51100,27100],actual:[context.viewer.centerX,context.viewer.centerY],pointer:focus.pointer};
await approveFixture('qupath_detect_cells',{parameters:{detectionImageBrightfield:'Hematoxylin OD',requestedPixelSizeMicrons:0.5,backgroundRadiusMicrons:8,medianRadiusMicrons:0,sigmaMicrons:1.5,minAreaMicrons:10,maxAreaMicrons:400,threshold:0.1,watershedPostProcess:true,cellExpansionMicrons:5,includeNuclei:true,smoothBoundaries:true,makeMeasurements:true}});
context=await call('qupath_context');assert(context.counts.cells>0,'Detection must create real cells.');observations.detection={counts:context.counts,measurementNames:context.measurementNames};
const regionId=context.primarySelectedId;
await call('qupath_select',{scope:'region',kind:'cell'});
const selectedCells=await call('qupath_context');
assert.equal(selectedCells.selectionCount,context.counts.cells);assert.equal(selectedCells.selection.length,100);assert.equal(selectedCells.selectionTruncated,true);assert.equal(selectedCells.contextKey.length,64);
observations.selectionPreview={fullCount:selectedCells.selectionCount,previewCount:selectedCells.selection.length,truncated:selectedCells.selectionTruncated,contextKeyLength:selectedCells.contextKey.length};
await call('qupath_select',{ids:[regionId]});
const measured=await call('qupath_measurements',{kind:'cell',scope:'region',limit:5});assert(measured.rows.length>0);assert.equal(measured.total,context.counts.cells);assert(Object.keys(measured.rows[0].measurements).length>0);
const dab=context.measurementNames.find((name:string)=>/Nucleus.*DAB.*mean/i.test(name));assert(dab,'Expected a real nuclear DAB measurement');
const classified=await approveFixture('qupath_classify',{scope:'region',kind:'cell',measurement:dab,threshold:0.2});
context=await call('qupath_context');observations.classification={result:classified,counts:context.counts};
const compared=await call('qupath_compare',{checkpointId:baseline.id});assert.equal(compared.delta.cells,context.counts.cells);observations.comparison=compared;
await call('qupath_overlays',{detections:true,fill:true,classification:'Positive',opacity:0.8});
await new Promise(resolve=>setTimeout(resolve,200));
const snapshot=await call('qupath_snapshot');await mkdir('.local/evidence',{recursive:true});await writeFile('.local/evidence/ki67-positive-view.png',Buffer.from(snapshot.data,'base64'));
await approveFixture('qupath_restore',{checkpointId:baseline.id});context=await call('qupath_context');assert.deepEqual(context.counts,{});assert.equal(context.selectionCount,0);assert.deepEqual(context.selection,[],'Restored hierarchy must not retain detached selected objects.');observations.restoredSelection=context.selection;observations.restoredCounts=context.counts;
await call('qupath_overlays',{classification:'',fill:false});
observations.completedAt=new Date().toISOString();observations.status='passed';await writeFile('.local/evidence/integration.json',JSON.stringify(observations,null,2)+'\n');
console.log(JSON.stringify(observations,null,2));
