# Set up Penny

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
git clone https://github.com/Sleep-On-It/Voice.git
cd Voice
python3 script/setup.py
./script/run-qupath.sh
./script/build_and_run.sh --verify
```

The bootstrap verifies SHA-256 hashes from `script/dependencies.json`, extracts official QuPath 0.7 and a full Temurin 25 JDK into `.local`, checks out pinned upstream sources/documentation, installs locked npm dependencies, and builds the bridge and local launcher.

The default QuPath runtime does not include `jdk.httpserver`, which this extension uses. The development launcher deliberately uses the full local JDK. Dropping the bridge JAR into an unmodified official app is insufficient with that runtime. `dist/QuPathLuma.app` uses links to the local installation; it is a development launcher, not a portable distributable.

The original extracted app and upstream source are preserved. After setup or launcher changes, run `npm run verify:qupath` for a complete local preservation check. It compares the stock app with a fresh temporary extraction of the pinned package and checks the separate launcher's documented changes. It makes no network/provider call and does not restart the running app. Failure never repairs or deletes an existing installation. Default bootstrap paths only; see [architecture](PENNY-ARCHITECTURE.md#preserving-qupath).

Packaging refuses to overwrite a launcher whose exact executable is running. To prepare an update without touching that instance, use `./script/package-qupath.sh --output dist/QuPathLumaPrepared.app`, then `npm run verify:qupath -- --launcher dist/QuPathLumaPrepared.app`. Do not launch it concurrently on the bridge port. Save/quit the existing QuPath normally, rebuild the default launcher and relaunch when ready. QuPath-managed settings use the supported `qupath.prefs.name` override with a per-checkout namespace; earlier running instances using only `java.util.prefs.userRoot` are not isolated on macOS. No existing settings are reset or migrated automatically.

The launch script reuses this project's already-running default instance without opening another or changing tokens/artifacts. After normal exit it refreshes the separate launcher's configuration/resources before opening it. It builds a missing bridge JAR; changed Java source still requires `./script/build-bridge.sh`. If exact process inventory fails, startup stops without changing anything. Custom prepared bundles are not launched through this script; avoid starting another instance on port 49556.

`script/build_and_run.sh` builds and ad-hoc signs `dist/Luma.app`, then launches it. The native app reuses an authenticated running gateway or starts and owns a Node child, so the gateway survives the launching shell. Normal Quit terminates only its own child. The Codex Run action calls this script. Left-click its waveform menu-bar icon to show/hide Penny; right-click for Quit. Close QuPath normally before rebuilding/restarting its adapter.

Open an image in QuPath. The image-type prompt is part of the real application; choose the interpretation appropriate to the dataset. Penny then follows the active image and selection. The quick actions focus, change detection display and save hierarchy checkpoints.

## Optional AWS-credit text control

The local quick actions/MCP need no model account. For text-agent control, read [AWS-TEXT.md](AWS-TEXT.md), authenticate an existing AWS profile with eligible credits and existing model access, then run `npm run enable:text`. Rebuild/restart Penny with `./script/build_and_run.sh --verify`. Type a request in the companion; risky scientific changes still wait for an exact human approval. Stop cancels unstarted work; it does not roll back completed operations or decide pending proposals. GPT-Live voice remains separately funding-blocked.

The configuration expires after 24 hours. Re-running activation rechecks funding for the same account and preserves all budget reservations. Do not delete the ledger to reset usage.

## Rebuild after changes

```sh
npm run check
./script/build-bridge.sh
./script/package-qupath.sh
./script/build_and_run.sh --verify
```

Restart QuPath to load a new bridge JAR. Restart the gateway to load TypeScript changes (`npm run dev` is not a watcher). Do not terminate unrelated Java or Node processes. Logs are `.local/gateway.log`, `.local/qupath.log` and `.local/qupath-error.log`.

Bridge builds compile into a fresh staging directory and replace the complete JAR on success. Failed compilation/package creation leaves the previous JAR intact. This prevents partially rewritten archives and stale compiled classes; it does not update classes already loaded in QuPath.

Build-only packages a new binary while preserving the running instance. Launch/restart modes wait until compilation and signing succeed, then request normal termination only for this project’s exact executable path. They do not stop same-named companions in other checkouts. The running instance keeps its previously loaded binary until an explicit restart.

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

Alternatively run `npm run mcp` from the repo root. The MCP reads the authenticated running gateway’s `/tools` catalog on each `tools/list` request, preserving its actual descriptions and argument schemas. It does not advertise newer source-only tools or fall back to them when the gateway is unavailable. The gateway validates calls using the same running registry. Clients that cache tool discovery should reconnect after a gateway upgrade. The MCP exposes a `qupath://guide` resource and a `qupath-researcher` prompt. It has no approval tool. Approve or decline proposals in the companion. `qupath_action_status` retrieves the eventual outcome.

Run `npm run doctor` for read-only setup, source-pin, running-schema and connection diagnostics. It prints no tokens or image names, installs nothing and returns a nonzero status when a core check fails. Voice readiness remains false under the current funding policy. File checks confirm existence, not a fresh build.

The doctor also compares the loaded Java extension's cached source identity and declared operations with `.local/bridge-build.json` and the completed JAR hash. It can therefore distinguish a reachable old bridge from the current built extension, independently of gateway schema agreement. A mismatch never closes QuPath: save/quit it normally, rebuild if needed, then relaunch and restart Penny after gateway changes. `adapterReadiness` is also included in new context responses. It compares completed build outputs, not unbuilt source edits; build explicitly after changing Java. Manual builds without the generated stamp remain usable but unverified by this diagnostic.

Run `npm run smoke` to test a real MCP client through the gateway and active QuPath session. This smoke test is read-only. It compares the MCP catalog with the running gateway and reports source schema agreement separately: a successful read-only smoke does not mean newer source tools or Java operations have been loaded.

## Local command-line inspection

```sh
npm run tool -- qupath_context
npm run tool -- qupath_commands '{"query":"measurements"}'
npm run tool -- qupath_knowledge '{"query":"cell detection"}'
```

These commands use the same validator and executor as MCP. With the default `LUMA_APPROVALS=auto`, mutating calls execute immediately (journaled, checkpointed); with `LUMA_APPROVALS=review` they only prepare a proposal for the companion to approve.

## Tokens and local data

Random tokens are generated in `.local/state` with restrictive permissions. The companion control token can decide approvals; the model/MCP gateway token cannot. The bridge token authenticates the gateway to QuPath. Do not put any of them in MCP configuration, screenshots, issues or Git.

`.local/state/actions.jsonl` retains reviewed action parameters, context and results for local reproducibility. It can contain local image names and approved script source. Images, raw microphone recordings and API credentials are not deliberately logged. The audit journal is local and is not a durable backup of QuPath project state.

## Other operating systems

The gateway and QuPath API design are portable. The companion and bootstrap are currently macOS Apple Silicon implementations. Windows/Linux adapters, installers and native overlays have not been built or verified. Do not advertise cross-platform support from the architecture alone.

## Updating the loaded adapter and reference tools

QuPath loads the bridge at application startup. After Java adapter changes, save your work and quit the project instance normally, then rebuild/relaunch through the existing scripts. Restart Penny and reconnect an MCP client after tool-registry changes. A new source checkout or compiled jar does not replace classes already loaded in a running QuPath process. Never force-close unsaved research work to update the adapter.

`npm run smoke:api` checks the pinned reference corpus independently of QuPath. The api_search/api_read tools use the configured commit’s Java blobs; they can work without an open image. Missing source returns setup guidance while the other local tools remain available. See [API-LOOKUP.md](API-LOOKUP.md).
