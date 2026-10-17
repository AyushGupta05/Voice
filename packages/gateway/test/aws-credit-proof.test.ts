import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { CreditData } from '@aws-sdk/client-billing';
import { eligibleCreditUSD, priceRates } from '../src/aws-credit-proof.js';
const account='123456789012';
function credit(id='1'):CreditData {return {creditId:id,accountId:account,creditType:'Promotion',description:'Synthetic test credit',initialAmount:{currencyCode:'USD',currencyAmount:'1000'},remainingAmount:{currencyCode:'USD',currencyAmount:'1000'},estimatedAmount:{currencyCode:'USD',currencyAmount:'800'},creditStatus:'ENABLED',applicableProductNames:['AmazonBedrockFoundationModels'],creditSharingType:'DEFAULT',startDate:new Date(Date.now()-1000),endDate:new Date(Date.now()+86400_000)};}
function product(direction:'input'|'output') {return {product:{attributes:{regionCode:'us-east-1',model:'gpt-oss-120b',provider:'OpenAI',servicecode:'AmazonBedrock',feature:'On-demand Inference',usagetype:`USE1-gpt-oss-120b-${direction}-tokens`}},terms:{OnDemand:{term:{effectiveDate:'2020-01-01T00:00:00Z',priceDimensions:{price:{unit:'1K tokens',beginRange:'0',endRange:'Inf',pricePerUnit:{USD:direction==='input'?'0.00015':'0.0006'}}}}}}};}

test('credit eligibility uses the smaller current estimate and rejects unrelated or restricted records',()=>{
 const valid=credit();assert.equal(eligibleCreditUSD([valid],account),800);
 const invalid:CreditData[]=[{...credit('2'),accountId:'999999999999'},{...credit('3'),creditStatus:'DISABLED'},{...credit('4'),applicableProductNames:['EC2']},{...credit('5'),endDate:new Date(Date.now()-1)},{...credit('6'),startDate:new Date(Date.now()+100_000)},{...credit('7'),endDate:new Date('invalid')},{...credit('8'),estimatedAmount:undefined},{...credit('9'),creditSharingType:'COST_CATEGORY_RULE'},{...credit('10'),purchaseTypeApplications:['UNKNOWN']},{...credit('11'),estimatedAmount:{currencyCode:'EUR',currencyAmount:'800'}}];
 assert.equal(eligibleCreditUSD(invalid,account),0);
 assert.throws(()=>eligibleCreditUSD([valid,valid],account),/Duplicate/);
});

test('only the exact current standard regional token price can fund a request',()=>{
 const input=product('input');const output=product('output');
 assert.deepEqual(priceRates([input,output]),{inputPerTokenUSD:0.00000015,outputPerTokenUSD:0.0000006});
 assert.throws(()=>priceRates([input,input,output]),/unambiguous/);
 const wrong=structuredClone(input);wrong.product.attributes.regionCode='us-west-2';assert.throws(()=>priceRates([wrong,output]),/unambiguous/);
 const tier=structuredClone(input);tier.product.attributes.usagetype+='-priority';assert.throws(()=>priceRates([tier,output]),/unambiguous/);
 const units=structuredClone(input);units.terms.OnDemand.term.priceDimensions.price.unit='Hour';assert.throws(()=>priceRates([units,output]),/dimension/);
});
