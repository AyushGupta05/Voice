# Isolated synthetic import verification

The user separately authorized this disposable test on 17 October 2026: synthetic image plus synthetic GeoJSON, with simulated approvals only in that instance. It does not authorize approving production actions or modifying research data. Execution results are recorded separately from this test plan.

```sh
node --import tsx script/synthetic-import-smoke.ts --authorized-synthetic-imports
```

Requires the default local Apple Silicon QuPath/JDK bootstrap, a built bridge and a usable normal desktop session. The script creates a fresh private directory under `.local/synthetic-import-fixtures`, prepares a distinct development app and launches it normally with LaunchServices. Preferences are entirely in memory; tokens, user directory and caches are separate. Normal QuPath/Penny processes and credentials are untouched. No provider or voice connection is used.

The official QuPath startup-script hook creates an actual QuPathGUI session with a 512 × 512 synthetic in-memory image. A test-only socket uses the unchanged production Java HTTP handler on a separate OS-assigned loopback port; the normal fixed-port auto-extension is disabled only in this test app. This is real QuPath import execution, but does not verify normal extension discovery, native image-file opening or foreground usability.

A real MCP SDK client connects through the isolated gateway and shared Runtime. It verifies empty synthetic context/build identity, inspects exact GeoJSON bytes, rejects changed-byte proposals, imports two annotations after an exact fixture-only decision, and verifies their original UUIDs, names, classes, pixel bounds, image planes and individual scores of 1 and 3. It also checks the full-scope score mean of 2, rejects duplicate IDs, compares and restores the empty baseline after a second exact fixture decision. Source GeoJSON bytes must match afterward.

Each simulated decision must match the last verified fixture context and have no open project. The import result supplies the next owned context key; independently changed state is never adopted for a restore proposal. Restore returns no context key, so cleanup ownership is re-established only after checking the exact synthetic image, no project and empty hierarchy/selection.

Startup is bounded to 45 seconds. If normal startup cannot obtain a JavaFX screen, the test fails honestly; it never unlocks the Mac, creates an alternate display or captures a desktop. Cleanup signals only the exact owned fixture executable after fresh authenticated context confirms the synthetic image, no project, no running analysis and the last verified context key. Explicit NoScreen startup failure also permits stopping that uninitialized test process. Unknown or independently changed state is left for inspection. No basename process kill or forced kill is used.

Private per-run evidence and logs remain in the fixture directory. Publish only a bounded sanitized summary after inspecting the actual result. A compilation or headless-library contract is not a passing actual-GUI import test.

## Verified result

The 17 October 2026, 07:34 BST run passed in an actual QuPathGUI instance through the complete 32-tool MCP catalog. Both annotations retained their exact UUIDs, names, classes, bounds, planes and individual measurements. Changed-file and duplicate imports were rejected before another proposal, comparison reported two added annotations, and restoration returned an empty hierarchy/selection. The source GeoJSON was unchanged and the disposable process stopped safely. Two simulated decisions applied only to this fixture; provider requests were zero. See the [sanitized evidence](pitch/synthetic-import-evidence.json).

An earlier attempt stopped before importing because the test expected explicit `null` rather than the bridge's omitted no-project field. Its failed evidence was retained; fresh authenticated context verified the empty synthetic state before exact-path cleanup. Review then identified and verified stronger context-ownership and exact-object assertions. The corrected fixture compiled and passed final focused review before the successful run. Normal application processes, credentials and production approvals were unchanged.
