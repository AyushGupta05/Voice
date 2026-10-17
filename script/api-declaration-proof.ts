import assert from 'node:assert/strict';
export type ExpectedDeclaration={source:string;sourceCommit:string;sourceSha256:string;line:number;signature:string};
/** Require a successful read of the exact pinned declaration and correctly assigned type fields. */
export function declarationTypes(expected:ExpectedDeclaration){
 const declaration=expected.signature.trim();
 const types=declaration.match(/^public\s+static\s+(\w+)\s+loadPixelClassifier\s*\(\s*(\w+)\s+\w+/);
 assert(types,'The pinned fixture declaration changed; update the test before invoking a model.');
 return{returnType:types[1],firstParameterType:types[2]};
}
export function verifyDeclaration(expected:ExpectedDeclaration,searches:unknown[],reads:unknown[],answer:string){
 const types=declarationTypes(expected),declaration=expected.signature.trim();
 const found=searches.some(value=>{
  if(!value||typeof value!=='object')return false;const search=value as Record<string,unknown>;
  return search.sourceCommit===expected.sourceCommit&&Array.isArray(search.results)&&search.results.some(hit=>hit?.source===expected.source&&hit.sourceSha256===expected.sourceSha256&&hit.matchLine===expected.line);
 });
 assert(found,'The model did not discover the requested pinned declaration through API search.');
 const observed=reads.some(value=>{
  if(!value||typeof value!=='object')return false;const read=value as Record<string,unknown>;
  if(read.source!==expected.source||read.sourceCommit!==expected.sourceCommit||read.sourceSha256!==expected.sourceSha256||!Number.isInteger(read.startLine)||!Number.isInteger(read.endLine)||typeof read.text!=='string')return false;
  const start=read.startLine as number,end=read.endLine as number;
  return start<=expected.line&&end>=expected.line&&read.text.split(/\r?\n/)[expected.line-start]?.trim()===declaration;
 });
 assert(observed,'The model did not read the exact pinned QP.loadPixelClassifier declaration.');
 const text=answer.trim().replace(/^```(?:json)?\s*\n([\s\S]*?)\n```$/,'$1');
 const parsed=JSON.parse(text);assert.deepEqual(parsed,types,'The answer must assign the actual return and first-parameter types correctly.');
 return{...expected,...types};
}
