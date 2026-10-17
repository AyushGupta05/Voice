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

Terms proposed by research agents are merged here during the Phase 8 review.
