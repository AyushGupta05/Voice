# Probes: agent G4 (P50–P59)

All probes were run on 2026-10-17, Windows 11 Home 10.0.26200, against the pinned Fiji at `C:\Users\arnav\Documents\dev\Fiji` (Fiji 2.18.1-SNAPSHOT, ij-1.54p, Zulu JDK 21.0.7).

The pin check after the session was run at 2026-10-17T08:22:41+01:00 with `./.venv/Scripts/python.exe -c "from fidelis.adapters.fiji import verify_runtime; from fidelis.config import fiji_executable; print(verify_runtime(fiji_executable()))"`. It returned `f66953972bb5ed85b8ab0273e4294af5b03136c28c9ef617e24556bca523bafa`, which **matches** the pin. Nothing was written into the Fiji directory.

| Probe | Purpose | Exact command | Date | Pin check |
|---|---|---|---|---|
| P50 | Stage the Fiji GUI for the A04 screenshots. Opens synthetic images, ROI Manager, Results, Histogram, Plot Profile, B&C, Threshold, Channels, Recorder, Log and Script Editor; after flag files it opens Command Finder and the Gaussian Blur `GenericDialog`. Also captures windows in-process (method B). Files: `P50-gui-staging.groovy`, `P50-gui-staging.out.txt` (status log of the final run and two earlier attempts) | From cwd `C:\Users\arnav\Documents\dev\Fiji`: `fiji-windows-x64.exe -Dimagej.updater.disableAutocheck=true --java-home=C:\Users\arnav\Documents\dev\Fiji\java\win64\zulu21.42.19-ca-jdk21.0.7-win_x64 --allow-multiple --run "C:\Users\arnav\Documents\dev\click-to-batch\docs\01-analysis\references\probes\P50-gui-staging.groovy"` (launched with PowerShell `Start-Process … -PassThru`; PIDs 14580, 20732, 12916, 32508; each killed with `Stop-Process -Id <pid> -Force`) | 2026-10-17 | match (after session) |
| P51 | Capture only Fiji-owned windows (process-tree filter). Modes: `list`, `capture` (screen copy with a `WindowFromPoint` ownership check; failed because of the screen saver), `printwindow` (method A, used), `menu` (not run). Files: `P51-capture-fiji-windows.ps1`, `P51-capture-fiji-windows.out.txt` | `powershell -File P51-capture-fiji-windows.ps1 -RootPid <pid> -Mode printwindow -OutDir <scratch> -TitleRegex "^<title>$" -TrimX 8 -TrimW <javaW-8> -TrimH <javaH-8>` (one call per window; Java sizes taken from the P50 status log) | 2026-10-17 | match |
| P52 | Side effects and environment. Covers: `IJ_Prefs.txt` before and after; a `HKCU\Software\JavaSoft\Prefs` registry diff; updater-autocheck evidence; the input desktop (`Screen-saver`) diagnosis; processes. File: `P52-session-side-effects.txt` | `Test-Path $env:USERPROFILE\.imagej\IJ_Prefs.txt`; `reg query HKCU\Software\JavaSoft\Prefs /s` before and after, SHA-256 compared; user32 `OpenInputDesktop`+`GetUserObjectInformation(UOI_NAME)` | 2026-10-17 | match |

Results:

- **IJ_Prefs.** `%USERPROFILE%\.imagej\IJ_Prefs.txt` did **not exist** before (08:12:06) or after (08:22:35) the session, so it was neither created nor changed. Fiji was always killed, so `ImageJ.run()`→`Prefs.savePreferences()` never ran.
- **Java Preferences.** SciJava Java Preferences **were** written to the registry during the GUI runs: the Script Editor geometry, `org.scijava.welcome…/firstRun-2.18.0/1.54p=false`, the FlatLaf look-and-feel, and console log formatting.
- **Updater.** No updater key was written and no updater dialog appeared.
