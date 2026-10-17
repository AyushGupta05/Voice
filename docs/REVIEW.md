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

## AWS-credit text adapter, 17 October 2026, 03:36 BST

Structured review exited 0 clean after three accepted findings were fixed: SDK bearer-token authentication could bypass the verified account; an image switch could escape the public fixture; and session-wide context could expose unrelated checkpoints/project state. Actual SDK middleware tests now prove SigV4-only account binding even with conflicting bearer configuration. The smoke uses a captured context identity and allowlisted initial/later model observations, filters focus output and strips unrelated session diagnostics. Configuration expiry was also checked at preflight completion and dispatch.

All **55 focused tests**, TypeScript compilation and schema-reference consistency pass. The real corrected Node SDK preflight verified eligible credits, existing agreement/access and current regional token prices without inference.

After the clean review, explicit activation and the bounded real model smoke passed. OpenAI GPT-OSS-120B through Bedrock `InvokeModel` made **three actual inference requests**, read allowed public OS-2 context, executed exact focus at (51100, 27100), and returned a confirmation grounded in the result. The hierarchy remained empty; no approval proposal or scientific mutation was possible through this fixture. The end-to-end text fixture took 5444 ms, including credit checks and tool calls; this is not voice latency.

Usage receipts estimate **$0.00140025 in tokens**, while the durable ledger retains **$3 of reserves**. Reservations are never automatically refunded and the project cap remains $200. AWS billing/credit settlement is not reconciled. No new subscription, API key or infrastructure was created, and no direct OpenAI billing route was used. The [public evidence](pitch/text-model-evidence.json) contains no account/credit identifiers or balances. GPT-Live and vision were not used; production companion text wiring remains the next increment.

## Companion text route, 17 October 2026, 03:58 BST

The required structured local autoreview exited 0 clean after three confirmed findings were fixed: failed HTTP health polling hid Stop while the WebSocket task continued; a fresh smoke installation lacked its first budget ledger; and event-channel reconnection could consume a typed draft before delivery. Task state now follows socket/task events, absent-only fixture ledgers count as empty, and native sends require the current event connection while retaining drafts until successful transport send. Failed/ambiguous delivery preserves the draft and asks for session inspection; there is no automatic retry.

`npm run check` passed **60 focused tests**, including full text results, follow-up history, late-answer suppression after Stop, supersession ownership, pending human decisions and the actual authenticated WebSocket protocol with a synthetic client. The native target compiled and **four PCM tests passed**. A normal exact-path development restart loaded the new app-owned gateway, which reports text configured, voice disabled and no pending/running actions. The Mac remains locked, so this does not establish foreground usability of the new native controls or audio hardware.

After the clean review, `npm run smoke:companion-text -- --public-fixture` passed through production conversation routing in an isolated authenticated loopback server, the actual credit-checked OpenAI text client, restricted public-slide Runtime and real QuPath. Three actual requests moved the viewer to (51100, 27100), downsample 3, and returned its confirmed center. The hierarchy stayed empty and no approval proposals were possible. This took 6079 ms; it is not voice or general latency evidence. See [the fixture record](pitch/companion-text-evidence.json).

Across both real text fixtures there are **six inference requests**, **$0.0028011 estimated token usage** and **$6 retained conservative reserves**. AWS billing/credit settlement remains unreconciled. Production GPT-Live is still disabled.

## Broader task coverage, 17 October 2026, 04:32 BST

Added seven tools (30 total), full-scope native plots, and exact choice/tab navigation. Structured local autoreview exited 0 clean after six findings were confirmed in the actual pinned source and fixed: pre-deserialization class resource limits; stable external/missing source IDs; raw numeric coordinates; visible class-summary truncation/full-source inspection; image-initialized fluorescence defaults; and original floating-precision geometry validation before native precision reduction. Imports reject invalid original topology, original out-of-image bounds, duplicate IDs and lost components/holes. Native review discloses precision changes. A strict UTF-8/depth-bounded parser and immutable off-JavaFX numeric summaries preserve explicit input and responsiveness boundaries.

`npm run check` passed 62 tests and all 30 generated schemas. Actual-library synthetic contracts and isolated JavaFX control tests passed, including malformed-coordinate/class/ID cases and fluorescence thresholds 25/100 for 8/16-bit inputs. Native compilation and four PCM tests passed. The source/scope-labeled chart was rendered offscreen using explicitly synthetic values and visually inspected; it does not establish foreground app interaction. No new provider request was needed.

