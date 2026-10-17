#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT_DIR="$PWD"
QUPATH_DIR="${LUMA_QUPATH_APP:-$ROOT_DIR/.local/qupath-package/QuPath-0.7.0-arm64-app.pkg/Payload/QuPath-0.7.0-arm64.app}"
JDK_DIR="${LUMA_JAVA_HOME:-$(find "$ROOT_DIR/.local/jdk" -maxdepth 4 -path '*/Contents/Home' -type d | head -1)}"
APP="$ROOT_DIR/dist/QuPathLuma.app"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/app" "$APP/Contents/Resources"
cp "$QUPATH_DIR/Contents/MacOS/QuPath-0.7.0-arm64" "$APP/Contents/MacOS/QuPath-0.7.0-arm64"
cp "$QUPATH_DIR/Contents/Resources/QuPath-0.7.0-arm64.icns" "$APP/Contents/Resources/"
for jar in "$QUPATH_DIR"/Contents/app/*.jar; do ln -sfn "$jar" "$APP/Contents/app/$(basename "$jar")"; done
ln -sfn "$ROOT_DIR/dist/luma-qupath-bridge.jar" "$APP/Contents/app/luma-qupath-bridge.jar"
ln -sfn "$(dirname "$(dirname "$JDK_DIR")")" "$APP/Contents/runtime"
python3 - "$QUPATH_DIR" "$APP" "$ROOT_DIR" <<'PY'
import sys,pathlib,plistlib
source,app,root=map(pathlib.Path,sys.argv[1:])
config=(source/'Contents/app/QuPath-0.7.0-arm64.cfg').read_text()
config=config.replace('[JavaOptions]','app.classpath=$APPDIR/luma-qupath-bridge.jar\n\n[JavaOptions]')
config+='\njava-options=-Djava.util.prefs.userRoot='+str(root/'.local/qupath-prefs')+'\n'
(app/'Contents/app/QuPath-0.7.0-arm64.cfg').write_text(config)
metadata=plistlib.loads((source/'Contents/Info.plist').read_bytes())
metadata.update(CFBundleIdentifier='science.luma.qupath',CFBundleName='QuPath + Luma')
(app/'Contents/Info.plist').write_bytes(plistlib.dumps(metadata))
PY
codesign --force --sign - "$APP"
echo "Built $APP (local development launcher; uses separately installed official QuPath jars and JDK)"
