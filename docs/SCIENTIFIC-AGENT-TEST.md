# Fixed public scientific-agent test

This developer test connects the actual credit-checked OpenAI text backend to the scientific workflow previously authorized for the disposable OS-2 image. It is separate from ordinary production actions and from the still-pending synthetic import-test authorization. It does not import another image, activate voice or test arbitrary audience requests.

## Authority and scope

The user explicitly authorized this disposable sequence: H-DAB, a 2200 × 2200 pixel region, cell detection, measured DAB threshold classification, comparison and restoration of the empty hierarchy. `script/public-analysis-fixture.ts` permits only the next exact step and arguments, with the same ROI/parameters as the existing integration fixture. A started native step cannot be replayed. Baseline checkpoint creation is trusted test setup, outside the model; its exact ID is the only comparison/restore target.

The model receives only the fixed tools and sanitized public-image context/results. Project metadata, unrelated checkpoints, dispatch diagnostics, file paths, viewer pixels and credentials are excluded. It has no approval tool. The trusted harness simulates decisions through the same Runtime only for this fixed test; normal application approvals are unchanged.

The production Runtime's classification preflight may read the exact selected-region cell count through the adapter. This internal read is restricted and redacted to the count; it does not expose another model tool or widen the scientific mutation scope.

Prerequisites are the dedicated public OS-2 fixture, H-DAB already selected, no project, empty hierarchy/selection, no running analysis or researcher actions, plane z=0/t=0 and no checkpoints belonging to other images. Do not edit that disposable instance concurrently. Image/state changes reject further work. A local exclusive lock prevents concurrent copies of this test; it is never silently reset.

## Run only with the explicit test authorization

```sh
node --import tsx script/analysis-model-smoke.ts --authorized-os2-analysis-fixture
```

This invokes the real model under the existing per-request credit/access/price checks and $200 conservative reserve cap. It is not a setup, CI or ordinary demo command. The backend's existing eight-round limit bounds this run to eight model-completion attempts; every actual attempt retains its $1 reservation. Failed/cancelled/ambiguous usage is never refunded. The script requires configured text funding but never creates model access, credentials, cloud resources or a cash fallback.

The actual model must execute all six ordered steps and return structured counts/restoration status. The fixture independently checks QuPath's actual counts (899 cells, 49 threshold-positive / 850 negative), the baseline comparison and the empty hierarchy/selection after restore. Those expected values come from the recorded fixed software fixture; they are not segmentation ground truth or clinical validation. Native detection's `completionVerified: false` is preserved.

On failure, the harness detaches automatic decisions and attempts the exact owned baseline restore through Runtime only when its last successfully verified context still matches. A failed/rejected dispatch never adopts its later state. Successful mutations must match native completion context, expected counts and the exact ROI/selection before ownership advances. Independent changes, uncertain partial native effects, a running analysis or an already-attempted failed restore require inspection instead. SIGINT/SIGTERM request cooperative cancellation and the same guarded cleanup; they do not signal QuPath. This is not universal undo and does not reset preferences, image settings or files. Existing checkpoints for this same disposable image can be evicted by QuPath's bounded cache; no other image's checkpoints are permitted at startup.

Private per-run evidence is saved in `.local/evidence/analysis-model-<id>.json`. Native diagnostics stay private. Public evidence may be published only after independently checking success, completed usage receipts and restoration. A scripted fixture proves only its exact operations, not general autonomous science, foreground UI quality, hardware audio or live voice.

## Actual outcome, 17 October 2026

The [complete real-model run](pitch/analysis-model-evidence.json) passed in **21,471 ms / eight model requests**. It executed image type, annotation, detection, classification, comparison and restoration through the shared Runtime and real QuPath, with five fixed-test simulated approvals. The model's structured answer matched the independently read 899 / 49 / 850 counts and the empty restored hierarchy/selection. Cleanup found the baseline already restored by the model's actual restore action. No source image/file was written, no production approval was automated and no voice was used. This is one controlled predefined workflow, not general scientific autonomy or a latency benchmark.

The [initial attempt](pitch/analysis-model-initial-failure.json) detected 899 cells but stopped after strict argument rejection. It returned null for unfinished results and failed the proof; trusted cleanup restored the known empty baseline. Its six requests/$6 reserves remain counted. The corrected harness supplies the safe canonical next step on rejection, keeping raw diagnostics private. The successful run corrected an attempted existing-class filter of `Positive` before classification; that rejected proposal never executed. The fixture's scope was not widened.

Across both attempts, fourteen actual requests added $0.01471005 estimated tokens and $14 retained reserves. All project fixtures total **26 completed requests / $0.02179905 estimated tokens / $26 conservative reserves**. AWS billing/credit settlement remains unreconciled. The full original QuPath bundle and prepared launcher passed the preservation check again afterward. Normal running app/gateway were not reloaded for new native/import flows; those foreground checks still await manual unlock and separate import-test authorization.

## Offline contracts

The gateway test suite exercises synthetic in-memory adapters: unsafe baselines, out-of-order/wider arguments, unrelated checkpoint IDs, unchanged normal approval gating, public-context/result/error sanitization (including audit failures), failed-step replay refusal, cleanup from known successful state and refusal to overwrite independently changed or uncertain partial state. The dispatch-race test changes the hierarchy between observation and native execution and verifies that cleanup cannot absorb it. These tests never invoke AWS or a scientific application. A passing contract is not an actual model/scientific result.
