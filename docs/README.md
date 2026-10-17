# Documentation map

Luma adds an agent and conversation layer to the original QuPath application. Start with the guide for your task; use the schema reference for exact accepted arguments.

| Reader or task | Start here | Next |
|---|---|---|
| New user | [Setup](SETUP.md) | [Foreground demo](LIVE-DEMO.md), [current readiness](READINESS.md) |
| MCP client developer | [MCP handbook](mcp/README.md) | [Connection and operation](mcp/OPERATIONS.md), [all 32 tools](mcp/TOOLS.md) |
| Researcher planning an analysis | [Worked workflows](mcp/WORKFLOWS.md) | [Scientific operating guide](../skills/qupath/SKILL.md), [dataset provenance](DATASETS.md) |
| Contributor adding a capability | [Contributing](CONTRIBUTING.md) | [Architecture](ARCHITECTURE.md), [protocol](PROTOCOL.md), [documentation maintenance](mcp/COVERAGE.md#maintaining-this-handbook) |
| Reviewer checking a claim | [Tool coverage and evidence](mcp/COVERAGE.md) | [Capability matrix](CAPABILITIES.md), [build journal](BUILD-JOURNAL.md) |
| Voice or model integrator | [Voice integration](VOICE.md) | [AWS text adapter](AWS-TEXT.md), [funding policy](FUNDING.md) |
| Presenter | [Demo guide](DEMO.md) | [Editable pitch](pitch/README.md), [product brief](PRODUCT.md) |

## Which document is authoritative?

- The running MCP connection's `tools/list` is authoritative for its available tools and input schemas. A local checkout can be newer than the loaded gateway or Java adapter.
- [tools.ts](../packages/gateway/src/tools.ts) defines the source registry. Its generated [readable reference](TOOL-REFERENCE.md) and [complete JSON registry](tool-schemas.json) retain every schema constraint. Regenerate them together; do not edit them by hand.
- The [MCP handbook](mcp/README.md) explains practical semantics, examples, failure handling and evidence. It complements the generated schemas.
- [READINESS.md](READINESS.md) and the [build journal](BUILD-JOURNAL.md) distinguish built, loaded and demonstrated behavior. Dated evidence records are historical observations, not a guarantee about the current running session.

Local tool execution, a model conversation and hardware voice are separate paths. A successful local MCP call does not establish provider funding, microphone capture or end-to-end voice. No example in this documentation grants permission to run a real mutation fixture or approve a production proposal automatically.
