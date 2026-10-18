import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { CreditBudget, renewBudget, reserveUSD, sharedObservation, type CreditObservation } from '../src/credit-budget.js';

const observation = (): CreditObservation => ({ accountHash: 'a'.repeat(64), observedAt: Date.now(), availableCreditUSD: 5000, maximumRequestUSD: 0.5 });
async function fixture() { const root=await mkdtemp(join(tmpdir(),'luma-budget-')); return {root,budget:new CreditBudget(root,2),clean:()=>rm(root,{recursive:true,force:true})}; }

test('attempt reserves survive restarts and are not refunded by successful or unknown receipts',async()=>{
 const f=await fixture();try{
  const first=await f.budget.reserve(observation(),new AbortController().signal);
  await f.budget.receipt(first.id,{outcome:'completed',inputTokens:10,outputTokens:2,estimatedUSD:0.000003});
  const second=await new CreditBudget(f.root,2).reserve(observation(),new AbortController().signal);
  await f.budget.receipt(second.id,{outcome:'unconfirmed'});
  await assert.rejects(new CreditBudget(f.root,2).reserve(observation(),new AbortController().signal),/exhausted/);
  assert.equal(JSON.parse(await readFile(join(f.root,'credit-budget.json'),'utf8')).reservations.length,2);
 }finally{await f.clean();}
});

test('fresh credit proof, conservative prices, margin and account identity are required before reserving',async()=>{
 const f=await fixture();try{
  for(const proof of [{...observation(),observedAt:Date.now()-31_000},{...observation(),maximumRequestUSD:0.51},{...observation(),availableCreditUSD:200},{...observation(),availableCreditUSD:NaN}])await assert.rejects(f.budget.reserve(proof,new AbortController().signal));
  await f.budget.reserve(observation(),new AbortController().signal);
  await assert.rejects(f.budget.reserve({...observation(),accountHash:'b'.repeat(64)},new AbortController().signal),/account or records/);
  assert.equal(JSON.parse(await readFile(join(f.root,'credit-budget.json'),'utf8')).reservations.length,1);
 }finally{await f.clean();}
});

test('concurrent budget instances cannot overspend the same final reserve',async()=>{
 const f=await fixture();try{
  const results=await Promise.allSettled([new CreditBudget(f.root,1).reserve(observation(),new AbortController().signal),new CreditBudget(f.root,1).reserve(observation(),new AbortController().signal)]);
  assert.equal(results.filter(result=>result.status==='fulfilled').length,1);
  assert.equal(JSON.parse(await readFile(join(f.root,'credit-budget.json'),'utf8')).reservations.length,1);
 }finally{await f.clean();}
});

test('a corrupt ledger or stale lock fails closed instead of resetting previous usage',async()=>{
 const f=await fixture();try{
  await writeFile(join(f.root,'credit-budget.json'),'{broken');
  await assert.rejects(f.budget.reserve(observation(),new AbortController().signal),/unreadable/);
  assert.equal(await readFile(join(f.root,'credit-budget.json'),'utf8'),'{broken');
  await mkdir(join(f.root,'credit-budget.lock'));
  await assert.rejects(f.budget.reserve(observation(),new AbortController().signal),/locked/);
  await assert.rejects(f.budget.reserve(observation(),new AbortController().signal),/locked/);
 }finally{await f.clean();}
});

test('cancelled requests cannot create a new reservation',async()=>{
 const f=await fixture();try{
  const controller=new AbortController();controller.abort();
  await assert.rejects(f.budget.reserve(observation(),controller.signal),{name:'AbortError'});
  await assert.rejects(readFile(join(f.root,'credit-budget.json')),{code:'ENOENT'});
 }finally{await f.clean();}
});

test('reservations cover twice the priced worst case per attempt and count legacy $1 entries toward the cap',async()=>{
 const f=await fixture();try{
  assert.equal(reserveUSD(0.0001),0.01);assert.equal(reserveUSD(0.03),0.06);assert.equal(reserveUSD(0.031),0.07);assert.equal(reserveUSD(0.5),1);
  await writeFile(join(f.root,'credit-budget.json'),JSON.stringify({version:1,accountHash:'a'.repeat(64),reservations:[{id:'00000000-0000-4000-8000-000000000000',at:new Date().toISOString(),amountUSD:1}]}));
  const small={...observation(),maximumRequestUSD:0.03};
  let last;for(let i=0;i<16;i++)last=await f.budget.reserve(small,new AbortController().signal);
  assert.equal(last!.reservedUSD,0.06);assert.equal(last!.totalReservedUSD,1.96);
  await assert.rejects(f.budget.reserve(small,new AbortController().signal),/exhausted/);
  assert.equal((await f.budget.reserve({...small,maximumRequestUSD:0.01},new AbortController().signal)).totalReservedUSD,1.98);
  await writeFile(join(f.root,'credit-budget.json'),JSON.stringify({version:1,accountHash:'a'.repeat(64),reservations:[{id:'00000000-0000-4000-8000-000000000000',at:new Date().toISOString(),amountUSD:0.001}]}));
  await assert.rejects(f.budget.reserve(small,new AbortController().signal),/account or records/);
 }finally{await f.clean();}
});

