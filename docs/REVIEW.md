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
