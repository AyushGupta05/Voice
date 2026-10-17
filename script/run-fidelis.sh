#!/bin/bash
# Builds Fidelis and starts it with Penny's bridge (docs/FIDELIS-PROTOCOL.md): shared bridge token and root,
# .local/state/app = fidelis for the companion's gateway, build and run output in .local/fidelis.log.
# Arguments go to Fidelis itself (e.g. --screenshot out.png). FIDELIS_SKIP_BUILD=1 launches the existing build.
set -euo pipefail
cd "$(dirname "$0")/.."
PORT=49558
ARGFILE="$PWD/fidelis-app/build/run.args"
# Refuse while any Fidelis runs: tools/run.py starts `java @<checkout>/fidelis-app/build/run.args`, so a live JVM naming a
# Fidelis argfile is a running Fidelis (this checkout's or another worktree's, which would hold the same bridge port).
RUNNING="$(python3 -I - "$ARGFILE" <<'PY'
import re, subprocess, sys
mine = sys.argv[1]
# ps joins arguments with spaces, so the argfile argument is matched whole: checkout paths may contain spaces.
argfile = re.compile(r'(?:^|\s)@(\S.*?/fidelis-app/build/run\.args)(?=\s|$)')
for line in subprocess.check_output(['/bin/ps', '-ww', '-axo', 'pid=,stat=,command='], text=True).splitlines():
    fields = line.split(None, 2)
    if len(fields) != 3 or 'Z' in fields[1]:
        continue
    match = argfile.search(fields[2])
    if match:
        print(fields[0], 'this' if match.group(1) == mine else 'other', match.group(1))
PY
)"
if [[ -n "$RUNNING" ]]; then
  read -r PID WHERE OTHER_ARGFILE <<<"$(head -n 1 <<<"$RUNNING")"
  if [[ "$WHERE" == this ]]; then
    echo "Fidelis from this checkout is already running (pid $PID); no second instance was opened. Quit it normally to load a new build."
    exit 0
  fi
  echo "Another Fidelis is running (pid $PID, ${OTHER_ARGFILE%/fidelis-app/build/run.args}); quit it normally before launching this checkout's." >&2
  exit 1
fi
if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then echo "Port 127.0.0.1:$PORT is already in use; nothing was launched." >&2; exit 1; fi
mkdir -p .local/state
if [[ ! -f .local/state/bridge-token ]]; then (umask 077; openssl rand -hex 32 > .local/state/bridge-token); fi
: > .local/fidelis.log
if [[ "${FIDELIS_SKIP_BUILD:-}" != 1 ]]; then
  echo "Building Fidelis (python3 -I fidelis-app/tools/build.py --no-tests)..."
  if ! python3 -I fidelis-app/tools/build.py --no-tests >> .local/fidelis.log 2>&1; then
    echo "Fidelis build failed; nothing was launched. See .local/fidelis.log." >&2
    tail -n 20 .local/fidelis.log >&2
    exit 1
  fi
fi
export LUMA_ROOT="$PWD"
# Tell the companion-owned gateway which application profile to load (LUMA_APP overrides).
printf fidelis > .local/state/app
# The bridge reads its token from <luma.root>/.local/state/bridge-token; fidelis.penny=true starts it explicitly.
# --isolated-home keeps ImageJ preferences away from the real profile (one Fidelis/Fiji JVM per checkout).
nohup python3 -I fidelis-app/tools/run.py --isolated-home --prop "luma.root=$PWD" --prop fidelis.penny=true -- "$@" >> .local/fidelis.log 2>&1 &
echo "Started Fidelis (pid $!); Penny bridge on 127.0.0.1:$PORT, log in .local/fidelis.log"
