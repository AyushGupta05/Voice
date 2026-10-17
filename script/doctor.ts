import { access, readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { Knowledge } from '../packages/gateway/src/knowledge.js';
import {bridgeReadiness,bridgeReady} from '../packages/gateway/src/bridge-readiness.js';
import {bridgeHealth} from '../packages/gateway/src/bridge.js';
import { activeProfile } from '../packages/gateway/src/apps/index.js';

type Check = { name: string; status: 'pass' | 'fail' | 'note'; detail: string };
const root=resolve(process.env.LUMA_ROOT??fileURLToPath(new URL('..',import.meta.url)));
// Diagnostics follow the selected application (LUMA_APP): its bridge artifact, launcher, tools and knowledge.
const profile=activeProfile();const {tools}=profile;const app=profile.displayName;
const checks:Check[]=[{name:'application',status:'note',detail:`${app} profile (LUMA_APP=${profile.id}); ${tools.length} tools.`}];
const add=(name:string,pass:boolean,good:string,bad:string)=>checks.push({name,status:pass?'pass':'fail',detail:pass?good:bad});
const exists=async(path:string)=>{try{await access(resolve(root,path));return true;}catch{return false;}};
add('platform',process.platform==='darwin'&&process.arch==='arm64','Apple Silicon macOS.','The native companion/bootstrap currently require Apple Silicon macOS.');
add('node',Number(process.versions.node.split('.')[0])>=22,`Node ${process.versions.node}.`,'Use Node.js 22 or newer.');
for(const [path,name] of [['node_modules/tsx/dist/loader.mjs','TypeScript loader'] as [string,string],...profile.doctorArtifacts,['dist/Luma.app/Contents/MacOS/LumaCompanion','Companion build'] as [string,string]]) {
  add(name,await exists(path),'Local artifact exists; runtime checks follow.',`Missing ${path}. Follow docs/PENNY-SETUP.md; this command installs nothing.`);
}
const manifest=JSON.parse(await readFile(new URL('./dependencies.json',import.meta.url),'utf8'));
for(const [directory,expected] of profile.id==='qupath'?[['qupath',manifest.qupath.sourceCommit],['qupath-docs',manifest.qupathDocs.commit]]:[]) {
  let actual='';try{actual=execFileSync('git',['-C',resolve(root,'.local/upstream',directory),'rev-parse','HEAD'],{encoding:'utf8',stdio:['ignore','pipe','ignore'],timeout:2000}).trim();}catch{}
  add(`${directory} source pin`,actual===expected,'Checkout matches dependencies.json.','Missing or different checkout. Preserve local changes and inspect the pinned revision before rebuilding.');
}
const knowledge=new Knowledge(profile);await knowledge.load(root);const documentCount=knowledge.search(app).documentCount;
add('knowledge',documentCount>4,`${documentCount} local guidance documents indexed.`,'Official documentation is absent or incomplete; run the documented bootstrap.');
let readyForVoice=false;
try {
  const token=(await readFile(resolve(root,'.local/state/gateway-token'),'utf8')).trim();
  const headers={Authorization:`Bearer ${token}`};
  const healthResponse=await fetch('http://127.0.0.1:49555/health',{headers,signal:AbortSignal.timeout(4000)});
  if(!healthResponse.ok)throw new Error('Authentication or gateway response failed.');
  const health=await healthResponse.json() as {application?:unknown;app?:{id?:unknown};bridgeError?:unknown;context?:{image?:unknown};voice?:{enabled?:unknown}};
  add('gateway',health.application==='Luma','Authenticated Luma gateway is reachable.','Unexpected service on the gateway port.');
  add('gateway application',health.app?.id===profile.id,`The running gateway drives ${app}.`,`The running gateway drives ${String(health.app?.id??'an older build without an app field')}, not ${profile.id}; restart it with LUMA_APP=${profile.id}.`);
  add(`${app} bridge`,!health.bridgeError&&!!health.context,'Real application context is reachable.',`Bridge unavailable or busy. Start ${app} with ${profile.launcherScript} and inspect local logs.`);
  // A profile without a bridge build identity (Fidelis) declares its operations in context.adapter, or on the bridge's own /health.
  let identity:unknown=health.context;
  if(profile.bridgeIdentity==='operations'&&health.context&&!(health.context as {adapter?:unknown}).adapter){
    let bridgeToken:string|undefined;try{bridgeToken=(await readFile(resolve(root,'.local/state/bridge-token'),'utf8')).trim();}catch{bridgeToken=undefined;}
    identity={...health.context,adapter:(await bridgeHealth(profile.bridgePort,bridgeToken))?.adapter};
  }
  const loadedBridge=await bridgeReadiness(root,identity,profile);
  add(`loaded ${app} build`,bridgeReady(loadedBridge),loadedBridge.message,loadedBridge.message);
  if(health.context&&!health.context.image)checks.push({name:'active image',status:'note',detail:'Open an image and select a region for scientific tools.'});
  const schemaResponse=await fetch('http://127.0.0.1:49555/tools',{headers,signal:AbortSignal.timeout(2000)});
  if(!schemaResponse.ok)throw new Error('Tool schema request failed.');
  const schema=await schemaResponse.json() as {tools?:unknown};
  add('running tool schemas',JSON.stringify(schema.tools)===JSON.stringify(tools),`All ${tools.length} running definitions match this checkout.`,'Gateway definitions differ. Restart its owning companion or development gateway after work has finished.');
  // This tool is diagnostic only. It cannot enable providers or establish credit eligibility.
  readyForVoice=false;
  add('production voice gate',health.voice?.enabled===false,'Disabled as required by the credit-only funding policy.','Unexpected voice gate state. Inspect the server before any provider use.');
} catch {
  checks.push({name:'gateway connection',status:'fail',detail:`Cannot complete authenticated local checks. Launch Luma and ${app}; a busy bridge or a timeout can also cause this result. No operation was retried.`});
}
const readyForLocalTools=checks.every(check=>check.status!=='fail');
console.log(JSON.stringify({readyForLocalTools,readyForVoice,checks,boundary:'Read-only local diagnostics. File existence is not a fresh build, connection success is not an analysis test, and voice funding/hardware remain unverified. No tokens, image names or provider calls are included.'},null,2));
if(!readyForLocalTools)process.exitCode=1;
