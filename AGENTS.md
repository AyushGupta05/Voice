# Penny contributor and agent guide

Read README.md, docs/CAPABILITIES.md and docs/BUILD-JOURNAL.md before changing behavior or reporting completion. The product is a real QuPath companion inspired by Clicky: short, action-first, voice-driven, and it acts immediately. Do not replace QuPath with a mock scientific viewer.

## Non-negotiable project constraints

- No cash-billed API calls. AWS credit limits: $200 overnight / $1,000 total. Follow docs/FUNDING.md. Do not turn a documented funding blocker into an automatic paid fallback.
- Observe live image/selection context. Keep exact IDs and actual measured values; never invent scientific findings.
- Data/code actions execute immediately by default (`LUMA_APPROVALS=auto`, the user's decision on 17 Oct 2026: no available action is damaging enough to need a card). They still go through the recorded `Runtime.decide()` path with `decidedBy: "policy"`, an audit record and a hierarchy checkpoint. `LUMA_APPROVALS=review` restores human review cards bound to exact arguments, image, selection, hierarchy and dialog state; there is still no model-callable approval tool.
- Never promise universal undo. Hierarchy checkpoints do not undo files, image settings or arbitrary code effects.
- Preserve unrelated dirty work. Keep dependencies, tokens, slides, logs and caches out of Git.
- Use `origin` (Sleep-On-It/Voice) as the primary repository and destination for progress pushes, as explicitly requested by Ayush on 17 Oct 2026. `publish` (AyushGupta05/Voice) remains the secondary fork. Keep both remote URLs intact and do not force-push.

## Map

- `adapters/qupath`: Java 25 extension, live application state and JavaFX actions.
- `packages/gateway`: TypeScript validation, approvals, MCP, knowledge and provider transport.
- `apps/companion`: SwiftPM native macOS UI and pointer/audio components.
- `skills/qupath`: authored scientific operating guidance.
- `script`: setup, build, launch and read-only smoke checks.

## Verify

Run `npm run check`, `./script/build-bridge.sh` and `./script/build_and_run.sh --verify` as appropriate. `npm run smoke` requires the gateway and QuPath and is read-only. Mocked tests exercise both the review and auto policies without touching research data. Voice flows are checked end to end with `npm run smoke:voice-pipeline -- --authorized-aws-speech --say="..."` against the running gateway and QuPath.

For non-trivial edits, run the configured autoreview helper, inspect advisory findings in the actual code, fix accepted issues, rerun affected checks and rerun until no accepted actionable findings remain. Distinguish a setup/reviewer failure from a clean review. There is no Crabbox config in this repository at foundation time.

Update documentation when a tool, schema, safety boundary, runtime requirement or verified capability changes. Report implemented, protocol-tested, real-app-tested and blocked behavior separately.
