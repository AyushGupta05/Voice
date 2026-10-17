# Tool coverage and evidence

This is a **source-linked coverage ledger**, not a new test run. It describes documentation base `7e81707c478f57b1071062e4156ee395986a892a` and the dated evidence committed there. The documentation branch does not execute QuPath mutations, provider calls, microphone capture or live UI workflows. See current [readiness](../READINESS.md) and the [build journal](../BUILD-JOURNAL.md) for subsequent observations.

## Read the evidence levels

- **Implemented/schema-defined:** source exposes an operation and accepted inputs. This alone proves neither loaded runtime availability nor successful execution.
- **Contract-tested:** tests exercise relevant logic with synthetic adapters, real-library data structures or isolated JavaFX controls. A generic transport/approval contract is not a separate end-to-end test of each scientific operation.
- **Real-app evidence:** a specific saved observation from actual QuPath, with dataset, fixture and approval scope. It does not generalize to all images/plugins or establish scientific validity.
- **Not asserted:** this ledger has no separate complete real-app proof for that route. This is a coverage limit, not a claim the implementation is broken.

All 32 tools share source-schema documentation checks. `Transport` below refers to live-catalog MCP/SDK contracts and generic dispatch/error behavior, not successful application execution for every listed name.

## Test source key

| Key | Exact source and scope |
|---|---|
| Transport | [mcp.test.ts](../../packages/gateway/test/mcp.test.ts): actual SDK with isolated loopback catalog, legacy schemas, rediscovery, malformed discovery and absence of approval credentials/routes |
| Runtime | [runtime.test.ts](../../packages/gateway/test/runtime.test.ts): synthetic adapter, immutable args, proposals, stale context, single decision, view execution, import/project/classification preflights, expiry and audit failures |
| Server | [server.test.ts](../../packages/gateway/test/server.test.ts): authenticated routes and companion/message contracts |
| Identity | [bridge-readiness.test.ts](../../packages/gateway/test/bridge-readiness.test.ts), [BridgeIdentityContractTest.java](../../adapters/qupath/src/test/java/science/luma/qupath/BridgeIdentityContractTest.java): loaded/build operation/source identity |
| Data | [DataContractTest.java](../../adapters/qupath/src/test/java/science/luma/qupath/DataContractTest.java): real-library summary/missing/extreme-value and GeoJSON identity/hash/geometry/type/duplicate contracts |
| UI | [UIContractTest.java](../../adapters/qupath/src/test/java/science/luma/qupath/UIContractTest.java): isolated controls/fingerprints, choices/tabs and checkbox/radio menu dispatch; no research session |
| Hierarchy | [HierarchyContractTest.java](../../adapters/qupath/src/test/java/science/luma/qupath/HierarchyContractTest.java): native hierarchy serialization preserves precise ROI/measurements/IDs/metadata/TMA |
| Detection | [DetectionContractTest.java](../../adapters/qupath/src/test/java/science/luma/qupath/DetectionContractTest.java): parameter/plugin contracts against pinned QuPath |
| Script | [ScriptContractTest.java](../../adapters/qupath/src/test/java/science/luma/qupath/ScriptContractTest.java): actual scripting bindings/API behavior in its fixture |
| API source | [api-source.test.ts](../../packages/gateway/test/api-source.test.ts), [api-declaration-proof.test.ts](../../packages/gateway/test/api-declaration-proof.test.ts): immutable-source boundaries, bounded excerpts and exact type declarations |
| Public fixture scope | [public-analysis-fixture.test.ts](../../packages/gateway/test/public-analysis-fixture.test.ts), [public-text-fixture.test.ts](../../packages/gateway/test/public-text-fixture.test.ts): fixture prerequisites and restricted requests, not permission for arbitrary mutations |

`API compilation` means the adapter code can be compiled against pinned dependencies. It does not mean a purpose-built behavioral contract exists for every row.

## All 32 tools

