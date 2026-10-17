# Credit-only funding decision

The user permits **$1,000 maximum in AWS credits total**, with **$200 maximum before waking**, and authorizes **no cash billing**.

## Verified on 17 October 2026

The signed-in AWS Billing Credits UI showed an active hackathon promotional credit expiring 31 October 2026. Its applicable-products list includes Amazon Bedrock, AmazonBedrockFoundationModels and EC2. This establishes credit eligibility for those listed services, not for every vendor endpoint reachable from an EC2 instance.

Official documentation lists supported OpenAI models on Bedrock and Responses/Chat Completions endpoints. It did not establish a Bedrock endpoint for OpenAI Realtime or GPT-Live. OpenAI's direct realtime service uses its own API authentication and billing.

A read-only official-documentation recheck at 06:52 BST still did not establish a funded live-voice route. OpenAI's [Bedrock guide](https://developers.openai.com/api/docs/guides/amazon-bedrock) documents Responses/Chat Completions compatibility and marks Responses WebSocket connections unavailable on Bedrock. Its separate [GPT-Live connection guide](https://developers.openai.com/api/docs/guides/voice-websockets?api=live) documents an OpenAI-hosted primary session with a project API key. These sources do not establish that GPT-Live is a credit-covered Bedrock service; the funding gate remains unchanged. No provider connection, grant redemption or billing configuration was attempted.

The text/reference fixtures, focused viewer follow-up and two fixed scientific-agent attempts made **26 completed real model requests** in total, with estimated token usage of **$0.02179905** and **$26 retained as conservative attempt reserves**. The first scientific attempt stopped at strict argument validation and restored its baseline; the corrected run completed the full authorized sequence. Both attempts remain fully counted. Fresh account/credit/access/price checks preceded each call. No direct cash-billed OpenAI route, new subscription or project cloud infrastructure was used. AWS invoice/credit settlement is not reconciled; these figures do not describe unrelated existing account usage.

## Enforced behavior

`packages/gateway/src/funding.ts` fails closed. The server's voice-start handler reports the blocker and never opens an upstream model connection. There is no fallback to a personal OpenAI API key, no environment switch that silently authorizes cash billing, and no pretend live voice result.

The standalone Realtime transport and the separate GPT-Live client-delegation transport/backend have local protocol tests. Neither is activated in the production server. The scripted voice-protocol smoke uses only loopback connections and display operations; it is not live AI or microphone verification. See VOICE.md.

## What would unblock live voice

Either establish an officially supported AWS-credit-covered OpenAI voice endpoint for this account and implement its authentication/protocol, or obtain a new explicit funding decision from the user for a different credit/prepaid arrangement. Hosting a proxy on AWS, using an OpenAI-shaped SDK, or finding an AWS Marketplace listing does not prove that inference is covered by the user's credits.

Before any paid test, document the exact service/SKU, credit eligibility, region, model, authentication, conservative spend guard, duration cap and shutdown behavior. AWS credit balances and billing estimates update with delay and are not instantaneous hard spending limits.

Sources:
- [OpenAI on Amazon Bedrock](https://developers.openai.com/api/docs/guides/amazon-bedrock)
- [AWS endpoint/model availability](https://docs.aws.amazon.com/bedrock/latest/userguide/models-endpoint-availability.html)
- [OpenAI Realtime WebSockets](https://developers.openai.com/api/docs/guides/voice-websockets?voice-api=realtime)
- [GPT-Live and backend delegation](https://developers.openai.com/api/docs/guides/live)
- [AWS promotional credit terms](https://aws.amazon.com/awscredits/)

Account balances, account IDs and credit IDs remain in the private console/local evidence rather than public documentation.

## Separate credit-verified text adapter

A later read-only API check verified current eligible credits, existing GPT-OSS-120B access and exact regional token prices. The native Bedrock text adapter and durable $200 attempt reserve are implemented under offline tests. They do not establish GPT-Live funding. See [AWS-TEXT.md](AWS-TEXT.md) for activation, the deliberately separate real-inference smoke command and proof boundaries. The direct backend and authenticated companion viewer tests passed with unchanged scientific objects. A third actual model fixture verified exact pinned API search/read and declaration type assignments, with no application actions. The native text/Stop controls compile; their foreground visual inspection still needs manual unlock.
