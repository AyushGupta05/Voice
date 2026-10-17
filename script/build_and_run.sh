#!/bin/bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"
MODE="${1:-run}"
case "$MODE" in
  run|--build-only|--verify|--debug|--logs|--telemetry) ;;
  *) echo "usage: $0 [run|--verify|--build-only|--debug|--logs|--telemetry]" >&2; exit 2 ;;
esac
mkdir -p .local/state dist
NODE_EXECUTABLE="$(command -v node)"
export CLANG_MODULE_CACHE_PATH="$ROOT_DIR/.local/clang-cache"
export SWIFTPM_MODULECACHE_OVERRIDE="$ROOT_DIR/.local/swift-module-cache"
swift build --package-path apps/companion --scratch-path .local/swift-build --cache-path .local/swift-cache
APP_BUNDLE="$ROOT_DIR/dist/Luma.app"
mkdir -p "$APP_BUNDLE/Contents/MacOS"
TEMP_EXECUTABLE="$APP_BUNDLE/Contents/MacOS/.LumaCompanion.$$"
trap 'rm -f "$TEMP_EXECUTABLE"' EXIT
cp .local/swift-build/debug/LumaCompanion "$TEMP_EXECUTABLE"
mv -f "$TEMP_EXECUTABLE" "$APP_BUNDLE/Contents/MacOS/LumaCompanion"
python3 - "$APP_BUNDLE/Contents/Info.plist" "$ROOT_DIR" "$NODE_EXECUTABLE" <<'PY'
import plistlib,sys
with open(sys.argv[1],'wb') as file:
    plistlib.dump({'CFBundleExecutable':'LumaCompanion','CFBundleIdentifier':'science.luma.companion','CFBundleName':'Penny','CFBundlePackageType':'APPL','CFBundleVersion':'1','CFBundleShortVersionString':'0.1.0','LSMinimumSystemVersion':'14.0','LSUIElement':True,'NSPrincipalClass':'NSApplication','NSMicrophoneUsageDescription':'Luma uses your microphone for continuous conversation with your scientific software.','NSAccessibilityUsageDescription':'Luma uses Accessibility to operate QuPath windows, plugin dialogs and file choosers on your behalf while its cursor shows you what it is doing.','LumaRoot':sys.argv[2],'LumaNode':sys.argv[3]},file)
PY
# A stable designated requirement keeps the microphone permission across rebuilds; ad-hoc cdhash requirements change every build.
codesign --force --sign - --identifier science.luma.companion -r='designated => identifier "science.luma.companion"' "$APP_BUNDLE"
if [[ "$MODE" != --build-only ]]; then
  python3 script/companion-process.py stop "$APP_BUNDLE/Contents/MacOS/LumaCompanion"
fi
case "$MODE" in
  --build-only) echo "Built $APP_BUNDLE" ;;
  run) /usr/bin/open -n "$APP_BUNDLE" ;;
  --verify)
    /usr/bin/open -n "$APP_BUNDLE"
    for attempt in {1..20}; do
      if python3 script/companion-process.py running "$APP_BUNDLE/Contents/MacOS/LumaCompanion" >/dev/null && [[ -f .local/state/gateway-token ]] && curl --max-time 2 -fsS -H "Authorization: Bearer $(cat .local/state/gateway-token)" http://127.0.0.1:49555/tools >/dev/null 2>&1; then
        echo 'Luma companion and authenticated gateway are running.'
        exit 0
      fi
      sleep 1
    done
    echo 'Luma did not connect to its gateway. Inspect .local/gateway.log.' >&2
    exit 1
    ;;
  --debug) lldb -- "$APP_BUNDLE/Contents/MacOS/LumaCompanion" ;;
  --logs|--telemetry) /usr/bin/open -n "$APP_BUNDLE"; /usr/bin/log stream --info --style compact --predicate 'process == "LumaCompanion"' ;;
  *) echo "usage: $0 [run|--verify|--build-only|--debug|--logs|--telemetry]" >&2; exit 2 ;;
esac