| Tool | Relevant contracts/source checks | Saved real-app evidence or explicit limit | Remaining boundary |
|---|---|---|---|
| [`qupath_context`](TOOLS.md#qupath-context) | Identity; transport | OS-2 metadata/selection preview; synthetic context | A current connection still needs fresh observation. |
| [`qupath_objects`](TOOLS.md#qupath-objects) | Transport; scoped classification/import preflights | Exact synthetic imported identities/classes/ROIs | Every scope/filter/plane combination is not separately demonstrated. |
| [`qupath_measurements`](TOOLS.md#qupath-measurements) | Data; transport | OS-2 stored measurements; synthetic scores 1 and 3 | Preview caps are not a full-population analysis. |
| [`qupath_measurement_summary`](TOOLS.md#qupath-measurement-summary) | Data | Synthetic imported score mean 2 | Clinical interpretation and large production populations unvalidated. |
| [`qupath_analysis_catalog`](TOOLS.md#qupath-analysis-catalog) | Data/API compilation; transport | No separate exhaustive foreground catalog proof asserted | Parameter availability is not algorithm success. |
| [`qupath_project_images`](TOOLS.md#qupath-project-images) | Runtime navigation preflight; API compilation | No separate real project-navigation fixture asserted | Entry listing is not successful image opening. |
| [`qupath_open_path`](TOOLS.md#qupath-open-path) | API compilation; generic approval contracts | Foreground opening work is tracked in live-demo/readiness docs | No blanket completion claim from native dispatch. |
| [`qupath_open_project_image`](TOOLS.md#qupath-open-project-image) | Runtime changed-source navigation contract; API compilation | No complete project-entry opening proof asserted here | Native reader/type prompts and unsaved work remain relevant. |
| [`qupath_inspect_import`](TOOLS.md#qupath-inspect-import) | Data; runtime import preview | Synthetic changed-file validation | Only supported annotation geometry/coordinates; not arbitrary import formats. |
| [`qupath_import_annotations`](TOOLS.md#qupath-import-annotations) | Data; runtime hash/context preview | Synthetic actual-QuPath import round trip | Private fixture socket does not prove normal extension installation or OS file opening. |
| [`qupath_select`](TOOLS.md#qupath-select) | API compilation; generic view execution | OS-2 full selection with 100-object context preview | No blanket promise for every selection/filter combination. |
| [`qupath_focus`](TOOLS.md#qupath-focus) | Runtime immediate view; transport | OS-2 exact center; real text-agent focus | Pointer/view proof is not arbitrary canvas clicking or voice. |
| [`qupath_overlays`](TOOLS.md#qupath-overlays) | API compilation; generic view execution | Public-slide positive display screenshot/workflow | Showing a class does not assign it or validate biology. |
| [`qupath_snapshot`](TOOLS.md#qupath-snapshot) | Transport handling in implementation; API compilation | Public-slide viewer screenshot | Viewport PNG is not a full-slide analysis or every desktop surface. |
| [`qupath_commands`](TOOLS.md#qupath-commands) | UI; API compilation | 232-menu read-only discovery observation | Discovered menus were not all executed. |
| [`qupath_ui`](TOOLS.md#qupath-ui) | UI fingerprints; runtime reviewed-control preview | Fixed public fixture image-type dialog route | OS dialogs/custom widgets and arbitrary plugins not universally covered. |
| [`qupath_ui_action`](TOOLS.md#qupath-ui-action) | UI; runtime stale-control binding | Fixed public fixture image-type control route | One control route does not prove all dialogs or foreground keyboard/mouse behavior. |
| [`qupath_annotate`](TOOLS.md#qupath-annotate) | API compilation; generic approval contracts | Fixed public OS-2 region creation | Chosen fixture geometry is not biological-region identification. |
| [`qupath_detect_cells`](TOOLS.md#qupath-detect-cells) | Detection; public fixture scope contracts | OS-2 899 cells in fixed disposable sequence | No segmentation validity, all-plugin coverage or general latency benchmark. |
| [`qupath_classify`](TOOLS.md#qupath-classify) | Runtime actual-candidate preview and empty refusal | OS-2 49 Positive / 850 Negative at chosen threshold | Threshold labels are not a biological diagnosis. |
| [`qupath_image_type`](TOOLS.md#qupath-image-type) | API compilation; generic approval contracts | Public H-DAB fixed workflow | Hierarchy recovery does not restore interpretation/stain settings. |
| [`qupath_checkpoint`](TOOLS.md#qupath-checkpoint) | Hierarchy; generic serialization contracts | OS-2/synthetic baseline capture | Volatile bounded cache, not file backup. |
| [`qupath_checkpoints`](TOOLS.md#qupath-checkpoints) | Hierarchy; API compilation | Used in fixed recovery workflows; no standalone exhaustive UI proof | Other-image entries are not eligible for current-image restore. |
| [`qupath_compare`](TOOLS.md#qupath-compare) | Hierarchy; API compilation | OS-2 count deltas; synthetic comparison | Count comparison is not cell tracking or measurement-distribution comparison. |
| [`qupath_restore`](TOOLS.md#qupath-restore) | Hierarchy; runtime approval/context | OS-2 and synthetic empty baseline restoration | No external-file/settings/script undo. |
| [`qupath_delete_selected`](TOOLS.md#qupath-delete-selected) | API compilation; generic approval contracts | No separate real deletion fixture asserted | Retention of children/TMA refusal still require correct scope. |
| [`qupath_run_command`](TOOLS.md#qupath-run-command) | UI menu toggle semantics; runtime approval | Menu discovery only is not command completion proof | Native dialogs/plugin/file effects need task-specific verification. |
| [`qupath_run_script`](TOOLS.md#qupath-run-script) | Script bindings/API contracts; runtime approval | Pinned API model lookup/type proof is not script execution | No general arbitrary-script safety or external-effect recovery. |
| [`qupath_knowledge`](TOOLS.md#qupath-knowledge) | Local knowledge implementation; transport | Read-only smoke path/guidance retrieval | Retrieved guidance does not establish live application state. |
| [`qupath_api_search`](TOOLS.md#qupath-api-search) | API source/declaration contracts | Pinned 879-file lookup and real model API check | Exact source lookup does not validate every plugin or scientific task. |
| [`qupath_api_read`](TOOLS.md#qupath-api-read) | API source bounds/pin contracts | Immutable committed excerpt/type check | No arbitrary local-file reader or execution. |
| [`qupath_action_status`](TOOLS.md#qupath-action-status) | Runtime expiry/concurrency/audit lifecycle; transport | Fixed agent/fixture result polling | Gateway history is bounded and not durable cross-restart replay. |

## Evidence records

| Record | What it establishes | What it does not establish |
|---|---|---|
| [Public integration](../pitch/integration-evidence.json), [fixture guide](../DEMO.md) | Actual OS-2 metadata, exact focus, selection preview, fixed detection/classification/count comparison and empty hierarchy recovery | Valid biological threshold/segmentation, production approvals automatically allowed, all analyses |
| [Scientific agent](../pitch/analysis-model-evidence.json), [test explanation](../SCIENTIFIC-AGENT-TEST.md) | Real OpenAI text agent completed the fixed public sequence; 899/49/850, eight model requests and five fixture-only decisions | Live voice, a universal speed benchmark or an automatic approval path for users |
| [Synthetic import](../pitch/synthetic-import-evidence.json), [test scope](../SYNTHETIC-IMPORT-TEST.md) | Actual isolated QuPathGUI, 32-tool catalog, two exact imported annotations, scores 1/3 and mean 2, changed-file/duplicate rejection, comparison and empty recovery | Normal extension auto-install, native image-file opening, foreground controls or microphone; test used private socket wiring to the unchanged handler |
| [Menu discovery](../pitch/capability-discovery.json) | 232 observed menus in that installation/session | 232 commands successfully executed or an invariant menu count |
| [Source lookup](../pitch/api-source-evidence.json), [model API check](../pitch/api-model-evidence.json) | Pinned Java source retrieval and type/signature checks | Scientific code was run in QuPath or arbitrary local files were read |
| [Text interaction](../pitch/companion-text-evidence.json), [fresh context](../pitch/companion-text-fresh-context-evidence.json) | Real authenticated text-agent viewer focus/follow-up behavior for its exact request | Hardware voice, every foreground control or general latency |
| [Preservation](../pitch/qupath-preservation-evidence.json) | Recorded stock-bundle/pinned-source preservation check | Every later local installation remains unchanged without rechecking |
| [Historical MCP catalog](../pitch/mcp-live-catalog-evidence.json), [bridge readiness](../pitch/bridge-readiness-evidence.json) | Correctly distinguished an older loaded runtime from newer source at that time | The current running tool count; never rewrite a historical record to describe a later reload |

The normal-app opening sequence and newer speech work are deliberately tracked in [LIVE-DEMO.md](../LIVE-DEMO.md), [READINESS.md](../READINESS.md) and [VOICE.md](../VOICE.md), rather than being claimed as freshly completed by this documentation branch. The saved synthetic plot image is a presentation fixture, not a real scientific report generated from OS-2.

## Checks that do not dispatch application actions

Run from the repository root after dependencies are installed:

```sh
npm run docs:check
git diff --check
```

The first checks generated reference/registry consistency with source (including nested constraints, descriptions and risk/operation metadata). It does not validate all prose or contact QuPath. For a docs-only patch also parse call examples with `validateTool` from `packages/gateway/src/tools.ts` **without calling Runtime, MCPGateway or an adapter**. That proves source-schema validity only. Resolve every local Markdown target/fragment and ensure every registry name has a guide section, example and ledger row.

The documentation-only branch performed those static checks; it did not rerun the historical scientific fixtures. Build/test results in existing evidence retain their original scope and date. Main source and schema count can change after this snapshot; repeat the static comparison when merging.

## Maintaining this handbook

1. Change implementation/schema in the appropriate source first. Determine reads, side effects, context binding, units, constraints, failure behavior and true recovery scope.
2. Regenerate both canonical artifacts with `npm run docs:generate`; run `npm run docs:check`. Never hand-edit their individual schemas or metadata.
3. Update the [tool guide](TOOLS.md) with actual defaults, a schema-valid example, result interpretation, prerequisites and limits. Keep output descriptions tied to real source; outputs are not automatically generated JSON schemas.
4. Add/update the relevant [workflow](WORKFLOWS.md), [operations](OPERATIONS.md), [capability matrix](../CAPABILITIES.md), task guidance and funding/setup requirements when their behavior changes.
5. Update this ledger with exact tests and dated evidence. Mark synthetic adapter/control tests separately from real QuPath, loaded-build checks separately from build success, and native capture separately from protocol tests.
6. Test only the affected behavior, with explicit authorization for real mutations. Follow [contributor verification](../CONTRIBUTING.md) and the repository's review requirements for nontrivial code edits. Documentation examples are never permission to simulate production approvals.
7. Before publishing, exclude local tokens, private data, caches and raw audit/model logs. Preserve unrelated work and upstream QuPath. Publish only to the approved remote and only when publication is authorized.

A future adapter should reuse the gateway approval/event mechanisms and add application-specific schemas/knowledge. A voice provider should deliver intent through the same tools, without a second execution or approval path. Neither general architecture claim is proof of another application's end-to-end support.