See [task coverage](SOFTWARE-COVERAGE.md), [the operating guide](../skills/qupath/references/import-and-explore.md) and [the bounded evidence record](pitch/breadth-evidence.json). The running QuPath/gateway have not been reloaded for this increment; new native import/navigation/dialog paths remain pending manual unlock and a separate disposable-instance authorization. Earlier real 899-cell and actual OpenAI text/view tests prove only their recorded operations.

## Pinned API reference and actual model grounding, 17 October 2026, 04:54 BST

Added two read-only reference tools (32 total), indexing only ordinary committed Java blobs from the configured QuPath source commit. The actual corpus is 879 files. Each blob ID and content SHA256 is checked; working-tree edits, symlinks, arbitrary source paths and replacement refs cannot alter retrieval. Missing/invalid sources disable lookup with setup guidance. Local subprocess/output/excerpt bounds are explicit and no source code is executed.

`npm run check` passed **69 focused tests** and schema consistency. Synthetic Git fixtures verify pinned bytes, source pin type, line/hash bounds, traversal/symlink exclusion, replacement-ref isolation and missing-source behavior. An authenticated gateway test retrieves references without a QuPath bridge. Actual read-only source lookup found the public `QP.loadPixelClassifier` declaration at line 4905 and expanded it from the configured commit. Its latest local query took 25 ms; this is not scientific-analysis or voice latency.

Structured autoreview found one accepted false-positive in the prepared model test. It now requires matching successful search metadata, the exact declaration line from the expected source/commit/hash, and a structured answer assigning the actual return/parameter types. Negative tests reject unrelated reads, missing search results, wrong hashes and reversed claims. The final helper run exited 0 clean.

After review, the separate real AWS-credit model fixture passed via production companion WebSocket routing. Four actual requests searched/read the exact pinned declaration and correctly assigned `PixelClassifier` / `String`. No application action, approval proposal, code execution or viewer/hierarchy change was permitted. The run took 9457 ms including credit preflight and inference. See [the actual model record](pitch/api-model-evidence.json).

Cumulative receipts now contain ten completed requests, **$0.0059721 estimated token usage** and **$10 retained conservative reserves** under the unchanged $200 cap. Invoice/credit settlement remains unreconciled. New native import/navigation flows still await manual unlock and the separate disposable-instance approval; GPT-Live remains disabled.

The editable pitch was refreshed to v6 with current coverage/evidence notes. Finalization/package/layout/font/import checks passed; all seven final slide pixels match inspected renders. SHA256: `626b1d32f6b4905965f5c63165894654e0f94c7fd1ac22faf6ef2107ddcf3216`. Static content/evidence-note refresh after the real test changed no runtime behavior. Drafts/reports remain private.

## QuPath preservation, 17 October 2026, 05:22 BST

The user's original-software requirement now has an installed-artifact checker, `npm run verify:qupath`. The actual pinned download SHA256 passed; all **553 stock-bundle entries / 389 regular files** matched fresh temporary extraction, and the original full macOS signature passed. Both pinned upstream checkouts are clean, with exceptional Git index flags explicitly refused. No source checkout, original app or research file was repaired/reset.

The separate prepared launcher preserves **181 original classpath entries**, including **165 top-level JARs and 16 bundled Javadoc JARs**, plus the stock hidden package marker. It adds the extension, its own metadata and the supported QuPath preference-node override. Its executable code matches after signature removal on temporary copies; its copied executable signature passes with external resource checking excluded. External links still make this a development launcher, not a strictly sealed/notarized distributable.

Structured review accepted four confirmed findings across iterations: the macOS Java preference-root property was ineffective; exceptional Git index flags could hide dirty files; nested destination links could redirect packaging writes into stock files; and nested Javadoc resources were missing. All were fixed. Actual artifact verification also caught the hidden package marker omitted by Bash globbing; the final fix includes it. The final helper (`.local/autoreview-preservation-shipping.*`) exited 0 clean. No further review was run afterward.