test('shared credit proofs are reused only while fresh and failures are never cached',async()=>{
 let calls=0;let fail=false;
 const observe=sharedObservation(async()=>{calls++;if(fail)throw new Error('denied');return {...observation(),observedAt:Date.now()};},50);
 await observe(new AbortController().signal);await observe(new AbortController().signal);assert.equal(calls,1);
 await new Promise(r=>setTimeout(r,60));const renewed=await observe(new AbortController().signal);assert.equal(calls,2);assert(Date.now()-renewed.observedAt<50);
 fail=true;await new Promise(r=>setTimeout(r,60));
 await assert.rejects(observe(new AbortController().signal),/denied/);await assert.rejects(observe(new AbortController().signal),/denied/);assert.equal(calls,4);
 const stopped=new AbortController();stopped.abort();await assert.rejects(observe(stopped.signal));
 assert.throws(()=>sharedObservation(async()=>observation(),30_000));
});

test('an explicit renewal archives the exhausted ledger with its reconciliation and starts an empty one under the lifetime cap',async()=>{
 const f=await fixture();try{
  const first=await f.budget.reserve(observation(),new AbortController().signal);
  await f.budget.receipt(first.id,{outcome:'completed',estimatedUSD:0.004});
  await assert.rejects(renewBudget(f.root,{capUSD:2,lifetimeCapUSD:10}),/does not need renewing/);
  await f.budget.reserve(observation(),new AbortController().signal);
  await assert.rejects(f.budget.reserve(observation(),new AbortController().signal),/exhausted/);
  await assert.rejects(renewBudget(f.root,{capUSD:2,lifetimeCapUSD:3}),/lifetime authorization/);
  const renewed=await renewBudget(f.root,{capUSD:2,lifetimeCapUSD:10,now:new Date('2026-10-18T05:00:00Z')});
  assert.equal(renewed.reservedUSD,2);assert.equal(renewed.receipts,1);assert.equal(renewed.estimatedSpendUSD,0);assert.equal(renewed.lifetimeReservedUSD,2);
  const archive=JSON.parse(await readFile(renewed.archive,'utf8'));
  assert.equal(archive.ledger.reservations.length,2);assert.equal(archive.authorizedBy,'--authorized-new-budget');
  assert.equal(JSON.parse(await readFile(join(f.root,'credit-budget.json'),'utf8')).reservations.length,0);
  // the new ledger reserves again for the same account, and a second renewal counts the archive towards the lifetime total
  await f.budget.reserve(observation(),new AbortController().signal);await f.budget.reserve(observation(),new AbortController().signal);
  await assert.rejects(renewBudget(f.root,{capUSD:2,lifetimeCapUSD:5}),/lifetime authorization/);
  const again=await renewBudget(f.root,{capUSD:2,lifetimeCapUSD:6,now:new Date('2026-10-18T06:00:00Z')});
  assert.equal(again.lifetimeReservedUSD,4);
 }finally{await f.clean();}
});

test('a renewal refuses a ledger with a record outside the rules and waits for the reserve lock',async()=>{
 const f=await fixture();try{
  await f.budget.reserve(observation(),new AbortController().signal);await f.budget.reserve(observation(),new AbortController().signal);
  const path=join(f.root,'credit-budget.json');const good=await readFile(path,'utf8');
  const corrupt=JSON.parse(good);corrupt.reservations.push({});await writeFile(path,JSON.stringify(corrupt));
  await assert.rejects(renewBudget(f.root,{capUSD:2,lifetimeCapUSD:10}),/outside the ledger rules/);
  await writeFile(path,good);
  await mkdir(join(f.root,'credit-budget.lock'));
  await assert.rejects(renewBudget(f.root,{capUSD:2,lifetimeCapUSD:10}),/locked/);
  await rm(join(f.root,'credit-budget.lock'),{recursive:true});
  const renewed=await renewBudget(f.root,{capUSD:2,lifetimeCapUSD:10});assert.equal(renewed.reservedUSD,2);
 }finally{await f.clean();}
});
