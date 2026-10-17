"""Launch Fidelis inside the pinned Fiji runtime.

Usage:  python fidelis-app/tools/run.py [--smoke] [--heap 4g] [--isolated-home] [--prop KEY=VALUE ...] [-- <app args>]

  --smoke          start the engine without a window, run one real Fiji command, verify it, exit
  --heap SIZE      JVM max heap (default 4g; Fiji's own default is 75% of RAM)
  --isolated-home  redirect user.home so ImageJ preferences do not touch the real profile
  --timeout SEC    kill the JVM after SEC seconds (default: none; smoke mode defaults to 180)
  --prop KEY=VALUE extra JVM system property -DKEY=VALUE (repeatable), e.g. --prop luma.root=<checkout> for the Penny
                   bridge (docs/FIDELIS-PROTOCOL.md); same on Windows and macOS

The JVM flags mirror Fiji's launcher (see tools/fijienv.py). The app jar must be built first.
"""

from __future__ import annotations

import os
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fijienv as env  # noqa: E402


def main() -> None:
    argv = sys.argv[1:]
    app_args: list[str] = []
    if "--" in argv:
        i = argv.index("--")
        app_args, argv = argv[i + 1:], argv[:i]
    smoke = "--smoke" in argv
    heap = argv[argv.index("--heap") + 1] if "--heap" in argv else "4g"
    timeout = float(argv[argv.index("--timeout") + 1]) if "--timeout" in argv else (180.0 if smoke else None)
    home = None
    if "--isolated-home" in argv:
        home = env.BUILD / "isolated-home"
        home.mkdir(parents=True, exist_ok=True)
    if not env.APP_JAR.exists():
        sys.exit("[run] build first: python fidelis-app/tools/build.py")
    cp = [str(env.APP_JAR)] + env.fiji_classpath()
    props = {"fidelis.repo.dir": str(env.REPO_DIR)}
    if smoke:
        props["fidelis.smoke"] = "true"
    for i, arg in enumerate(argv):
        if arg != "--prop":
            continue
        key, sep, value = (argv[i + 1] if i + 1 < len(argv) else "").partition("=")
        if not sep or not key.strip() or any(c.isspace() for c in key):
            sys.exit("[run] --prop takes KEY=VALUE (no spaces in KEY)")
        props[key] = value
    args = env.jvm_flags(heap=heap, user_home=home, extra_props=props, background=smoke)
    args += ["-cp", env.CLASSPATH_SEPARATOR.join(cp), "sc.fidelis.Main"] + app_args
    argfile = env.write_argfile(env.BUILD / "run.args", args)
    print(f"[run] {env.jdk_bin('java')} @{argfile.name} (heap {heap}{', isolated home' if home else ''})")
    start = time.monotonic()
    proc = subprocess.Popen([env.jdk_bin("java"), f"@{argfile}"], cwd=str(env.fiji_dir()))
    try:
        rc = proc.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        print(f"[run] timeout after {timeout}s; terminating JVM {proc.pid}")
        if os.name == "nt":
            subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"], capture_output=True)
        else:
            proc.kill()
        rc = 124
    print(f"[run] exit {rc} after {time.monotonic() - start:.1f}s")
    sys.exit(rc)


if __name__ == "__main__":
    main()
