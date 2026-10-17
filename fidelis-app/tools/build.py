"""Compile Fidelis with the JDK bundled in the pinned Fiji; no Maven or Gradle required.

Usage:  python fidelis-app/tools/build.py [--no-tests] [--clean]

Produces fidelis-app/build/classes, build/test-classes and build/fidelis-app.jar.
Reproducible: the compiler, its classpath and every library come from the pinned Fiji
(external/fiji.lock.json) plus the pinned JUnit console jar (tools/fetch_junit.py).
"""

from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fijienv as env  # noqa: E402


def sources(root: Path) -> list[str]:
    return sorted(str(p) for p in root.rglob("*.java"))


def javac(out: Path, srcs: list[str], classpath: list[str], label: str) -> None:
    if not srcs:
        print(f"[build] no {label} sources")
        return
    out.mkdir(parents=True, exist_ok=True)
    args = ["-d", str(out), "--release", "21", "-encoding", "UTF-8", "-Xlint:-options", "-proc:none",
            "-cp", ";".join(classpath)] + srcs
    argfile = env.write_argfile(env.BUILD / f"javac-{label}.args", args)
    print(f"[build] javac {label}: {len(srcs)} files")
    r = subprocess.run([env.jdk_bin("javac"), f"@{argfile}"])
    if r.returncode:
        sys.exit(f"[build] {label} compilation failed")


def copy_resources(src: Path, out: Path) -> None:
    if src.is_dir():
        shutil.copytree(src, out, dirs_exist_ok=True)


def main() -> None:
    clean = "--clean" in sys.argv
    with_tests = "--no-tests" not in sys.argv
    if clean and env.BUILD.exists():
        shutil.rmtree(env.BUILD)
    fiji_cp = env.fiji_classpath()
    main_src = env.APP_DIR / "src" / "main" / "java"
    test_src = env.APP_DIR / "src" / "test" / "java"

    javac(env.CLASSES, sources(main_src), fiji_cp, "main")
    copy_resources(env.APP_DIR / "src" / "main" / "resources", env.CLASSES)
    # The command catalogue (docs/data) ships inside the jar as the engine's static schema source.
    data_out = env.CLASSES / "sc" / "fidelis" / "engine" / "data"
    data_out.mkdir(parents=True, exist_ok=True)
    for name in ("commands.csv", "commands-core-parameters.csv"):
        shutil.copy2(env.REPO_DIR / "docs" / "data" / name, data_out / name)

    # Jar (manifest main class is the bootstrap; run through tools/run.py so the Fiji flags apply).
    env.APP_JAR.parent.mkdir(parents=True, exist_ok=True)
    r = subprocess.run([env.jdk_bin("jar"), "--create", "--file", str(env.APP_JAR),
                        "--main-class", "sc.fidelis.Main", "-C", str(env.CLASSES), "."])
    if r.returncode:
        sys.exit("[build] jar failed")

    if with_tests:
        if not env.JUNIT_JAR.exists():
            sys.exit(f"[build] missing {env.JUNIT_JAR.name}; run tools/fetch_junit.py")
        javac(env.TEST_CLASSES, sources(test_src), [str(env.CLASSES), str(env.JUNIT_JAR)] + fiji_cp, "test")
        copy_resources(env.APP_DIR / "src" / "test" / "resources", env.TEST_CLASSES)
    print(f"[build] OK -> {env.APP_JAR}")


if __name__ == "__main__":
    main()
