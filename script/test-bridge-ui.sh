#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
./script/test-bridge.sh
JDK_DIR="${LUMA_JAVA_HOME:-$(find "$PWD/.local/jdk" -path '*/Contents/Home' -type d -maxdepth 4 | head -1)}"
QUPATH_DIR="${LUMA_QUPATH_APP:-$PWD/.local/qupath-package/QuPath-0.7.0-arm64-app.pkg/Payload/QuPath-0.7.0-arm64.app}"
"$JDK_DIR/bin/java" -Djavafx.cachedir="$PWD/.local/javafx-cache" -cp "$QUPATH_DIR/Contents/app/*:dist/luma-qupath-bridge.jar:.local/bridge-test-classes" science.luma.qupath.UIContractTest
