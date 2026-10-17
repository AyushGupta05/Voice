# AWS-credit OpenAI text backend

This adapter is separate from GPT-Live voice. It uses the already-enabled OpenAI GPT-OSS-120B model through Amazon Bedrock `InvokeModel` in `us-east-1`. It is text-only: application context and measured values are available, while viewer vision is excluded. All 32 MCP tools remain available to compatible external clients; this text backend omits the viewer-image tool.

The adapter passed a bounded real model-to-QuPath test on 17 October 2026: three actual model requests focused the public viewer at (51100, 27100), with no object changes or approval proposals. Token usage estimates total $0.00140025; $3 remains reserved conservatively, and the AWS invoice is not reconciled. The companion text/Stop route also passed its actual authenticated WebSocket → model → QuPath fixture in 6079 ms, with three further requests. A third, reference-only real-model fixture verified exact API source retrieval and declaration types using four further requests, without application actions. A focused repeat after clarifying use of the host's fresh context completed the same viewer request in two requests / 4665 ms, adding $0.0011169 estimated tokens and $2 reserves. These are individual runs, not a latency benchmark. The later fixed scientific-agent test completed the full detection/classification/comparison/restoration sequence after an initial safely stopped attempt. Those two attempts added fourteen completed requests; cumulative estimates are **$0.02179905** with **$26 retained reserves** across 26 completed requests. See [scientific-agent scope and evidence](SCIENTIFIC-AGENT-TEST.md). The native controls compile; foreground visual checks remain pending manual unlock. Production GPT-Live remains disabled. See [direct model evidence](pitch/text-model-evidence.json), [original companion-route evidence](pitch/companion-text-evidence.json) and [the focused follow-up](pitch/companion-text-fresh-context-evidence.json).

## Why this route

Read-only authenticated checks on 17 October 2026 verified an active credit with `AmazonBedrockFoundationModels` eligibility, including an estimated balance that accounts for open bills. The record matches the earlier console credit name. GPT-OSS-120B reports existing agreement, entitlement, authorization and regional availability. The checked frontier OpenAI alternatives do not report an existing agreement, so they were not invoked or automatically subscribed.

The adapter uses the documented native inference API, not an OpenAI SDK pointed at a direct cash-billed endpoint. Its request contains one non-streaming model completion with local function definitions. The application executes functions through the existing Runtime and human-approval path. AWS-hosted tools, Marketplace infrastructure deployments, server-side agent loops and new agreements are not used.

## Before each model request

1. Load a private, expiring account-bound local configuration.
2. Resolve existing AWS credentials in memory, then authenticate their account through STS.
3. Read current promotional credits, existing model access and public Standard-tier token prices. Those service clients use fixed official endpoints and a single SDK attempt. Both Bedrock clients allow SigV4 only, ignoring bearer-token authentication preferences so inference cannot switch accounts. Existing login credentials can refresh through the official provider.
4. Count only enabled, unexpired USD credits owned by this account, explicitly covering foundation models, without unknown purchase/cost-category restrictions. Use the smaller of remaining and estimated balances.
5. Verify an exact Standard on-demand regional price. The current price-list API reports $0.15 per million input tokens and $0.60 per million output tokens. The whole 128K model window and 16K maximum output cost less than the **$1 reserved per attempt**; requests themselves are capped at 4096 output tokens.
6. Persist and sync the $1 reserve before inference. Keep an additional $200 credit margin and subtract all old reserves again when checking available credits.
7. Invoke only the configured OpenAI model with Standard/default processing, no trace request and no automatic retry or provider fallback.

The durable project cap is **$200 across all attempts**, including after the overnight deadline; it never automatically rises to the user's $1000 total authorization. Successful, failed, cancelled and ambiguous requests never receive automatic reserve refunds. Usage receipts provide token-cost estimates, while the reservation ledger remains authoritative. Corrupt state or a stale lock blocks further calls. Preserve those files for reconciliation; deleting them would discard the guard's history.

This guard bounds project attempts using current verified credit/pricing observations. AWS balances and estimates can be delayed; it does not control unrelated account usage or replace invoice reconciliation. Configuration/credentials never make the separate GPT-Live endpoint credit-covered.

## Explicit local activation and test

After review, using an existing authenticated AWS profile with eligible credits and existing model access:

```sh
npm run enable:text
npm run smoke:text -- --public-fixture
```

The first command performs read-only AWS checks and writes an expiring private configuration. It makes no inference call and never resets the budget. Missing prerequisites block activation.

The second command makes real, credit-checked model requests. It refuses to run without the exact public OS-2 metadata and empty fixture hierarchy. A separate fixture Runtime permits only context reads and the exact requested focus at (51100, 27100), downsample 3. It cannot create a data proposal or simulate human approval. Every observed context must retain the captured public-fixture identity and empty hierarchy, and focus is bound to it at dispatch. Model input is an allowlisted context with fixed public image metadata and numeric viewer state; session-wide checkpoints, project paths, dispatch errors and unknown fields are excluded from both the initial delegation and later results. It verifies the actual viewer center and unchanged hierarchy, records model/usage evidence privately and states that no voice or vision was used. Do not run it merely as a generic install check.

Credentials, account/credit identifiers and balances remain private. The adapter does not create an API key or save resolved credentials. Local state is `.local/state/text-funding.json`, `credit-budget.json`, `credit-budget.lock` and `credit-usage.jsonl`. AWS CLI login credentials use the official JavaScript credential provider; another valid profile can be selected before activation with `AWS_PROFILE`. A different account cannot silently reuse an existing configuration or ledger.

## Verification

`npm run check` exercises only mocks and synthetic records. It verifies credit filtering, exact price selection, durable reserves, concurrency, stale/corrupt state, cancellation, text-only request conversion and completed-response parsing. It cannot prove a provider request, actual credit application or native UI flow. The actual smoke command is deliberately separate.

Sources checked on 17 October 2026:

- [GetCredits and balance fields](https://docs.aws.amazon.com/aws-cost-management/latest/APIReference/API_billing_GetCredits.html)
- [Credit eligibility and estimated amounts](https://docs.aws.amazon.com/aws-cost-management/latest/APIReference/API_billing_CreditData.html)
- [Model access and agreement status](https://docs.aws.amazon.com/bedrock/latest/userguide/model-access.html)
- [GPT-OSS-120B capabilities and limits](https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-openai-gpt-oss-120b.html)
- [Native OpenAI request/response format](https://docs.aws.amazon.com/bedrock/latest/userguide/model-parameters-openai.html)
- [Price-list GetProducts](https://docs.aws.amazon.com/aws-cost-management/latest/APIReference/API_pricing_GetProducts.html)
- [AWS login credential provider](https://docs.aws.amazon.com/AWSJavaScriptSDK/v3/latest/Package/-aws-sdk-credential-providers/)

## Companion path

After local activation, rebuild/restart Penny through its normal launcher. The text field sends researcher messages to the same gateway Runtime as MCP. New requests supersede older work, and Stop cancels unstarted work while preserving completed actions and human-review proposals. Full answers are selectable in session history; internal context reads do not clutter the visible history. The native interface still shows the separate voice funding gate.

`npm run smoke:companion-text -- --public-fixture` makes real credit-checked requests through an isolated authenticated WebSocket server using production conversation routing and the restricted public-fixture Runtime. It is separate from offline CI and does not click or visually inspect the native interface.
