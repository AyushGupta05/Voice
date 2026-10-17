# Glossary of Fiji/ImageJ and project terminology

This is the single authoritative glossary. Other documents link here rather than redefining terms. The sources are the documents named in each row, and pinned revisions are listed in [`docs/data/upstream-manifest.yaml`](../data/upstream-manifest.yaml).

| Term | Definition | Source / detail |
|---|---|---|
| **ImageJ1 (IJ1)** | The original ImageJ application and library (`ij.jar`, package `ij.*`), developed by Wayne Rasband at NIH. AWT-based, with a heavy use of static state. | A07 |
| **ImageJ2 (IJ2)** | A redesign built on SciJava and ImgLib2, with an n-dimensional data model, services and typed commands. It runs alongside IJ1 through the legacy layer. | A07; Rueden et al. 2017 |
| **Fiji** | "Fiji Is Just ImageJ": a distribution bundling ImageJ2, ImageJ1, many plugins, update sites and a launcher. | A07; Schindelin et al. 2012 |
| **SciJava** | A framework providing `Context`, services, plugin discovery, modules/commands, `@Parameter` injection, events and script languages. | A07 |
| **imagej-legacy** | The bridge that runs IJ1 inside an ImageJ2 context and synchronises images and commands between the two. | A07, A15 |
| **ij1-patcher** | A library that patches `ij.jar` bytecode at runtime to insert hooks and enable headless use. | A07, A15 |
| **ImgLib2** | A generic library for n-dimensional image data structures and algorithms used by ImageJ2. | A07, A09 |
| **SCIFIO / Bio-Formats** | Image I/O frameworks. Bio-Formats reads many proprietary microscopy formats into the OME data model. The supported-format count is cited in A14. | A14 |
| **ImagePlus** | The IJ1 image document: one or more `ImageProcessor`s or an `ImageStack`, plus title, calibration, LUT/display range, ROI and properties. | A09 |
| **ImageProcessor** | The IJ1 2D pixel array plus operations. Subclasses: `ByteProcessor` (8-bit), `ShortProcessor` (16-bit), `FloatProcessor` (32-bit), `ColorProcessor` (RGB). | A09 |
| **Hyperstack** | An `ImagePlus` with channel (C), slice (Z) and frame (T) dimensions. | A09 |
| **CompositeImage** | A multichannel `ImagePlus` with per-channel LUTs and display modes (composite, colour, greyscale). | A09 |
| **Display range** | The min/max mapping of pixel values to the LUT. Changing it alters display, not data, until *Apply* is used. | A04, A09 |
| **Calibration** | The pixel size, units, origin and value calibration function attached to an `ImagePlus`. | A09 |
| **Dataset / ImgPlus** | ImageJ2's image types: an ImgLib2 `Img` with axes and metadata. | A09 |
| **ROI / Selection** | A region of interest (`ij.gui.Roi` subclasses) attached to an image. Measurements and many commands operate on it. | A10 |
| **ROI Manager** | The IJ1 window (`ij.plugin.frame.RoiManager`) that holds a list of ROIs for measurement and reuse. | A10 |
| **Overlay** | Non-destructive ROIs drawn over an image (`ij.gui.Overlay`). | A10 |
| **ResultsTable** | The IJ1 table of measurements (`ij.measure.ResultsTable`), shown in the "Results" window. | A11 |
| **Set Measurements** | The global measurement configuration (which statistics are measured, redirect, decimal places). It is stored as preferences. | A11 |
| **Macro (IJM)** | A program in the ImageJ Macro Language, run by `ij.macro.Interpreter`. `run("Command", "options")` invokes menu commands. | A12 |
| **Macro options string** | The `key=value [key=[value with spaces]] flag` string passed to a command. Dialogs read it instead of prompting. | A12 |
| **Recorder** | `ij.plugin.frame.Recorder`: it records user actions as macro, JavaScript, BeanShell or Java text. | A12 |
| **Batch mode** | Macro mode (`setBatchMode(true)`) in which images are not displayed, making execution faster. | A12 |
| **GenericDialog** | The IJ1 standard parameter dialog (`ij.gui.GenericDialog`). Under macros it takes its values from the options string. | A11, A12 |
| **PlugIn / PlugInFilter** | IJ1 plugin interfaces: `PlugIn.run(arg)`, and `PlugInFilter.setup`/`run(ip)` for filters operating on the current image. | A13 |
| **Command (SciJava)** | An IJ2 plugin implementing `org.scijava.command.Command`, with typed `@Parameter` inputs and outputs. | A13 |
| **Op** | An ImageJ Ops typed operation, discoverable by name and argument types. | A11, A13 |
| **Update site** | A remote repository of Fiji plugins and jars managed by the Fiji Updater. | A13, A25 |
| **Headless** | Running the JVM with no display (`java.awt.headless=true`). GUI classes throw `HeadlessException` unless patched. | A15 |
| **PyImageJ** | A Python library that starts ImageJ2/Fiji in-process through JPype. | A15, A21 |
| **faster-whisper / whisper.cpp** | Local Whisper speech-recognition implementations: CTranslate2/Python and C/C++ (ggml) respectively. | A16 |
| **Command catalogue** | This project's machine-readable inventory of installed commands: [`docs/data/commands.csv`](../data/commands.csv). | A08 |
| **Compatibility category** | This project's classification of plugin behaviour, from C1 (headless-safe) to C6 (native/3D). | A13 |
| **Parity status** | A per-feature status in the parity matrix: complete / partial / legacy fallback / deferred / unsupported. | A28 |
| **Evidence class** | VERIFIED / INFERRED / PROPOSED / UNKNOWN. | F04 §1 |
| **Pin / runtime lock** | `external/fiji.lock.json`: the SHA-256 inventory of the external Fiji installation. | F03 |

