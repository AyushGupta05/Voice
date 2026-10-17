# Pitch material

[Luma-Pitch-v4.pptx](Luma-Pitch-v4.pptx) is the current seven-slide editable deck. It keeps the actual QuPath images and now states that real AWS-credit OpenAI text works while live voice remains funding-blocked. [Version 3](Luma-Pitch-v3.pptx) is retained as an earlier snapshot.

The measured analysis fixture is recorded in [integration-evidence.json](integration-evidence.json). Its counts describe a chosen detection and threshold on a public sample, not validated biological ground truth. The separate [direct text model](text-model-evidence.json) and [authenticated companion-route](companion-text-evidence.json) records distinguish actual inference from protocol mocks and native foreground inspection.

All slide text is editable in PowerPoint. [build.mjs](build.mjs) is the authoring source; it uses the Codex bundled `@oai/artifact-tool` runtime rather than a public npm dependency. It makes no provider calls. The source locates repository images through its own path, and keeps drafts, renders and validation reports under `.local/pitch-build-v4`.

To rebuild in an environment with that bundled runtime, obtain Node, node_modules and Python paths from `load_workspace_dependencies`, then supply them along with the installed presentation skill directory:

```sh
RUNTIME_NODE_MODULES="/path/to/bundled/node_modules" \
PRESENTATION_SKILL_DIR="/path/to/presentations/skill" \
PRESENTATION_PYTHON="/path/to/bundled/python3" \
"/path/to/bundled/node" docs/pitch/build.mjs
```

The finalizer checks package structure, geometry, fonts and importability, and refuses to overwrite an existing final deck. For a new revision, update the source's output/build filenames and its status/evidence notes first. Render and inspect every slide before publishing. The bundle is needed only to regenerate from source; ordinary slide edits need PowerPoint or another compatible editor.

See [the demo guide](../DEMO.md) for the spoken storyline and preparation. The deck does not prove voice activation, native audio hardware or scientific validity.
