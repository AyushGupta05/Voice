import {BillingClient,GetCreditsCommand} from '@aws-sdk/client-billing';
import {PricingClient,GetProductsCommand} from '@aws-sdk/client-pricing';
import {STSClient,GetCallerIdentityCommand} from '@aws-sdk/client-sts';
import {PollyClient,DescribeVoicesCommand} from '@aws-sdk/client-polly';
import {fromNodeProviderChain} from '@aws-sdk/credential-providers';
import type {AwsCredentialIdentity} from '@aws-sdk/types';
import {accountHash,eligibleCreditUSD} from './aws-credit-proof.js';
import type {CreditObservation} from './credit-budget.js';

export const SPEECH_REGION='us-east-1';
/** 30 minutes per Transcribe session (owner's request, 17 Oct 2026): 1800 s × ~$0.000167/s ≈ $0.30, reserved twice over (≤ $1). */
export const MAX_VOICE_SECONDS=1800;
export const MAX_SPOKEN_CHARACTERS=500;
export type VoiceEngine='neural'|'generative';
export type Voice={id:string;language:string;engine:VoiceEngine};
/** Natural-sounding voices that exist in us-east-1. LUMA_VOICE="Amy:neural" picks another; unknown values fail closed. */
const VOICES:Record<string,Voice>={
 Danielle:{id:'Danielle',language:'en-US',engine:'generative'},Ruth:{id:'Ruth',language:'en-US',engine:'generative'},Joanna:{id:'Joanna',language:'en-US',engine:'generative'},
 Matthew:{id:'Matthew',language:'en-US',engine:'generative'},Stephen:{id:'Stephen',language:'en-US',engine:'generative'},Amy:{id:'Amy',language:'en-GB',engine:'generative'},
 Brian:{id:'Brian',language:'en-GB',engine:'generative'},Olivia:{id:'Olivia',language:'en-AU',engine:'generative'},Niamh:{id:'Niamh',language:'en-IE',engine:'generative'}};
