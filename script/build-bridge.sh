#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
JDK_DIR="${LUMA_JAVA_HOME:-$(find "$PWD/.local/jdk" -path '*/Contents/Home' -type d -maxdepth 4 | head -1)}"
QUPATH_DIR="${LUMA_QUPATH_APP:-$PWD/.local/qupath-package/QuPath-0.7.0-arm64-app.pkg/Payload/QuPath-0.7.0-arm64.app}"
mkdir -p .local/bridge-classes dist
"$JDK_DIR/bin/javac" --release 25 -cp "$QUPATH_DIR/Contents/app/*" -d .local/bridge-classes adapters/qupath/src/main/java/science/luma/qupath/*.java
cp -R adapters/qupath/src/main/resources/. .local/bridge-classes/
"$JDK_DIR/bin/jar" --create --file dist/luma-qupath-bridge.jar -C .local/bridge-classes .
echo 'Built dist/luma-qupath-bridge.jar'
