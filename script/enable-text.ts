/** Explicit local activation after read-only AWS credit/access/price checks. No inference. */
import { mkdir, readFile, writeFile, rename, unlink } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { observeAWSCredits, BEDROCK_MODEL } from '../packages/gateway/src/aws-credit-proof.js';
import type { TextConfig } from '../packages/gateway/src/bedrock-client.js';
const state=resolve(process.env.LUMA_ROOT??process.cwd(),'.local/state');
const proof=await observeAWSCredits(AbortSignal.timeout(25_000));
if(proof.availableCreditUSD<401||proof.maximumRequestUSD>0.5)throw new Error('Eligible credits and current prices do not cover the full reserve plus margin. No activation occurred.');
await mkdir(state,{recursive:true,mode:0o700});
const destination=join(state,'text-funding.json');
try{
 const existing=JSON.parse(await readFile(destination,'utf8')) as TextConfig;
 if(existing.accountHash!==proof.accountHash)throw new Error('Existing text configuration belongs to another account. Preserve its budget and reconcile before switching.');
}catch(error){if((error as NodeJS.ErrnoException).code!=='ENOENT')throw error;}
const config:TextConfig={version:1,accountHash:proof.accountHash,expiresAt:new Date(Date.now()+24*3600_000).toISOString(),model:BEDROCK_MODEL,maximumReservedUSD:200};
const temporary=join(state,`.text-funding-${randomUUID()}.tmp`);
try{await writeFile(temporary,JSON.stringify(config)+'\n',{mode:0o600,flag:'wx'});await rename(temporary,destination);}finally{await unlink(temporary).catch(error=>{if(error.code!=='ENOENT')throw error;});}
console.log(JSON.stringify({configured:'AWS-credit OpenAI text adapter',model:BEDROCK_MODEL,reserveCapUSD:200,automaticReserveRefund:false,vision:false,voiceEnabled:false,modelCallsMade:0}));
