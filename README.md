# Luma — make scientific software conversational

A voice-native scientific companion inspired by [Clicky](https://github.com/farzaa/clicky), built for **QuPath** at Reinvent the Wheel: Tools for Science (17–18 October 2026).

Speak while working in the real application. Luma reads the current image and selection, uses structured tools to act, and shows what it is doing. The researcher can interrupt, inspect parameters, approve risky operations, and recover earlier results.

## Build in progress

See [the build journal](docs/BUILD-JOURNAL.md), [product brief](docs/PRODUCT.md), and [architecture](docs/ARCHITECTURE.md). Status and limitations are recorded explicitly; configured support is not evidence of a live funded voice session.

## Repository layout

- `apps/companion/` — native macOS floating companion and pointer overlay.
- `packages/gateway/` — local agent runtime, OpenAI realtime relay, approvals, audit history and MCP server.
- `adapters/qupath/` — QuPath extension exposing authenticated local operations.
- `skills/qupath/` — task guidance and application knowledge for agents.
- `script/` — reproducible setup, build and verification entrypoints.
- `docs/` — architecture, contributor guidance, demo, pitch and verification evidence.

Existing Apache-2.0 license applies to original project code. Upstream QuPath is GPLv3 and remains separately licensed; no QuPath source is relicensed by this repository.
