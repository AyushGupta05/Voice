# AWS speech route

Penny's production voice uses **Amazon Transcribe streaming → OpenAI `gpt-oss-120b` on Bedrock → Amazon Polly**, paid only from AWS promotional credits. The user approved this route on 17 October 2026. It is **not GPT-Live**: no AWS-credit-covered OpenAI realtime endpoint exists for this account (see [FUNDING.md](FUNDING.md)). Treat it as a speech-to-text and text-to-speech wrapper around the same tool-using agent that powers typed requests.

## Flow

```
mic (AVAudioEngine, voice processing / echo cancellation)
  → 24 kHz PCM16 frames over the authenticated /events socket
  → SpeechSession → Transcribe streaming (en-GB, stable partials)
  → final transcript → TextConversation.submit(text, spoken=true) → Runtime → QuPath
  → short final answer → Polly generative voice (default Danielle; `LUMA_VOICE=Amy:neural` etc.) 16 kHz PCM → resampled to 24 kHz → companion playback
```

- **Wake word and attention:** the mic stays open for 30 minutes in rooms where other people talk, so a sentence reaches the agent only when it names Penny ("Hey Penny, analyse the cells here"; "Penny" alone answers "Yes?"), is a short command ("zoom in", "undo that", "stop"), or arrives within 30 s of Penny's last request or reply (natural follow-ups). Everything else is logged as `ignored` and shown dimmed; single stray words are always ignored. `LUMA_WAKE_WORD=off` accepts any two-word-plus utterance (quiet room, solo use). Transcribe spellings "Luna/Looma/Lumo" count as the name.
- **Barge-in:** the first partial transcript of a new utterance meant for Penny interrupts current playback and stops the running agent task. An action already executing in QuPath is not rolled back; say "undo that" to restore the checkpoint.
- **Spoken style:** spoken requests add `spokenStyle` (`backend.ts`) to the instructions, asking for one short sentence without coordinates or IDs. Full details still appear in the companion. `spokenReply()` trims anything longer than 240 characters to its first sentence.
- **Bounds:** sessions last at most 30 minutes, 200 turns and 400 spoken replies (acknowledgments included); each Transcribe session reserves twice its worst case (1800 s at the observed rate is about $0.30, so $0.60, still under the $1 per-request cap). Microphone input is rate-limited to real time, with a one-second send buffer and a 500-character reply limit. A second companion cannot open a parallel voice stream.

## Latency design

Measured with `smoke:voice-pipeline` on the public OS-2 slide (individual runs, not a benchmark):

| Stage | Typical |
|---|---|
| End of speech → final transcript | 0.6–0.7 s (Transcribe endpointing) |
| One Bedrock `gpt-oss-120b` round (us-east-1, from Europe) | ~1.1 s; `gpt-oss-20b` was not faster |
| Transcript → visible QuPath action | 1.3–2.3 s (one model round) |
| End of speech → first spoken audio | 0.4 s on the instant route; 1.7 s with a cached acknowledgment; previously 4.2–10.5 s |
| End of speech → data action starts (auto policy) | ~2.4 s, spoken "Detecting cells." / "Classifying." as it starts; in review mode the card appears instead with "Ready for your approval." |
| Data action result → spoken outcome (local finish) | ~0 s; the sentence comes from the tool result, no model round (was +1.2–1.6 s) |

What makes it feel live:

1. **Acknowledgments without a model round.** `acknowledgmentFor()` turns the agent's first instant view call into a phrase ("Zooming in.", "Here: 49 positive cells.") that is spoken while QuPath moves. The final answer queues behind it, and a bare "Done." stays silent.
2. **Pre-synthesized phrases.** Common acknowledgments are synthesized once per gateway when listening starts, then served from memory.
3. **Warm connections.** Bedrock and Polly clients are reused per credential identity, saving about 0.3 s of TLS per request.
4. **Shared credit proofs.** A proof is reused for 20 s or less, and the speech proof is warmed while the agent works.
5. **Instant routes.** `fast-path.ts` executes a few unambiguous whole-utterance display commands (zoom in/out, show/hide cells or annotations, fill cells) directly, with no model round. Anything with a target, a filter or a data change still goes to the agent.
6. **No wasted rounds.** The agent is not offered `qupath_context`, because fresh context is injected every round. It is told never to claim an action no tool result shows. An earlier run said "Zoomed out" without acting; that is now prevented.
7. **Local finishing.** For spoken requests the final model round is skipped when every call in the round succeeded and its result already says what to speak (`localSentenceFor()` in `backend.ts`): view calls (`focus`, `highlight`, `overlays`, `select`) finish silently behind their acknowledgment; `annotate` → "Added a Tumor region."; `detect_cells` → "Detected 294 cells."; `classify` → "49 of 294 cells are positive (17%)."; `class_summary` → "294 objects: Negative 245 (83%), Positive 49 (17%)." The numbers are the tool result's, never the model's. The model still phrases the answer after a failed call, a human-reviewed action (`LUMA_APPROVALS=review`), a snapshot, an open-ended read (`knowledge`, `measurements`, `find_images`, `open_path`, …), a typed request, or wording that asks for more than the call delivered ("… and count the cells", "analyse the cells" after detection alone). The spoken sentence is recorded in the task memory (`spoken`, `finishedLocally`) so follow-ups see it.
8. **Smaller later rounds.** Earlier rounds' observation JSON is replaced by a one-line stub in the model input; only the latest `qupathObservation` is sent in full (about 2.5k fewer prompt tokens per extra round).

