import { randomUUID } from 'node:crypto';
import { appendFile, mkdir, open, readFile, rename, rmdir, unlink } from 'node:fs/promises';
import { join } from 'node:path';

export type CreditObservation = {
  accountHash: string; observedAt: number; availableCreditUSD: number; maximumRequestUSD: number;
};
type Reservation = { id: string; at: string; amountUSD: 1 };
type Ledger = { version: 1; accountHash: string; reservations: Reservation[] };
export type UsageReceipt = { inputTokens?: number; outputTokens?: number; estimatedUSD?: number; outcome: 'completed' | 'unconfirmed' };

/** Conservative durable attempt budget. Reservations are never refunded automatically. */
export class CreditBudget {
  private readonly ledgerPath: string;
  private readonly lockPath: string;
  constructor(private directory: string, private maximumRequests = 200) {
    if (!Number.isInteger(maximumRequests) || maximumRequests < 1 || maximumRequests > 200) throw new Error('The project reserve cap cannot exceed $200.');
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
      if (!Number.isFinite(observation.maximumRequestUSD) || observation.maximumRequestUSD < 0 || observation.maximumRequestUSD > 0.5) throw new Error('Current model prices exceed the conservative $1 request reserve.');
      let ledger: Ledger;
      try { ledger = JSON.parse(await readFile(this.ledgerPath, 'utf8')); }
      catch (error) {
        if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw new Error('Credit budget is unreadable. Preserve it for reconciliation; no request was sent.');
        ledger = { version: 1, accountHash: observation.accountHash, reservations: [] };
      }
      if (!ledger || ledger.version !== 1 || ledger.accountHash !== observation.accountHash || !Array.isArray(ledger.reservations)
        || ledger.reservations.length > 200 || ledger.reservations.some(entry => !entry || typeof entry.id !== 'string' || !/^[a-f0-9-]{36}$/.test(entry.id) || !Number.isFinite(Date.parse(entry.at)) || entry.amountUSD !== 1)
        || new Set(ledger.reservations.map(entry => entry.id)).size !== ledger.reservations.length) throw new Error('Credit budget account or records do not match. No request was sent.');
      if (ledger.reservations.length >= this.maximumRequests) throw new Error('The $200 project attempt reserve is exhausted. Reconcile usage before requesting a new budget.');
      // Subtract every old reservation again, even if AWS has already applied its actual cost.
      // This intentionally overestimates in-flight usage and keeps a $200 unrelated-usage margin.
      if (!Number.isFinite(observation.availableCreditUSD) || observation.availableCreditUSD < ledger.reservations.length + 1 + 200) throw new Error('Verified eligible credits do not cover the reserve and safety margin.');
      signal.throwIfAborted();
      const reservation: Reservation = { id: randomUUID(), at: new Date().toISOString(), amountUSD: 1 };
      ledger.reservations.push(reservation);
      const file = await open(temporary, 'wx', 0o600);
      try { await file.writeFile(JSON.stringify(ledger) + '\n'); await file.sync(); } finally { await file.close(); }
      await rename(temporary, this.ledgerPath);
      const directory = await open(this.directory, 'r');
      try { await directory.sync(); } finally { await directory.close(); }
      return { id: reservation.id, reservedUSD: 1, totalReservedUSD: ledger.reservations.length };
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
