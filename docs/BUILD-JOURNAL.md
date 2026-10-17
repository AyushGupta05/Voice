# Build journal

## 17 October 2026, 00:55 BST — foundation

- Existing repo is clean and contains an Apache-2.0 license; remote is Sleep-On-It/Voice.
- Read Clicky's public README and architecture. Its public pipeline is push-to-talk + screenshot + Claude + ElevenLabs; we will implement continuous OpenAI voice and semantic QuPath control rather than copy its provider pipeline.
- Official QuPath release v0.7.0 selected and source/release/JDK downloads started locally.
- Native macOS companion chosen to preserve the floating desktop experience.
- Paid resources remain disabled pending verification of credit eligibility and realtime availability.
- No project cloud resources created and no project API inference used.


## 17 October 2026, 01:25 BST — real QuPath milestone

- User explicitly approved publishing to the personal fork AyushGupta05/Voice after upstream permission was verified read-only. The initial documentation commit is published there; origin remains unchanged.
- Implemented the native SwiftPM companion, pointer overlay, authenticated gateway, 23 MCP tools, resource/prompt, authored guidance and a 66-document local knowledge index.
- QuPath 0.7 source/API and official release are installed locally. A normal project-local macOS launcher uses a full Temurin JDK because the stock runtime omits jdk.httpserver.
- Nine focused tests pass. Native build/sign/launch passes. Actual MCP smoke test reads real image metadata, menu commands and knowledge.
- Live testing found and fixed mouse-anchored zoom behavior. The corrected viewer center exactly matches the requested image coordinates.
- Automatic approval review initially rejected automated real mutations. The user subsequently explicitly authorized the bounded disposable OS-2 test sequence. The guarded script only accepts the expected OS-2 metadata and an empty hierarchy; it has no production auto-approval role.
- The authorized real fixture passed: H-DAB native dialog interaction, 2200 x 2200 pixel annotation, 899 detected cells, actual DAB measurements, 49 Positive / 850 Negative at nuclear DAB OD mean >= 0.2, comparison, viewer screenshot and restoration to the empty hierarchy. This is software verification, not scientific validation.
- Public sample evidence and provenance are in docs/DATASETS.md and docs/pitch/integration-evidence.json. Original images stay excluded from Git and are not overwritten.
- Production voice stays disabled: no AWS-credit-covered OpenAI realtime/GPT-Live endpoint has been established. No paid inference calls/cloud resources have been used.
- A quiet continuation heartbeat is scheduled every 30 minutes until 07:45 BST, preserving budget and approval constraints.
- Autoreview attempts: default CLI wrapper was broken; the installed desktop Codex CLI works. First review failed the helper's structured-path validation, so it is not a clean result. Rerun is in progress with explicit repository-relative review metadata.
- Seven-slide editable pitch deck is being generated from real viewer evidence and verified implementation claims.

## Next work

1. Finish autoreview, verify findings, fix accepted issues, rerun affected checks and review.
2. Finish pitch rendering/inspection and demo/contributor documentation.
3. Commit and publish reviewed implementation milestones to the approved fork.
4. Continue broader QuPath/native flow checks and supported voice-funding research without paid fallback.
5. Keep documentation status accurate and stop the heartbeat at 07:45 BST.

## Autoreview iteration

The valid structured Codex review reported six actionable findings, all verified against QuPath 0.7 source and accepted: blocking detection on JavaFX, missing primary-selection binding, detached selection after hierarchy restore, missing pre-UI-action checkpoint, overstated plugin completion, and cross-plane focus. The bridge now runs detection orchestration on a worker with captured approved parent objects, includes the primary selected ID, clears selection on restore, checkpoints before UI dispatch, explicitly marks per-tile analysis completion unverified, and navigates to an object's z/t plane. The integration fixture now checks that restore clears selection. A rerun of the real fixture and autoreview is required before the implementation commit.

## Affected verification after accepted fixes

The same explicitly authorized OS-2 fixture passed again with 899 cells and 49 Positive / 850 Negative. It now additionally asserts an empty selection after hierarchy restore, which passed. Exact focus coordinates and actual MCP smoke passed again (23 tools, 66 guidance documents). The native companion rebuilt and launched successfully. Final structured review is still running.

## 17 October 2026, 01:35 BST — second review fixes verified

