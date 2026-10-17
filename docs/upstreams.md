# Upstream research and integration choices

Research checked 17 October 2026 against upstream repositories and documentation.

| Component | Repository / license | Dependencies and build | Fidelis decision |
| --- | --- | --- | --- |
| Fiji distribution | [fiji/fiji](https://github.com/fiji/fiji); current POM declares GPL-3.0-or-later; component licenses vary | Maven aggregation over ImageJ2, SciJava, ImageJ1 and plugins; current distribution uses Java 21 | Reuse official dated portable binary externally; checksum pin; no source fork or wholesale copy |
| ImageJ1 | [imagej/ImageJ](https://github.com/imagej/ImageJ); NIH public-domain notice | Java; upstream source can build independently; included in Fiji | Execute genuine ImageJ macro commands in Fiji, using the bundled ij-1.54p.jar |
| ImageJ2 | [imagej/imagej2](https://github.com/imagej/imagej2); BSD-2-Clause core, dependent components retain own licenses | Java / Maven, SciJava plugin and service architecture | Available through the external Fiji distribution; broader service adapters deferred |
| PyImageJ | [imagej/pyimagej](https://github.com/imagej/pyimagej); Apache-2.0 in its license file | Python, JPype, scyjava, NumPy, xarray; local Fiji or versioned Maven initialization | Researched, not installed. Consider for persistent image data and richer ImageJ2 service access |
| faster-whisper | [SYSTRAN/faster-whisper](https://github.com/SYSTRAN/faster-whisper/tree/v1.2.1); MIT | Python >=3.9, CTranslate2, PyAV, tokenizers, Hugging Face Hub, ONNX Runtime; GPU requires compatible CUDA/cuDNN | Selected v1.2.1, installed as an isolated Python 3.11 worker, CPU int8; transitive packages hash-locked |
| Whisper model | [Systran/faster-whisper-tiny.en](https://huggingface.co/Systran/faster-whisper-tiny.en); MIT model card / upstream Whisper license | Converted Whisper English model; downloaded separately | Immutable model revision and local file hashes; original Whisper attribution retained |
| whisper.cpp | [ggml-org/whisper.cpp](https://github.com/ggml-org/whisper.cpp); MIT | C/C++, CMake, platform-specific acceleration and converted model weights | Credible alternative for a smaller native desktop bundle; not installed or benchmarked here |

## Why process separation first

Fiji's repository is an assembly of components maintained in separate repositories. Copying the top-level repository would not by itself produce a complete runnable engine. Its [Maven POM](https://github.com/fiji/fiji/blob/main/pom.xml) describes a large dependency graph. Reusing a portable distribution keeps that graph upstream.

The [headless guide](https://imagej.net/learn/headless) documents --headless -macro and explicitly notes GUI-bound plugin limitations. This milestone validates a small supported subset rather than promising universal headless compatibility.

[PyImageJ initialization](https://py.imagej.net/en/latest/Initialization.html) supports local installations and versioned Maven endpoints. It would be useful for in-process data access, but introduces JVM/Python lifetime and native-library compatibility concerns unnecessary for the first macro-based demonstration. Its [headless notes](https://py.imagej.net/en/latest/Headless.html) explain the limits of WindowManager and RoiManager operations without a GUI.

faster-whisper offers [CPU int8 inference](https://github.com/SYSTRAN/faster-whisper/tree/v1.2.1). PyAV bundles audio decoding libraries, so a separate system FFmpeg executable is unnecessary for this worker. The isolated environment avoids forcing its native dependency wheels into the API's Python 3.14 environment.

## License preservation and release decisions

Fiji has mixed licensing. Its current POM says GPLv3+, while historical README wording refers to a GPLv2 majority. Use the exact component's license and pinned distribution notices; do not flatten the entire stack into a single MIT claim. The installed Fiji POM is preserved in third_party/fiji-installed-pom.xml as evidence.

Original upstream licenses, copyright notices and exceptions are preserved under third_party. The installed Fiji licenses and bundled Java legal directories remain intact externally. Installed Python package notices and a dependency inventory are copied separately from their implementations. See [ImageJ licensing](https://imagej.net/licensing/) and third_party/NOTICE.md.

Process separation is an engineering boundary, not a claim that distribution obligations disappear. Before shipping a bundled application, inventory the actual distributed components and provide their required notices and corresponding source arrangements. No original-code license was selected for the owner, and no distributable binary bundle was published in this milestone.
