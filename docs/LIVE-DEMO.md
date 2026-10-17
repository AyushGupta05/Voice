# Foreground demo

Luma keeps QuPath as the scientific application. Its companion surface is a 420 × 56 px Dynamic-Island-style bar at the top of the screen with four phases:

| Phase | What you see | Driven by |
|---|---|---|
| Idle | Mic glyph, "Hey Luma · tap to talk" (or the connection/setup reason when not ready) | voice off |
| Listening | Animated waveform from your microphone level and the live partial transcript; the mic glyph becomes Stop | `voice_state: listening`, `voice_transcript` |
| Executing | The phrase Luma is saying while it acts ("Detecting cells.") with a shimmer, then the one-sentence result for a few seconds | `text_ack`, `task_state`, `text_result` |
| Speaking | Small animated dots while the reply plays | `voice_speaking` |

Click the status or chevron to expand the details panel: conversation, measured results and action history. Workflow scripts render as monospaced blocks with Copy; class summaries render as a count/percent table. Stop cancels unstarted agent work; completed actions remain. Luma's own cursor flies to what it talks about and outlines matched cells or controls.

Actions execute immediately by default (`LUMA_APPROVALS=auto`); every action is journaled and hierarchy changes take a checkpoint first. `LUMA_APPROVALS=review` restores approval cards.

## Load the latest version safely

1. Save or discard current work and quit QuPath normally. Replacing the extension JAR does not update a running QuPath.
2. Run `./script/build-bridge.sh` and `./script/run-qupath.sh`.
3. Run `./script/build_and_run.sh --verify`. This restarts only this project's companion and its owned gateway.
4. Run `npm run doctor` and `npm run smoke`. The loaded bridge identity and running schemas must match the current source (`/health` → `context.adapterReadiness.status: matches_build`) before testing new operations.

## Opening an image end to end

Say or type: "Open the OS-2 slide" (or give the exact path). QuPath's native image-type dialog may appear; Luma points at the right choice and Apply. Verify the window title and `context.image` before calling it done.

Then try: "Zoom in a little" (instant route), "Highlight the middle of this slide", "Add a tumor annotation on the right".

## Voice

Voice is live on the AWS speech route (Transcribe → OpenAI on Bedrock → Polly), credits only. See [AWS-SPEECH.md](AWS-SPEECH.md) for the latency design and [DEMO.md](DEMO.md) for the verified demo script. Do not label text-only or synthetic-audio tests as live microphone sessions; the user's own mic sessions are confirmed through the `science.luma.companion` audio log category.