## Additional Fiji/ImageJ internals (merged from research agents, Phase 8)

| Term | Definition | Source / detail |
|---|---|---|
| **Command table** | The IJ1 map from command label to `class("arg")` (or a `command:`/`script:` identifier in Fiji), returned by `Menus.getCommands()`. Labels are matched exactly, including whitespace. | A08; `ij/Menus.java` |
| **IJ_Props.txt** | Properties file inside `ij.jar` that defines the IJ1 menus as `<prefix>NN="Label[shortcut]",class("arg")`. | A08 |
| **plugins.config** | Per-jar file (`menu, "Label", class("arg")`) that installs IJ1 plugins in the menus. | A08, A13 |
| **SciJava plugin index** | `META-INF/json/org.scijava.plugin.Plugin` in each jar, generated from `@Plugin` annotations. | A08, A13 |
| **Legacy command** | An IJ1 command wrapped as a SciJava module (`LegacyCommandInfo`). Running it through `ModuleService` bypasses `Executer`. | A08, P80 |
| **no-legacy** | A `@Plugin` attribute that removes an ImageJ2 command from the module index when running with the legacy UI. | A08 |
| **Macro key** | The option key for a dialog field: the first word of its label, lower-cased (`Macro.trimKey`). | A12 |
| **Temporary current image** | A per-thread override of the current image, set by `IJ.run(imp, …)` (`WindowManager.setTempCurrentImage`). | A09, A15 |
| **Undo slot** | IJ1's single static undo buffer. It is reset on window switch and does not cover whole-stack filters. | A09, A19 |
| **Sub-pixel ROI** | A `Roi` with float coordinates. Preserved in `.roi` files; rounded by the Recorder. | A10 |
| **.roi / RoiSet.zip** | IJ1 binary ROI format (`RoiEncoder`/`RoiDecoder`) and a zip archive of such files. | A10 |
| **ExtendedPlugInFilter** | A `PlugInFilter` with its own dialog and live-preview support. | A11 |
| **Measurement flags** | The bitmask chosen in Set Measurements. It is JVM-global and persisted as a preference. | A11 |
| **Redirect image** | Set Measurements option that measures another image using the current image's selection. | A11 |
| **Black background** | `Prefs.blackBackground`: defines the binary foreground and changes the results of binary operations. | A11, A05 UP-04 |
| **CommandListener** | IJ1 hook called before every `Executer` command. It can veto or substitute the command, and it sees the macro options. | A12, A18, P31/P80 |
| **Script parameter (`#@`)** | SciJava declaration of script inputs and outputs. Processed only through `ScriptService`. | A12, P36 |
| **IJ1MacroEngine** | The imagej-legacy JSR-223 engine that runs `.ijm` files through SciJava, with parameter injection. | A12 |
| **AutoRun** | Macros or scripts run automatically when Fiji starts. | A12, A25 |
| **LegacyHooks** | The call-out object held in `ij.IJ._hooks`. Patched IJ1 code calls it for status, progress, log and other events. | A07, A20 |
| **Legacy mode** | True when an `ij.ImageJ` main frame instance exists. It gates whether patched windows actually show. | A15 §4.4 |
| **HeadlessGenericDialog** | The superclass that ij1-patcher injects into `GenericDialog` in headless JVMs. It reads values from macro options only. | A15 §4.2 |
| **Pre-/postprocessor** | SciJava plugins run before or after a module to fill inputs (active image, macro options, harvesting) or to handle outputs. | A07 |
| **UserInterface plugin** | SciJava plugin type implementing an application UI. Selected by priority or by `-Dscijava.ui`. | A07, P49 |
| **Input harvester** | Preprocessor that asks the user for unresolved module inputs. It runs only for its own UI when that UI is visible. | A15 §5 |
| **Smart recording** | `GenericDialog` option that omits unchanged values from recorded macros. | A04 |
| **Macro shortcut** | A single-key binding installed by a macro's `[x]` suffix. It takes precedence over menu shortcuts. | A04 |
| **Subtitle line** | The information text above each IJ1 image (position, size, type, memory). | A04 |
| **Fiji search bar** | The SciJava search field in the Fiji main window. In Fiji, `l` focuses it. | A04 |
| **Virtual stack** | A disk-resident stack that loads one plane at a time. Edits to it cannot be undone by snapshot. | A09, A19 |
| **Jaunch** | Fiji's launcher (`fiji-windows-x64.exe` plus `config/jaunch/*.toml`). | A22 |

