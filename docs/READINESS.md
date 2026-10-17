# Readiness and safe next steps

Snapshot: 17 October 2026, 10:10 BST. The [build journal](BUILD-JOURNAL.md) records later changes; run the diagnostic commands below for the current machine.

Luma operates the existing QuPath application through a separate extension, local gateway, MCP and floating companion. The downloaded original application and upstream source stay intact. The agent has no human approval credential.

## What is proven

| Area | Verified result | Remaining boundary |
|---|---|---|
| Original QuPath | All 553 stock bundle entries / 389 files matched fresh extraction of the verified official package; original signature and clean source pins passed | Separate external-JDK/extension launcher is a development bundle, not a notarized standalone release |
| Scientific execution | Actual QuPath watershed detection, measured classification, comparison and empty hierarchy restoration | 899 cells/49 Positive/850 Negative describe one chosen public fixture, not validated segmentation or biology |
| Actual OpenAI agent | Fixed public scientific workflow completed in 21.5 s / eight requests; prior stopped attempt is retained | Five approvals were simulated only under explicit fixture authorization; ordinary approvals stay human |
| MCP and application awareness | Normal application safely reloaded; actual stdio SDK smoke matches all 32 running schemas and loaded Java build identity; 67 knowledge documents | Each scientific/UI operation still needs its own outcome verification |
| GeoJSON import | Isolated actual QuPathGUI → MCP test passes exact UUIDs/classes/geometry/planes/scores, changed-file/duplicate rejection, comparison and empty baseline restoration | Test-only socket wiring; native file opening, normal extension bootstrap and foreground controls remain unverified |
| Reliability | 102 offline gateway tests and 14 native tests pass, with native build/signing and clean focused review after accepted fixes | Native tests include four PCM, four capture-lifecycle and six status/layout contracts; no hardware audio proof |
| Compact bar | Actual native bar and expandable conversation inspected; real typed request reached OpenAI, the human approved, and actual OS-2 image metadata appeared | QuPath's native image-type dialog remains open; complete dispatch/foreground checks, plots and audio still need verification |
| Pitch | Seven editable slides, actual app images, final package checks and every slide visually inspected | Live voice is visibly disclosed as pending |

The actual read-only MCP smoke reports source agreement separately. It now reports true with 32 source/running tools after the normal save/quit/reload. The doctor passes loaded-build/schema checks and local readiness, while correctly reporting voice unready.

## Reload the real application safely

The user confirmed the Mac was unlocked this morning. The prior public OS-2 state was saved through QuPath's native save dialog to a separate private recovery file before normal quit; the source slide was not overwritten. The new normal QuPath and companion-owned gateway loaded successfully. Use these steps again for future updates:

1. Manually unlock the Mac. Finish, save or explicitly discard work through QuPath's normal interface; resolve any pending/running scientific actions. Do not force-close an app to update an extension.
2. Quit this development instance of QuPath and Luma normally. Luma stops only a gateway it owns. An externally started gateway must be handled by its owner after its work has finished.
3. Rebuild and launch the separate development applications:

```sh
./script/build-bridge.sh
./script/run-qupath.sh
./script/build_and_run.sh --verify
npm run doctor
npm run smoke
```

The diagnostic/smoke commands are read-only and make no model call. Check the actual loaded adapter identity, schema agreement and desired open image before requesting an operation. New Java classes are loaded at QuPath startup; replacing a JAR on disk does not update a running application. Current launcher settings use QuPath's supported separate preference node; earlier running instances did not establish that isolation. See [preservation details](ARCHITECTURE.md#preserving-qupath).

The compact bar and native text submission have now been inspected in the foreground. The human approved the real public-slide opening proposal; actual OS-2 dimensions/name are present and the native image-type prompt is waiting. Dispatch remains queued while that modal is open, so full completion is not yet claimed. Measurement plots/navigation need their own checks. The separately authorized synthetic import test passed at 07:34 BST in its own disposable QuPathGUI instance with two fixture-only decisions; it restored the empty baseline and stopped safely. Ordinary production approvals are unchanged. See [foreground demo](LIVE-DEMO.md), [import test boundaries](SYNTHETIC-IMPORT-TEST.md) and [task coverage](SOFTWARE-COVERAGE.md).

## Finish live voice

Production GPT-Live remains disabled. An AWS-credit-covered OpenAI live-voice service/model/SKU has not been established. The existing Bedrock OpenAI text model does not fund the separate voice frontend.

A supported funded route must be verified before constructing its provider client. Then complete native capture/playback wiring, session ownership/interruption recovery and a bounded real microphone/public-slide session with final usage accounting. Local protocol/PCM success is not live voice evidence. Do not substitute a different provider or cash-billed OpenAI endpoint silently. See [funding](FUNDING.md) and [voice integration](VOICE.md).

Across existing paid fixtures, 26 completed requests estimate $0.02179905 in tokens; all $26 conservative attempt reserves remain under the $200 pre-wake cap. Invoice/credit settlement is unreconciled. Preserve the private ledger and receipts; never reset or refund them to manufacture headroom. No further unchanged paid smoke is needed.

## Contribute without cloud credentials

Start with `npm ci` and `npm run check`. They use offline/synthetic fixtures and local test servers, with no provider request. Read [the contributor guide](CONTRIBUTING.md) for native and actual-library checks. The [tool reference](TOOL-REFERENCE.md) and [complete schema registry](tool-schemas.json) are generated together from the executable definitions, so CI can catch documentation drift. Use [the operating skill](../skills/qupath/SKILL.md), pinned API lookup and live command discovery when adding a task.

Public milestone evidence: [preservation](pitch/qupath-preservation-evidence.json), [scientific agent](SCIENTIFIC-AGENT-TEST.md), [loaded bridge](pitch/bridge-readiness-evidence.json), [live MCP catalog](pitch/mcp-live-catalog-evidence.json), [synthetic import](pitch/synthetic-import-evidence.json), [editable pitch](pitch/README.md). These are scoped historical records, not a claim of universal plugin support.
