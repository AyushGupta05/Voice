import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import {loadSpeechConfig} from './speech-config.js';
export type Funding = { enabled: boolean; reason: string; route?:'aws-transcribe-polly-openai' };
/** Only the explicitly authorized AWS speech route may activate; GPT-Live remains separate. */
export async function fundingStatus(root: string): Promise<Funding> {
  try{await loadSpeechConfig(join(root,'.local/state'));return{enabled:true,route:'aws-transcribe-polly-openai',reason:'AWS speech with the OpenAI Bedrock agent. Credits/prices are checked before each speech request; sessions stop after 30 minutes.'};}catch{}
  // An AWS credit balance is not proof that an external API charge is covered.
  // Deliberately no boolean environment variable that can turn a direct cash-billed fallback on.
  try {
    const proof = JSON.parse(await readFile(join(root,'.local/state/funding-evidence.json'),'utf8'));
    return { enabled:false, reason:proof.blocker ?? 'An AWS-credit-covered OpenAI realtime endpoint still needs implementation and verification.' };
  } catch {
    return { enabled:false, reason:'Voice is waiting for a verified AWS-credit-covered OpenAI realtime endpoint. No paid requests are made.' };
  }
}
