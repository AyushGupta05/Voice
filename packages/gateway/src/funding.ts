import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
export type Funding = { enabled: false; reason: string };
/** Fail closed: no current AWS-native OpenAI realtime endpoint has been verified for this account. */
export async function fundingStatus(root: string): Promise<Funding> {
  // An AWS credit balance is not proof that an external API charge is covered.
  // Deliberately no boolean environment variable that can turn a direct cash-billed fallback on.
  try {
    const proof = JSON.parse(await readFile(join(root,'.local/state/funding-evidence.json'),'utf8'));
    return { enabled:false, reason:proof.blocker ?? 'An AWS-credit-covered OpenAI realtime endpoint still needs implementation and verification.' };
  } catch {
    return { enabled:false, reason:'Voice is waiting for a verified AWS-credit-covered OpenAI realtime endpoint. No paid requests are made.' };
  }
}
