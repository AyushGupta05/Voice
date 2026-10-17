import { EventEmitter } from 'node:events';
import { randomUUID } from 'node:crypto';
import { appendFile, mkdir } from 'node:fs/promises';
import { join } from 'node:path';
import type { Adapter, Context } from './bridge.js';
import { validateTool, type Arguments, type Tool } from './tools.js';
import type { Knowledge } from './knowledge.js';
import { findLocalImages } from './local-images.js';

export type Action = {
  id: string; tool: string; arguments: Arguments; risk: Tool['risk']; status: 'pending' | 'running' | 'completed' | 'denied' | 'failed' | 'expired';
  createdAt: string; expiresAt: string; context: Context; result?: unknown; error?: string; auditWarning?: string; decidedBy?: 'human' | 'policy';
};
/** Approval policy. The researcher asked for a fully agentic companion, so the default executes data/code actions
 * immediately; hierarchy checkpoints, the audit journal and Stop remain. LUMA_APPROVALS=review restores human review cards. */
export type RuntimeOptions = { autoApprove?: boolean };
export const approvalPolicy = (setting = process.env.LUMA_APPROVALS): 'auto' | 'review' => {
  if (setting === undefined || setting === '' || setting === 'auto') return 'auto';
  if (setting === 'review') return 'review';
  throw new Error('LUMA_APPROVALS must be "auto" or "review".');
};
export class Runtime extends EventEmitter {
  readonly actions = new Map<string, Action>();
  readonly autoApprove: boolean;
  private operationQueue: Promise<unknown> = Promise.resolve();
  private readonly expirations = new Map<string, Promise<void>>();
  constructor(readonly adapter: Adapter, readonly knowledge: Knowledge, private stateDirectory: string, options: RuntimeOptions = {}) { super(); this.autoApprove = options.autoApprove === true; }
  async call(name: string, args: Arguments, signal?: AbortSignal, beforeDispatch?: (context?: Context) => void) {
    signal?.throwIfAborted(); beforeDispatch?.();
    const spec = validateTool(name, args);
    // Own the request before asynchronous observations/previews can yield.
    args=structuredClone(args);
    if (name === 'qupath_knowledge') return this.knowledge.search(args.query as string);
    if (name === 'qupath_find_images') return findLocalImages(join(this.stateDirectory, '..', '..'), (args.query as string | undefined) ?? '', (args.limit as number | undefined) ?? 20);
    if (name === 'qupath_api_search') return this.knowledge.api.search(args.query as string,args.limit as number|undefined);
    if (name === 'qupath_api_read') return this.knowledge.api.read(args.source as string,args.startLine as number|undefined,args.lineCount as number|undefined);
    if (name === 'qupath_action_status') {
      await this.expirePending();
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
      if (name === 'qupath_import_annotations') {
        const preview = await this.adapter.execute('inspect_import',{path:args.path},{expectedContext:context.contextKey}) as Record<string,unknown>;
        signal?.throwIfAborted(); beforeDispatch?.(context);
        if (preview.sha256 !== args.sha256) throw new Error('Import file changed since inspection. Inspect and review again.');
        context.reviewTarget = { type:'GeoJSON annotations',text:`${preview.annotations} annotations in full-resolution image pixels`,...preview };
      }
      if (name === 'qupath_classify') {
        const filter:Arguments={kind:args.kind==='cell'?'cell':'detection',limit:1};
        for(const key of ['scope','classification','ids'])if(args[key]!==undefined)filter[key]=args[key];
        const candidates=await this.adapter.execute('objects',filter,{expectedContext:context.contextKey}) as {total?:unknown};
        signal?.throwIfAborted();beforeDispatch?.(context);
        if(!Number.isSafeInteger(candidates.total)||Number(candidates.total)<0)throw new Error('Cannot verify the matching detection count. Inspect the scope before classification.');
        const count=Number(candidates.total);
        if(count===0)throw new Error(args.classification
          ?'No detections match this existing-class filter. classification filters CURRENT labels; omit it only if the researcher requested thresholding all matching detections. No action was prepared.'
          :'No detections match the requested scope. Inspect the selected region and existing detections before classification. No action was prepared.');
        context.reviewTarget={type:'Threshold classification',candidateCount:count,measurement:args.measurement,threshold:args.threshold,
          existingClassFilter:args.classification||null,assignedClasses:['Positive','Negative'],
          text:`${count} matching detection${count===1?'':'s'}${args.classification?` (current class: ${args.classification})`:''}; assign Positive / Negative`};
      }
      if (name === 'qupath_open_project_image') {
        const listing = await this.adapter.execute('project_images',{entryId:args.entryId},{expectedContext:context.contextKey}) as {images:Array<Record<string,unknown>>};
        signal?.throwIfAborted(); beforeDispatch?.(context);
        const entry=listing.images.find(entry=>entry.id===args.entryId);
        if (!entry || entry.fingerprint!==args.entryFingerprint) throw new Error('Project entry changed. Refresh project_images and review again.');
        context.reviewTarget={type:'Project image',text:entry.name,...entry};
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
      if (this.autoApprove) {
        // Same recorded decision path as a human click, taken by policy; no pending card is shown.
        const settled = await this.decide(action.id, true, 'policy');
        return { status: settled.status, actionId: settled.id, tool: name, arguments: args, autoApproved: true, result: settled.result, error: settled.error, auditWarning: settled.auditWarning,
          recovery: 'Hierarchy changes can be restored from the checkpoint; files, image settings and script effects cannot.' };
      }
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
  async decide(id: string, approved: boolean, decidedBy: 'human' | 'policy' = 'human') {
    const action = this.actions.get(id);
    if (!action || action.status !== 'pending') throw new Error('Action is no longer awaiting approval.');
    if (Date.now() >= Date.parse(action.expiresAt)) {
      await this.expirePending(); throw new Error('Approval expired. Request a new action.');
    }
    // Claim before the first await: two clicks cannot execute an approved action twice.
    action.status = approved ? 'running' : 'denied'; action.decidedBy = decidedBy;
    if (!approved) { await this.terminal(action); return action; }
    this.emit('action',action);
    try { await this.audit(action); }
    catch {
      // Without a recorded human decision, fail before the adapter is called.
      // Wake backend/UI waiters even when the journal itself is unavailable.
      action.status='failed'; action.error='The approval decision could not be recorded; nothing was executed. Request a fresh action after fixing local audit storage.';
      action.auditWarning=this.auditWarning; this.emit('action',action); return action;
    }
    return this.serial(async () => {
      try {
        const spec = validateTool(action.tool, action.arguments);
        action.result = await this.adapter.execute(spec.operation!, action.arguments, { expectedContext: action.context.contextKey, approved: true, expectedControl: action.context.reviewTarget });
        action.status = 'completed';
        this.emit('tool',{name: action.tool, arguments: action.arguments, result: action.result});
      } catch (error) { action.status = 'failed'; action.error = error instanceof Error ? error.message : String(error); }
      await this.terminal(action);
      return action;
    });
  }
  /** Deadline bookkeeping only: never dispatches an application operation or decides approval. */
  async expirePending() {
    for (const action of this.actions.values()) {
      if (action.status !== 'pending' || Date.now() < Date.parse(action.expiresAt)) continue;
      // Claim before awaiting the audit so concurrent status/health/decision calls cannot replay expiry.
      action.status='expired'; action.error='Approval expired. No operation was executed.';
      const settled=this.terminal(action).finally(()=>{this.expirations.delete(action.id);});
      this.expirations.set(action.id,settled);
    }
    await Promise.all(this.expirations.values());
  }
  async recentActions() {
    await this.expirePending();
    return [...this.actions.values()].slice(-30);
  }
  private readonly auditWarning='Local audit update could not be recorded. Use the actual status/result below; no operation was retried. Inspect QuPath before any new action. Persistence is unconfirmed.';
  private async terminal(action:Action) {
    try { await this.audit(action); } catch { action.auditWarning=this.auditWarning; }
    // Recording failure cannot erase a completed operation or strand its waiter.
    this.emit('action',action);
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
