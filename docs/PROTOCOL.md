# Local protocol and adapter contract

## Processes

The Swift companion talks to the gateway on `127.0.0.1:49555`. The gateway talks to QuPath on `127.0.0.1:49556`. All requests use random per-install bearer tokens from `.local/state`. Browser Origin headers are rejected. Neither service exposes a network-wide listener or a CORS policy.

MCP annotations distinguish scientific read/view/data/code policy from cache side effects: creating a hierarchy checkpoint is not idempotent and is not advertised as read-only or additive-only, because it adds volatile recovery state and can evict an older snapshot. It still requires no scientific mutation approval.

The MCP stdio process is a client of the existing gateway, not a second independent action runtime. A voice session, MCP client and companion therefore share approvals and action history.

## Gateway HTTP

| Method and route | Credential | Result |
|---|---|---|
| `GET /health` | Gateway or companion | Voice blocker, text-configuration status, real QuPath context, recent actions, bridge error if unavailable |
| `GET /tools` | Gateway or companion | Current tool schemas and risk classes |
| `POST /tools/call` | Gateway or companion | `{name, arguments}` validated against the tool schema |
| `POST /approvals/:id` | Companion only | `{approved: boolean}` decides one pending action |
| WebSocket `/events` | Companion only | Connection, action and tool events; voice-start reports funding blocker |

The JSON request limit is 256 KiB. Unknown fields and invalid tool arguments fail schema validation. A model cannot attach an `approved` flag to tool arguments. MCP has no route for obtaining the companion token and no approval tool.

## Approval state machine

```mermaid
stateDiagram-v2
    [*] --> pending: proposal captures exact arguments and context
    pending --> denied: human declines
    pending --> expired: five minutes elapse
    pending --> running: human approves once
    running --> completed: adapter verifies context and executes
    running --> failed: state changed or execution failed
```

The executor claims an action before awaiting work, preventing duplicate approval clicks from executing it twice. Data/view operations use a shared serial queue. Approval compares the real image, selected IDs, hierarchy revision and z/t plane. Relevant image property changes also advance revision.

For JavaFX control actions, the proposal includes the window/control label and reviewed dialog fingerprint. Execution rejects changed control values, disabled or vanished targets and actions behind another modal dialog. A dispatched menu or button may still open a dialog, so the result explicitly does not claim completion.

A failed operation can have partial effects. Inspect QuPath and the checkpoint before retrying. Network timeouts do not prove the operation stopped.

## Bridge HTTP

Authenticated `POST /execute` accepts:

```json
{
  "operation": "focus",
  "arguments": {"x": 1000, "y": 1500, "downsample": 2},
  "expectedContext": "optional opaque observed context key"
}
```

Risky bridge calls require the gateway's approved invocation and expected context. The bridge is a trusted local component, not a sandbox against a malicious process that can read its token. General scripts execute with QuPath's local privileges.

The bridge reads and mutates JavaFX-owned state on the UI thread. Plugin detection uses QuPath's task runner. Modal menu/button actions dispatch after returning so HTTP completion does not deadlock on an open dialog. The JavaFX task has a 120-second wait limit. A queued task is cancelled when the wait expires, but an already-running task may still complete.

## Results and units

Coordinates refer to full-resolution image pixels with explicit image plane. Pointer results use JavaFX screen coordinates; the native overlay maps them to the matching macOS display. Smaller downsample means greater zoom.

A region scope tests object centroids inside the selected ROI on the same plane. List results contain an untruncated total and bounded rows. The measurement API returns raw named measurements and explicit non-finite markers rather than invented numeric values.

## New application adapter

Implement `Adapter.execute(operation, arguments, options)`. Start with observation, capability discovery, a reversible view action, and one scientifically useful operation. Keep application-specific APIs in the adapter. Add knowledge with version/source provenance. Reuse the gateway's validation, approvals and action events. Do not let a new voice provider or MCP transport invent a parallel approval path.

Detection is an exception to synchronous JavaFX dispatch: preparation and final observations run on JavaFX, while plugin orchestration runs on the request worker using a captured immutable list of approved parent objects. Other gateway mutations reject while detection is running. The native progress dialog provides cancellation. A client timeout can still leave an analysis running, so inspect that dialog before retrying. Detection results explicitly distinguish plugin execution from verified segmentation completion.


## Researcher text conversation

Authenticated companion `/events` peers may send `{ "type": "text", "text": "..." }` (1–4000 characters) or `{ "type": "stop_task" }`. A text request creates backend work only through the configured AWS-credit adapter. It cannot select a provider/model or enable voice. Responses include `task_state`, `text_progress`, `text_result` and `text_error`; full results stay separate from concise progress. Existing `tool` and `action` events report real operations and human decisions.

Each peer owns a bounded conversation and backend result history. A new message aborts its older task; Stop/disconnect cancels unstarted/cooperative work and suppresses late answers. Already executed operations and pending human proposals remain. Human approval still requires the companion HTTP decision route; text such as “yes” is never an approval token. The production entry point always uses the credit-checked Bedrock client. Programmatic server options exist for synthetic/restricted test fixtures, not as an HTTP or environment provider bypass.

`/health.text.configured` reports a valid local configuration, not current credit availability or model success; fresh funding checks occur on each request. The voice status remains independently disabled.
