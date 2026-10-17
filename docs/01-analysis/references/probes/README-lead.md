# Lead probes (P00–P09)

Probes are diagnostic evidence only. They are not application code (F04 §9). The Fiji pin was re-verified after each run: `verify_runtime` = `f66953972bb5ed85b8ab0273e4294af5b03136c28c9ef617e24556bca523bafa`.

| ID | Purpose | Command | Date | Result | Pin |
|---|---|---|---|---|---|
| P00 | Independent re-run of prototype tests | `FIDELIS_INTEGRATION=1 .venv/Scripts/python.exe -m pytest -v; ruff check .; npm --prefix frontend run build` | 2026-10-17 | 23 passed, 1 warning; ruff clean; build OK. See [P00 log](P00-prototype-test-rerun.log). | OK |
| P01 | Cost of one headless Fiji process (start → trivial macro → exit) | Driver script (scratchpad `p01.py`) runs `fiji-windows-x64.exe --java-home=<bundled> --allow-multiple --headless -macro P01-headless-startup.ijm <marker>` five times | 2026-10-17 | 4.14–4.69 s wall per run. Marker confirms IJ 2.18.0/1.54p, Java 21.0.7, `java.awt.headless=true`. A first attempt using `print()` produced no stdout, because headless `print` output is not forwarded by the launcher. See [P01 output](P01-headless-startup.txt). | OK |

Supplementary observations, made with read-only inspection on 2026-10-17:

- **No IJ1 prefs file exists.** There is no `IJ_Prefs.txt` under `C:\Users\arnav` (depth ≤ 4) and no `~/.imagej`, despite 18+ prototype headless runs. The ImageJ2/SciJava options are stored in the Windows registry under `HKCU\Software\JavaSoft\Prefs\net\imagej\…` and `…\org\scijava\…` (`java.util.prefs`).
