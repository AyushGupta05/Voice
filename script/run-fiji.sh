#!/bin/bash
# Starts the pinned Fiji with the Luma bridge: symlinks the built jar into Fiji.app/plugins, exports the shared token and root, logs to .local/fiji.log.
set -euo pipefail
cd "$(dirname "$0")/.."
FIJI_APP="${LUMA_FIJI_APP:-$PWD/.local/fiji/Fiji.app}"
LAUNCHER="$FIJI_APP/Fiji.app/Contents/MacOS/fiji-macos-arm64"
if [[ ! -x "$LAUNCHER" ]]; then echo "Fiji launcher not found at $LAUNCHER; run python3 script/setup-fiji.py first." >&2; exit 1; fi
# Refuse while an interactive instance of this launcher runs (headless/batch macro runs by other tools are not the demo instance) or the bridge port is taken.
RUNNING="$(python3 - "$LAUNCHER" <<'PY'
import subprocess, sys
from pathlib import Path
expected = str(Path(sys.argv[1]).resolve())
for line in subprocess.check_output(['/bin/ps', '-ww', '-axo', 'pid=,stat=,command='], text=True).splitlines():
    fields = line.split(None, 2)
    if len(fields) != 3 or 'Z' in fields[1]:
        continue
    command = fields[2]
    executable = command.split(' ')[0]
    if not executable.startswith('/') or str(Path(executable).resolve()) != expected:
        continue
    if '--headless' in command or ' -batch ' in command + ' ':
        continue
    print(fields[0])
PY
)"
if [[ -n "$RUNNING" ]]; then
  echo "This project Fiji instance is already running (pid $RUNNING); no second instance was opened. Quit Fiji normally before loading a bridge update."
  exit 0
fi
if lsof -nP -iTCP:49557 -sTCP:LISTEN >/dev/null 2>&1; then echo 'Port 127.0.0.1:49557 is already in use; nothing was launched.' >&2; exit 1; fi
mkdir -p .local/state
if [[ ! -f .local/state/bridge-token ]]; then (umask 077; openssl rand -hex 32 > .local/state/bridge-token); fi
if [[ ! -f dist/luma-fiji-bridge.jar ]]; then ./script/build-fiji-bridge.sh; fi
# The only change inside Fiji.app: one symlink to the built bridge jar (Fiji scans plugins/ for plugins.config and SciJava plugins).
ln -sfn "$PWD/dist/luma-fiji-bridge.jar" "$FIJI_APP/plugins/luma-fiji-bridge.jar"
export LUMA_BRIDGE_TOKEN="$(cat .local/state/bridge-token)"
export LUMA_ROOT="$PWD"
# Tell the companion-owned gateway which application profile to load (LUMA_APP overrides).
printf fiji > .local/state/app
# Fiji must run on the Java it ships with (Azul Zulu 21 under Fiji.app/java): its launcher refuses other
# installations ("No matching Java installations found") and exits, which looked like a crash. Never pass --java-home.
# Jaunch passes -D options straight to the JVM; the SciJava LumaBridgeService auto-starts the bridge at startup.
nohup "$LAUNCHER" -Dluma.root="$PWD" "$@" > .local/fiji.log 2>&1 &
echo "Started Fiji (pid $!); bridge on 127.0.0.1:49557, log in .local/fiji.log"
