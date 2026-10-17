# Probe evidence index

Probes are diagnostic scripts run against the pinned Fiji runtime (lock `f66953972bb5ed85b8ab0273e4294af5b03136c28c9ef617e24556bca523bafa`) or against the prototype environment. They provide the VERIFIED evidence cited across the analysis and design documents. **They are not application code** (F04 §9). Each range has its own README, giving the exact command, date and pin-check result for every probe.

| Range | Topic | README | Cited mainly by |
|---|---|---|---|
| P00–P01 | Prototype test re-run; headless start-up timing | [README-lead.md](README-lead.md) | F02, A22, A23 |
| P10–P16 | Command inventory (static and runtime), headless behaviour matrix | [README-G1.md](README-G1.md) | A08 |
| P30–P39 | IJ1 internals: Recorder headless, CommandListener, image events, undo, macro options, ROI round trip, script parameters, conversion/threshold/measure, pixel sharing, Measure accumulation | [README-G2.md](README-G2.md) | A09–A12 |
| P40–P49 | Launcher, headless subsystems, GUI JVM with hidden windows, format fidelity, plugin GUI scans, class hierarchy, OME-TIFF in a GUI JVM, plugin index, alternative UI registration | [README-G3.md](README-G3.md) | A07, A13–A15 |
| P50–P52 | GUI staging and window capture for screenshots; session side effects | [README-G4.md](README-G4.md) | A04 |
| P60–P65 | faster-whisper timing, vocabulary biasing and silence, whisper.cpp packaging, prototype worker, CUDA check, local LLM plans | [README-G5.md](README-G5.md) | A16, A17 |
| P70–P73 | Jar licence scan, sample-image headers, BBBC/OME headers, speech DLL inspection | [README-G7.md](README-G7.md) | A24, A29 |
| P80–P87 | GUI-mode feasibility: listeners, Recorder fidelity, hidden-window/batch execution, dialogs, pixel transfer, start-up, undo, JavaFX/WebView | [README-G8.md](README-G8.md) | A15, A18–A23, A21 |

Re-running a probe: use the command recorded in its README. Then re-verify the pin with:

```
.venv/Scripts/python.exe -c "from fidelis.adapters.fiji import verify_runtime; from fidelis.config import fiji_executable; print(verify_runtime(fiji_executable()))"
```

GUI probes must pass `-Dimagej.updater.disableAutocheck=true` and isolate preferences (NFR-MAINT-004).
