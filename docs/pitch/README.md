# Pitch material

[Luma-Pitch-v6.pptx](Luma-Pitch-v6.pptx) is the current seven-slide editable deck. It keeps the actual QuPath images and now shows 32 MCP tools and pinned API lookup, while stating that real AWS-credit OpenAI text works and live voice remains funding-blocked. [Version 3](Luma-Pitch-v3.pptx) and [version 4](Luma-Pitch-v4.pptx) are retained as earlier snapshots.

The measured analysis fixture is recorded in [integration-evidence.json](integration-evidence.json). Its counts describe a chosen detection and threshold on a public sample, not validated biological ground truth. The separate [direct text model](text-model-evidence.json) and [authenticated companion-route](companion-text-evidence.json) records distinguish actual inference from protocol mocks and native foreground inspection.

Later source milestones retain their own evidence: [original-app preservation](qupath-preservation-evidence.json) and [the same-request text follow-up](companion-text-fresh-context-evidence.json). The latter took two model requests / 4665 ms in one run; it is not a voice latency benchmark. Deck v6's dated presenter notes describe its earlier milestone; current funding totals and remaining work are in [FUNDING.md](../FUNDING.md) and the [build journal](../BUILD-JOURNAL.md).

The later [actual scientific-agent run](analysis-model-evidence.json) completed the fixed 899-cell analysis/classification/comparison/restoration sequence. Its [test guide](../SCIENTIFIC-AGENT-TEST.md) explains the narrow preauthorized simulated approvals and preserves the initial safely stopped attempt. It does not establish live voice or unrestricted autonomous science.

All slide text is editable in PowerPoint. [build.mjs](build.mjs) is the authoring source; it uses the Codex bundled `@oai/artifact-tool` runtime rather than a public npm dependency. It makes no provider calls. The source locates repository images through its own path, and keeps drafts, renders and validation reports under `.local/pitch-build-v6`.

To rebuild in an environment with that bundled runtime, obtain Node, node_modules and Python paths from `load_workspace_dependencies`, then supply them along with the installed presentation skill directory:

```sh
RUNTIME_NODE_MODULES="/path/to/bundled/node_modules" \
PRESENTATION_SKILL_DIR="/path/to/presentations/skill" \
PRESENTATION_PYTHON="/path/to/bundled/python3" \
"/path/to/bundled/node" docs/pitch/build.mjs
```

The finalizer checks package structure, geometry, fonts and importability, and refuses to overwrite an existing final deck. For a new revision, update the source's output/build filenames and its status/evidence notes first. Render and inspect every slide before publishing. The bundle is needed only to regenerate from source; ordinary slide edits need PowerPoint or another compatible editor.

See [the demo guide](../DEMO.md) for the spoken storyline and preparation. The deck does not prove voice activation, native audio hardware or scientific validity.
