# Architecture

```mermaid
flowchart LR
    Researcher[Researcher: voice / text / mouse] --> Companion[Native macOS companion]
    Companion <--> Gateway[Loopback gateway]
    Gateway -. planned .-> Voice[GPT-Live: funding blocked]
    Gateway <--> MCP[MCP stdio clients]
    Gateway --> Approval[Human approval + bound action]
    Approval --> Bridge[Authenticated QuPath extension]
    Bridge <--> QuPath[Real viewer / selection / image / hierarchy]
    Gateway --> Knowledge[Versioned QuPath task knowledge]
    Gateway --> Audit[Local action journal]
```

## Boundaries

The extension owns application state and runs JavaFX mutations on the UI thread. The gateway owns provider credentials, tool validation, approvals and conversation execution. The companion owns microphone capture, audio playback and presentation. MCP clients call the same tool executor as voice so validation and approval behavior cannot drift.

No arbitrary shell tool is exposed. General Groovy execution is available only as an explicit approval-gated escape hatch. QuPath plugins and scripts can access local files and run code; approval must show the exact source and be scoped to one invocation.

Only loopback interfaces are used. Per-install random tokens protect the bridge and gateway. Browser-origin requests are rejected. Secrets and image data stay out of Git. Structured observations are preferred to whole-screen capture; no unrelated application screenshot is sent by default.

## Provider and budget policy

The separate Realtime and GPT-Live transports and provider-injected backend are locally protocol-tested; see VOICE.md. Neither transport is constructed by the production server. A provider factory and native capture/playback wiring remain. Provider configuration is not proof of AWS credit eligibility. A verified local funding attestation, endpoint and hard session duration limit are required before opening a paid session. No automatic provider fallback can introduce cash billing.

## Extensibility

Application adapters implement capability discovery, context observation and tool execution. QuPath-specific APIs remain in the adapter. Knowledge is Markdown with source references and version notes. Add a narrow structured operation before resorting to general scripting.

The native companion supervises a project-local Node child when no authenticated gateway is available. It reuses external development gateways and terminates only a child it owns. The launcher records the resolved Node executable in the local app bundle. No login item or system service is installed. Quitting the companion does not roll back or cancel QuPath work already underway.
