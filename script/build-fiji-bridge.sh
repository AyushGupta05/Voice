#!/bin/bash
# Compiles adapters/fiji against the pinned Fiji's own jars with the local JDK and writes dist/luma-fiji-bridge.jar.
set -euo pipefail
cd "$(dirname "$0")/.."
JDK_DIR="${LUMA_JAVA_HOME:-$(find "$PWD/.local/jdk" -path '*/Contents/Home' -type d -maxdepth 4 | head -1)}"
FIJI_APP="${LUMA_FIJI_APP:-$PWD/.local/fiji/Fiji.app}"
if [[ ! -d "$FIJI_APP/jars" ]]; then echo "Fiji is not installed at $FIJI_APP; run python3 script/setup-fiji.py first." >&2; exit 1; fi
mkdir -p .local dist
BUILD_DIR="$(mktemp -d "$PWD/dist/.fiji-bridge-build.XXXXXX")"
trap 'rm -rf "$BUILD_DIR"' EXIT
python3 script/bridge-identity-fiji.py prepare "$PWD" "$BUILD_DIR"
# Fiji's SciJava annotation processor (on the class path) writes META-INF/json/org.scijava.plugin.Plugin for the @Plugin classes.
"$JDK_DIR/bin/javac" --release 21 -proc:full -Xlint:-options -cp "$FIJI_APP/jars/*" -d "$BUILD_DIR/classes" "$BUILD_DIR"/source/java/science/luma/fiji/*.java
cp -R "$BUILD_DIR/source/resources/." "$BUILD_DIR/classes/"
if [[ ! -f "$BUILD_DIR/classes/META-INF/json/org.scijava.plugin.Plugin" ]]; then echo 'SciJava plugin index was not generated; the Bridge status command and auto-start service would be invisible to Fiji.' >&2; exit 1; fi
python3 script/bridge-identity-fiji.py stamp "$BUILD_DIR"
"$JDK_DIR/bin/jar" --create --file "$BUILD_DIR/bridge.jar" -C "$BUILD_DIR/classes" .
python3 script/bridge-identity-fiji.py finish "$BUILD_DIR"
# Replace atomically on the same filesystem: a failed compile leaves the previous jar and manifest intact.
mv -f "$BUILD_DIR/bridge.jar" dist/luma-fiji-bridge.jar
mv -f "$BUILD_DIR/bridge-build.json" .local/fiji-bridge-build.json
echo 'Built dist/luma-fiji-bridge.jar'
