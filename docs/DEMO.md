# Demonstration guide

## The story

A researcher keeps using QuPath exactly as before. They talk to Penny the way they'd talk to a colleague sitting next to them, and Penny acts in the real application: it moves the viewer, points at things with its own cursor, proposes analyses for approval and reads back real numbers. Nothing is mocked; the counts come from QuPath.

## Preparation

1. Follow [SETUP.md](SETUP.md); launch the development QuPath and Penny (`./script/run-qupath.sh`, `./script/build_and_run.sh --verify`).
2. Enable voice once: `npm run enable:speech -- --authorized-aws-speech` (read-only AWS checks), then restart Penny. `/health` reports `voice.enabled: true`.
3. Ask Penny to open the public OS-2 slide ("Open the OS-2 slide") and choose **Brightfield H-DAB** in QuPath's native image-type dialog. Penny points at the Apply button.
4. Click the mic in Penny's top bar. The bar says "Voice on"; Penny's cursor rides beside your pointer while it listens.

## The three-minute flow (verified 17 October 2026)

Everything below ran end to end on this slide. Times are from the end of your sentence.

| You say | What happens | Measured |
|---|---|---|
| "Open the OS-2 slide." | Finds the file by name, opens it, and settles QuPath's "Set image type" dialog itself (H-DAB) | voice ~3 s |
| "Zoom in a little." | Instant route, no model round. Penny says "Zooming in." as the viewer moves | voice 0.4 s, action 8 ms after transcript |
| "Create a small tumour region in the centre of the slide and analyse the cells in it." | "Drawing the region." then detection and threshold classification run in one request | 294 cells, 23 positive |
| "Show me the positive cells." | Each positive cell is outlined, the cursor points at the group: "Here: positive cells." | voice ~3.9 s |
| "What percentage of the cells are positive?" | QuPath-computed class summary: "7.8% of the cells are positive." | voice ~3.9 s |
| "That threshold looks too sensitive, make it stricter." | "Classifying." Same measurement, threshold ×1.5: "18 of 294 cells are positive (6%)." | final sentence 3.9 s |
| "Actually, go back to the previous threshold." | "Restoring." Checkpoint before the last change restored: 23 positive again | voice ~4 s |
| "Save this procedure as a workflow so I can use it on other samples." | "Saving the workflow." QuPath's history (image type, stains, detection, classification) written as Groovy to `.local/workflows/` | voice ~3.8 s |
| "Add a tumor annotation on the right." | One `qupath_annotate` with class Tumor, placed from the viewer's visible area | action 2.6 s |

Start a request with "Penny" (or "Hey Penny"); once you are talking to it, follow-ups within 30 seconds need no name, and short commands ("zoom in", "undo that", "stop") always work. Overheard conversation is ignored. You can interrupt Penny at any time by speaking to it; it stops talking and stops the running task. Actions execute immediately by default (`LUMA_APPROVALS=auto`); every one is journaled and hierarchy changes get a checkpoint first. Set `LUMA_APPROVALS=review` before launching if you want approval cards for data changes.

## Taking audience requests

Penny has 33 tools covering context, objects, measurements, navigation, display, highlights, analysis, checkpoints, live menus, JavaFX dialogs and reviewed Groovy. For anything unfamiliar it searches the bundled QuPath documentation and the pinned QuPath source before acting. Good audience prompts: "What stains does this image have?", "Show me the biggest cell", "Hide the annotations", "What's the mean nucleus area here?", "Open the Measure menu". Data changes and native dialogs always come back as approval cards. If something needs a plugin that isn't installed, Penny says so.

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
