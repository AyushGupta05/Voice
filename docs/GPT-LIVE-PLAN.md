# GPT-Live integration boundary

This is an implementation plan, not a claim of working live voice. Production activation stays funding-blocked under [FUNDING.md](FUNDING.md).

## Separate conversation from scientific execution

The requested GPT-Live frontend handles continuous conversation. A backend agent owns scientific instructions, tools, task progress and QuPath context. Both use the existing gateway approval path. Spoken “yes” must never become an automatic approval-token action.

Client delegation fits the adapter architecture: Luma controls which observations and tool outcomes reach the voice frontend, and can keep using a separately configured OpenAI backend. A backend served by Bedrock would not make the separate GPT-Live frontend credit-covered.

## Protocol work still required

1. A separate Live transport, rather than renaming `RealtimeSession`: connect, send `session.start`, await `session.started`, and stream ordered, complete PCM16 samples at the configured rate. The existing native audio components target mono 24 kHz.
2. Keep timestamped input/output transcript fragments and current task state. A Live delegation event contains an ID and metadata, not the task utterance. Resolve corrections from conversation history and fresh application context.
3. Route delegated work through a backend agent and the same `Runtime.call` path as MCP. Keep exact approvals in the companion. Return concise verified outcomes with the original delegation ID; keep large scientific payloads in the backend.
4. Handle interruption explicitly: stop playback, cancel work that has not started, preserve already executed operations and pending human decisions, and explain what actually changed. A voice interruption cannot roll back arbitrary scientific work.
5. Request graceful session close, collect final usage, apply a bounded shutdown timeout and report missing final accounting. No automatic paid reconnect/fallback.

## Verification before enabling it

Use an in-process mock provider first: startup ordering, transcript corrections, duplicate delegation delivery, stale selection, waiting for human approval, denial, connection loss, audio ordering and shutdown. Verify the native microphone/playback lifecycle separately on the intended hardware. After a supported credit-covered route exists, perform one bounded live public-fixture session, measure interaction latency and inspect the resulting QuPath state. Do not promote mocked protocol tests to live-voice evidence.

Sources reviewed on 17 October 2026:

- [GPT-Live architecture](https://developers.openai.com/api/docs/guides/live)
- [Live WebSocket startup and audio](https://developers.openai.com/api/docs/guides/voice-websockets?api=live)
- [Client delegation and conversation context](https://developers.openai.com/api/docs/guides/live-delegation?delegation-mode=client)
