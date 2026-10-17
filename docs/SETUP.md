# Set up Luma

## What works today

The native macOS companion, authenticated local QuPath adapter and MCP server are implemented. They can observe and operate the real application. Continuous voice is not enabled: an AWS-credit-covered OpenAI realtime/GPT-Live endpoint has not been verified. Local tools do not require model API calls.

## Prerequisites

- Apple Silicon Mac, macOS 14 or later.
- Xcode with its command-line tools selected (`xcode-select -p`).
- Node.js 22+, npm, Git and Python 3.9+.
- Internet access for the initial official downloads and npm installation.
- Approximately 2–4 GB for the app, full JDK, source and build caches; the optional OS-2 slide adds about 1 GB.

No cloud account, API key or paid resource is needed for the local build. Nothing is installed globally by the bootstrap.

## Install and run

```sh
git clone https://github.com/AyushGupta05/Voice.git
cd Voice
python3 script/setup.py
./script/run-qupath.sh
./script/build_and_run.sh --verify
```

The bootstrap verifies SHA-256 hashes from `script/dependencies.json`, extracts official QuPath 0.7 and a full Temurin 25 JDK into `.local`, checks out pinned upstream sources/documentation, installs locked npm dependencies, and builds the bridge and local launcher.

The default QuPath runtime does not include `jdk.httpserver`, which this extension uses. The development launcher deliberately uses the full local JDK. Dropping the bridge JAR into an unmodified official app is insufficient with that runtime. `dist/QuPathLuma.app` uses links to the local installation; it is a development launcher, not a portable distributable.

`script/build_and_run.sh` builds and ad-hoc signs `dist/Luma.app`, then launches it. The native app reuses an authenticated running gateway or starts and owns a Node child, so the gateway survives the launching shell. Normal Quit terminates only its own child. The Codex Run action calls this script. Left-click its waveform menu-bar icon to show/hide Luma; right-click for Quit. Close QuPath normally before rebuilding/restarting its adapter.

Open an image in QuPath. The image-type prompt is part of the real application; choose the interpretation appropriate to the dataset. Luma then follows the active image and selection. The quick actions focus, change detection display and save hierarchy checkpoints.

## Rebuild after changes

```sh
npm run check
./script/build-bridge.sh
./script/package-qupath.sh
./script/build_and_run.sh --verify
```

Restart QuPath to load a new bridge JAR. Restart the gateway to load TypeScript changes (`npm run dev` is not a watcher). Do not terminate unrelated Java or Node processes. Logs are `.local/gateway.log`, `.local/qupath.log` and `.local/qupath-error.log`.

Useful companion launch modes are `--build-only`, `--verify`, `--logs`, `--telemetry` and `--debug`.

## MCP connection

Keep the gateway and QuPath running. Configure a desktop MCP client to run Node with the installed TypeScript loader:

```json
{
  "mcpServers": {
    "luma-qupath": {
      "command": "node",
      "args": ["--import", "/ABSOLUTE/PATH/Voice/node_modules/tsx/dist/loader.mjs", "/ABSOLUTE/PATH/Voice/packages/gateway/src/mcp.ts"],
      "env": { "LUMA_ROOT": "/ABSOLUTE/PATH/Voice" }
    }
  }
}
```

Alternatively run `npm run mcp` from the repo root. The MCP exposes tools, a `qupath://guide` resource and a `qupath-researcher` prompt. It has no approval tool. Approve or decline proposals in the companion. `qupath_action_status` retrieves the eventual outcome.

Run `npm run doctor` for read-only setup, source-pin, running-schema and connection diagnostics. It prints no tokens or image names, installs nothing and returns a nonzero status when a core check fails. Voice readiness remains false under the current funding policy. File checks confirm existence, not a fresh build.

Run `npm run smoke` to test a real MCP client through the gateway and active QuPath session. This smoke test is read-only.

## Local command-line inspection

```sh
npm run tool -- qupath_context
npm run tool -- qupath_commands '{"query":"measurements"}'
npm run tool -- qupath_knowledge '{"query":"cell detection"}'
```

These commands use the same validator and executor as MCP. Mutating calls only prepare a proposal; they do not auto-approve it.

## Tokens and local data

Random tokens are generated in `.local/state` with restrictive permissions. The companion control token can decide approvals; the model/MCP gateway token cannot. The bridge token authenticates the gateway to QuPath. Do not put any of them in MCP configuration, screenshots, issues or Git.

`.local/state/actions.jsonl` retains reviewed action parameters, context and results for local reproducibility. It can contain local image names and approved script source. Images, raw microphone recordings and API credentials are not deliberately logged. The audit journal is local and is not a durable backup of QuPath project state.

## Other operating systems

The gateway and QuPath API design are portable. The companion and bootstrap are currently macOS Apple Silicon implementations. Windows/Linux adapters, installers and native overlays have not been built or verified. Do not advertise cross-platform support from the architecture alone.
