# Contributor and agent guide

Read README.md, docs/CAPABILITIES.md, docs/INTEGRATION.md and docs/BUILD-JOURNAL.md before changing behaviour or
reporting completion. The product is Penny, a voice companion inspired by Clicky (short, action-first, voice-driven, it
acts immediately), over real scientific applications: the Fidelis workstation (the real Fiji in one persistent JVM),
stand-alone Fiji and QuPath. Do not replace any of them with a mock scientific viewer.

## Non-negotiable project constraints

- No cash-billed API calls. AWS credit limits: $200 overnight / $1,000 total. Follow docs/FUNDING.md. Do not turn a
  documented funding blocker into an automatic paid fallback.
- Observe live image/selection context. Keep exact IDs and actual measured values; never invent scientific findings.
  Scientific behaviour is verified by numbers (pixel hashes, Results doubles, ROI vertices against
  `verification/baselines` and the pinned Fiji), never by appearance. Never weaken or disable a test to go green.
- Data/code actions execute immediately by default (`LUMA_APPROVALS=auto`, the user's decision on 17 Oct 2026). They
  still go through the recorded `Runtime.decide()` path with `decidedBy: "policy"`, an audit record and a checkpoint.
  `LUMA_APPROVALS=review` restores human review cards bound to exact arguments and application state; there is no
  model-callable approval tool. In Fidelis every Penny operation is a plan assessed by Fidelis's own
  `ConfirmationPolicy`: ordinary steps run at once and land in History, explicit plans (macros, AC4+, a changed `black`)
  wait for the researcher's Confirm or spoken "yes" (docs/FIDELIS-PROTOCOL.md).
- Never promise universal undo. Checkpoints and History snapshots do not undo files, image settings or arbitrary code
  effects.
- Preserve unrelated dirty work. Keep dependencies, tokens, slides, logs, caches and `.local` out of Git. Never modify
  the downloaded QuPath or Fiji installations (the Fiji bridge jar copied into `plugins/` is the one exception), the
  Windows registry or system-wide Java settings.
- Use `origin` (Sleep-On-It/Voice) as the primary repository and destination for progress pushes, as requested by Ayush
  on 17 Oct 2026. `publish` (AyushGupta05/Voice) remains the secondary fork. Keep both remote URLs intact and never
  force-push.

## Map

- `packages/gateway`: TypeScript validation, decisions and audit, MCP, knowledge, voice/text transport and the app
  profiles (`src/apps/{qupath,fiji,fidelis}*.ts`).
- `apps/companion`: SwiftPM native macOS UI, pointer, audio and desktop control.
- `adapters/fiji`: Java bridge plugin for stand-alone Fiji; `adapters/qupath`: Java 25 QuPath extension.
- `fidelis-app`: the Fidelis workstation (`sc.fidelis.{engine,ui,nl,speech,penny}`), its JUnit tests and `tools/`
  (build, run, test, fetch_junit). Contract: docs/FIDELIS-ENGINE.md.
- `verification`: reference runner, dumper, comparators, baselines and replay against the pinned Fiji;
  `fixtures`: fixture manifest and synthetic fixtures; `speech`: Fidelis's local Whisper worker; `external`: Fiji and
  speech-model pins.
- `skills/{fiji,fidelis,qupath}`: authored scientific operating guidance.
- `script`: setup, build, launch and read-only smoke checks; `docs/data`: the Fiji command catalogue compiled into the
  Fidelis jar.

## Verify

- Gateway: `npm run check` (TypeScript, focused tests, tool-reference consistency; regenerate with `npm run docs:generate`
  after a schema change). `npm run smoke` needs the gateway and the application and is read-only.
- Fiji bridge: `./script/build-fiji-bridge.sh`, `./script/test-fiji-bridge.sh`. QuPath: `./script/build-bridge.sh`,
  `./script/test-bridge.sh`. Companion: `./script/build_and_run.sh --verify`, `swift test --package-path apps/companion`.
- Fidelis: `python3 -I fidelis-app/tools/build.py`, `python3 -I fidelis-app/tools/test.py [--select-class FQCN]` (one
  live Fiji JVM per checkout at a time; always `--isolated-home` for `run.py`), `python3 -I verification/test_verification.py`.
- Voice flows are checked end to end with `npm run smoke:voice-pipeline -- --authorized-aws-speech --say="..."` against
  the running gateway and application; real microphone sessions are the owner's and are reported as such.

For non-trivial edits, run the configured autoreview helper, inspect advisory findings in the actual code, fix accepted
issues, rerun affected checks and rerun until no accepted actionable findings remain. Distinguish a setup/reviewer
failure from a clean review.

Update documentation when a tool, schema, safety boundary, runtime requirement or verified capability changes. Report
implemented, protocol-tested, real-app-tested and blocked behaviour separately.