Not done: Amazon Transcribe streaming has no end-of-utterance silence parameter (only partial-result stabilization, already on), and `parallel_tool_calls` stays off because the Runtime executes tools serially anyway and the loop's correctness checks are per call. Tool schemas are about 5.4k of the ~11k prompt tokens per round, but every droppable schema is used by a spoken recipe, so they are sent unchanged.

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

### Pathology vocabulary (recommended)

Amazon Transcribe mishears domain words ("stroma" → "Ostroma") unless given a custom vocabulary. Create one once (digits are not allowed in phrases):

```sh
aws transcribe create-vocabulary --region us-east-1 --vocabulary-name luma-pathology --language-code en-GB --phrases QuPath Penny annotation annotations tumour tumor stroma Ki-sixty-seven DAB haematoxylin hematoxylin eosin H-DAB brightfield fluorescence immunohistochemistry nucleus nuclei nuclear cells cell detection detections classifier classification threshold positive negative superpixels tiles TMA dearray GeoJSON Groovy workflow measurement measurements histogram zoom overlay overlays slide annotate highlight checkpoint undo stricter looser percentage pixel micron microns H-score
```

When `aws transcribe get-vocabulary --vocabulary-name luma-pathology` reports `READY` (a few minutes), write `.local/state/speech-vocabulary.json` containing `{"name":"luma-pathology","languageCode":"en-GB"}`. Each new voice session passes it as `VocabularyName`; no restart is needed after the file exists. Custom vocabularies are free.

## Verify

| Command | What it proves | Cost |
|---|---|---|
| `npm run smoke:speech` | Polly speaks a fixed phrase and Transcribe hears it, through the guarded provider | 2 small speech requests |
| `npm run smoke:voice-pipeline` | Full gateway path: Polly audio streamed as microphone input → Transcribe → agent → real QuPath `focus` → Polly reply. Fails if an approval is requested; it never approves | ~4 small requests; moves the real viewer |

Both commands need `-- --authorized-aws-speech`.

Measured on 17 October 2026 (public OS-2 slide, one run each, `smoke:voice-pipeline`, auto approval policy). "Before" is the same pipeline earlier that day, when every spoken action needed a second model round for the closing sentence (ack ≈ 2.3 s, final +1.2–1.6 s); "after" is with local finishing:

| Utterance | Route | End of speech → first audio | End of speech → final audio | Model rounds |
|---|---|---|---|---|
| "Zoom in a little." | instant route | 0.5 s ("Zooming in.") | same | 0 (unchanged) |
| "Highlight the middle of this slide." | one model round → `qupath_highlight` | 9.0 s ("Here: center."); that Bedrock round took 5.6 s and the uncached Polly phrase 1.7 s | same, "Done." stays silent (before: +1.2–1.6 s for the model's "Done.") | 1 (before: 2) |
| "Add a tumor annotation on the right." | one model round → `qupath_annotate` | 1.8 s ("Drawing the region.") | 2.9 s ("Added a Tumor region.", 3 ms after the action) | 1 (before: 2) |

The final sentence now arrives within milliseconds of the tool result, so a spoken action costs one Bedrock round plus speech. The highlight run shows the remaining variance: a single `gpt-oss-120b` round took 5.6 s instead of the usual 1.1–1.5 s (the annotate run's round took 1.4 s), and a first-time acknowledgment phrase waits for Polly. Two further highlight runs could not be measured because the AWS login session expired (`Your session has expired. Please reauthenticate.`); the first of them stalled inside the credential refresh before any model request.

Earlier that day, before local finishing: "Zoom in on the centre of this slide." took 4.3 s from end of speech to reply audio, 2.9 s of it two Bedrock rounds; before proof sharing and short spoken answers it was 10.5 s.

These are single runs, not a latency benchmark.

**Not yet proven:** a real microphone and speakers, echo cancellation under room conditions, and a long multi-turn session in the foreground app. Run the companion, press the mic in the top bar and speak.
