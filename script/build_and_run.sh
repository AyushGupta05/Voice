#!/bin/bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"
MODE="${1:-run}"
mkdir -p .local/state dist
NODE_EXECUTABLE="$(command -v node)"
pkill -x LumaCompanion >/dev/null 2>&1 || true
export CLANG_MODULE_CACHE_PATH="$ROOT_DIR/.local/clang-cache"
export SWIFTPM_MODULECACHE_OVERRIDE="$ROOT_DIR/.local/swift-module-cache"
swift build --package-path apps/companion --scratch-path .local/swift-build --cache-path .local/swift-cache
APP_BUNDLE="$ROOT_DIR/dist/Luma.app"
mkdir -p "$APP_BUNDLE/Contents/MacOS"
cp .local/swift-build/debug/LumaCompanion "$APP_BUNDLE/Contents/MacOS/LumaCompanion"
python3 - "$APP_BUNDLE/Contents/Info.plist" "$ROOT_DIR" "$NODE_EXECUTABLE" <<'PY'
import plistlib,sys
with open(sys.argv[1],'wb') as file:
    plistlib.dump({'CFBundleExecutable':'LumaCompanion','CFBundleIdentifier':'science.luma.companion','CFBundleName':'Luma','CFBundlePackageType':'APPL','CFBundleVersion':'1','CFBundleShortVersionString':'0.1.0','LSMinimumSystemVersion':'14.0','LSUIElement':True,'NSPrincipalClass':'NSApplication','NSMicrophoneUsageDescription':'Luma uses your microphone for continuous conversation with your scientific software.','LumaRoot':sys.argv[2],'LumaNode':sys.argv[3]},file)
PY
codesign --force --sign - "$APP_BUNDLE"
case "$MODE" in
  --build-only) echo "Built $APP_BUNDLE" ;;
  run) /usr/bin/open -n "$APP_BUNDLE" ;;
  --verify)
    /usr/bin/open -n "$APP_BUNDLE"
    for attempt in {1..20}; do
      if pgrep -x LumaCompanion >/dev/null && [[ -f .local/state/gateway-token ]] && curl --max-time 2 -fsS -H "Authorization: Bearer $(cat .local/state/gateway-token)" http://127.0.0.1:49555/tools >/dev/null 2>&1; then
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
