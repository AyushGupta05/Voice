# Contributing

Start with the [product brief](PRODUCT.md), [capability matrix](CAPABILITIES.md) and [setup](SETUP.md). The aim is to make existing scientific software easier to operate while keeping its real data, algorithms and interface.

## Add a structured QuPath tool

1. Define a narrow operation and JSON schema in `packages/gateway/src/tools.ts`. Decide whether it reads state, changes display, changes scientific data or runs code.
2. Implement the QuPath operation in `LumaExtension.java`. Use actual image/object APIs and JavaFX ownership. Validate prerequisites and return verifiable results with units.
3. For data changes, preserve recoverable hierarchy state and require explicit approval. State what cannot be recovered.
4. Add a meaningful gateway or adapter test for the failure that matters: stale selection, invalid parameter, cancelled analysis, missing measurement, duplicate approval, unavailable plugin or changed dialog.
5. Update task guidance, capability status and any protocol/setup changes. Run `npm run docs:generate` after tool-schema changes; `npm run docs:check` detects a stale [tool reference](TOOL-REFERENCE.md).
6. Run focused checks and autoreview. Verify the visible application when UI or scientific outcomes change.

The same tool list feeds MCP and the realtime transport. Do not duplicate provider-specific scientific tool implementations.

## Validation layers

- `npm run doctor`: read-only setup/connection diagnostics; it does not replace an MCP or analysis test.
- `npm run verify:qupath`: installed stock-bundle comparison against the pinned package, separate-launcher checks and clean upstream pins. Temporary extraction only; no restart, provider call or repair. Requires the default macOS local installation.
- `python3 script/test-verify-qupath.py -v`: portable synthetic filesystem contracts for mutation, deletion, injection, symlink changes, overlap refusal and preservation on failure.
- `python3 script/test-build-bridge.py -v`: portable disposable build fixture; failed compilation preserves the previous JAR and successful packaging replaces it from fresh classes.
- `python3 script/test-qupath-launch.py -v`: portable startup fixture; an already-running instance or failed inventory leaves tokens, artifacts and the filesystem unchanged, without opening an app.
- `python3 script/test-companion-process.py -v`: macOS-only disposable compiled-process fixture; proves exact-path stop leaves a same-named foreign process running.
- `./script/test-audio.sh`: four offline native PCM tests; no microphone, playback or provider session.
- `npm run check`: TypeScript compilation, schema documentation consistency and focused gateway/approval/protocol tests. Mocked approval decisions touch no scientific application data.
- `./script/build-bridge.sh`: compile against the pinned real QuPath API.
- `./script/build_and_run.sh --verify`: build/sign/launch the native companion and confirm its process.
- `npm run smoke`: actual MCP SDK client through the running gateway to QuPath. Read-only.
- `node --import tsx script/integration.ts --public-fixture`: specifically authorized destructive test on a clean, disposable OS-2 sample session. It checks exact metadata and an empty hierarchy, simulates companion approvals only for a fixed test sequence, and restores the empty baseline. Never use it on research data or generalize it into an agent auto-approval feature.

The current real fixture checks image-type dialog control, annotation creation, exact focus coordinates, actual detection, measured values, threshold classification, comparison, a viewer screenshot and hierarchy recovery. A passing fixture verifies software behavior, not scientific validity.

## Documentation expectations

A new contributor should be able to answer what an operation does, why its parameters matter, what it reads/writes, how it fails, what needs approval, what can be undone, and how it was tested. Keep those answers next to the implementation or in the appropriate linked guide.

Record evidence separately from claims. Mark a feature implemented, locally protocol-tested, real-app-tested or blocked. A successful build cannot substitute for a rendered or end-to-end check.

## Publication

Use ordinary incremental commits. Do not commit `.local`, tokens, caches, source datasets or raw logs. Public documentation may include screenshots of freely distributable sample data with provenance. The approved progress destination is the personal fork at https://github.com/AyushGupta05/Voice . Keep the original remote intact and do not force-push.

Run `./script/test-bridge.sh` after Java adapter changes. It tests parameter rejection and workflow replay against the installed QuPath jars using a synthetic in-memory image. The public slide integration fixture requires the explicit disposable-fixture authorization described in the demo documentation.

`./script/test-bridge-ui.sh` additionally checks isolated JavaFX list/table selection fingerprints and selectable menu actions. It needs a usable desktop JavaFX runtime, opens no windows and touches no scientific session. Its disposable Java process has a 45-second outer startup/execution timeout; a locked/unavailable display is not a passing check. Both bridge test lanes use an in-memory preference factory, and the headless lane verifies the actual QuPath preference-node override without reading/writing the user's preference store. The headless checks also cover exact hierarchy recovery and scientific-notation parsing.

For voice/backend changes, run `npm run check` and read [VOICE.md](VOICE.md). `npm run smoke:voice -- --public-fixture` connects synthetic protocol events and scripted backend responses to real QuPath read/view operations. It never proves live inference or microphone quality. Preserve the production funding gate.

## Continuous integration

`.github/workflows/verify.yml` runs `npm ci` / `npm run check` and portable preservation/build/startup fixtures on Ubuntu 24.04 and the offline native PCM tests/build and disposable process-scope fixture on macOS 15. It uses pinned official action revisions and standard GitHub-hosted runners only. Jobs are restricted to public repositories, where those runners are [free under GitHub's documented policy](https://docs.github.com/en/billing/concepts/product-billing/github-actions). Private repositories skip both jobs. No provider secrets, image datasets, cache uploads, artifact storage, paid runners or deployment steps are configured.

CI does not launch QuPath, test native audio hardware or validate a scientific result. Keep the real-app and disposable-fixture checks in the validation layers above. A passing workflow establishes only the commands shown in its logs.

The AWS text-adapter tests use only synthetic credit/price records, mocked inference and intercepted SDK requests. `npm run check` never enables funding or invokes a real model. `npm run enable:text` and `npm run smoke:text -- --public-fixture` are explicit, separate commands; read [AWS-TEXT.md](AWS-TEXT.md) before using them. The latter consumes verified AWS credits and is not a generic CI/setup check.

`npm run smoke:companion-text -- --public-fixture` separately tests actual credit-funded text through an authenticated loopback server using production conversation routing and the restricted public-slide Runtime. It does not click the native controls, activate voice, approve a proposal or permit scientific mutations. Ordinary text-conversation/Stop tests in `npm run check` use an injected synthetic client and never invoke AWS.

`npm run smoke:api` verifies actual pinned API-source lookup without touching QuPath or calling a provider. Source-reference tests use temporary Git fixtures; lookup reads verified committed Java blobs, not arbitrary filesystem paths. See [API-LOOKUP.md](API-LOOKUP.md).

The separately authorized fixed scientific-model fixture is documented in [SCIENTIFIC-AGENT-TEST.md](SCIENTIFIC-AGENT-TEST.md). Its real command can perform the exact preapproved OS-2 mutations with test-only simulated decisions. It is never a general approval mechanism or a CI/install step; the ordinary test suite uses synthetic adapters only.

`npm run smoke:classification -- --public-fixture` is a separate read-only actual-app check on the empty public OS-2 baseline. It verifies that an empty existing-class filter creates no proposal and leaves context unchanged. It never approves, invokes a provider or performs a scientific mutation; positive candidate previews and stale-scope decisions have synthetic contract coverage.
