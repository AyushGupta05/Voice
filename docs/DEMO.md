# Demonstration guide

## The story

A researcher keeps using QuPath exactly as before. They talk to Luma the way they'd talk to a colleague sitting next to them, and Luma acts in the real application: it moves the viewer, points at things with its own cursor, proposes analyses for approval and reads back real numbers. Nothing is mocked; the counts come from QuPath.

## Preparation

1. Follow [SETUP.md](SETUP.md); launch the development QuPath and Luma (`./script/run-qupath.sh`, `./script/build_and_run.sh --verify`).
2. Enable voice once: `npm run enable:speech -- --authorized-aws-speech` (read-only AWS checks), then restart Luma. `/health` reports `voice.enabled: true`.
3. Ask Luma to open the public OS-2 slide ("Open the OS-2 slide") and choose **Brightfield H-DAB** in QuPath's native image-type dialog. Luma points at the Apply button.
4. Click the mic in Luma's top bar. The bar says "Voice on"; Luma's cursor rides beside your pointer while it listens.

## The three-minute flow (verified 17 October 2026)

Everything below ran end to end on this slide. Times are from the end of your sentence.

| You say | What happens | Measured |
|---|---|---|
| "Zoom in a little." | Instant route, no model round. Luma says "Zooming in." as the viewer moves | voice 0.4 s, action 8 ms after transcript |
| "Highlight the middle of this slide." | Agent calls `qupath_highlight`; cursor flies there with a label | voice ~1.7 s |
| "Create a small square region in the centre of this slide, about 2000 pixels wide." | Luma says "Drawing the region." and the annotation appears | action ~2.4 s after transcript |
| "Select that region and detect the cells in it." | "Detecting cells." Calibrated watershed parameters (µm) run at once; a checkpoint is taken first | 294 cells |
| "Mark the cells in this region with a DAB mean above 0.2 as positive." | "Classifying." Agent matches "DAB mean" to the exact `Cell: DAB OD mean` | 19 Positive / 275 Negative |
| "Show me the positive cells." | Display only: each visible positive cell is outlined, cursor points at the group; Luma: "Here are the 19 positive cells." | voice ~3.9 s |
| "How many are positive?" | Read-only answer from real class counts | one model round |
| "Undo that." / "Compare with the earlier result." | Checkpoint compare / restore of the object hierarchy | |

You can interrupt Luma at any time by speaking; it stops talking and stops the running task. Actions execute immediately by default (`LUMA_APPROVALS=auto`); every one is journaled and hierarchy changes get a checkpoint first. Set `LUMA_APPROVALS=review` before launching if you want approval cards for data changes.

## Taking audience requests

Luma has 33 tools covering context, objects, measurements, navigation, display, highlights, analysis, checkpoints, live menus, JavaFX dialogs and reviewed Groovy. For anything unfamiliar it searches the bundled QuPath documentation and the pinned QuPath source before acting. Good audience prompts: "What stains does this image have?", "Show me the biggest cell", "Hide the annotations", "What's the mean nucleus area here?", "Open the Measure menu". Data changes and native dialogs always come back as approval cards. If something needs a plugin that isn't installed, Luma says so.

## What to say honestly

- Voice is AWS Transcribe → OpenAI `gpt-oss-120b` on Bedrock → Amazon Polly, paid from AWS credits. It is not GPT-Live; no credit-covered realtime endpoint exists.
- Counts verify that the software did what was asked. They are not validated segmentation or a clinical Ki-67 score. See [DATASETS.md](DATASETS.md).
- Checkpoints restore the object hierarchy only, not files or image settings.

## Pitch outline, about three minutes

- 0:00–0:25: Researchers know their science; every tool makes them learn its operating language instead.
- 0:25–0:50: Show QuPath as it is. Click the mic.
- 0:50–2:10: The flow above: zoom, region, detect, classify, "show me the positive cells".
- 2:10–2:35: Ask the audience for a request.
- 2:35–3:00: The architecture: one voice layer, one audited action model with checkpoints, app-specific adapters over MCP. QuPath today; any scientific tool with an API next.
