/** The researcher's explicit new attempt budget after the $200 reserve is exhausted (docs/FUNDING.md). No AWS call. */
import { resolve } from 'node:path';
import { renewBudget } from '../packages/gateway/src/credit-budget.js';
if (!process.argv.includes('--authorized-new-budget')) {
  console.error('Refused: a new budget is the researcher\'s decision. Run `npm run budget:renew -- --authorized-new-budget` yourself after reading docs/FUNDING.md.');
  process.exit(2);
}
const state = resolve(process.env.LUMA_ROOT ?? process.cwd(), '.local/state');
const renewed = await renewBudget(state);
console.log(JSON.stringify({ renewed: 'new $200 attempt reserve', ...renewed, note: 'Reservations are worst-case holds, never spend; the archive keeps the exhausted ledger and the receipts\' estimated spend. Restart the companion so its gateway re-reads the ledger.' }));
