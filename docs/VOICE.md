# Voice transport and backend integration

Production voice is disabled under the credit-only [funding policy](FUNDING.md). The modules below have local protocol tests; they have not made a live provider connection or captured a microphone session.

## Two distinct protocols

`realtime.ts` implements the older Realtime event flow. `live.ts` implements GPT-Live client delegation: `session.start`, readiness, continuous PCM audio, timestamped transcripts, delegation IDs, context/commentary updates and graceful shutdown. Renaming one transport would not implement the other.

`LiveSession` accepts an already-created WebSocket and an injected `DelegationBackend`. It does not create a vendor client, read an API key, choose a funded endpoint or reconnect automatically. The server's production `start_voice` route still returns the funding blocker and never constructs either transport.

## GPT-Live lifecycle

1. Send the short conversation configuration first and wait for `session.started` before accepting audio or commands.
2. Stream ordered, complete mono PCM16 samples at the configured 24 kHz rate. Transcript fragments include timestamps; they are not assumed to be complete turns.
3. On `session.delegation.created`, capture actual QuPath context and pass the original delegation ID, offset and saved conversation to the backend. Duplicate IDs do not start duplicate work. A distinct new delegation aborts the previous backend owner before starting its replacement. Conversation/audio reception continues during backend work.
4. Return short spoken commentary or quiet context with the original ID. A conservative 480-byte UTF-8 limit keeps individual append messages below the protocol's token ceiling; large results remain with the backend/display callback.
5. Explicit interruption stops local playback and aborts cooperative backend work. Runtime checks cancellation and transcript revision again when queued view operations start, after asynchronous context observation, and before publishing a proposal. Guarded display operations also bind the latest application context at the adapter boundary. An action already executing is not rolled back. Existing human-review proposals remain for the researcher to decide. Model audio stays suppressed after interruption until the application explicitly resumes the current playback epoch after clearing stale audio. An instruction acknowledgment is not a recovery signal. Interruption also blocks delegations still in transit and all later delegations in that session. The trusted host may use `resumeDelegation(id, epoch)` only for one explicitly reconciled new request, or replace the session and restore task context. Receiving another transcript or resuming playback does not authorize backend work. This strict recovery policy is not wired to the native UI yet.
6. Close gracefully and retain the terminal usage payload. The module reports whether it received that payload; it does not calculate charges or reconcile a credit balance. Missing final usage is explicit. A maximum 180-second transport lifetime and bounded close timeout prevent unattended sessions from remaining open.

The timestamped transcript buffer is bounded and reports truncation. Suppressed assistant transcripts are marked; a transcript is not proof that speech was heard. It supplies conversation to the backend; it is not a durable scientific notebook or a replacement for the action audit journal.

## Provider-injected backend

`backend.ts` provides a bounded Responses-shaped tool loop. A provider adapter must implement `BackendClient.complete(request, signal)` under an approved funding route/model. The production researcher-text route now uses the credit-checked Bedrock OpenAI adapter described in [AWS-TEXT.md](AWS-TEXT.md), including real message-route and source-reference verification. It remains text-only and separately funded from the GPT-Live frontend. Production voice still has no funded provider factory or native audio wiring; it does not instantiate `LiveSession`.

The backend retains bounded conversation-scoped task results and detailed answers for follow-ups, with up to eight task records and an 8 MiB logical history limit. Omitted history is explicit. A large later approval result is trimmed again even if the corresponding call arguments were already omitted. It also reconciles later human decisions on interrupted proposals. Use one backend instance per researcher conversation, and clear history before switching unrelated users.

The backend uses the same tool registry and `Runtime.call` as MCP. It refreshes application context each round, supplies both the original delegation observation and current observation, and preserves transcript corrections. New user fragments arriving during inference invalidate the stale response before any tool or answer is published; remaining unstarted calls in a batch receive cancellation results before the backend replans. If a reference becomes ambiguous after an independent application change, the backend instructions require clarification rather than silent retargeting. Risky actions still bind exact parameters and current application state for human review.

The model is told that the host's fresh `qupathObservation` already satisfies the context-refresh step. It can use that result directly instead of spending an extra inference round requesting the same context again. The context tool remains available, and Runtime's pre-dispatch freshness checks are unchanged. This is a latency optimization opportunity, not a fixed response-time guarantee.

When a tool creates a proposal, the backend waits for the actual human decision. It has no approval tool or companion control token. Denied/failed/expired results return as such. Cancellation releases its listeners and local model wait even if an injected client ignores cancellation; it cannot approve the proposal. The provider adapter must still honor the abort signal to stop its own network work. Local cancellation alone is not a billing guarantee. Proposal eviction also emits expiry so waiting conversations can stop waiting.

Model requests explicitly disable stored Responses state, disable parallel function calls within a response, and bound rounds/output/tool calls. These are protocol limits, not monetary guarantees. They do not replace an approved rate card, credit eligibility check or spend guard.

A QuPath snapshot becomes actual image input for a vision-capable adapter, rather than base64 text in a function result. New image captures are limited to four PNG viewer frames of about 2 MiB each per delegation. One retained historical viewer frame can also be attached to a follow-up, clearly labeled as historical. Long user-facing results are retained in the required display callback, while speech receives a short notice. Private reasoning/output items needed for model continuation are not spoken as results.

## Local verification

```sh
npm run check
npm run smoke:voice -- --public-fixture
```

The second command requires the gateway and public OS-2 slide. It uses a loopback mock voice provider and scripted backend responses, changes only the real viewer's focus, checks the resulting coordinates and confirms the hierarchy is unchanged. Its synthetic PCM is neither microphone recording nor played speech. The reported timing measures local glue only, not model or voice latency.

Evidence is written to `.local/evidence/voice-protocol.json`. Never present this fixture as a live AI conversation. The actual cell-analysis fixture and MCP smoke are separate checks described in [REVIEW.md](REVIEW.md).

## Remaining activation work

A verified credit-covered provider factory, native capture/playback wiring, supported backend model configuration, real usage accounting and a bounded live public-slide session are still required. The native `AudioIO` component uses a separately tested PCM codec: signed little-endian samples, clipping, non-finite input, bounded complete chunks and RMS metering have four offline PCM tests (`./script/test-audio.sh`). Four additional hardware-free capture-lifecycle tests cover Stop while permission is pending, stale permission after a newer owner, task cancellation, denial and device-start cleanup/retry. Native capture/playback ownership is serialized on MainActor; the permission lifecycle prevents a late grant from starting capture after Stop. Capture startup failure removes its tap. These checks do not exercise a microphone, speakers, echo handling, playback queue recovery or a funded end-to-end hardware session. Do not turn local protocol success into a claim that voice is ready or that every scientific result is validated.

Sources reviewed on 17 October 2026:

- [GPT-Live WebSockets](https://developers.openai.com/api/docs/guides/voice-websockets?api=live)
- [Client delegation](https://developers.openai.com/api/docs/guides/live-delegation?delegation-mode=client)
- [Responses function calling](https://developers.openai.com/api/docs/guides/function-calling)
- [Image input](https://developers.openai.com/api/docs/guides/images-vision)
- [Playback control and recovery](https://developers.openai.com/api/docs/guides/voice-server-controls?api=live#control-playback-when-needed)
