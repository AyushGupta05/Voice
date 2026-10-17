#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
./script/test-bridge.sh
JDK_DIR="${LUMA_JAVA_HOME:-$(find "$PWD/.local/jdk" -path '*/Contents/Home' -type d -maxdepth 4 | head -1)}"
QUPATH_DIR="${LUMA_QUPATH_APP:-$PWD/.local/qupath-package/QuPath-0.7.0-arm64-app.pkg/Payload/QuPath-0.7.0-arm64.app}"
python3 - "$JDK_DIR/bin/java" "$QUPATH_DIR/Contents/app/*:dist/luma-qupath-bridge.jar:.local/bridge-test-classes" "$PWD/.local/javafx-cache" <<'PY'
import subprocess,sys
try:
    result=subprocess.run([sys.argv[1],'-Djava.util.prefs.PreferencesFactory=science.luma.qupath.MemoryPreferencesFactory',
        '-Dqupath.prefs.name=science.luma/test-contract/qupath/0.7','-Djavafx.cachedir='+sys.argv[3],
        '-cp',sys.argv[2],'science.luma.qupath.UIContractTest'],timeout=45)
    raise SystemExit(result.returncode)
except subprocess.TimeoutExpired:
    print('Isolated JavaFX test startup/execution timed out. Retry in a usable desktop session; this is not a passing UI check.',file=sys.stderr)
    raise SystemExit(1)
PY
