# Demonstration guide

## The story

A researcher knows what they want to do. Luma reads the actual selected region and turns that intent into an operation in the existing application. The useful moment is the visual application change, followed by measured results and an explicit review/recovery path.

## Preparation

1. Follow SETUP.md and launch the real QuPath instance and companion.
2. Open the public OS-2 Ki-67 slide. Choose H-DAB image type based on its known source.
3. Select a small region of tissue. Keep the application's image and the companion visible.
4. Connect an MCP client using the provided prompt/skill. The MCP path is usable without a live voice provider.
5. If live voice is still funding-blocked, state that clearly and demonstrate semantic tool control. Do not play a canned response as if it were live inference.

## Suggested live requests

“Focus on this region.” The agent refreshes context and centers the real viewer. The pointer highlights the target without moving the researcher's actual mouse.

“Detect the cells here.” The agent inspects image type, calibration and selected ROI, searches guidance if needed, then proposes explicit watershed parameters. The researcher approves. The agent checks real counts and measurements after execution.

“Only highlight the positive ones.” If classifications exist, this is a display operation. Otherwise the agent proposes a named measurement and explicit threshold for approval, then filters the display. It must not silently invent a positive class or biological threshold.

“How many meet that threshold?” The agent uses actual class counts and states the denominator. It does not count a truncated measurement page as the total population.

“Compare that with the earlier result.” The agent uses a named existing checkpoint, reports actual count changes and offers a recoverable hierarchy change if useful.

“Restore the previous objects.” The researcher reviews the checkpoint and approves. The agent reads context again to verify recovery. Image settings, files and external script effects have separate recovery boundaries.

## Audience-selected tasks

Discover commands in the current installed version before invoking them. Inspect native JavaFX dialog controls after menu dispatch. If a request needs a specialized plugin, search the application knowledge and inspect installed capabilities. A native dialog or an unsupported plugin can require manual researcher work. Do not promise every QuPath feature was pretested.

## Verified fixture

The bounded public-slide sequence in `script/integration.ts` is a development test, not the production demo's only command path. Its measured result is 899 cells, with 49 Positive and 850 Negative at nuclear DAB OD mean >= 0.2. It restores its original empty hierarchy and leaves the source slide untouched.

Do not describe this as a clinically validated Ki67 score. See DATASETS.md for parameters and provenance.

## Pitch outline, about three minutes

- 0:00–0:25: Researchers know their science but must learn each application's operating language.
- 0:25–0:50: Show the existing QuPath interface and a selected region.
- 0:50–1:50: Perform a short real workflow with visible approval and resulting application changes.
- 1:50–2:20: Compare or restore an earlier hierarchy state and explain the recovery boundary.
- 2:20–2:45: Explain the reusable adapter/MCP architecture and searchable application knowledge.
- 2:45–3:00: State what is verified, the live voice funding blocker if still present, and the next researcher-led validation step.
