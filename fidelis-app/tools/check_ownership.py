"""Check that a branch only changes files its workstream owns (docs/03-development/WORKSTREAM-CONTRACTS.md).

Usage:  python -I fidelis-app/tools/check_ownership.py --stream engine|voice|ui|integrator [--base REF]
        python -I fidelis-app/tools/check_ownership.py --who PATH [PATH ...]

Compares HEAD with the merge base of --base (default: voice/development/fidelis-workstation, then
origin/development/fidelis-workstation) and classifies every changed path. Exit code 0 when every
path is owned by the stream; 1 when a path belongs to another stream or is a shared contract (those
need a linked `needs:contract-review` issue and the integrator's approval); 2 on usage errors.
Read-only: it never modifies the repository.
"""

from __future__ import annotations

import fnmatch
import subprocess
import sys

J = "fidelis-app/src/main/java/sc/fidelis/"
T = "fidelis-app/src/test/java/sc/fidelis/"
R = "fidelis-app/src/test/resources/sc/fidelis/"

# Order matters: the first matching rule wins (shared contracts before their packages).
RULES: list[tuple[str, str]] = [
    # shared contracts: change only through a needs:contract-review issue approved by the integrator
    (J + "engine/api/*", "contract"),
    (J + "nl/NlService.java", "contract"),
    (J + "speech/SpeechService.java", "contract"),
    # engine and science (Arnav)
    (J + "engine/*", "engine"),
    (T + "engine/*", "engine"),
    (T + "verification/*", "engine"),
    (T + "testkit/*", "engine"),
    ("fidelis-app/src/test/resources/META-INF/*", "engine"),
    ("verification/*", "engine"),
    ("fixtures/*", "engine"),
    ("docs/data/commands.csv", "engine"),
    ("docs/data/commands-core-parameters.csv", "engine"),
    ("docs/03-development/VERIFICATION-RESULTS.md", "engine"),
    ("docs/03-development/handoffs/ARNAV.md", "engine"),
    # voice and language (Ayush)
    (J + "nl/*", "voice"),
    (J + "speech/*", "voice"),
    (T + "nl/*", "voice"),
    (T + "speech/*", "voice"),
    (R + "nl/*", "voice"),
    (R + "speech/*", "voice"),
    ("speech/*", "voice"),
    ("external/stt-model*", "voice"),
    ("docs/03-development/SPEECH-RESULTS.md", "voice"),
    ("docs/03-development/handoffs/AYUSH.md", "voice"),
    # desktop UI (Alex)
    (J + "ui/*", "ui"),
    (T + "ui/*", "ui"),
    ("fidelis-app/src/main/resources/sc/fidelis/ui/*", "ui"),
    ("docs/03-development/screenshots/*", "ui"),
    ("docs/03-development/handoffs/ALEX.md", "ui"),
    # integrator (Arnav as coordinator): bootstrap, tools, team documents, repository configuration
    (J + "Main.java", "integrator"),
    (J + "Bootstrap.java", "integrator"),
    ("fidelis-app/tools/*", "integrator"),
    ("docs/03-development/*", "integrator"),
    (".github/*", "integrator"),
    ("CLAUDE.md", "integrator"),
    ("README.md", "integrator"),
    ("CONTRIBUTING.md", "integrator"),
    (".gitignore", "integrator"),
    ("docs/*", "integrator"),
    # the earlier prototype is frozen evidence
    ("backend/*", "frozen"),
    ("frontend/*", "frozen"),
    ("tests/*", "frozen"),
    ("scripts/*", "frozen"),
    ("fiji/*", "frozen"),
    ("external/*", "frozen"),
    # anything else (root configuration, licences, third_party/) needs the integrator
    ("*", "integrator"),
]

# Arnav is both the engine owner and the integrator.
STREAM_OWNS = {
    "engine": {"engine"},
    "voice": {"voice"},
    "ui": {"ui"},
    "integrator": {"engine", "integrator"},
}


def owner(path: str) -> str:
    p = path.replace("\\", "/")
    for pattern, who in RULES:
        if fnmatch.fnmatchcase(p, pattern):
            return who
    return "unowned"


def git(*args: str) -> str:
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def changed_paths(base: str | None) -> list[str]:
    candidates = [base] if base else ["voice/development/fidelis-workstation", "origin/development/fidelis-workstation"]
    for ref in candidates:
        try:
            mb = git("merge-base", "HEAD", ref).strip()
        except subprocess.CalledProcessError:
            continue
        committed = git("diff", "--name-only", f"{mb}...HEAD").split()
        worktree = git("diff", "--name-only", "HEAD").split()
        worktree += git("ls-files", "--others", "--exclude-standard").split()
        return sorted(set(committed + worktree))
    sys.exit(f"[ownership] none of {candidates} is a known ref; run `git fetch` or pass --base")


def main() -> int:
    argv = sys.argv[1:]
    if "--who" in argv:
        for p in argv[argv.index("--who") + 1:]:
            print(f"{owner(p):11} {p}")
        return 0
    if "--stream" not in argv:
        print(__doc__)
        return 2
    stream = argv[argv.index("--stream") + 1]
    if stream not in STREAM_OWNS:
        print(f"[ownership] unknown stream {stream!r}; use one of {sorted(STREAM_OWNS)}")
        return 2
    base = argv[argv.index("--base") + 1] if "--base" in argv else None
    problems = 0
    paths = changed_paths(base)
    for p in paths:
        who = owner(p)
        ok = who in STREAM_OWNS[stream]
        if not ok:
            problems += 1
        tag = "ok " if ok else ("CONTRACT" if who == "contract" else "FOREIGN ")
        print(f"[ownership] {tag} {who:11} {p}")
    print(f"[ownership] stream={stream}: {len(paths)} changed path(s), {problems} outside ownership")
    if problems:
        print("[ownership] Paths outside your ownership need a linked issue: `needs:contract-review` for contracts,"
              " otherwise ask the owning workstream (docs/03-development/GITHUB-WORKFLOW.md section 5).")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
