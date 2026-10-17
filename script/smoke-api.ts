/** Read-only actual pinned-source lookup. No scientific session, provider request or source execution. */
import assert from 'node:assert/strict';
import {mkdir,writeFile} from 'node:fs/promises';
import{resolve}from'node:path';
import{ApiSource}from'../packages/gateway/src/api-source.js';
const root=resolve(process.env.LUMA_ROOT??process.cwd());const api=new ApiSource();
const started=performance.now();await api.load(root);const indexedMs=Math.round(performance.now()-started);
const lookup=performance.now();const result=api.search('loadPixelClassifier');const lookupMs=Math.round(performance.now()-lookup);
assert.equal(result.available,true,'Run setup to obtain the pinned QuPath source.');
const hit=result.results.find(hit=>hit.symbol==='qupath.lib.scripting.QP');assert(hit,'Expected actual QP API was not found.');
const expanded=api.read(hit.source,Math.max(1,hit.matchLine-4),24);assert.match(expanded.text,/loadPixelClassifier/);
const evidence={status:'passed',observedAt:new Date().toISOString(),sourceCommit:result.sourceCommit,version:result.version,fileCount:result.fileCount,indexedMs,lookupMs,query:'loadPixelClassifier',matchedSymbol:hit.symbol,matchLine:hit.matchLine,sourceSha256:hit.sourceSha256,expandedLines:expanded.endLine-expanded.startLine+1,workingTreeRead:false,scientificSessionTouched:false,inferenceRequests:0};
await mkdir(resolve(root,'.local/evidence'),{recursive:true});await writeFile(resolve(root,'.local/evidence/api-source.json'),JSON.stringify(evidence,null,2)+'\n');console.log(JSON.stringify(evidence,null,2));
