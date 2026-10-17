#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
./script/build-bridge.sh
JDK_DIR="${LUMA_JAVA_HOME:-$(find "$PWD/.local/jdk" -path '*/Contents/Home' -type d -maxdepth 4 | head -1)}"
QUPATH_DIR="${LUMA_QUPATH_APP:-$PWD/.local/qupath-package/QuPath-0.7.0-arm64-app.pkg/Payload/QuPath-0.7.0-arm64.app}"
mkdir -p .local/bridge-test-classes
"$JDK_DIR/bin/javac" --release 25 -cp "$QUPATH_DIR/Contents/app/*:dist/luma-qupath-bridge.jar" -d .local/bridge-test-classes adapters/qupath/src/test/java/science/luma/qupath/*.java
"$JDK_DIR/bin/java" -Djava.awt.headless=true -Djavafx.cachedir="$PWD/.local/javafx-cache" -Djava.util.prefs.PreferencesFactory=science.luma.qupath.MemoryPreferencesFactory -Dqupath.prefs.name=science.luma/test-contract/qupath/0.7 -Dorg.bytedeco.javacpp.cachedir="$PWD/.local/javacpp-cache" -cp "$QUPATH_DIR/Contents/app/*:dist/luma-qupath-bridge.jar:.local/bridge-test-classes" science.luma.qupath.DetectionContractTest

"$JDK_DIR/bin/java" -Djava.awt.headless=true -Djavafx.cachedir="$PWD/.local/javafx-cache" -Djava.util.prefs.PreferencesFactory=science.luma.qupath.MemoryPreferencesFactory -Dqupath.prefs.name=science.luma/test-contract/qupath/0.7 -Dorg.bytedeco.javacpp.cachedir="$PWD/.local/javacpp-cache" -cp "$QUPATH_DIR/Contents/app/*:dist/luma-qupath-bridge.jar:.local/bridge-test-classes" science.luma.qupath.HierarchyContractTest
"$JDK_DIR/bin/java" -Djava.awt.headless=true -Djavafx.cachedir="$PWD/.local/javafx-cache" -Djava.util.prefs.PreferencesFactory=science.luma.qupath.MemoryPreferencesFactory -Dqupath.prefs.name=science.luma/test-contract/qupath/0.7 -Dorg.bytedeco.javacpp.cachedir="$PWD/.local/javacpp-cache" -cp "$QUPATH_DIR/Contents/app/*:dist/luma-qupath-bridge.jar:.local/bridge-test-classes" science.luma.qupath.ScriptContractTest
"$JDK_DIR/bin/java" -Djava.awt.headless=true -Djavafx.cachedir="$PWD/.local/javafx-cache" -Djava.util.prefs.PreferencesFactory=science.luma.qupath.MemoryPreferencesFactory -Dqupath.prefs.name=science.luma/test-contract/qupath/0.7 -Dorg.bytedeco.javacpp.cachedir="$PWD/.local/javacpp-cache" -cp "$QUPATH_DIR/Contents/app/*:dist/luma-qupath-bridge.jar:.local/bridge-test-classes" science.luma.qupath.DataContractTest

"$JDK_DIR/bin/java" -Djava.awt.headless=true -Djava.util.prefs.PreferencesFactory=science.luma.qupath.MemoryPreferencesFactory -Dqupath.prefs.name=science.luma/test-contract/qupath/0.7 -cp "$QUPATH_DIR/Contents/app/*:dist/luma-qupath-bridge.jar:.local/bridge-test-classes" science.luma.qupath.PreferenceContractTest
