#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
JDK_DIR="${LUMA_JAVA_HOME:-$(find "$PWD/.local/jdk" -path '*/Contents/Home' -type d -maxdepth 4 | head -1)}"
QUPATH_DIR="${LUMA_QUPATH_APP:-$PWD/.local/qupath-package/QuPath-0.7.0-arm64-app.pkg/Payload/QuPath-0.7.0-arm64.app}"
mkdir -p .local dist
BUILD_DIR="$(mktemp -d "$PWD/dist/.bridge-build.XXXXXX")"
trap 'rm -rf "$BUILD_DIR"' EXIT
python3 script/bridge-identity.py prepare "$PWD" "$BUILD_DIR"
"$JDK_DIR/bin/javac" --release 25 -cp "$QUPATH_DIR/Contents/app/*" -d "$BUILD_DIR/classes" "$BUILD_DIR"/source/java/science/luma/qupath/*.java
cp -R "$BUILD_DIR/source/resources/." "$BUILD_DIR/classes/"
python3 script/bridge-identity.py stamp "$BUILD_DIR"
"$JDK_DIR/bin/jar" --create --file "$BUILD_DIR/bridge.jar" -C "$BUILD_DIR/classes" .
python3 script/bridge-identity.py finish "$BUILD_DIR"
# Compile/package from scratch, then replace on the same filesystem. Failed
# compilation/package creation leaves the prior JAR intact; no process sees a
# partially rewritten archive. Already loaded classes still require restart.
# Manifest follows the complete JAR; diagnostics fail closed during a mismatch.
mv -f "$BUILD_DIR/bridge.jar" dist/luma-qupath-bridge.jar
mv -f "$BUILD_DIR/bridge-build.json" .local/bridge-build.json
echo 'Built dist/luma-qupath-bridge.jar'
