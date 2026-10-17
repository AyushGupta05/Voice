import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { CreditBudget, type CreditObservation } from '../src/credit-budget.js';

const observation = (): CreditObservation => ({ accountHash: 'a'.repeat(64), observedAt: Date.now(), availableCreditUSD: 5000, maximumRequestUSD: 0.03 });
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
