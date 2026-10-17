import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';
import WebSocket from 'ws';
import { startServer } from '../src/server.js';

test('loopback gateway separates model tools from human approval and blocks browser origins',async()=>{
  const root=await mkdtemp(join(tmpdir(),'luma-server-'));const gateway=await startServer(root,0);
  const address=gateway.server.address();assert(address&&typeof address==='object');
  const base=`http://127.0.0.1:${address.port}`;
  const model=(await readFile(join(root,'.local/state/gateway-token'),'utf8')).trim();
  const human=(await readFile(join(root,'.local/state/companion-token'),'utf8')).trim();
  try {
    assert.equal((await fetch(base+'/tools')).status,401);
    assert.equal((await fetch(base+'/tools',{headers:{Authorization:`Bearer ${model}`,Origin:'https://attacker.example'}})).status,403);
    const tools=await fetch(base+'/tools',{headers:{Authorization:`Bearer ${model}`}});assert.equal(tools.status,200);
    assert.equal((await tools.json()).tools.length,23);
    const denied=await fetch(base+'/approvals/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',{method:'POST',headers:{Authorization:`Bearer ${model}`,'Content-Type':'application/json'},body:'{"approved":true}'});assert.equal(denied.status,403);
    const ws=new WebSocket(`ws://127.0.0.1:${address.port}/events`,{headers:{Authorization:`Bearer ${human}`}});
    await once(ws,'open');
    const result=once(ws,'message');ws.send(JSON.stringify({type:'start_voice'}));
    const [raw]=await result;const event=JSON.parse(raw.toString());
    // The connection greeting may race with the start request; wait for the funding response explicitly.
    const message=event.type==='error'?event:JSON.parse((await once(ws,'message'))[0].toString());
    assert.equal(message.type,'error');assert.match(message.message,/AWS-credit-covered/);
    ws.close();await once(ws,'close');
  }finally{await gateway.close();await rm(root,{recursive:true,force:true});}
});
