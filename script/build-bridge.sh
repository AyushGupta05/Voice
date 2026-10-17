#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
JDK_DIR="${LUMA_JAVA_HOME:-$(find "$PWD/.local/jdk" -path '*/Contents/Home' -type d -maxdepth 4 | head -1)}"
QUPATH_DIR="${LUMA_QUPATH_APP:-$PWD/.local/qupath-package/QuPath-0.7.0-arm64-app.pkg/Payload/QuPath-0.7.0-arm64.app}"
mkdir -p dist
BUILD_DIR="$(mktemp -d "$PWD/dist/.bridge-build.XXXXXX")"
trap 'rm -rf "$BUILD_DIR"' EXIT
"$JDK_DIR/bin/javac" --release 25 -cp "$QUPATH_DIR/Contents/app/*" -d "$BUILD_DIR/classes" adapters/qupath/src/main/java/science/luma/qupath/*.java
cp -R adapters/qupath/src/main/resources/. "$BUILD_DIR/classes/"
"$JDK_DIR/bin/jar" --create --file "$BUILD_DIR/bridge.jar" -C "$BUILD_DIR/classes" .
# Compile/package from scratch, then replace on the same filesystem. A failed
# build leaves the existing JAR intact; an active process is never handed a
# partially rewritten archive. Already loaded classes still require restart.
mv -f "$BUILD_DIR/bridge.jar" dist/luma-qupath-bridge.jar
echo 'Built dist/luma-qupath-bridge.jar'
