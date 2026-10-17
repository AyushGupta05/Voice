# Verification and review record

Verified locally on 17 October 2026, Apple Silicon macOS, QuPath 0.7.0 and Temurin 25. This records the foundation milestone; it does not establish scientific validity, live voice or universal plugin coverage.

## Structured review

The final Codex autoreview helper exited 0: **no accepted/actionable findings reported**. It reviewed approval binding, validation, recovery, QuPath API contracts, JavaFX dispatch and the app-owned gateway lifecycle. Review was read-only; verification tests were run separately by the implementation agent.

Actual command, from the repository root on this Mac:

```sh
/Users/ayush/.codex/skills/autoreview/scripts/autoreview \
  --mode local \
  --codex-bin /Applications/ChatGPT.app/Contents/Resources/codex-cli/bin/codex \
  --prompt-file .local/review-context.md \
  --output .local/autoreview-final.md \
  --json-output .local/autoreview-final.json
```

The review prompt/results are private local build evidence. Use the installed helper's current path and the correct dirty/branch/commit target when reviewing later changes; an empty local diff is not a review of committed work.

Across preceding valid review iterations, **24 findings were accepted after checking actual code and pinned QuPath/JavaFX source; none were rejected**. Fixes cover:

- Worker execution and captured analysis/script targets; primary selection, project and image-plane binding.
- Context keys bounded by hashing; full selection counts distinct from previews.
- Deferred UI revalidation, modal ownership, source-editor execution restrictions and selectable-menu behavior.
- Strict detection choices/types, exact numeric parsing and replayable workflow history.
- Native binary hierarchy recovery preserving coordinates, confidence, root data, UUIDs and TMA-grid relationships.
- Safe selection cleanup and refusal of ordinary TMA-core deletion.
- Accurate completion claims and documented recovery boundaries.

A further launch defect was found during real testing and fixed before the clean review: the companion now supervises its gateway rather than relying on a shell background job.

## Tests and proof

| Check | Result and boundary |
|---|---|
| `npm run check` | TypeScript compilation and 9 focused gateway, approval and Realtime protocol tests passed |
| `./script/test-bridge.sh` | Actual QuPath jars: detection choices/types/scientific notation, captured targets, workflow identity, exact hierarchy recovery and captured worker script bindings passed |
| `./script/test-bridge-ui.sh` | Isolated real JavaFX controls: list/table selection and CodeArea source fingerprint changes, check/radio menu behavior passed; no scientific session touched |
| `./script/build_and_run.sh --verify` | Native build, ad-hoc signing, launch and authenticated gateway checks passed |
| Native lifecycle | Owned gateway survived launcher exit; app termination stopped its own child; a stopped gateway recovered and MCP reconnected |
| `npm run smoke` | Actual MCP SDK client through gateway to real QuPath: 23 tools, image metadata, commands, 66 indexed guidance documents and resource |
| Authorized OS-2 fixture | 899 cells, 49 above the chosen nuclear DAB OD mean threshold of 0.2, 850 below, comparison, viewer capture and empty hierarchy/selection restoration passed |
| Selection display | Fixture verified full count 899, preview 100, explicit truncation and 64-character context key; actual native panel visually showed 899 selected |
| Bootstrap | Documented setup reran successfully using verified cached downloads and existing pinned checkouts; not a fresh-machine test |
| Pitch | Seven source slide renders inspected; final PPTX package/import/layout validation passed; no native PowerPoint execution claimed |

The native Start voice control was checked and reports the funding blocker without starting microphone capture or a provider session. Project API/cloud spend is $0. See [capabilities](CAPABILITIES.md), [funding](FUNDING.md) and [dataset provenance](DATASETS.md).

## GPT-Live protocol milestone, 17 October 2026, 02:52 BST

The separate client-delegation transport and provider-injected Responses backend passed structured Codex autoreview with exit 0 and no accepted/actionable findings. The helper used the same command above with `.local/review-live.md`, `.local/autoreview-live.md` and `.local/autoreview-live.json`. No additional review was run after the clean result.

Seven findings from prior phase-two review iterations were verified and accepted: lost cross-delegation results, stale inference after corrections, late audio after interruption, invalidation lost in executor waits, duplicate ownership of corrected requests, large approval output escaping memory trimming, and delayed delegations starting after Stop. The fixes preserve bounded result history, check transcript/application state at dispatch, assign one current delegation owner, suppress stale playback and require explicit host reconciliation for interrupted-session work. See [VOICE.md](VOICE.md) for the recovery policy and remaining integration work.

TypeScript compilation and **36 focused gateway/backend/protocol tests passed**. Actual MCP smoke still reports 23 tools and 66 guidance documents. The display-only public-slide protocol fixture passed: the viewer reached (51100, 27100), and the hierarchy remained unchanged. It uses scripted backend responses and synthetic PCM, with no live AI, microphone, speaker or provider session. Its timing is local glue time only.

The Mac became locked during later native UI checks. Those checks remain deferred pending manual unlock; no lock bypass was attempted. The unexecuted disposable approval-card proposal was cleared by restarting the project's idle owned gateway after confirming that nothing was running. The public slide remained at its empty hierarchy baseline.

Production voice is still disabled and project API/cloud spend remains $0. The protocol review does not establish funded activation, hardware audio quality or scientific validation.

## Contributor and native PCM increment, 17 October 2026, 02:58 BST

Structured autoreview exited 0 clean for the PCM core, setup diagnostics, generated tool reference and MCP checkpoint annotations. One accepted finding was fixed before the clean rerun: checkpoint eviction must be conservatively marked destructive in MCP metadata, as well as non-read-only and non-idempotent. Actual MCP smoke verifies those annotations without creating a checkpoint.

Four offline Swift tests pass for known signed little-endian values, clipping/non-finite samples, malformed/oversized chunks, synthetic signal round-trip and RMS level. The native target compiles with the extracted codec. This is not microphone, speaker, echo, real-time callback or playback-queue testing. Capture startup cleanup is implemented but still needs a hardware lifecycle test.

All 36 gateway/backend/protocol tests, TypeScript compilation and generated-reference consistency pass. The doctor passes on this setup and returns exit 1 with actionable failures for an absent root. It reads local configuration and authenticated observation endpoints without printing tokens or image names. A local Markdown check resolved 63 file/anchor targets. These diagnostics and generated documentation do not replace real application/scientific verification.

## Public CI and scoped launcher, 17 October 2026, 03:04 BST

The reviewed public-only CI workflow was published in 3270a67. Its [first actual run](https://github.com/AyushGupta05/Voice/actions/runs/38993074803) completed successfully: Ubuntu 24.04 ran all 36 gateway tests and reference consistency; macOS 15 ARM64 with Swift 6.1.2 compiled the native source and passed all four PCM tests. Both jobs ran; neither was skipped. These are standard free public-repository runners, with no provider secrets, cache/artifact uploads or deployment resources.

A separate launcher increment then passed structured autoreview with exit 0 clean. It removes broad process-name termination, validates modes first, builds/signs before restart, replaces the binary atomically and scopes normal termination/verification to this project's exact canonical executable path. A macOS disposable compiled-process fixture proves that a same-named process in a different path remains running and repeated stop is harmless. That fixture is added to the next macOS CI run.

Actual `--build-only` and invalid-mode checks preserved the existing companion PID, and authenticated local diagnostics still pass afterward. No actual app restart or foreground UI check was performed in this increment because the Mac remains locked. The helper's stop behavior was tested only on disposable processes; earlier foundation app/gateway lifecycle checks remain separate evidence.