The next structured review identified five additional concrete adapter defects, all confirmed in upstream code and accepted. Detection now validates the active parameter list (including exact choices and scalar types), records the upstream replayable plugin class with executed/default parameters, and preserves its approved target set. Deferred clicks recheck context, control fingerprint, visibility and modal ownership immediately before firing. Menu dispatch rechecks context and command state. Deferred failures are exposed in context and shown by the companion. Ordinary deletion refuses TMA cores and clears selection.

The new headless Java contract check passes against real QuPath jars for invalid choice/type rejection, immutable parent capture and workflow replay identity. Nine gateway/protocol tests pass. The authorized OS-2 fixture passed again with the same 899 / 49 / 850 counts and empty restoration. Actual MCP smoke and native build/sign/launch pass. The native knowledge search was exercised visually with “cell detection”. The seven-slide deck passed package, import and layout validation and was visually inspected. Another structured review is running before the software commit.

Funding research was refreshed against the official Bedrock endpoint/API tables. They still list OpenAI text-model APIs rather than GPT-Live/Realtime; no credit-covered voice endpoint is established. Project API/cloud spend remains $0.

The documented bootstrap was rerun end to end using the verified cached downloads and existing pinned upstream checkouts: npm ci, bridge compilation and launcher packaging all passed; npm reported zero dependency vulnerabilities. This verifies the rerun path on this Mac, not a fresh-machine installation. All local documentation links resolve. Native Checkpoint quick action also reached the real application and appeared in session history.

## 17 October 2026, 01:43 BST — recovery fidelity and broader state binding

A third valid review found six further issues: list-selection approval binding, JSON checkpoint data loss, project identity when no image is open, oversized selection keys, QuPath numeric parsing of lowercase scientific notation, and selectable-menu dispatch. All were confirmed against upstream contracts and fixed. Checkpoints now use bounded private native serialization, project/selection context is hashed, dialog selections and scalar values are fingerprinted, parsed numeric values are checked, and check/radio menus apply selection behavior before firing.

Headless tests preserve exact ROI precision, class probability, root measurements/metadata, object IDs and TMA-grid relationships. An isolated JavaFX test confirms classifier/table selection invalidation and check/radio dispatch semantics; it opens no windows and touches no QuPath session. A 1e-7 detection-parameter regression passes. The authorized OS-2 integration and actual MCP smoke passed again with the same counts and empty restoration. The native companion built and launched with project information in its approval panel. Structured review is running again.

The next implementation milestone should add GPT-Live's actual client-delegation protocol and a backend-agent interface under local mock tests, preserving the closed production funding gate. The separate protocol and verification boundary is documented in GPT-LIVE-PLAN.md. Do not rename RealtimeSession or claim a live funded session.

## Script responsiveness and modal safety

The fourth review identified two remaining issues, both accepted: synchronous Groovy on JavaFX and menu dispatch behind modal dialogs. Scripts now capture reviewed project/image parameters and checkpoint on JavaFX, then execute on a worker with QuPath’s normal imports/bindings. A synthetic Groovy test confirms the captured image and named worker. The first test attempt hit JavaFX cache permissions while checking thread status; it was not a pass. The revised headless check uses an explicit named worker and project-local caches and passes. Main-window menu commands check for an active modal before queuing and immediately before firing. Another structured review is running.

The native Start voice button was exercised: it reports the funding blocker, leaves voice inactive and opens no provider session. Research of the event setup did not establish a supported OpenAI voice credit route; no private event messages or redemption links are included in repository files.

Continuation scheduling audit found the existing overnight heartbeat paused. It was reactivated through the Codex automation tool under the user’s explicit overnight-work instruction, with the same 07:45 BST deadline and funding/approval constraints. Tool output and the saved automation state confirm ACTIVE.

## 17 October 2026, 02:00 BST — source binding, selection and launch lifecycle

Four further findings were fixed: RichTextFX source digest/range binding, absent script-context fallback, nullable image names and full selection counts. A later review confirmed that native script-editor Run workers still capture context after dispatch; those editable source-window actions are now refused in favor of the explicit captured-parameters script tool. Explicit scripts require both a project and image, a documented restriction. The original structured scientific tools remain usable on standalone images.

A final visual check exposed a real launcher defect: shell background cleanup terminated the nohup gateway. The native companion now owns a Node child when needed and reuses external gateways. Its launch verification checks authenticated gateway reachability, and SIGTERM follows normal app cleanup. The interrupted disposable test was restored to its existing empty baseline using the authorized recovery scope before rerunning. No research files were involved.