## Speech and language terms

| Term | Definition | Source / detail |
|---|---|---|
| **CTranslate2** | C++ inference engine used by faster-whisper. | A16 |
| **ggml / GBNF** | The tensor library and model format under whisper.cpp / the grammar notation it uses to penalise decoding tokens that the grammar rejects. | A16 |
| **VAD** | Voice activity detection (Silero). It prevents transcription of silence. | A16, P61 |
| **initial_prompt / hotwords** | Text prepended to Whisper's decoding context to bias its vocabulary. In faster-whisper 1.2.1 both use the same mechanism. | A16, P61 |
| **ASR hallucination** | Transcript text that is not in the audio, for example "You" on silence. | A16; Radford et al. 2022 |
| **Command plan** | Ordered steps, each a catalogue command with typed slots and an explicit target. The only artefact an NL front end may produce. | A17, D08 |
| **Grounding** | Resolving an instruction to an existing catalogue command and its real macro option keys. | A17 |
| **Consequential parameter** | A parameter whose value changes scientific results. It must never be filled silently. | A17, FR-NL-003 |
| **Deixis** | A context-dependent reference, such as "this image" or "channel 2". | A17, D08 |
| **Unsafe-execution rate** | The share of evaluation cases executed with an unstated consequential parameter or outside the catalogue. The release gate is 0. | A17, NFR-USE-003 |

## Project design vocabulary

| Term | Definition | Source / detail |
|---|---|---|
| **Execution modes M1–M5** | M1 headless; M2 GUI-capable JVM with the main window hidden; M3 visible Fiji; M4/M5 PyImageJ headless/interactive. | A15 §3 |
| **Routes R1–R4** | Execution routes: R1 `IJ.run(imp, label, options)`; R2 SciJava module with resolved inputs; R3 direct API; R4 macro/script engine. | A18, D04 |
| **Action consequence class (AC0–AC7)** | Classes from read-only (AC0) to code execution (AC7). They drive the confirmation policy. | A19 §3, D06 §4 |
| **Engine service** | The product's UI-agnostic SciJava service inside the Fiji JVM. It is the only writer of scientific state. | D01 CMP-20…31 |
| **ActionRequest** | The single typed request structure produced by every input modality. | D06 §2 |
| **State digest** | The post-action summary of state changes that drives UI refresh and postconditions. | A18, D09 §3 |
| **Observed action** | A command or change in the Fiji JVM that the product did not initiate (for example, from a legacy plugin window). | A18, FR-HIST-008 |
| **Window policy (P-A/P-B/P-C)** | Show-then-hide, batch mode, or visible legacy window, for images under product control. | D04 §3 |
| **Reference-execution oracle** | Expected results produced by running the pinned Fiji itself on an independently generated macro. | A30 |
| **Persona (PER-n), problem (UP-nn), workflow (W-nn)** | Identifiers defined in A02, A05 and A03 respectively. | A02, A03, A05 |

## Security and licensing terms

| Term | Definition | Source / detail |
|---|---|---|
| **Classpath Exception** | The GPLv2 linking exception in OpenJDK, which lets independent code linked to the JDK carry its own licence. | A24 |
| **Corresponding Source** | GPL-3 term for the complete source needed to build and run distributed object code. | A24 |
| **DNS rebinding / CSWSH** | Attacks that let a web page reach a local server: by re-resolving a domain to 127.0.0.1, or by opening a cross-site WebSocket. They are mitigated by Host and Origin checks and a token. | A25, NFR-SEC-002 |
| **Prompt injection** | Instructions embedded in data, such as metadata or file names, that an LLM treats as commands. | A25, FR-NL-010 |
| **CC BY-NC-SA 3.0** | The non-commercial share-alike licence of BBBC001/004. These images are not to be committed to the repository. | A29 |
