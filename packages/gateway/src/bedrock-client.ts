import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { BedrockRuntimeClient, InvokeModelCommand } from '@aws-sdk/client-bedrock-runtime';
import type { BackendClient, BackendRequest, BackendResponse } from './backend.js';
import { BEDROCK_MODEL, BEDROCK_REGION, observeAWSCredits, type AWSProof } from './aws-credit-proof.js';
import { CreditBudget, type UsageReceipt } from './credit-budget.js';
import { bedrockSigV4Only } from './aws-auth.js';

type ChatMessage = { role: string; content?: string | null; tool_call_id?: string; tool_calls?: Array<Record<string, unknown>> };
export type TextConfig = { version: 1; accountHash: string; expiresAt: string; model: typeof BEDROCK_MODEL; maximumReservedUSD: 200 };
export async function loadTextConfig(directory: string): Promise<TextConfig> {
  let config: TextConfig;
  try { config = JSON.parse(await readFile(join(directory, 'text-funding.json'), 'utf8')); }
  catch { throw new Error('AWS-credit text is not configured. Run npm run enable:text after reviewing docs/FUNDING.md.'); }
  if (!config || config.version !== 1 || !/^[a-f0-9]{64}$/.test(config.accountHash) || config.model !== BEDROCK_MODEL
    || config.maximumReservedUSD !== 200 || !Number.isFinite(Date.parse(config.expiresAt)) || Date.parse(config.expiresAt) <= Date.now()) throw new Error('AWS-credit text configuration is invalid or expired. Reverify funding.');
  return config;
}
function textContent(value: unknown) {
  if (typeof value === 'string') return value;
  if (!Array.isArray(value)) throw new Error('Expected text content.');
  return value.map(part => {
    if (!part || !['input_text', 'output_text'].includes(part.type) || typeof part.text !== 'string') throw new Error('The configured OpenAI backend supports text only; image input is unavailable.');
    return part.text;
  }).join('\n');
}
export function chatPayload(request: BackendRequest) {
  if (request.model !== BEDROCK_MODEL || request.store !== false || request.parallel_tool_calls !== false
    || !Number.isInteger(request.max_output_tokens) || request.max_output_tokens < 1 || request.max_output_tokens > 4096) throw new Error('Unsupported backend request configuration.');
  const messages: ChatMessage[] = [{ role: 'developer', content: request.instructions + '\nThis backend is text-only. Use actual application metadata and measurements; do not claim to see viewer pixels. Never expose private reasoning. No tool has executed until its actual Runtime result is supplied.' }];
  let batch: Array<Record<string, unknown>> = [];
  const flush = () => { if (batch.length) { messages.push({ role: 'assistant', content: null, tool_calls: batch }); batch = []; } };
  for (const item of request.input) {
    if (item.type === 'function_call') {
      if (typeof item.call_id !== 'string' || typeof item.name !== 'string' || typeof item.arguments !== 'string') throw new Error('Invalid saved tool call.');
      batch.push({ id: item.call_id, type: 'function', function: { name: item.name, arguments: item.arguments } });
    } else {
      flush();
      if (item.type === 'function_call_output') {
        if (typeof item.call_id !== 'string' || typeof item.output !== 'string') throw new Error('Invalid saved tool result.');
        messages.push({ role: 'tool', tool_call_id: item.call_id, content: item.output });
      } else if (['user', 'assistant', 'developer'].includes(String(item.role))) messages.push({ role: String(item.role), content: textContent(item.content) });
      else throw new Error('Unsupported saved backend input item.');
    }
  }
  flush();
  const functions = request.tools.map(tool => {
    if (tool.type !== 'function' || typeof tool.name !== 'string' || !tool.name.startsWith('qupath_') || !tool.parameters) throw new Error('Only local QuPath function tools are allowed.');
    return { type: 'function', function: { name: tool.name, description: tool.description, parameters: tool.parameters, strict: false } };
  });
  const body = { model: BEDROCK_MODEL, messages, tools: functions, parallel_tool_calls: false,
    max_completion_tokens: request.max_output_tokens, reasoning_effort: 'low', stream: false, n: 1 };
  const data = Buffer.from(JSON.stringify(body));
  if (data.length > 1024 * 1024) throw new Error('Text model input exceeds the bounded request size. Inspect existing results before continuing.');
  return data;
}
function userFacingText(content: unknown) {
  if (content === null || content === undefined) return '';
  if (typeof content !== 'string') throw new Error('Invalid model text.');
  const text = content.replace(/^\s*<reasoning>[\s\S]*?<\/reasoning>\s*/, '').trim();
  if (text.includes('<reasoning>') || text.includes('</reasoning>')) throw new Error('Model result contains an incomplete private-reasoning boundary.');
  return text;
}
export function parseChat(data: Uint8Array) {
  if (!data.byteLength || data.byteLength > 2 * 1024 * 1024) throw new Error('Model response exceeded its size limit.');
  const body = JSON.parse(Buffer.from(data).toString('utf8'));
  if (!Array.isArray(body.choices) || body.choices.length !== 1) throw new Error('Expected exactly one model choice.');
  const choice = body.choices[0];
  if (!['stop', 'tool_calls'].includes(choice?.finish_reason) || choice.message?.role !== 'assistant') throw new Error('Model response was incomplete or blocked. No new tool call was executed.');
  const output: Record<string, unknown>[] = [];
  const content = userFacingText(choice.message.content);
  if (content) output.push({ type: 'message', role: 'assistant', content: [{ type: 'output_text', text: content }] });
  if (choice.message.tool_calls !== undefined && !Array.isArray(choice.message.tool_calls)) throw new Error('Invalid model function list.');
  for (const call of choice.message.tool_calls ?? []) {
    if (call?.type !== 'function' || typeof call.id !== 'string' || typeof call.function?.name !== 'string' || typeof call.function?.arguments !== 'string') throw new Error('Invalid model function.');
    output.push({ type: 'function_call', call_id: call.id, name: call.function.name, arguments: call.function.arguments });
  }
  if (!output.length) throw new Error('Model returned no result.');
  const usage = body.usage;
  const tokens = usage && [usage.prompt_tokens, usage.completion_tokens].every(value => Number.isSafeInteger(value) && value >= 0)
    ? { inputTokens: usage.prompt_tokens as number, outputTokens: usage.completion_tokens as number } : undefined;
  return { response: { output, ...(content ? { output_text: content } : {}) } satisfies BackendResponse, tokens };
}
export type BedrockDependencies = {
  observe: (signal: AbortSignal) => Promise<AWSProof>;
  invoke: (proof: AWSProof, body: Uint8Array, signal: AbortSignal) => Promise<Uint8Array>;
};
const productionDependencies: BedrockDependencies = {
  observe: observeAWSCredits,
  invoke: async (proof, body, signal) => {
    const client = new BedrockRuntimeClient({ region: BEDROCK_REGION, credentials: proof.credentials, maxAttempts: 1, ...bedrockSigV4Only(),
      endpoint: 'https://bedrock-runtime.us-east-1.amazonaws.com' });
    try {
      const result = await client.send(new InvokeModelCommand({ modelId: BEDROCK_MODEL, body, contentType: 'application/json', accept: 'application/json',
        trace: 'DISABLED', performanceConfigLatency: 'standard', serviceTier: 'default' }), { abortSignal: signal });
      if (!result.body) throw new Error('Model returned no body.');
      return result.body;
    } finally { client.destroy(); }
  },
};

