import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Readable } from 'node:stream';
import { BedrockClient, GetFoundationModelAvailabilityCommand } from '@aws-sdk/client-bedrock';
import { BedrockRuntimeClient, InvokeModelCommand } from '@aws-sdk/client-bedrock-runtime';
import { bedrockSigV4Only } from '../src/aws-auth.js';

test('actual SDK middleware signs both Bedrock clients with verified credentials despite conflicting bearer settings',async()=>{
 const saved=[process.env.AWS_BEARER_TOKEN_BEDROCK,process.env.AWS_AUTH_SCHEME_PREFERENCE];
 process.env.AWS_BEARER_TOKEN_BEDROCK='synthetic-other-account-token';process.env.AWS_AUTH_SCHEME_PREFERENCE='httpBearerAuth';
 const headers:Array<Record<string,string>>=[];
 const requestHandler={handle:async(request:any)=>{headers.push(request.headers);return{response:{statusCode:200,headers:{'content-type':'application/json'},body:Readable.from([Buffer.from('{}')])}};},destroy:()=>{}};
 const settings={region:'us-east-1',credentials:{accessKeyId:'AKIDBOUNDACCOUNT0001',secretAccessKey:'not-a-real-secret'},token:{token:'synthetic-explicit-bearer'},maxAttempts:1,requestHandler,...bedrockSigV4Only()};
 const control=new BedrockClient({...settings,endpoint:'https://bedrock.us-east-1.amazonaws.com'});
 const runtime=new BedrockRuntimeClient({...settings,endpoint:'https://bedrock-runtime.us-east-1.amazonaws.com'});
 try{
  await control.send(new GetFoundationModelAvailabilityCommand({modelId:'openai.gpt-oss-120b-1:0'}));
  await runtime.send(new InvokeModelCommand({modelId:'openai.gpt-oss-120b-1:0',body:Buffer.from('{}'),contentType:'application/json',serviceTier:'default'}));
  assert.equal(headers.length,2);
  for(const value of headers){assert.match(value.authorization,/^AWS4-HMAC-SHA256 Credential=AKIDBOUNDACCOUNT0001\//);assert.match(value.authorization,/us-east-1\/bedrock\/aws4_request/);assert(!value.authorization.includes('Bearer'));}
  assert.equal(headers[1]['x-amzn-bedrock-service-tier'],'default');
 }finally{
  control.destroy();runtime.destroy();
  for(const [index,key]of ['AWS_BEARER_TOKEN_BEDROCK','AWS_AUTH_SCHEME_PREFERENCE'].entries()){if(saved[index]===undefined)delete process.env[key];else process.env[key]=saved[index];}
 }
});

test('missing SigV4 credentials cannot fall back to an explicitly supplied bearer token',async()=>{
 let requests=0;
 const client=new BedrockRuntimeClient({region:'us-east-1',endpoint:'https://bedrock-runtime.us-east-1.amazonaws.com',credentials:async()=>{throw new Error('Synthetic verified identity unavailable');},token:{token:'synthetic-bearer'},maxAttempts:1,...bedrockSigV4Only(),requestHandler:{handle:async()=>{requests++;throw new Error('Must not dispatch');}}});
 try{await assert.rejects(client.send(new InvokeModelCommand({modelId:'openai.gpt-oss-120b-1:0',body:Buffer.from('{}')})),/verified identity unavailable/);assert.equal(requests,0);}finally{client.destroy();}
});
