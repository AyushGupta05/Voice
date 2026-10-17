import {open} from 'node:fs/promises';
import {constants} from 'node:fs';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
import {tools} from './tools.js';

export type BridgeReadiness={status:'matches_build'|'restart_required'|'build_unverified'|'unavailable';message:string;missingOperations?:string[]};
// desktop_* operations are served by the companion's Accessibility layer, not the Java extension.
export const requiredBridgeOperations=[...new Set(tools.flatMap(tool=>tool.operation&&!tool.operation.startsWith('desktop_')?[tool.operation]:[]))].sort();
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
export async function bridgeReadiness(root:string,context:unknown):Promise<BridgeReadiness>{
  if(!context||typeof context!=='object')return{status:'unavailable',message:'The running QuPath context is unavailable.'};
  let built:{version?:unknown;protocolVersion?:unknown;sourceSha256?:unknown;jarSha256?:unknown};
  try{
    built=JSON.parse((await readBoundedFile(join(root,'.local/bridge-build.json'),4096)).toString('utf8'));
    if(!built||built.version!==1||built.protocolVersion!==1||!sha(built.sourceSha256)||!sha(built.jarSha256))throw new Error('Invalid build manifest');
    const jar=await readBoundedFile(join(root,'dist/luma-qupath-bridge.jar'),16*1024*1024);
    if(createHash('sha256').update(jar).digest('hex')!==built.jarSha256)throw new Error('Built artifact differs');
  }catch{return{status:'build_unverified',message:'The local bridge build identity is absent or inconsistent. Rebuild with script/build-bridge.sh; no application was changed.'};}
  const loaded=(context as {adapter?:Record<string,unknown>}).adapter;
  if(!loaded)return{status:'restart_required',message:'The running QuPath bridge predates build identity. Save work and quit/relaunch QuPath normally to load the built extension.'};
  if(loaded.protocolVersion!==1||!sha(loaded.sourceSha256)||!Array.isArray(loaded.operations)
      ||loaded.operations.length>64||loaded.operations.some(value=>typeof value!=='string'||!/^[a-z][a-z0-9_]{0,63}$/.test(value))
      ||new Set(loaded.operations).size!==loaded.operations.length)
    return{status:'build_unverified',message:'The loaded bridge identity/protocol cannot be verified. Inspect compatibility before using new capabilities.'};
  const operations=loaded.operations as string[];
  const missing=requiredBridgeOperations.filter(operation=>!operations.includes(operation));
  if(loaded.sourceSha256!==built.sourceSha256||missing.length)return{status:'restart_required',missingOperations:missing,
    message:'The running QuPath extension differs from the completed build or lacks declared operations. Save work and quit/relaunch QuPath normally; no automatic restart was attempted.'};
  return{status:'matches_build',message:`The loaded extension reports the completed build's source identity and all ${requiredBridgeOperations.length} required operations. Behavior and scientific validity require separate checks.`};
}
