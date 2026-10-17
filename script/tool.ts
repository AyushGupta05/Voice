import { readFile, writeFile } from 'node:fs/promises';
const [name,args='{}',output]=process.argv.slice(2);
if(!name)throw new Error('Usage: npm run tool -- qupath_context [JSON arguments] [snapshot output path]');
const token=(await readFile('.local/state/gateway-token','utf8')).trim();
const response=await fetch('http://127.0.0.1:49555/tools/call',{method:'POST',headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},body:JSON.stringify({name,arguments:JSON.parse(args)}),signal:AbortSignal.timeout(130000)});
const body=await response.json();
if(!response.ok)throw new Error(body.error);
if(name==='qupath_snapshot'&&output){await writeFile(output,Buffer.from(body.result.data,'base64'));console.log(`Saved ${output}`);}else console.log(JSON.stringify(body.result,null,2));
