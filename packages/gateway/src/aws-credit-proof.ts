import { createHash } from 'node:crypto';
import { BillingClient, GetCreditsCommand, type CreditData } from '@aws-sdk/client-billing';
import { BedrockClient, GetFoundationModelAvailabilityCommand } from '@aws-sdk/client-bedrock';
import { PricingClient, GetProductsCommand } from '@aws-sdk/client-pricing';
import { STSClient, GetCallerIdentityCommand } from '@aws-sdk/client-sts';
import { fromNodeProviderChain } from '@aws-sdk/credential-providers';
import type { AwsCredentialIdentity } from '@aws-sdk/types';
import type { CreditObservation } from './credit-budget.js';
import { bedrockSigV4Only } from './aws-auth.js';

export const BEDROCK_MODEL = 'openai.gpt-oss-120b-1:0';
export const BEDROCK_REGION = 'us-east-1';
export type PriceRates = { inputPerTokenUSD: number; outputPerTokenUSD: number };
export type AWSProof = CreditObservation & { credentials: AwsCredentialIdentity; rates: PriceRates };
export const accountHash = (account: string) => createHash('sha256').update(account).digest('hex');

export function eligibleCreditUSD(records: CreditData[], account: string, now = Date.now(), requiredProducts = ['AmazonBedrockFoundationModels']) {
  let total = 0;
  const seen = new Set<string>();
  for (const record of records) {
    if (record.accountId !== account || record.creditStatus !== 'ENABLED' || !requiredProducts.length || !requiredProducts.every(name=>record.applicableProductNames?.includes(name))) continue;
    const start = record.startDate?.getTime(); const end = record.endDate?.getTime();
    if (start === undefined || end === undefined || !Number.isFinite(start) || !Number.isFinite(end) || start > now || end < now + 60 * 60_000) continue;
    if (!['DEFAULT', 'DISABLED'].includes(record.creditSharingType ?? '') || record.purchaseTypeApplications != null) continue;
    if (!record.creditId) continue;
    if (seen.has(record.creditId)) throw new Error('Duplicate credit records require reconciliation.');
    seen.add(record.creditId);
    const amounts = [record.remainingAmount, record.estimatedAmount];
    if (amounts.some(amount => !amount || amount.currencyCode !== 'USD' || !/^\d+(\.\d+)?$/.test(amount.currencyAmount ?? ''))) continue;
    const remaining = Math.min(...amounts.map(amount => Number(amount!.currencyAmount)));
    if (Number.isFinite(remaining) && remaining > 0) total += Math.floor(remaining * 100) / 100;
  }
  if (!Number.isFinite(total) || total > 1e9) throw new Error('Credit amount could not be verified.');
  return total;
}
export function priceRates(products: unknown[]): PriceRates {
  const result: Partial<PriceRates> = {};
  for (const direction of ['input', 'output'] as const) {
    const candidates = products.filter(item => {
      const attributes = (item as any)?.product?.attributes;
      return attributes?.regionCode === BEDROCK_REGION && attributes.model === 'gpt-oss-120b' && attributes.provider === 'OpenAI'
        && attributes.servicecode === 'AmazonBedrock' && attributes.feature === 'On-demand Inference'
        && attributes.usagetype === `USE1-gpt-oss-120b-${direction}-tokens`;
    });
    if (candidates.length !== 1) throw new Error('An unambiguous current Standard-tier price is required.');
    const terms = Object.values((candidates[0] as any).terms?.OnDemand ?? {}) as any[];
    if (terms.length !== 1 || !Number.isFinite(Date.parse(terms[0].effectiveDate)) || Date.parse(terms[0].effectiveDate) > Date.now()) throw new Error('Current price term is unavailable.');
    const dimensions = Object.values(terms[0].priceDimensions ?? {}) as any[];
    if (dimensions.length !== 1 || dimensions[0].unit !== '1K tokens' || dimensions[0].beginRange !== '0' || dimensions[0].endRange !== 'Inf') throw new Error('Unsupported price dimension.');
    const price = Number(dimensions[0].pricePerUnit?.USD);
    if (!Number.isFinite(price) || price <= 0) throw new Error('A positive USD token price is required.');
    result[direction === 'input' ? 'inputPerTokenUSD' : 'outputPerTokenUSD'] = price / 1000;
  }
  return result as PriceRates;
}

/** Read-only AWS preflight. No model invocation, agreement acceptance or resources. */
export async function observeAWSCredits(signal: AbortSignal): Promise<AWSProof> {
  signal.throwIfAborted();
  const credentials = await fromNodeProviderChain({ profile: process.env.AWS_PROFILE ?? 'default', timeout: 3000, maxRetries: 0 })();
  const settings = { region: BEDROCK_REGION, credentials, maxAttempts: 1 };
  const sts = new STSClient({ ...settings, endpoint: 'https://sts.us-east-1.amazonaws.com' });
  const billing = new BillingClient({ ...settings, endpoint: 'https://billing.us-east-1.api.aws' });
  const bedrock = new BedrockClient({ ...settings, ...bedrockSigV4Only(), endpoint: 'https://bedrock.us-east-1.amazonaws.com' });
  const pricing = new PricingClient({ ...settings, endpoint: 'https://api.pricing.us-east-1.amazonaws.com' });
  try {
    const identity = await sts.send(new GetCallerIdentityCommand({}), { abortSignal: signal });
    if (!identity.Account || !/^\d{12}$/.test(identity.Account)) throw new Error('AWS account identity is unavailable.');
    const startedAt = Date.now();
    const [credits, access, prices] = await Promise.all([
      billing.send(new GetCreditsCommand({ accountId: identity.Account, startDate: new Date(startedAt - 364 * 86400_000), payerAccountFlag: false }), { abortSignal: signal }),
      bedrock.send(new GetFoundationModelAvailabilityCommand({ modelId: BEDROCK_MODEL }), { abortSignal: signal }),
      pricing.send(new GetProductsCommand({ ServiceCode: 'AmazonBedrock', Filters: [
        { Type: 'TERM_MATCH', Field: 'model', Value: 'gpt-oss-120b' },
        { Type: 'TERM_MATCH', Field: 'regionCode', Value: BEDROCK_REGION },
        { Type: 'TERM_MATCH', Field: 'feature', Value: 'On-demand Inference' },
      ], MaxResults: 100 }), { abortSignal: signal }),
    ]);
    if (access.modelId !== 'openai.gpt-oss-120b-1' || access.authorizationStatus !== 'AUTHORIZED' || access.agreementAvailability?.status !== 'AVAILABLE'
      || access.entitlementAvailability !== 'AVAILABLE' || access.regionAvailability !== 'AVAILABLE') throw new Error('Existing model access/agreement is required; automatic subscriptions are disabled.');
    if (prices.NextToken) throw new Error('Price catalog is incomplete.');
    const rates = priceRates((prices.PriceList ?? []).map((value: string) => JSON.parse(value)));
    return { credentials, rates, accountHash: accountHash(identity.Account), observedAt: startedAt,
      availableCreditUSD: eligibleCreditUSD(credits.credits ?? [], identity.Account),
      // Use the whole model window and maximum output, not the much smaller requested output.
      maximumRequestUSD: 131_072 * rates.inputPerTokenUSD + 16_384 * rates.outputPerTokenUSD };
  } finally { sts.destroy(); billing.destroy(); bedrock.destroy(); pricing.destroy(); }
}
