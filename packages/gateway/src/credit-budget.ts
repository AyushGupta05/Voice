import { randomUUID } from 'node:crypto';
import { appendFile, mkdir, open, readFile, rename, rmdir, unlink } from 'node:fs/promises';
import { join } from 'node:path';

export type CreditObservation = {
  accountHash: string; observedAt: number; availableCreditUSD: number; maximumRequestUSD: number;
};
/** Older entries reserved a flat $1; newer entries reserve twice the request's computed worst case, rounded up to a cent. */
type Reservation = { id: string; at: string; amountUSD: number };
const cents = (usd: number) => Math.round(usd * 100);
/** Twice the priced worst case, at least one cent and never more than the original $1 per attempt. */
export const reserveUSD = (maximumRequestUSD: number) => Math.min(100, Math.max(1, Math.ceil(maximumRequestUSD * 200 - 1e-9))) / 100;
type Ledger = { version: 1; accountHash: string; reservations: Reservation[] };
export type UsageReceipt = { inputTokens?: number; outputTokens?: number; estimatedUSD?: number; outcome: 'completed' | 'unconfirmed' };

/** Conservative durable attempt budget. Reservations are never refunded automatically. */
export class CreditBudget {
  private readonly ledgerPath: string;
  private readonly lockPath: string;
  constructor(private directory: string, private maximumReservedUSD = 200) {
    if (!Number.isInteger(maximumReservedUSD) || maximumReservedUSD < 1 || maximumReservedUSD > 200) throw new Error('The project reserve cap cannot exceed $200.');
    this.ledgerPath = join(directory, 'credit-budget.json');
    this.lockPath = join(directory, 'credit-budget.lock');
  }
  async reserve(observation: CreditObservation, signal: AbortSignal) {
    signal.throwIfAborted();
    await mkdir(this.directory, { recursive: true, mode: 0o700 });
    try { await mkdir(this.lockPath, { mode: 0o700 }); }
    catch { throw new Error('Credit budget is locked or unavailable. Inspect local state; no model request was sent.'); }
    const temporary = join(this.directory, `.credit-budget-${randomUUID()}.tmp`);
    try {
      signal.throwIfAborted();
      const age = Date.now() - observation.observedAt;
      if (!/^[a-f0-9]{64}$/.test(observation.accountHash) || !Number.isFinite(age) || age < 0 || age > 30_000) throw new Error('Fresh authenticated credit proof is required.');
      if (!Number.isFinite(observation.maximumRequestUSD) || observation.maximumRequestUSD < 0 || observation.maximumRequestUSD > 0.5) throw new Error('Current prices exceed the conservative $1 per-attempt reserve.');
      let ledger: Ledger;
      try { ledger = JSON.parse(await readFile(this.ledgerPath, 'utf8')); }
      catch (error) {
        if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw new Error('Credit budget is unreadable. Preserve it for reconciliation; no request was sent.');
        ledger = { version: 1, accountHash: observation.accountHash, reservations: [] };
      }
      if (!ledger || ledger.version !== 1 || ledger.accountHash !== observation.accountHash || !Array.isArray(ledger.reservations)
        || ledger.reservations.length > 100_000 || ledger.reservations.some(entry => !entry || typeof entry.id !== 'string' || !/^[a-f0-9-]{36}$/.test(entry.id) || !Number.isFinite(Date.parse(entry.at)) || typeof entry.amountUSD !== 'number' || !Number.isInteger(cents(entry.amountUSD)) || Math.abs(cents(entry.amountUSD) - entry.amountUSD * 100) > 1e-6 || entry.amountUSD < 0.01 || entry.amountUSD > 1)
        || new Set(ledger.reservations.map(entry => entry.id)).size !== ledger.reservations.length) throw new Error('Credit budget account or records do not match. No request was sent.');
      const amount = reserveUSD(observation.maximumRequestUSD);
      const reservedCents = ledger.reservations.reduce((sum, entry) => sum + cents(entry.amountUSD), 0);
      if (reservedCents + cents(amount) > this.maximumReservedUSD * 100) throw new Error(`The $${this.maximumReservedUSD} project attempt reserve is exhausted. Reconcile usage before requesting a new budget.`);
      // Subtract every old reservation again, even if AWS has already applied its actual cost.
      // This intentionally overestimates in-flight usage and keeps a $200 unrelated-usage margin.
      if (!Number.isFinite(observation.availableCreditUSD) || observation.availableCreditUSD * 100 < reservedCents + cents(amount) + 20_000) throw new Error('Verified eligible credits do not cover the reserve and safety margin.');
      signal.throwIfAborted();
      const reservation: Reservation = { id: randomUUID(), at: new Date().toISOString(), amountUSD: amount };
      ledger.reservations.push(reservation);
      const file = await open(temporary, 'wx', 0o600);
      try { await file.writeFile(JSON.stringify(ledger) + '\n'); await file.sync(); } finally { await file.close(); }
      await rename(temporary, this.ledgerPath);
      const directory = await open(this.directory, 'r');
      try { await directory.sync(); } finally { await directory.close(); }
      return { id: reservation.id, reservedUSD: amount, totalReservedUSD: (reservedCents + cents(amount)) / 100 };
    } finally {
      await unlink(temporary).catch(error => { if (error.code !== 'ENOENT') throw error; });
      await rmdir(this.lockPath);
    }
  }
  async receipt(id: string, usage: UsageReceipt) {
    // Receipts never lower the authoritative reservation count, including network errors/abort.
    await appendFile(join(this.directory, 'credit-usage.jsonl'), JSON.stringify({ id, at: new Date().toISOString(), ...usage }) + '\n', { mode: 0o600 });
  }
}

/** Share one read-only credit proof across the requests of a single voice/text turn.
 * A cached proof is reused only while younger than maxAgeMs (below reserve()'s 30 s freshness bound);
 * every request still makes its own durable reservation. Failed observations are never cached. */
export function sharedObservation<T extends CreditObservation>(observe: (signal: AbortSignal) => Promise<T>, maxAgeMs = 20_000) {
  if (!Number.isFinite(maxAgeMs) || maxAgeMs < 0 || maxAgeMs > 25_000) throw new Error('Shared credit proofs must stay well inside the reserve freshness bound.');
  let pending: Promise<T> | undefined;
  const fresh = () => {
    const value = observe(AbortSignal.timeout(25_000));
    pending = value;
    value.catch(() => { if (pending === value) pending = undefined; });
    return value;
  };
  return async (signal: AbortSignal): Promise<T> => {
    signal.throwIfAborted();
    const race = (value: Promise<T>) => new Promise<T>((resolve, reject) => {
      const aborted = () => reject(signal.reason);
      signal.addEventListener('abort', aborted, { once: true });
      value.then(resolve, reject).finally(() => signal.removeEventListener('abort', aborted));
    });
    let value = await race(pending ?? fresh());
    if (Date.now() - value.observedAt > maxAgeMs) value = await race(fresh());
    signal.throwIfAborted();
    return value;
  };
}
