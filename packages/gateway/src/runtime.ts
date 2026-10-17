import { EventEmitter } from 'node:events';
import { randomUUID } from 'node:crypto';
import { appendFile, mkdir } from 'node:fs/promises';
import { join } from 'node:path';
import type { Adapter, Context } from './bridge.js';
import { validateTool, type Arguments, type Tool } from './tools.js';
import type { Knowledge } from './knowledge.js';

export type Action = {
  id: string; tool: string; arguments: Arguments; risk: Tool['risk']; status: 'pending' | 'running' | 'completed' | 'denied' | 'failed' | 'expired';
  createdAt: string; expiresAt: string; context: Context; result?: unknown; error?: string;
};
export class Runtime extends EventEmitter {
  readonly actions = new Map<string, Action>();
  private operationQueue: Promise<unknown> = Promise.resolve();
  constructor(readonly adapter: Adapter, readonly knowledge: Knowledge, private stateDirectory: string) { super(); }
  async call(name: string, args: Arguments, signal?: AbortSignal, beforeDispatch?: (context?: Context) => void) {
    signal?.throwIfAborted(); beforeDispatch?.();
    const spec = validateTool(name, args);
    if (name === 'qupath_knowledge') return this.knowledge.search(args.query as string);
    if (name === 'qupath_action_status') {
      const action = this.actions.get(args.actionId as string);
      if (!action) throw new Error('Action not found (history is scoped to the running gateway).');
      return action;
    }
    if (spec.risk === 'data' || spec.risk === 'code') {
      const context = await this.adapter.execute('context') as Context;
      signal?.throwIfAborted(); beforeDispatch?.(context);
      if (name === 'qupath_ui_action') {
        const ui = await this.adapter.execute('ui') as { windows: Array<{ title: string; fingerprint: string; controls: Array<Record<string,unknown>> }> };
        signal?.throwIfAborted(); beforeDispatch?.(context);
        const window = ui.windows.find(window => window.controls.some(control => control.id === args.nodeId));
        const control = window?.controls.find(control => control.id === args.nodeId);
        if (!control || control.disabled) throw new Error('Target control is absent or disabled. Inspect QuPath UI again.');
        context.reviewTarget = { window: window!.title, windowFingerprint: window!.fingerprint, ...control };
      }
      const action: Action = {
        id: randomUUID(), tool: name, arguments: structuredClone(args), risk: spec.risk, status: 'pending',
        createdAt: new Date().toISOString(), expiresAt: new Date(Date.now()+5*60_000).toISOString(), context,
      };
      // Keep pending exact source available to the human, never an executable model-issued approval.
      await this.audit(action);
      try {
        signal?.throwIfAborted();
        if (beforeDispatch) {
          const current = await this.adapter.execute('context') as Context;
          signal?.throwIfAborted(); beforeDispatch(current);
        }
        if (Date.now() >= Date.parse(action.expiresAt)) throw new Error('Proposal expired while being prepared.');
      } catch (error) {
        action.status = 'expired'; action.error = 'Request cancelled before the proposal was published.';
        await this.audit(action); throw error;
      }
      this.actions.set(action.id,action);
      this.prune();
      this.emit('action', action);
      return { status: 'approval_required', actionId: action.id, tool: name, arguments: args, image: context.image, target: context.reviewTarget, expiresAt: action.expiresAt, message: 'The researcher must review and approve this exact action in Luma. No execution has occurred.' };
    }
    return this.serial(async () => {
      signal?.throwIfAborted(); beforeDispatch?.();
      let expectedContext: string | undefined;
      if (beforeDispatch) {
        const current = await this.adapter.execute('context') as Context;
        signal?.throwIfAborted(); beforeDispatch(current); expectedContext = current.contextKey;
      }
      const result = await this.adapter.execute(spec.operation!,args,expectedContext ? { expectedContext } : undefined);
      this.emit('tool', { name, arguments: args, result });
      return result;
    });
  }
  async decide(id: string, approved: boolean) {
    const action = this.actions.get(id);
    if (!action || action.status !== 'pending') throw new Error('Action is no longer awaiting approval.');
    if (Date.now() >= Date.parse(action.expiresAt)) {
      action.status = 'expired'; await this.audit(action); this.emit('action',action); throw new Error('Approval expired. Request a new action.');
    }
    // Claim before the first await: two clicks cannot execute an approved action twice.
    action.status = approved ? 'running' : 'denied';
    this.emit('action',action);
    await this.audit(action);
    if (!approved) return action;
    return this.serial(async () => {
      try {
        const spec = validateTool(action.tool, action.arguments);
        action.result = await this.adapter.execute(spec.operation!, action.arguments, { expectedContext: action.context.contextKey, approved: true, expectedControl: action.context.reviewTarget });
        action.status = 'completed';
        this.emit('tool',{name: action.tool, arguments: action.arguments, result: action.result});
      } catch (error) { action.status = 'failed'; action.error = error instanceof Error ? error.message : String(error); }
      await this.audit(action); this.emit('action',action);
      return action;
    });
  }
  private async serial<T>(operation: () => Promise<T>): Promise<T> {
    const next = this.operationQueue.then(operation,operation);
    this.operationQueue = next.catch(() => undefined);
    return next;
  }
  private prune() {
    for (const [id,action] of this.actions) {
      if (action.status === 'pending' && Date.now() > Date.parse(action.expiresAt)) { action.status='expired'; this.emit('action',action); }
      if (this.actions.size > 200 && !['pending','running'].includes(action.status)) this.actions.delete(id);
    }
    if ([...this.actions.values()].filter(action => action.status === 'pending').length > 20) {
      const oldest = [...this.actions.values()].find(action => action.status === 'pending');
      if (oldest) { oldest.status='expired'; this.emit('action',oldest); }
    }
  }
  private async audit(action: Action) {
    await mkdir(this.stateDirectory,{recursive:true,mode:0o700});
    // No credentials, image pixels or raw microphone audio. Local reviewed scripts are intentionally retained for reproducibility.
    await appendFile(join(this.stateDirectory,'actions.jsonl'),JSON.stringify({ at:new Date().toISOString(),...action })+'\n',{mode:0o600});
  }
}
