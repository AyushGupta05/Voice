#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
APP="$PWD/dist/QuPathLuma.app"
if python3 script/companion-process.py running "$APP/Contents/MacOS/QuPath-0.7.0-arm64" >/dev/null; then
  echo 'This project QuPath instance is already running; no second instance was opened. Save and quit normally before loading an adapter update.'
  exit 0
else
  PROCESS_STATUS=$?
  if [[ $PROCESS_STATUS -ne 1 ]]; then echo 'Could not verify QuPath process state; nothing was launched or changed.' >&2; exit "$PROCESS_STATUS"; fi
fi
mkdir -p .local/state
# Tell the companion-owned gateway which application profile to load (LUMA_APP overrides).
printf qupath > .local/state/app
if [[ ! -f .local/state/bridge-token ]]; then (umask 077; openssl rand -hex 32 > .local/state/bridge-token); fi
if [[ ! -f dist/luma-qupath-bridge.jar ]]; then ./script/build-bridge.sh; fi
# Refresh configuration/resources only after normal exit; packaging rechecks
# exact process state before writing. Source edits still need an explicit build.
./script/package-qupath.sh
/usr/bin/open -n "$APP" --env "LUMA_BRIDGE_TOKEN=$(cat .local/state/bridge-token)" --env "LUMA_ROOT=$PWD" --stdout "$PWD/.local/qupath.log" --stderr "$PWD/.local/qupath-error.log" --args --quiet "$@"
