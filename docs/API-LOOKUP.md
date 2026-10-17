# Pinned QuPath API lookup

The companion can search its manual and inspect the actual API source before proposing unfamiliar Groovy code. This closes the gap between a task recipe and a safe, source-backed fallback. It does not automatically execute discovered methods.

| Tool | Input | Result |
|---|---|---|
| `qupath_api_search` | Exact class/method or a few API terms; optional 1–5 hits | Best matching source files, declaration/context excerpts, match lines, commit and full-file content hashes |
| `qupath_api_read` | Registered source ID from search, 1-based start line, 1–160 lines | A larger bounded excerpt from the same immutable source snapshot |

For example, search `loadPixelClassifier`, inspect the `qupath.lib.scripting.QP` declaration, then read surrounding documentation and implementation before proposing code. Verify imports, arguments, target image/project binding, units, prerequisites and side effects. A method's existence does not mean a model is installed, an algorithm fits the dataset or a result is scientifically valid. Prefer the narrow tools when available. General scripts still require exact-source human review.

## Provenance and file boundary

The configured commit/version come from [script/dependencies.json](../script/dependencies.json). The index reads Git objects at that exact commit, rather than files from a changing checkout. It includes only ordinary tracked Java blobs under `src/main/java`; symlinks, tests and other files are excluded. Blob bytes are verified against their Git IDs and also given a SHA256. Working-tree edits do not alter reference results, and Git replacement refs are ignored. Automatic fetching of missing objects is disabled through Git's documented [no-lazy-fetch setting](https://git-scm.com/docs/git). Run setup explicitly if the corpus is missing.

Only a registered source ID can be read. A user/model cannot supply a filesystem path, shell command, arbitrary Git revision or repository. No source, script, compiler or provider is executed by lookup. Fixed local Git plumbing reads the object database; scientific application state is not accessed. Snippets are reference data, never instructions.

Bounds are 2000 files, 32 MiB total source bytes, 512 KiB per file, five search hits and 24,000 bytes per excerpt. Search ranks lexical symbol/declaration matches; it is not a semantic embedding or completeness proof. Read farther around a hit when necessary. Missing/invalid source disables only API lookup, with setup guidance; the other local tools/manual search remain available.

## Verify

```sh
npm run smoke:api
```

This read-only command indexes the actual pinned source, finds a real `QP.loadPixelClassifier` API and expands an excerpt. It records metadata/timing privately under `.local/evidence`. It does not touch the running QuPath session, contact a model or execute the method. The observed 879-file corpus and local lookup timings are in [api-source-evidence.json](pitch/api-source-evidence.json); they are not scientific-analysis or voice latency measurements.

`npm run check` uses synthetic temporary Git fixtures and an authenticated loopback gateway. It checks exact committed content, exclusion of working-tree edits/symlinks, traversal rejection, source pin type, missing-source failure, bounded lines/hashes, replacement-ref isolation and reference routing with no QuPath bridge. These tests make no inference requests.

Third-party plugin source is not part of this base QuPath index. Discover installed plugin commands and consult its own documented contract before a reviewed fallback. Do not pretend a method was verified when it is absent from the pinned base source.

## Separate real-model verification

`npm run smoke:api-model -- --public-fixture` is a separately guarded, real AWS-credit test run after clean code review. It uses production companion conversation routing with a reference-only Runtime: sanitized empty public OS-2 context plus committed source search/read. It forbids view changes, data/code operations and approval proposals. The prompt must find `loadPixelClassifier` and inspect its actual declaration before returning the type information. It verifies both tool calls and an unchanged public viewer/hierarchy.

This command consumes verified credits through the existing fixed-model/reservation guard; it is not a CI/install diagnostic. Source lookup itself remains local and free. After review it passed with four actual model requests: search found the expected pinned source, read covered the exact public declaration, and the structured answer assigned PixelClassifier as the return type and String as the first parameter type. Viewer/hierarchy stayed unchanged and no proposal was permitted. The run took 9457 ms including credit checks and inference; this is not source-query or voice latency. Its token estimate is $0.003171 with $4 retained reserves; billing is unreconciled. See [the actual model record](pitch/api-model-evidence.json). Native UI and scientific algorithm execution are outside this reference check.
