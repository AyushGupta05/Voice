# AWS speech route

Luma's production voice uses **Amazon Transcribe streaming → OpenAI `gpt-oss-120b` on Bedrock → Amazon Polly**, paid only from AWS promotional credits. The user approved this route on 17 October 2026. It is **not GPT-Live**: no AWS-credit-covered OpenAI realtime endpoint exists for this account (see [FUNDING.md](FUNDING.md)). Treat it as a speech-to-text and text-to-speech wrapper around the same tool-using agent that powers typed requests.

## Flow

```
mic (AVAudioEngine, voice processing / echo cancellation)
  → 24 kHz PCM16 frames over the authenticated /events socket
  → SpeechSession → Transcribe streaming (en-GB, stable partials)
  → final transcript → TextConversation.submit(text, spoken=true) → Runtime → QuPath
  → short final answer → Polly neural "Amy" 16 kHz PCM → resampled to 24 kHz → companion playback
```

- **Barge-in:** the first partial transcript of a new utterance interrupts current playback and stops the running agent task. Pending human approvals stay pending.
- **Spoken style:** spoken requests add `spokenStyle` (`backend.ts`) to the instructions, asking for one short sentence without coordinates or IDs. Full details still appear in the companion. `spokenReply()` trims anything longer than 240 characters to its first sentence.
- **Bounds:** sessions last at most 180 s, 16 turns and 8 spoken replies. Microphone input is rate-limited to real time, with a one-second send buffer and a 500-character reply limit. A second companion cannot open a parallel voice stream.

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