export function selectVoice(setting=process.env.LUMA_VOICE):Voice{
 if(!setting)return VOICES.Danielle;
 const [id,engine='generative']=setting.split(':');const voice=VOICES[id];
 if(!voice||!['neural','generative'].includes(engine))throw new Error(`LUMA_VOICE must be one of ${Object.keys(VOICES).join(', ')}, optionally ":neural" or ":generative".`);
 return {...voice,engine:engine as VoiceEngine};
}
export const VOICE=selectVoice();
const POLLY_SKU:Record<VoiceEngine,string>={neural:'USE1-SynthesizeSpeechNeural-Characters',generative:'USE1-SynthesizeSpeechGenerative-Characters'};
export type SpeechRates={transcribePerSecondUSD:number;pollyPerCharacterUSD:number};
export type SpeechProof=CreditObservation&{credentials:AwsCredentialIdentity;rates:SpeechRates};
export function speechRates(transcribe:unknown[],polly:unknown[],engine:VoiceEngine=VOICE.engine):SpeechRates{
 const rate=(products:any[],service:string,usage:string,operation:string|undefined,unit:string)=>{
  const selected=products.filter(p=>p?.product?.attributes?.regionCode===SPEECH_REGION&&p.product.attributes.servicecode===service&&p.product.attributes.usagetype===usage&&(operation===undefined||p.product.attributes.operation===operation));
  if(selected.length!==1)throw new Error('An unambiguous current AWS speech price is required.');
  const terms:any[]=Object.values(selected[0].terms?.OnDemand??{});
  if(terms.length!==1||!Number.isFinite(Date.parse(terms[0].effectiveDate))||Date.parse(terms[0].effectiveDate)>Date.now())throw new Error('Current speech price term is unavailable.');
  const dimensions:any[]=Object.values(terms[0].priceDimensions??{});
  if(dimensions.length!==1||dimensions[0].unit!==unit||dimensions[0].beginRange!=='0'||dimensions[0].endRange!=='Inf')throw new Error('Unsupported speech price dimension.');
  const value=Number(dimensions[0].pricePerUnit?.USD);if(!Number.isFinite(value)||value<=0)throw new Error('A positive speech USD price is required.');return value;
 };
 const rates={transcribePerSecondUSD:rate(transcribe,'transcribe','USE1-StreamingAudio','StreamingAudio','second'),pollyPerCharacterUSD:rate(polly,'AmazonPolly',POLLY_SKU[engine],undefined,'Characters')};
 if(rates.transcribePerSecondUSD*MAX_VOICE_SECONDS>0.5||rates.pollyPerCharacterUSD*MAX_SPOKEN_CHARACTERS>0.5)throw new Error('Speech prices exceed the conservative request reserve.');
 return rates;
}
/** Read-only account, credit, rate and existing voice-availability proof; no speech is sent. */
export async function observeSpeechCredits(signal:AbortSignal):Promise<SpeechProof>{
 signal.throwIfAborted();
 const credentials=await fromNodeProviderChain({profile:process.env.AWS_PROFILE??'default',timeout:3000,maxRetries:0})();
 const settings={region:SPEECH_REGION,credentials,maxAttempts:1};
 const sts=new STSClient({...settings,endpoint:'https://sts.us-east-1.amazonaws.com'});
 const billing=new BillingClient({...settings,endpoint:'https://billing.us-east-1.api.aws'});
 const pricing=new PricingClient({...settings,endpoint:'https://api.pricing.us-east-1.amazonaws.com'});
 const polly=new PollyClient({...settings,endpoint:'https://polly.us-east-1.amazonaws.com'});
 try{
  const identity=await sts.send(new GetCallerIdentityCommand({}),{abortSignal:signal});
  if(!identity.Account||!/^\d{12}$/.test(identity.Account))throw new Error('AWS speech account identity is unavailable.');
  const observedAt=Date.now();
  const [credits,stt,tts,voices]=await Promise.all([
   billing.send(new GetCreditsCommand({accountId:identity.Account,startDate:new Date(observedAt-364*86400_000),payerAccountFlag:false}),{abortSignal:signal}),
   pricing.send(new GetProductsCommand({ServiceCode:'transcribe',Filters:[{Type:'TERM_MATCH',Field:'regionCode',Value:SPEECH_REGION}],MaxResults:100}),{abortSignal:signal}),
   pricing.send(new GetProductsCommand({ServiceCode:'AmazonPolly',Filters:[{Type:'TERM_MATCH',Field:'regionCode',Value:SPEECH_REGION}],MaxResults:100}),{abortSignal:signal}),
   polly.send(new DescribeVoicesCommand({Engine:VOICE.engine,LanguageCode:VOICE.language as any}),{abortSignal:signal})
  ]);
  if(stt.NextToken||tts.NextToken||voices.NextToken)throw new Error('Speech price or voice catalog is incomplete.');
  if(!voices.Voices?.some(v=>v.Id===VOICE.id&&v.LanguageCode===VOICE.language&&v.SupportedEngines?.includes(VOICE.engine)))throw new Error(`The configured AWS speech voice ${VOICE.id} (${VOICE.engine}) is unavailable.`);
  const rates=speechRates((stt.PriceList??[]).map((p:string)=>JSON.parse(p)),(tts.PriceList??[]).map((p:string)=>JSON.parse(p)));
  return{credentials,rates,accountHash:accountHash(identity.Account),observedAt,availableCreditUSD:eligibleCreditUSD(credits.credits??[],identity.Account,observedAt,['Amazon Transcribe','Amazon Polly','AmazonBedrockFoundationModels']),maximumRequestUSD:MAX_VOICE_SECONDS*rates.transcribePerSecondUSD};
 }finally{sts.destroy();billing.destroy();pricing.destroy();polly.destroy();}
}