/** AWS-only, single-attempt OpenAI text adapter. It cannot activate GPT-Live or accept agreements. */
export class BedrockBackendClient implements BackendClient {
  readonly supportsVision = false;
  private budget: CreditBudget;
  constructor(private stateDirectory: string, private dependencies: BedrockDependencies = productionDependencies) { this.budget = new CreditBudget(stateDirectory); }
  async complete(request: BackendRequest, externalSignal: AbortSignal): Promise<BackendResponse> {
    externalSignal.throwIfAborted();
    const body = chatPayload(request);
    const config = await loadTextConfig(this.stateDirectory);
    const signal = AbortSignal.any([externalSignal, AbortSignal.timeout(90_000)]);
    const proof = await this.dependencies.observe(signal);
    signal.throwIfAborted();
    if (Date.parse(config.expiresAt) <= Date.now()) throw new Error('Text funding configuration expired during preflight.');
    if (proof.accountHash !== config.accountHash) throw new Error('AWS identity differs from the configured credit account. No model request was sent.');
    const reservation = await this.budget.reserve(proof, signal);
    let receipt: UsageReceipt = { outcome: 'unconfirmed' };
    try {
      signal.throwIfAborted();
      if (Date.parse(config.expiresAt) <= Date.now()) throw new Error('Text funding configuration expired before dispatch.');
      const data = await this.dependencies.invoke(proof, body, signal);
      signal.throwIfAborted();
      const result = parseChat(data);
      if (result.tokens) receipt = { ...result.tokens, estimatedUSD: result.tokens.inputTokens * proof.rates.inputPerTokenUSD + result.tokens.outputTokens * proof.rates.outputPerTokenUSD, outcome: 'completed' };
      return result.response;
    } finally {
      // Best effort receipt; the already-synced $1 reservation remains authoritative even if logging fails.
      await this.budget.receipt(reservation.id, receipt).catch(() => undefined);
    }
  }
}
