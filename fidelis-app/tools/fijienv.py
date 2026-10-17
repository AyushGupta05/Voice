"""Shared environment for the Fidelis Java build and launch tools.

Fidelis runs *inside* the pinned Fiji runtime (docs: A21 option O2, ADR-002). These helpers locate
that runtime, reproduce the JVM flags Fiji's own launcher (Jaunch) uses, and build the classpath:
every jar under Fiji/jars (and subfolders) and Fiji/plugins (and subfolders), plus our classes.
The installed Fiji is never modified.

Configuration:
  FIDELIS_FIJI_DIR   path to the Fiji installation (default: C:\\Users\\arnav\\Documents\\dev\\Fiji)
  FIDELIS_JAVA_HOME  JDK to use (default: the JDK bundled with that Fiji)
"""

from __future__ import annotations

import glob
import os
import sys
from pathlib import Path

APP_DIR = Path(__file__).resolve().parents[1]  # fidelis-app/
REPO_DIR = APP_DIR.parent
BUILD = APP_DIR / "build"
CLASSES = BUILD / "classes"
TEST_CLASSES = BUILD / "test-classes"
APP_JAR = BUILD / "fidelis-app.jar"
LIB = APP_DIR / "lib"
JUNIT_JAR = LIB / "junit-platform-console-standalone-1.11.4.jar"

JAVAFX_MODULES = "javafx.base,javafx.controls,javafx.fxml,javafx.graphics,javafx.media,javafx.web"


def fiji_dir() -> Path:
    d = Path(os.environ.get("FIDELIS_FIJI_DIR", r"C:\Users\arnav\Documents\dev\Fiji"))
    if not (d / "jars").is_dir():
        sys.exit(f"Fiji installation not found at {d} (set FIDELIS_FIJI_DIR)")
    return d


def java_home(fiji: Path | None = None) -> Path:
    if "FIDELIS_JAVA_HOME" in os.environ:
        return Path(os.environ["FIDELIS_JAVA_HOME"])
    fiji = fiji or fiji_dir()
    candidates = sorted(glob.glob(str(fiji / "java" / "win64" / "zulu*")))
    if not candidates:
        sys.exit("No bundled JDK under Fiji/java/win64 (set FIDELIS_JAVA_HOME)")
    return Path(candidates[-1])


def jdk_bin(tool: str, fiji: Path | None = None) -> str:
    exe = java_home(fiji) / "bin" / (tool + (".exe" if os.name == "nt" else ""))
    if not exe.exists():
        sys.exit(f"{tool} not found in {java_home(fiji)}")
    return str(exe)


def fiji_classpath(fiji: Path | None = None) -> list[str]:
    """Jar list in the order Jaunch uses (fiji.toml jvm.classpath)."""
    fiji = fiji or fiji_dir()
    jars: list[str] = []
    for pattern in ("jars/*.jar", "jars/*/*.jar", "plugins/*.jar", "plugins/*/*.jar"):
        jars.extend(sorted(glob.glob(str(fiji / pattern))))
    return jars


def jvm_flags(fiji: Path | None = None, heap: str = "4g", user_home: Path | None = None,
              extra_props: dict[str, str] | None = None) -> list[str]:
    """JVM options matching Fiji's launcher dry-run (docs/01-analysis/references/probes/P40), plus ours."""
    fiji = fiji or fiji_dir()
    f = str(fiji)
    exe = str(fiji / "fiji-windows-x64.exe")
    flags = [
        "-XX:+UseG1GC",
        f"--module-path={fiji / 'jars' / 'win64'}",
        f"--add-modules={JAVAFX_MODULES}",
        "-Dpython.cachedir.skip=true",
        f"-Dplugins.dir={f}", f"-Dimagej.dir={f}", f"-Dij.dir={f}", f"-Dfiji.dir={f}",
        f"-Dfiji.executable={exe}", f"-Dij.executable={exe}",
        f"-Djava.library.path={fiji / 'lib' / 'win64'}", f"-Djna.library.path={fiji / 'lib' / 'win64'}",
        "--enable-native-access=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "-Dscijava.app.unlock-modules=true",
        "-Dscijava.app.name=Fiji", f"-Dscijava.app.directory={f}", f"-Dscijava.app.executable={exe}",
        "-Dscijava.context.strict=false",
        "-Dsun.java2d.uiScale=true",
        # Fidelis policy (FR-SYS-008): never let the Updater phone home on our launches.
        "-Dimagej.updater.disableAutocheck=true",
        f"-Xmx{heap}",
    ]
    if user_home is not None:
        flags.append(f"-Duser.home={user_home}")
    for k, v in (extra_props or {}).items():
        flags.append(f"-D{k}={v}")
    return flags


def write_argfile(path: Path, args: list[str]) -> Path:
    """Write a java/javac @argfile (quotes every argument; handles backslashes and spaces)."""
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = []
    for a in args:
        lines.append('"' + a.replace("\\", "\\\\").replace('"', '\\"') + '"')
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return path
