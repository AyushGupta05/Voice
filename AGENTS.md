# Luma contributor and agent guide

Read README.md, docs/CAPABILITIES.md and docs/BUILD-JOURNAL.md before changing behavior or reporting completion. The product is a real QuPath companion inspired by Clicky, with short action-first interaction and visible approvals. Do not replace QuPath with a mock scientific viewer.

## Non-negotiable project constraints

- No cash-billed API calls. AWS credit limits: $200 overnight / $1,000 total. Follow docs/FUNDING.md. Do not turn a documented funding blocker into an automatic paid fallback.
- Observe live image/selection context. Keep exact IDs and actual measured values; never invent scientific findings.
- Risky operations require human approval. No model approval tool. Bind approval to exact arguments, image, selection, hierarchy and relevant dialog state.
- Never promise universal undo. Hierarchy checkpoints do not undo files, image settings or arbitrary code effects.
- Preserve unrelated dirty work. Keep dependencies, tokens, slides, logs and caches out of Git.
- Use the approved `publish` remote (AyushGupta05/Voice) for progress pushes. `origin` is the original read-only Sleep-On-It/Voice repository; do not silently change it.

## Map

- `adapters/qupath`: Java 25 extension, live application state and JavaFX actions.
- `packages/gateway`: TypeScript validation, approvals, MCP, knowledge and provider transport.
- `apps/companion`: SwiftPM native macOS UI and pointer/audio components.
- `skills/qupath`: authored scientific operating guidance.
- `script`: setup, build, launch and read-only smoke checks.

## Verify

Run `npm run check`, `./script/build-bridge.sh` and `./script/build_and_run.sh --verify` as appropriate. `npm run smoke` requires the gateway and QuPath and is read-only. Mocked tests can exercise approvals without touching research data. Real mutation tests require explicit test-scope authorization; do not programmatically approve production actions.

For non-trivial edits, run the configured autoreview helper, inspect advisory findings in the actual code, fix accepted issues, rerun affected checks and rerun until no accepted actionable findings remain. Distinguish a setup/reviewer failure from a clean review. There is no Crabbox config in this repository at foundation time.

Update documentation when a tool, schema, safety boundary, runtime requirement or verified capability changes. Report implemented, protocol-tested, real-app-tested and blocked behavior separately.
