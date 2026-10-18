## What and why

<!-- One purpose per PR. -->

## Verification

<!-- Exact commands and their result lines, for the parts you touched: -->
- [ ] `npm run check` (gateway)
- [ ] `./script/test-fiji-bridge.sh` (Fiji bridge)
- [ ] `python3 -I fidelis-app/tools/build.py` and `python3 -I fidelis-app/tools/test.py` (Fidelis; paste the `[fidelis-tests]` line)
- [ ] `python3 -I verification/test_verification.py` (verification harness)
- [ ] `swift test --package-path apps/companion` (companion)
- [ ] Scientific behaviour changed? Parity evidence against `verification/baselines` (pixels, ROI vertices, Results doubles, globals). Visual similarity is not evidence.
- [ ] UI changed? Screenshots attached that were opened and inspected.
- [ ] Live voice claimed? Real microphone runs with the device and measured numbers.

## Checklist

- [ ] No upstream QuPath/Fiji/ImageJ/plugin source, runtimes, model weights, large datasets, build output, tokens or credentials added
- [ ] Documentation updated where a tool, schema, safety boundary, runtime requirement or verified capability changed
- [ ] Claims are honest: implemented, protocol-tested, real-app-tested and blocked behaviour are reported separately
