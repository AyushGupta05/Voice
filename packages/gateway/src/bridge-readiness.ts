import {open} from 'node:fs/promises';
import {constants} from 'node:fs';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
import {activeProfile,type AppProfile} from './apps/index.js';

/** `operations_match` is the ready state of a profile without a bridge build identity (bridgeIdentity 'operations',
 * Fidelis): the running bridge declares every required operation; there is no completed-build comparison. */
export type BridgeReadiness={status:'matches_build'|'operations_match'|'restart_required'|'build_unverified'|'unavailable';message:string;missingOperations?:string[]};
/** Whether a readiness result means the loaded bridge can serve every declared tool. */
export const bridgeReady=(readiness:BridgeReadiness)=>readiness.status==='matches_build'||readiness.status==='operations_match';
// desktop_* operations are served by the companion's Accessibility layer, not the Java extension.
export const requiredOperationsFor=(profile:AppProfile)=>[...new Set(profile.tools.flatMap(tool=>tool.operation&&!tool.operation.startsWith('desktop_')?[tool.operation]:[]))].sort();
/** The active application's required bridge operations. */
export const requiredBridgeOperations=requiredOperationsFor(activeProfile());
const sha=(value:unknown):value is string=>typeof value==='string'&&/^[a-f0-9]{64}$/.test(value);
async function readBoundedFile(path:string,maximum:number):Promise<Buffer>{
  // Nonblocking open lets us reject FIFOs/devices before reading. A descriptor
  // also keeps validation tied to the file actually read across atomic swaps.
  const file=await open(path,constants.O_RDONLY|constants.O_NONBLOCK);
  try{
    const info=await file.stat();
    if(!info.isFile()||info.size>maximum)throw new Error('Not a bounded regular build file');
    const chunks:Buffer[]=[];let total=0;
    while(total<=maximum){
      const chunk=Buffer.allocUnsafe(Math.min(64*1024,maximum+1-total));
      const {bytesRead}=await file.read(chunk,0,chunk.length,null);
      if(!bytesRead)return Buffer.concat(chunks,total);
      total+=bytesRead;if(total>maximum)throw new Error('Build file grew past its bound');
      chunks.push(chunk.subarray(0,bytesRead));
    }
    throw new Error('Build file exceeds bound');
  }finally{await file.close();}
}

/** Read-only comparison of startup-cached identity with the latest completed artifact.
 * It does not establish source freshness, tested behavior, or permission to restart.
 */
export async function bridgeReadiness(root:string,context:unknown,profile:AppProfile=activeProfile()):Promise<BridgeReadiness>{
  const app=profile.displayName;const required=requiredOperationsFor(profile);
  if(!context||typeof context!=='object')return{status:'unavailable',message:`The running ${app} context is unavailable.`};
  if(profile.bridgeIdentity==='operations')return declaredOperations(context,profile,required);
  let built:{version?:unknown;protocolVersion?:unknown;sourceSha256?:unknown;jarSha256?:unknown};
  try{
    built=JSON.parse((await readBoundedFile(join(root,profile.buildManifest),4096)).toString('utf8'));
    if(!built||built.version!==1||built.protocolVersion!==1||!sha(built.sourceSha256)||!sha(built.jarSha256))throw new Error('Invalid build manifest');
    const jar=await readBoundedFile(join(root,profile.bridgeJar),16*1024*1024);
    if(createHash('sha256').update(jar).digest('hex')!==built.jarSha256)throw new Error('Built artifact differs');
  }catch{return{status:'build_unverified',message:`The local ${app} bridge build identity is absent or inconsistent. Rebuild with ${profile.buildScript}; no application was changed.`};}
  const loaded=(context as {adapter?:Record<string,unknown>}).adapter;
  if(!loaded)return{status:'restart_required',message:`The running ${app} bridge predates build identity. Save work and quit/relaunch ${app} normally to load the built extension.`};
  if(loaded.protocolVersion!==1||!sha(loaded.sourceSha256)||!Array.isArray(loaded.operations)
      ||loaded.operations.length>64||loaded.operations.some(value=>typeof value!=='string'||!/^[a-z][a-z0-9_]{0,63}$/.test(value))
      ||new Set(loaded.operations).size!==loaded.operations.length)
    return{status:'build_unverified',message:'The loaded bridge identity/protocol cannot be verified. Inspect compatibility before using new capabilities.'};
  const operations=loaded.operations as string[];
  const missing=required.filter(operation=>!operations.includes(operation));
  if(loaded.sourceSha256!==built.sourceSha256||missing.length)return{status:'restart_required',missingOperations:missing,
    message:`The running ${app} extension differs from the completed build or lacks declared operations. Save work and quit/relaunch ${app} normally; no automatic restart was attempted.`};
  return{status:'matches_build',message:`The loaded extension reports the completed build's source identity and all ${required.length} required operations. Behavior and scientific validity require separate checks.`};
}
/** Readiness from the operations a bridge declares on `GET /health` (`adapter.operations`), for an application built by
 * its own tools (Fidelis): no source digest or artifact hash is compared, so a stale but complete build passes. */
function declaredOperations(context:object,profile:AppProfile,required:string[]):BridgeReadiness{
  const app=profile.displayName;
  const loaded=(context as {adapter?:Record<string,unknown>}).adapter;
  if(!loaded||typeof loaded!=='object')return{status:'unavailable',message:`The running ${app} bridge did not report its operations on /health. Start ${app} with ${profile.launcherScript}.`};
  if(loaded.protocolVersion!==1||!Array.isArray(loaded.operations)||loaded.operations.length>64
      ||loaded.operations.some(value=>typeof value!=='string'||!/^[a-z][a-z0-9_]{0,63}$/.test(value))||new Set(loaded.operations).size!==loaded.operations.length)
    return{status:'build_unverified',message:`The running ${app} bridge's protocol or operation list cannot be verified. Inspect compatibility before using new capabilities.`};
  const operations=loaded.operations as string[];
  const missing=required.filter(operation=>!operations.includes(operation));
  if(missing.length)return{status:'restart_required',missingOperations:missing,
    message:`The running ${app} bridge lacks ${missing.length} declared operation${missing.length===1?'':'s'}. Save work, quit ${app} normally, rebuild with ${profile.buildScript} and relaunch with ${profile.launcherScript}; no automatic restart was attempted.`};
  return{status:'operations_match',message:`The running ${app} bridge declares all ${required.length} required operations. ${app} has no separate bridge build identity, so loaded code is not compared with the checkout; behavior and scientific validity require separate checks.`};
}
