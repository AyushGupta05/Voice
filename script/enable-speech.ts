/** Explicit user-authorized activation after read-only AWS speech proof. Never invokes speech. */
import {mkdir,readFile,writeFile,rename,unlink} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {randomUUID} from 'node:crypto';
import {observeSpeechCredits} from '../packages/gateway/src/aws-speech-proof.js';
import {loadTextConfig} from '../packages/gateway/src/bedrock-client.js';
import type {SpeechConfig} from '../packages/gateway/src/speech-config.js';
if(!process.argv.includes('--authorized-aws-speech'))throw new Error('Explicit authorization for AWS speech plus the OpenAI agent is required.');
const state=resolve(process.env.LUMA_ROOT??process.cwd(),'.local/state');
const text=await loadTextConfig(state),proof=await observeSpeechCredits(AbortSignal.timeout(25000));
if(proof.accountHash!==text.accountHash||proof.availableCreditUSD<401)throw new Error('Eligible speech credits/account do not cover the shared guard and margin.');
const config:SpeechConfig={version:1,route:'aws-transcribe-polly-openai',accountHash:proof.accountHash,expiresAt:new Date(Math.min(Date.parse(text.expiresAt),Date.now()+24*3600000)).toISOString(),maximumReservedUSD:200,maximumSessionSeconds:180};
await mkdir(state,{recursive:true,mode:0o700});const destination=join(state,'speech-funding.json');
try{const old=JSON.parse(await readFile(destination,'utf8'));if(old.accountHash!==proof.accountHash)throw new Error('Preserve and reconcile the existing speech credit account.');}catch(error){if((error as NodeJS.ErrnoException).code!=='ENOENT')throw error;}
const temporary=join(state,`.speech-funding-${randomUUID()}.tmp`);
try{await writeFile(temporary,JSON.stringify(config)+'\n',{mode:0o600,flag:'wx'});await rename(temporary,destination);}finally{await unlink(temporary).catch(e=>{if(e.code!=='ENOENT')throw e;});}
console.log(JSON.stringify({configured:config.route,maximumSessionSeconds:180,sharedReserveCapUSD:200,automaticReserveRefund:false,speechRequestsMade:0,hardwareVerified:false}));
