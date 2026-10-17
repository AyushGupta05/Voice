# Verification record

Checked on Windows x64 on 17 October 2026. All claims below refer to this limited integration milestone.

## Automated results

**23 tests passed**, including two real integration tests. The final full run is recorded in ignored outputs/all-tests.xml. No Fiji or speech implementation is mocked in those two integration tests.

- Generated WAV → real faster-whisper CPU transcription → command preview API → real Fiji inversion → output image and measurements → replay in a new job.
- Transcription was “Invert the image.” The parser produced exactly one invert action.
- Original image mean: 18.75. Inverted mean: 236.25. Every output pixel equals 255 minus the original pixel. Replay was pixel-identical.
- Uploaded grayscale PNG → Gaussian blur, sigma 2 → fixed threshold 128 → ImageJ Measure. The blur changes the image; the mask contains only 0 and 255, retains the brighter square's center and rejects the dimmer square's center. Measurement does not change the mask. Full-image area is 4,096 pixels and mean is 11.953125, matching independent image statistics.
- Stage event order and artifact availability checked. Synthetic input remained unchanged.
- Invalid/unsupported commands, illegal parameter combinations, nonfinite/out-of-range numbers, extra fields, invalid image uploads, invalid IDs and unrelated browser origins rejected.
- Changed runtime bytes are rejected by the pin verifier.
- Timeout cleanup tested using a real sleeping subprocess substituted for the Fiji launcher; the worker terminated. This tests cleanup behavior, not a hanging scientific plugin.
- Simulated engine failure becomes a failed API job with an error event. This failure-path test intentionally substitutes the adapter.

Ruff lint, TypeScript checking, Vite production build, core pip dependency checks and speech uv dependency checks passed. Speech packages were synchronized against the hash-enforced lock. Model file checksums passed. The official dated Fiji archive checksum matches the digest recorded during the original installation.

The suite emits one upstream Starlette warning that its httpx TestClient compatibility is deprecated in favor of httpx2. It does not fail the tests; no warning was suppressed.

## Browser checks

The local React workbench was exercised through the Codex in-app browser: command review, Run in Fiji, running/completed status, visible stage events, actual output images, numerical table and selection of a previous timeline stage. Both inversion/measurement and blur/threshold/measurement were run through the UI. The browser's file chooser and native microphone capture were not exercised; the audio/image upload APIs were covered by the real integration tests.

## Issues found and repaired

1. Gaussian Blur completed image processing but left the Java worker alive. Generated worker macros now explicitly exit their dedicated JVM. Subsequent tests completed successfully.
2. Default ImageJ numeric-to-string conversion truncated measured means. CSV export now explicitly writes eight decimal places; numerical tests pass without weakening their tolerance.
3. Dedicated --allow-multiple execution and an explicit bundled Java path avoid accidentally reusing another Fiji session or selecting an unpinned system Java.

## Not tested or not implemented

- Real microphone input, browser audio file selection, noisy/accented human speech, speech latency/accuracy benchmarking, non-English commands or GPU inference.
- General natural-language reasoning, arbitrary macro import, native Fiji Recorder interception, plugin-wide coverage, segmentation validity on real scientific data.
- Multi-image batches, TIFF/OME/Bio-Formats, stacks/3D, calibrated units, ROIs, user cancellation, durable queue/history recovery or automatic artifact retention.
- Clean-machine setup, Linux/macOS, deployment packaging, final UI composition or comprehensive accessibility/mobile QA.

The existing dated Fiji installation was reused and verified. Re-downloading/extracting its archive on a clean machine remains a separate acceptance check. No upstream codebase was rewritten, no GitHub remote was renamed and no changes were pushed.
