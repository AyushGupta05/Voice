# AWS speech route

Luma's production voice uses **Amazon Transcribe streaming → OpenAI `gpt-oss-120b` on Bedrock → Amazon Polly**, paid only from AWS promotional credits. The user approved this route on 17 October 2026. It is **not GPT-Live**: no AWS-credit-covered OpenAI realtime endpoint exists for this account (see [FUNDING.md](FUNDING.md)). Treat it as a speech-to-text and text-to-speech wrapper around the same tool-using agent that powers typed requests.

## Flow

```
mic (AVAudioEngine, voice processing / echo cancellation)
  → 24 kHz PCM16 frames over the authenticated /events socket
  → SpeechSession → Transcribe streaming (en-GB, stable partials)
  → final transcript → TextConversation.submit(text, spoken=true) → Runtime → QuPath
  → short final answer → Polly generative voice (default Danielle; `LUMA_VOICE=Amy:neural` etc.) 16 kHz PCM → resampled to 24 kHz → companion playback
```

- **Barge-in:** the first partial transcript of a new utterance interrupts current playback and stops the running agent task. An action already executing in QuPath is not rolled back; say "undo that" to restore the checkpoint.
- **Spoken style:** spoken requests add `spokenStyle` (`backend.ts`) to the instructions, asking for one short sentence without coordinates or IDs. Full details still appear in the companion. `spokenReply()` trims anything longer than 240 characters to its first sentence.
- **Bounds:** sessions last at most 180 s, 16 turns and 24 spoken replies (acknowledgments included). Microphone input is rate-limited to real time, with a one-second send buffer and a 500-character reply limit. A second companion cannot open a parallel voice stream.

## Latency design

Measured with `smoke:voice-pipeline` on the public OS-2 slide (individual runs, not a benchmark):

| Stage | Typical |
|---|---|
| End of speech → final transcript | 0.6–0.7 s (Transcribe endpointing) |
| One Bedrock `gpt-oss-120b` round (us-east-1, from Europe) | ~1.1 s; `gpt-oss-20b` was not faster |
| Transcript → visible QuPath action | 1.3–2.3 s (one model round) |
| End of speech → first spoken audio | 0.4 s on the instant route; 1.7 s with a cached acknowledgment; previously 4.2–10.5 s |
| End of speech → data action starts (auto policy) | ~2.4 s, spoken "Detecting cells." / "Classifying." as it starts; in review mode the card appears instead with "Ready for your approval." |

What makes it feel live:

1. **Acknowledgments without a model round.** `acknowledgmentFor()` turns the agent's first instant view call into a phrase ("Zooming in.", "Here: 49 positive cells.") that is spoken while QuPath moves. The final answer queues behind it, and a bare "Done." stays silent.
2. **Pre-synthesized phrases.** Common acknowledgments are synthesized once per gateway when listening starts, then served from memory.
3. **Warm connections.** Bedrock and Polly clients are reused per credential identity, saving about 0.3 s of TLS per request.
4. **Shared credit proofs.** A proof is reused for 20 s or less, and the speech proof is warmed while the agent works.
5. **Instant routes.** `fast-path.ts` executes a few unambiguous whole-utterance display commands (zoom in/out, show/hide cells or annotations, fill cells) directly, with no model round. Anything with a target, a filter or a data change still goes to the agent.
6. **No wasted rounds.** The agent is not offered `qupath_context`, because fresh context is injected every round. It is told never to claim an action no tool result shows. An earlier run said "Zoomed out" without acting; that is now prevented.

## Funding guards

Every Transcribe session and Polly reply goes through the same guards as text:

1. Read-only proof: STS identity, Billing `GetCredits`, Pricing `GetProducts` for the exact `us-east-1` SKUs, and Polly voice availability. One credit grant must cover Transcribe, Polly and Bedrock together.
2. A durable reservation in `.local/state/credit-budget.json` for twice the request's computed worst case, rounded up to a cent (at least $0.01, at most $1). The sum of reservations, including older flat $1 entries, can never exceed **$200**. Reservations are never refunded.
3. A receipt in `.local/state/speech-usage.jsonl`, with `billingReconciled: false`.

`sharedObservation()` lets requests within one turn reuse a proof for up to 20 s, which stays inside the ledger's 30 s freshness rule. Failed proofs are never cached. Each request still reserves separately. The speech proof is warmed while the agent works, so the Polly reply doesn't wait for it.

## Enable

```sh
node --import tsx script/enable-speech.ts --authorized-aws-speech
```

This makes read-only AWS calls and writes `.local/state/speech-funding.json` (it expires within 24 hours and is tied to the text-funding account). Restart the companion so its gateway re-reads funding. `/health` then reports `voice.enabled: true`.

## Verify

| Command | What it proves | Cost |
|---|---|---|
| `npm run smoke:speech` | Polly speaks a fixed phrase and Transcribe hears it, through the guarded provider | 2 small speech requests |
| `npm run smoke:voice-pipeline` | Full gateway path: Polly audio streamed as microphone input → Transcribe → agent → real QuPath `focus` → Polly reply. Fails if an approval is requested; it never approves | ~4 small requests; moves the real viewer |

Both commands need `-- --authorized-aws-speech`.

Measured on 17 October 2026 (public OS-2 slide, "Zoom in on the centre of this slide."):

- Exact transcript.
- Agent called `qupath_focus`.
- Spoken reply: "Zoomed in on the slide centre at downsample 5."
- End of speech to final transcript: about 0.7 s.
- End of speech to start of reply audio: **4.3 s**, down from 10.5 s before proof sharing and short spoken answers. About 2.9 s of that is two Bedrock model rounds.

This is one run, not a latency benchmark.

**Not yet proven:** a real microphone and speakers, echo cancellation under room conditions, and a long multi-turn session in the foreground app. Run the companion, press the mic in the top bar and speak.
