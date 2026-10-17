"""Run the Fidelis JUnit 5 tests inside the pinned Fiji runtime.

Usage:  python fidelis-app/tools/test.py [--select-class FQCN] [--tag TAG] [--heap 4g] [--timeout SEC]

Tests run in one JVM with the same flags as the application (tools/fijienv.py) and an isolated
user.home, so ImageJ preferences never touch the developer's profile (NFR-MAINT-004).
Tests that need a live Fiji context use sc.fidelis.testkit.FijiFixture.
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fijienv as env  # noqa: E402


def main() -> None:
    argv = sys.argv[1:]
    heap = argv[argv.index("--heap") + 1] if "--heap" in argv else "4g"
    timeout = float(argv[argv.index("--timeout") + 1]) if "--timeout" in argv else 900.0
    if not env.TEST_CLASSES.exists():
        sys.exit("[test] build first: python fidelis-app/tools/build.py")
    home = env.BUILD / "test-home"
    home.mkdir(parents=True, exist_ok=True)
    cp = [str(env.TEST_CLASSES), str(env.CLASSES), str(env.JUNIT_JAR)] + env.fiji_classpath()
    launcher = ["org.junit.platform.console.ConsoleLauncher", "execute", "--disable-banner",
                "--details=tree", "--fail-if-no-tests"]
    if "--select-class" in argv:
        launcher += ["--select-class", argv[argv.index("--select-class") + 1]]
    else:
        launcher += ["--scan-classpath", str(env.TEST_CLASSES)]
    if "--tag" in argv:
        launcher += ["--include-tag", argv[argv.index("--tag") + 1]]
    props = {"fidelis.repo.dir": str(env.REPO_DIR), "java.awt.headless": "false"}
    args = env.jvm_flags(heap=heap, user_home=home, extra_props=props) + ["-cp", ";".join(cp)] + launcher
    argfile = env.write_argfile(env.BUILD / "test.args", args)
    proc = subprocess.Popen([env.jdk_bin("java"), f"@{argfile}"], cwd=str(env.fiji_dir()))
    try:
        rc = proc.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        print(f"[test] timeout after {timeout}s; terminating JVM {proc.pid}")
        if os.name == "nt":
            subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"], capture_output=True)
        else:
            proc.kill()
        rc = 124
    sys.exit(rc)


if __name__ == "__main__":
    main()
