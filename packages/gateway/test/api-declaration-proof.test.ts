import{test}from'node:test';import assert from'node:assert/strict';
import{verifyDeclaration}from'../../../script/api-declaration-proof.js';
const expected={source:'QP.java',sourceCommit:'fixed-commit',sourceSha256:'fixed-hash',line:10,signature:'public static PixelClassifier loadPixelClassifier(String name) throws IllegalArgumentException {'};
const read={...expected,startLine:9,endLine:11,text:`/** public reference */\n${expected.signature}\nreturn classifier;`};
const searches=[{sourceCommit:expected.sourceCommit,results:[{source:expected.source,sourceSha256:expected.sourceSha256,matchLine:expected.line}]}];
const answer='{"returnType":"PixelClassifier","firstParameterType":"String"}';
test('declaration proof requires the exact pinned source read and correct type assignment',()=>{
 assert.equal(verifyDeclaration(expected,searches,[read],answer).returnType,'PixelClassifier');
 assert.throws(()=>verifyDeclaration(expected,[],[read],answer),/did not discover/);
 assert.throws(()=>verifyDeclaration(expected,searches,[],answer),/did not read/);
 assert.throws(()=>verifyDeclaration(expected,searches,[{...read,text:'package qupath.lib.scripting;'}],answer),/did not read/);
 assert.throws(()=>verifyDeclaration(expected,searches,[{...read,source:'Other.java'}],answer),/did not read/);
 assert.throws(()=>verifyDeclaration(expected,searches,[{...read,sourceSha256:'another-hash'}],answer),/did not read/);
 assert.throws(()=>verifyDeclaration(expected,searches,[read],'{"returnType":"String","firstParameterType":"PixelClassifier"}'),/assign/);
 assert.throws(()=>verifyDeclaration(expected,searches,[read],'PixelClassifier and String are mentioned.'),/Unexpected token/);
});
