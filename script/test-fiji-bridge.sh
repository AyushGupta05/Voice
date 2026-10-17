#!/bin/bash
# Contract tests for the Fiji bridge: pure geometry/identity checks headless, then a windowless ImageJ (NO_SHOW) session on a synthetic image.
set -euo pipefail
cd "$(dirname "$0")/.."
./script/build-fiji-bridge.sh
JDK_DIR="${LUMA_JAVA_HOME:-$(find "$PWD/.local/jdk" -path '*/Contents/Home' -type d -maxdepth 4 | head -1)}"
FIJI_APP="${LUMA_FIJI_APP:-$PWD/.local/fiji/Fiji.app}"
CP="$FIJI_APP/jars/*:dist/luma-fiji-bridge.jar"
mkdir -p .local/fiji-bridge-test-classes
"$JDK_DIR/bin/javac" --release 21 -proc:none -Xlint:-options -cp "$CP" -d .local/fiji-bridge-test-classes adapters/fiji/src/test/java/science/luma/fiji/*.java
"$JDK_DIR/bin/java" -Djava.awt.headless=true -cp "$CP:.local/fiji-bridge-test-classes" science.luma.fiji.BridgeIdentityContractTest
"$JDK_DIR/bin/java" -Djava.awt.headless=true -cp "$CP:.local/fiji-bridge-test-classes" science.luma.fiji.GeometryContractTest
# ImageJ's ROI Manager is a Frame even when hidden, so this run needs a window session; nothing is shown and no Dock icon appears.
python3 - "$JDK_DIR/bin/java" "$CP:.local/fiji-bridge-test-classes" <<'PY'
import subprocess, sys
try:
    result = subprocess.run([sys.argv[1], '-Dapple.awt.UIElement=true', '-Djava.awt.headless=false', '-Dpatch.ij1=false', '-Dluma.root=' + __import__('os').getcwd(),
        '-cp', sys.argv[2], 'science.luma.fiji.HeadlessContractTest'], timeout=180)
    for test in ('TrackingContractTest', 'AnalysisContractTest', 'MacrosContractTest', 'PipelineContractTest'):
        if result.returncode: break
        result = subprocess.run([sys.argv[1], '-Dapple.awt.UIElement=true', '-Djava.awt.headless=false', '-Dpatch.ij1=false', '-Dluma.root=' + __import__('os').getcwd(),
            '-cp', sys.argv[2], 'science.luma.fiji.' + test], timeout=240)
    raise SystemExit(result.returncode)
except subprocess.TimeoutExpired:
    print('Windowless ImageJ contract test timed out; this is not a passing check. Retry in a usable desktop session.', file=sys.stderr)
    raise SystemExit(1)
PY