The enhanced fixed fixture passed with 899 cells, a 100-object preview explicitly marked truncated, a full count of 899, a 64-character context key, the same 49 / 850 threshold counts, and empty restoration. CUA visually confirmed “899 objects selected” in the actual companion. The app-owned gateway survived the launching shell; its parent PID was the companion. A graceful termination test stopped both the companion and only its owned gateway. Another structured review now includes this lifecycle change. No project API/cloud spend.

The launcher lifecycle check confirmed graceful SIGTERM cleanup: the companion’s Node child stopped with it, and a subsequent launcher run started a fresh authenticated pair. The app is running again with the source slide untouched and the empty fixture hierarchy restored.

Owned-gateway recovery also passed: terminating only that idle Node child caused the companion to start a new authenticated gateway, and actual MCP smoke passed afterward. The public fixture remained empty throughout this lifecycle check.

## 17 October 2026, 02:03 BST — clean reviewed milestone

Final structured Codex autoreview exited 0 with no accepted/actionable findings. All 24 findings from prior valid review passes were accepted, verified and fixed. No further review was run after the clean result. The final adapter was loaded into QuPath and the authorized enhanced fixture passed again with 899 cells, 49 / 850 threshold classes, explicit 899/100 selection-preview semantics, comparison and empty hierarchy/selection restoration. Actual MCP smoke passed again. Full commands and proof boundaries are in REVIEW.md.

The code and documentation/pitch are ready for separate progress commits to the approved personal fork. Production voice remains funding-blocked, project API/cloud spend remains $0, and the continuation heartbeat is confirmed ACTIVE until 07:45 BST. The next implementation milestone is the actual GPT-Live client-delegation protocol and backend interface under local tests; preserve the closed funding gate.

## 17 October 2026, 02:52 BST — GPT-Live protocol reviewed clean

Implemented the actual GPT-Live client-delegation transport separately from the older Realtime protocol, plus a provider-injected Responses-shaped scientific backend. It retains timestamped conversation and bounded task results, sends actual viewer images as vision input, uses the same 23 Runtime tools as MCP and waits for real human decisions. Production constructs no provider client and still refuses voice activation.

Seven accepted review findings were fixed and tested across successive passes. Final structured autoreview exited 0 clean. TypeScript compilation and 36 focused tests pass; actual MCP smoke and the scripted display-only real-QuPath protocol fixture pass. The fixture uses synthetic audio and no model inference, microphone or speaker. It verifies viewer center and unchanged scientific objects, not voice latency.

Read-only discovery observed 232 installed menu commands across 13 menu families (223 enabled, 9 disabled in this public-slide state). This demonstrates discovery breadth, not universal execution coverage. No menu command was invoked during that inventory.

The Mac is locked, so additional native UI inspection waits for manual unlock. Code, protocol and synthetic-audio work continue. An isolated synthetic 48 kHz stereo to 24 kHz mono conversion probe produced the expected signal level without opening audio hardware. Native capture/playback wiring and funded end-to-end voice remain unfinished.

The next increment will make tool documentation reproducible from its schemas and add focused offline native PCM checks. Existing published milestones are 31338fe and 6d222af in the approved personal fork. No project API/cloud spend.

## 17 October 2026, 02:58 BST — reproducible reference and offline audio

Added a schema-derived reference covering all 23 tools and parameters. `npm run docs:generate` regenerates it; `npm run check` now rejects a stale reference. `npm run doctor` checks setup artifacts, pinned upstream revisions, local knowledge, authenticated bridge reachability, matching running schemas and the disabled production voice gate. It passes here and correctly fails for an absent setup, with no credential or image-name output.

Extracted bounded signed PCM16 conversion into a small native module. Four offline Swift tests pass, including invalid device values that previously could trap integer conversion. Capture startup failure now removes its installed tap. No microphone or speakers were opened; hardware audio and new UI checks remain deferred.

One accepted review finding refined checkpoint MCP metadata: eviction means creation is neither idempotent, read-only nor additive-only. The actual MCP smoke asserts all three hints and passes. Final structured autoreview for this increment exited 0 clean. The existing 36 gateway tests and documentation consistency check pass. Production voice is disabled; project API/cloud spend remains $0.

Next: add bounded public-only CI using free standard GitHub runners, with no provider secrets, cache or artifact storage. Official billing documentation was checked; the approved fork is public. No workflow has been published yet.