**Seven portable preservation tests and one disposable build test pass.** They cover hidden Git changes, additions/removals/link changes, real shell refusal of interior symlinks/hardlinks, no repair on failure, and clean atomic JAR replacement. Actual-library headless contracts pass, including the real pinned QuPath preference override with an entirely memory-only preference store. The JavaFX control rerun stalled at startup because no Screen was available; only that uniquely identified disposable process was terminated. It is not a passing UI check. Its launcher now has a 45-second outer timeout.

`dist/QuPathLumaPrepared.app` was built and verified without launching it. The default launcher remains running with its older configuration; its current preservation check correctly refuses to certify the old preference setup. It needs normal save/quit/rebuild/relaunch to adopt the corrected settings isolation and documentation resources. Existing user preferences have not been reset or migrated. The actual packaging guard refused the confirmed-running default launcher before any write. The existing authenticated session still reports an empty hierarchy/selection, no pending/running actions and its older 23-tool gateway. No new inference/provider call was made. See [public preservation evidence](pitch/qupath-preservation-evidence.json).

## QuPath startup reuse, 17 October 2026, 05:27 BST

Published preservation commit `1d68709`; its [actual public CI run](https://github.com/AyushGupta05/Voice/actions/runs/39002373083) passed both Linux and macOS jobs, including the new preservation/build fixtures and existing 69 gateway tests/native PCM checks.

A focused follow-up makes `run-qupath.sh` reuse the exact running default instance before changing any token/artifact. Inventory errors stop startup. After normal exit it refreshes the separate launcher before opening it; changed Java source still needs an explicit build. The portable startup fixture passes for reuse and inventory-error cases with unchanged filesystem, token, JAR bytes and inode. The actual launch script returned “already running” against the existing project instance without opening a second app. Structured autoreview exited 0 clean without findings (`.local/autoreview-qupath-startup.*`). No app restart, provider request or scientific mutation occurred. Documentation also corrects the old voice-guide statement that no production provider adapter existed: the working credit-checked text adapter is separate from the still-disabled GPT-Live frontend.

## Fresh context in the backend, 17 October 2026, 05:32 BST

The startup follow-up `becee1d` passed [both public CI jobs](https://github.com/AyushGupta05/Voice/actions/runs/39002624890). A further instruction clarification tells the backend model that the host already refreshed `qupath_context` before its request and that `qupathObservation` is current. Tool availability, exact dispatch-context guards, interruption handling and human approvals are unchanged. TypeScript compilation, schema-reference consistency and **33 focused backend/runtime/text-conversation tests pass**. Structured autoreview exited 0 clean without findings (`.local/autoreview-fresh-context.*`).

After clean review, the same restricted public-slide companion WebSocket fixture and unchanged user message passed with **two actual model requests / 4665 ms**, versus the earlier single run's three / 6079 ms. It focused (51100, 27100), downsample 3, left the hierarchy unchanged and created no proposal. This is two individual text-only observations, not a latency benchmark, native foreground check or live voice. The context tool remains available; no claim is made that every redundant context read disappears. [Public evidence](pitch/companion-text-fresh-context-evidence.json) preserves the original baseline separately.

This added $0.0011169 estimated token usage and $2 retained reservations. The ledger and all twelve completed receipts agree: **$0.007089 cumulative token estimate / $12 conservative reserves**, with invoice/credit settlement unreconciled. No infrastructure, model agreement, direct billing fallback or production voice activation was used. The normal already-running app/gateway were not restarted for this test.

## Actual scientific agent, 17 October 2026, 05:54 BST

The context-refresh milestone f4a5131 passed [public CI](https://github.com/AyushGupta05/Voice/actions/runs/39002982800). Added a test-only authority wrapper and real-model driver for the exact previously authorized disposable OS-2 sequence. No synthetic import authorization was assumed. The model receives only public context and the fixed tools; the trusted harness simulates five decisions in a fresh private Runtime, while ordinary approvals remain human-owned. Started native steps cannot replay, comparison/restore target only the owned baseline, and source image/files are not written.

Two structured-review findings were confirmed and fixed: post-error observation could absorb independent changes for cleanup, and errors outside the native mutation body could expose audit/context paths to later model requests. Failed dispatch now preserves prior ownership; successful mutation state must match exact counts/ROI and native completion context before adoption. Unknown partial states refuse cleanup. Errors across the complete Adapter/Runtime boundaries are sanitized, with bounded private diagnostics and cancellation preserved. Tests reproduce a during-dispatch context rejection and audit-path error in an actual subsequent fake-backend request. Final review of corrective guidance (`.local/autoreview-analysis-fixture-guidance.*`) exited 0 clean without findings. **77 gateway tests**, TypeScript compilation and all 32 generated schemas pass.

The first real attempt detected 899 cells but did not submit valid later steps. It returned null for unfinished counts, failed the proof and restored the known empty baseline through the trusted cleanup path. Its six completed requests/$6 reserves remain counted. Improved safe errors now supply only the canonical public next step; execution scope was not broadened. The subsequent actual OpenAI run corrected one existing-class-filter misuse and completed all six operations in **21,471 ms / eight requests**, with five scoped simulated approvals. Its structured 899 / 49 / 850 result and restored baseline matched independent QuPath observations. This proves one predefined software workflow, not biological validity, general autonomy, foreground UX or voice.

The actual original-app preservation checker passed again afterward. Both attempts added $0.01471005 estimated tokens and $14 retained reserves. Cumulative receipts and ledger agree at **26 completed requests / $0.02179905 estimate / $26 reserves**, under the unchanged $200 cap; invoice/credit settlement remains unreconciled. No new cloud resource, model agreement or cash fallback. See [the scientific test guide and evidence](SCIENTIFIC-AGENT-TEST.md). Normal app/gateway retain their previous loaded code; native/import checks remain deferred.

## Classification candidate review, 17 October 2026, 06:05 BST

Scientific-agent commit 1312895 passed [both public CI jobs](https://github.com/AyushGupta05/Voice/actions/runs/39004463265). Its real run exposed a tool-parameter ambiguity: `classification: Positive` filters existing Positive objects; it does not choose an output label. Schemas/operating guidance now make that distinction explicit. The detection-only threshold tool rejects annotation kind, matching its existing native behavior.

The Runtime now reads the actual scoped eligible-detection total before creating a classification proposal. Empty/unverifiable totals fail without approval; nonempty proposals show count, measurement, threshold, optional existing-class filter and assigned Positive/Negative labels. No filter is automatically removed. Validated arguments are copied before asynchronous observations, preventing caller changes from making a preview disagree with reviewed parameters. Existing context-bound human execution remains unchanged. The fixed scientific test adapter supports only the exact internal count query and redacts raw object rows.

**80 offline tests**, TypeScript compilation and generated schema consistency pass. Added contracts cover zero/malformed totals, effective detection kinds, previewed human decisions/stale context, and argument edits while the count query is pending. Structured autoreview exited 0 clean without findings (`.local/autoreview-classification-preview.*`). After clean review, the [actual empty public fixture check](pitch/classification-preview-evidence.json) passed: existing-class filter refused, zero proposals, same context key, no provider call or scientific mutation. Nonempty card content has contract coverage; foreground native review still awaits manual unlock. No paid scientific workflow was repeated and totals remain $0.02179905 estimated tokens / $26 reserves, settlement unreconciled.

## Loaded extension readiness, 17 October 2026, 06:24 BST

Classification preview c51ebcc passed [public CI](https://github.com/AyushGupta05/Voice/actions/runs/39004999262). The bridge now caches adapter 0.2.0/protocol/source-input digest/28 declared operations at startup and includes that identity in context and authenticated health, retaining the original QuPath version and flat adapterVersion field. Builds compile an immutable snapshot of regular source/resource inputs, stamp its digest, and record the completed JAR hash. This is separate from the original QuPath app/source, which is unchanged.

Two accepted review findings were fixed: pathname stat/read races and nonregular files could defeat diagnostic bounds; explicit fingerprint files and ancestor directories could bypass linked-input refusal. Reads now validate actual nonblocking regular-file descriptors and consume at most the limit plus one byte. Bounded child fixtures verify FIFO/device refusals for manifest and JAR. The snapshot validates the whole input set and ancestors before copying. Builder fixtures verify failed-build preservation, compiler input stability during a working-file edit, and source/build/helper/manifest/main/ancestor link refusal.

**84 offline gateway tests**, schema consistency, portable builder contracts and actual-library headless contracts pass. The new Java contract verifies the packaged stamp, cached immutable identity and operation protocol. Final structured autoreview exited 0 clean (`.local/autoreview-bridge-readiness-final.*`). No further review was run after the clean result.

The [actual read-only check](pitch/bridge-readiness-evidence.json) correctly reports `restart_required` for the still-running legacy extension, while confirming original QuPath 0.7.0 and empty hierarchy/selection. Source doctor also reports the older gateway schema. No app was restarted, data changed, provider invoked or funding gate altered. Matching/current/stale metadata cases have synthetic contracts; the new extension still needs normal save/quit/relaunch for a live matching identity check. These diagnostics compare completed source identity, local artifact integrity and declarations; they do not attest loaded binary bytes, unbuilt edits or scientific behavior.

## Editable pitch v7, 17 October 2026, 06:33 BST

Refreshed the seven-slide editable pitch around the actual OpenAI scientific workflow. It preserves the charcoal/mint/Arial design and original public QuPath images. The visual proof now states 899 detected cells, 49 above the chosen DAB threshold, and 21.5 seconds with explicitly simulated test approvals. Notes disclose the earlier screenshot provenance, fixed recipe, initial stopped attempt, normal human approvals, unvalidated segmentation/biology and remaining live-voice/native-flow work. Current cumulative 26 requests/$0.02179905 estimated tokens/$26 reserves appear in dated notes with settlement unreconciled.

Final artifact package/layout/font/import checks pass: seven slides, zero findings/warnings, native editable text. All seven final PPTX slides were re-imported/rendered and individually inspected; RGB pixels match the inspected source renders. No native PowerPoint execution is claimed. Focused source/content autoreview exited 0 clean (`.local/autoreview-pitch-v7.*`). Final SHA256 `3d4fbce6d754177b180a9c136d17070dd6d24e0d429f92123c1d4113c0ce65d6`, 1,706,955 bytes. Build drafts/receipts stay private; v3/v4/v6 remain unchanged. No inference or app action was needed for this artifact refresh.

## Live MCP discovery, 17 October 2026, 06:42 BST

MCP now discovers the authenticated running gateway's exact catalog instead of advertising newer source-only tools. Calls use that gateway's own Runtime validation, preserving approval/context binding and the separate human credential. No fallback invents a catalog when discovery fails; local guide/prompt explain their potentially newer build. Cached clients need rediscovery/reconnection after gateway upgrades.

All89 offline gateway tests, compilation and schema consistency pass. Five new real-SDK/in-memory contracts use an isolated synthetic HTTP gateway with deliberately older schemas: exact discovery, rediscovery, legacy arguments, rejected invalid calls, malformed definitions, checkpoint annotations, guide notice and agent-only routing/credentials. Focused autoreview exited0 clean. Actual read-only SDK smoke passed with23 loaded tools,32 source tools and sourceSchemaMatch:false, seven measurement menu matches and66 documents in the existing older gateway. No app/gateway restart, provider request or scientific mutation. See [live catalog evidence](pitch/mcp-live-catalog-evidence.json).

## Preserve actual outcomes when local auditing fails, 17 October 2026, 06:47 BST

Three actual-filesystem regressions demonstrated unhandled EISDIR failures in the previous approval lifecycle. The executor now blocks before adapter dispatch when the human decision cannot be recorded, but preserves actual success/failure/results if a terminal append fails after execution. All terminal outcomes notify waiters with a separate bounded `auditWarning`. No replay, journal repair, automatic approval or app change. Backend answers/follow-ups and native activity carry the warning without changing the real operation status. Outcomes are volatile when persistence is unconfirmed.

Two review findings were accepted and fixed: a near-deadline denial could be mistaken for expiry during its delayed journal write; and an interrupted proposal's early terminal snapshot could hide a later recording warning permanently. Tests now cover genuine blocked FIFO appends with a deterministic approval clock, late EISDIR after interrupted-decision observation, released listeners, denied/expired warnings and no repeated mutation. The final structured helper `.local/autoreview-audit-outcomes-shipping.*` exited0 clean. All96 gateway tests, compilation and generated-schema consistency pass. Native build-only/signing and four PCM tests pass. The first sandboxed Swift invocation failed with sandbox_apply; the same authorized build-only check passed outside that restriction. Running companion/gateway/QuPath were not restarted; foreground warning layout remains pending manual unlock. No provider request or real scientific mutation was needed.
