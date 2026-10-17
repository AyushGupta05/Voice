#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .local/state .local/qupath-prefs
if [[ ! -f .local/state/bridge-token ]]; then (umask 077; openssl rand -hex 32 > .local/state/bridge-token); fi
if [[ ! -d dist/QuPathLuma.app ]]; then ./script/build-bridge.sh; ./script/package-qupath.sh; fi
/usr/bin/open -n "$PWD/dist/QuPathLuma.app" --env "LUMA_BRIDGE_TOKEN=$(cat .local/state/bridge-token)" --stdout "$PWD/.local/qupath.log" --stderr "$PWD/.local/qupath-error.log" --args --quiet "$@"
