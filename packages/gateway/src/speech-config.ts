import {readFile} from 'node:fs/promises';
import {join} from 'node:path';
import {loadTextConfig} from './bedrock-client.js';
export type SpeechConfig={version:1;route:'aws-transcribe-polly-openai';accountHash:string;expiresAt:string;maximumReservedUSD:200;maximumSessionSeconds:180};
export async function loadSpeechConfig(directory:string):Promise<SpeechConfig>{
 let config:SpeechConfig;
 try{config=JSON.parse(await readFile(join(directory,'speech-funding.json'),'utf8'));}catch{throw new Error('AWS-credit-covered speech is not configured. See docs/AWS-SPEECH.md.');}
 if(!config||config.version!==1||config.route!=='aws-transcribe-polly-openai'||!/^[a-f0-9]{64}$/.test(config.accountHash)||config.maximumReservedUSD!==200||config.maximumSessionSeconds!==180||!Number.isFinite(Date.parse(config.expiresAt))||Date.parse(config.expiresAt)<=Date.now())throw new Error('AWS speech funding configuration is invalid or expired.');
 const text=await loadTextConfig(directory);if(text.accountHash!==config.accountHash)throw new Error('Speech and OpenAI credit accounts must match.');
 return config;
}
