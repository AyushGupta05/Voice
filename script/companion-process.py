#!/usr/bin/env python3
"""Identify only a specified macOS executable; never stop processes by basename."""
import argparse
import os
from pathlib import Path
import signal
import subprocess
import sys
import time


def matching_pids(executable):
    if sys.platform != 'darwin':
        raise RuntimeError('Companion process management currently supports macOS only.')
    expected = str(Path(executable).resolve())
    snapshot = subprocess.check_output(
        ['/bin/ps', '-ww', '-axo', 'pid=,stat=,comm='], text=True)
    matches = []
    for line in snapshot.splitlines():
        fields = line.split(None, 2)
        if len(fields) != 3:
            continue
        pid, state, command = fields
        if 'Z' in state or not Path(command).is_absolute():
            continue
        if str(Path(command).resolve()) == expected:
            matches.append(int(pid))
    return matches


def stop(executable):
    candidates = matching_pids(executable)
    for pid in candidates:
        # Re-observe immediately before signaling; an exited process is not a target.
        if pid not in matching_pids(executable):
            continue
        try:
            os.kill(pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
    deadline = time.monotonic() + 5
    while matching_pids(executable):
        if time.monotonic() >= deadline:
            raise RuntimeError('The project companion did not quit normally. Inspect it before restarting; no force kill was used.')
        time.sleep(0.05)
    return len(candidates)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['running', 'stop'])
    parser.add_argument('executable', help='Exact absolute companion executable path')
    args = parser.parse_args()
    if not Path(args.executable).is_absolute():
        parser.error('Use an absolute executable path.')
    try:
        if args.action == 'running':
            pids = matching_pids(args.executable)
            if pids:
                print(' '.join(map(str, pids)))
            return 0 if pids else 1
        print('Stopped {} project companion process(es).'.format(stop(args.executable)))
        return 0
    except (OSError, subprocess.SubprocessError, RuntimeError) as error:
        print(str(error), file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
