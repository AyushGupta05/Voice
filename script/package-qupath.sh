#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT_DIR="$PWD"
QUPATH_DIR="${LUMA_QUPATH_APP:-$ROOT_DIR/.local/qupath-package/QuPath-0.7.0-arm64-app.pkg/Payload/QuPath-0.7.0-arm64.app}"
JDK_DIR="${LUMA_JAVA_HOME:-$(find "$ROOT_DIR/.local/jdk" -maxdepth 4 -path '*/Contents/Home' -type d | head -1)}"
APP="$ROOT_DIR/dist/QuPathLuma.app"
if [[ $# -eq 2 && "$1" == "--output" ]]; then
  APP="$(python3 -c 'import pathlib,sys; print(pathlib.Path(sys.argv[1]).absolute())' "$2")"
elif [[ $# -ne 0 ]]; then
  echo "Usage: $0 [--output APP_PATH]" >&2; exit 2
fi
python3 - "$QUPATH_DIR" "$APP" <<'PY'
import pathlib,sys
sys.path.insert(0,'script')
from qupath_config import validate_destination
source,target=map(pathlib.Path,sys.argv[1:])
validate_destination(source,target)
PY
if python3 script/companion-process.py running "$APP/Contents/MacOS/QuPath-0.7.0-arm64" >/dev/null; then
  echo 'This QuPath launcher is running. Save and quit it normally before repackaging, or use --output for a separate prepared bundle.' >&2
  exit 1
else
  PROCESS_STATUS=$?
  if [[ $PROCESS_STATUS -ne 1 ]]; then echo 'Could not verify launcher process state; no package was changed.' >&2; exit "$PROCESS_STATUS"; fi
fi
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/app" "$APP/Contents/Resources"
cp "$QUPATH_DIR/Contents/MacOS/QuPath-0.7.0-arm64" "$APP/Contents/MacOS/QuPath-0.7.0-arm64"
cp "$QUPATH_DIR/Contents/Resources/QuPath-0.7.0-arm64.icns" "$APP/Contents/Resources/"
shopt -s dotglob
for entry in "$QUPATH_DIR"/Contents/app/*; do
  if [[ "$(basename "$entry")" != "QuPath-0.7.0-arm64.cfg" ]]; then
    ln -sfn "$entry" "$APP/Contents/app/$(basename "$entry")"
  fi
done
ln -sfn "$ROOT_DIR/dist/luma-qupath-bridge.jar" "$APP/Contents/app/luma-qupath-bridge.jar"
ln -sfn "$(dirname "$(dirname "$JDK_DIR")")" "$APP/Contents/runtime"
python3 - "$QUPATH_DIR" "$APP" "$ROOT_DIR" <<'PY'
import sys,pathlib,plistlib
source,app,root=map(pathlib.Path,sys.argv[1:])
sys.path.insert(0,str(root/'script'))
from qupath_config import launcher_config
config=launcher_config((source/'Contents/app/QuPath-0.7.0-arm64.cfg').read_text(),root)
(app/'Contents/app/QuPath-0.7.0-arm64.cfg').write_text(config)
metadata=plistlib.loads((source/'Contents/Info.plist').read_bytes())
metadata.update(CFBundleIdentifier='science.luma.qupath',CFBundleName='QuPath + Luma')
(app/'Contents/Info.plist').write_bytes(plistlib.dumps(metadata))
PY
codesign --force --sign - "$APP"
echo "Built $APP (local development launcher; uses separately installed official QuPath jars and JDK)"
