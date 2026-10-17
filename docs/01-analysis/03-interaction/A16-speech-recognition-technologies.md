---
id: A16
title: Speech Recognition Technologies
stage: 01-analysis
status: Draft
priority: P1
source_revisions: [SYSTRAN/faster-whisper@v1.2.1 (65882eee), ggml-org/whisper.cpp@master (0c7be696), ggml-org/whisper.cpp release v1.9.5 (d1be6fde), Systran/faster-whisper-tiny.en@0d3d19a3]
related_requirements: []
related_decisions: []
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# A16 — Speech Recognition Technologies

## Executive Summary

Two local Whisper inference engines are realistic for the Fiji Modernisation Project: **faster-whisper** (Python, on the CTranslate2 inference engine) and **whisper.cpp** (C/C++, on the ggml tensor library). Both run the same OpenAI Whisper weights, both are MIT-licensed, and both run fully offline.

- **Measured locally (VERIFIED, probes P60/P63).** The prototype's faster-whisper 1.2.1 / `tiny.en` / CPU int8 set-up loads the model in about 0.20 s and transcribes the 1.94 s fixture in 0.18–0.21 s once warm (0.26–0.29 s on the first call). One prototype worker request costs about 0.95–1.0 s end to end, and most of that is process start-up and Python imports.
- **Vocabulary biasing (VERIFIED, P61).** `initial_prompt` and `hotwords` fixed "ROI" (heard as "the other eye" without a prompt) and the capitalisation of command names. The same prompt also made one clip worse ("microns per pixel" became "microns per cycle"). In faster-whisper 1.2.1 the two options are mechanically the same: both are prepended as `<|startofprev|>` context.
- **Constrained decoding.** whisper.cpp has GBNF grammar-constrained decoding, implemented as a logit penalty, and a "guided" command-list mode. faster-whisper has neither.
- **GPU (VERIFIED, P64).** The installed ctranslate2 wheel sees the RTX 5070 but cannot run on it: it bundles `cudnn64_9.dll` and fails on the missing `cublas64_12.dll`.
- **Java.** whisper.cpp ships JNA-based Java bindings. The Maven artefact (`io.github.ggerganov:whispercpp:1.4.0`) dates from September 2023. At master, its `WhisperFullParams` struct mapping omits the C struct's trailing VAD fields.

**Recommendation (PROPOSED).** Keep faster-whisper in the prototype's process-isolated worker pattern, made persistent so the model loads once. Use English-only models, a short command-vocabulary prompt and Silero VAD. Every transcript goes to the A17 command layer as editable text that the user confirms; it is never a direct trigger.

Prefer whisper.cpp in either of these cases:
- the speech engine must live in the Java/Fiji process or ship as a single native DLL without Python;
- grammar-constrained decoding of a closed command vocabulary is required.

## Purpose and Scope

This document compares candidate speech-to-text (STT) engines for driving existing Fiji operations by voice. It covers architecture, dependencies, Windows packaging, GPU options, model formats and sizes, decoding features that matter for a scientific command vocabulary, licences, and measured local performance.

It does not cover the mapping from text to Fiji commands (A17), microphone and UI design (D-series), or accuracy on real human speech, which no probe here establishes.

## Research Questions

1. What are the architectural and dependency differences between faster-whisper 1.2.1 and whisper.cpp at the cached master, and what is the latest release of each?
2. Which can be packaged for Windows next to a Fiji installation, and with what GPU options?
3. Which decoding controls (prompt, hotwords, grammar, VAD, timestamps, streaming) can bias recognition towards terms such as "Gaussian", "sigma", "ROI" and "Otsu"?
4. What latency does the existing prototype set-up achieve on this machine, and where does the time go?
5. Which Whisper model properties (training data, English-only versus multilingual, hallucination on silence) constrain the design?

## Sources and Evidence

| Source | Revision | Use |
|---|---|---|
| `fiji-research-cache/faster-whisper` | tag v1.2.1, 65882eee (the latest tag on `git ls-remote --tags https://github.com/SYSTRAN/faster-whisper`; GitHub release published 2025-10-31) | Source inspection |
| `fiji-research-cache/whisper.cpp` | master 0c7be696, equal to remote `HEAD` on 2026-10-17 | Source inspection |
| whisper.cpp latest release | tag **v1.9.5**, which peels to d1be6fde (`git ls-remote --tags https://github.com/ggml-org/whisper.cpp`). Published 2026-10-06 with no assets; the binaries are under build tag `b5454` at the same commit (probe P62) | Packaging inspection |
| `.venv-stt` | Python 3.11.16; faster-whisper 1.2.1, ctranslate2 4.8.3, onnxruntime 1.31.0, av 18.1.0 ([requirements.lock.txt](../../../speech/requirements.lock.txt)) | Local probes |
| `external/models/tiny.en` | `Systran/faster-whisper-tiny.en`@0d3d19a3 ([stt.lock.json](../../../external/stt.lock.json)) | Local probes |
| Radford et al. (2022), arXiv:2212.04356 | v1 PDF | Model properties |
| Probes P60–P64 | `probes/README-G5.md` | Measured evidence |

## Verified Findings

### 1. Architecture

| Aspect | faster-whisper 1.2.1 | whisper.cpp (0c7be696) | Evidence |
|---|---|---|---|
| Inference engine | CTranslate2 C++ runtime, through `ctranslate2.models.Whisper(...)` | ggml C library, vendored in `ggml/`. Model loader checks `GGML_FILE_MAGIC` | VERIFIED: `SYSTRAN/faster-whisper@65882eee:faster_whisper/transcribe.py#L689`; `ggml-org/whisper.cpp@0c7be696:src/whisper.cpp#L1507-L1512` |
| Host language | Python package. Feature extraction, tokenisation, decoding loop, VAD and timestamp logic are in Python | C API `whisper.h` (`whisper_full`, `whisper_full_params`), C++ examples (`whisper-cli`, `whisper-server`, `whisper-stream`, `whisper-command`) | VERIFIED: `include/whisper.h#L487-L592` |
| Audio decoding | PyAV (`import av`) in `faster_whisper/audio.py#L15` (`decode_audio`), so any FFmpeg-decodable format works | The library takes 16 kHz float PCM. The examples read WAV/MP3/FLAC/OGG through `miniaudio.h`/`stb_vorbis.c`, and optionally FFmpeg (`WHISPER_COMMON_FFMPEG`) | VERIFIED: `examples/` listing; `CMakeLists.txt#L112` |
| VAD | Silero VAD v6 ONNX (`faster_whisper/assets/silero_vad_v6.onnx`) run with onnxruntime (`vad.py#L291-L310`) | Silero v6.2.0 converted to ggml (`ggml-silero-v6.2.0.bin`, 864 KB), enabled through `whisper_full_params.vad` / `vad_model_path` | VERIFIED: `include/whisper.h#L586-L591`; README §VAD |
| Batched inference | `BatchedInferencePipeline` (`transcribe.py#L111`) | Not equivalent; parallel processors through `whisper_full_parallel` | VERIFIED (source) |

### 2. Dependencies

| | faster-whisper | whisper.cpp |
|---|---|---|
| Runtime requirements | `ctranslate2>=4.0,<5`, `huggingface_hub>=0.21`, `tokenizers>=0.13,<1`, `onnxruntime>=1.14,<2`, `av>=11`, `tqdm` (`requirements.txt`); `python_requires=">=3.9"` (`setup.py#L54`) | None at runtime beyond the C runtime. Optional: SDL2 for microphone examples, CUDA/Vulkan/BLAS/OpenVINO SDKs for back ends |
| Locked set in prototype | 24 packages, hash-locked, including numpy, protobuf, flatbuffers and huggingface-hub (VERIFIED: `speech/requirements.lock.txt`) | n/a |
| Build | Wheels only; no compiler needed | CMake (`cmake -B build`, with `-DGGML_CUDA=1`, `-DGGML_VULKAN=1`, `-DGGML_BLAS=1`, `-DWHISPER_OPENVINO=1` and so on: README §NVIDIA/Vulkan/BLAS), or the prebuilt release zips |

The prototype loads the model with `local_files_only=True` and a checksum check ([worker.py](../../../speech/worker.py#L16-L27)). The Hugging Face Hub client is installed but not used at runtime once the model is present (VERIFIED by source reading of `worker.py` and `scripts/setup_speech.py`).

### 3. Windows packaging

| | faster-whisper | whisper.cpp |
|---|---|---|
| Distribution | PyPI wheels. The ctranslate2 4.8.3 wheel contains `ctranslate2.dll`, `cudnn64_9.dll` and `libiomp5md.dll` (VERIFIED: P64 listing of `site-packages/ctranslate2`) | Release zips (P62): `whisper-bin-x64.zip` is 8.9 MB and holds `whisper.dll` (1.37 MB), `ggml.dll`, `ggml-base.dll`, nine CPU-variant DLLs (`ggml-cpu-haswell.dll`, `-alderlake`, `-skylakex`, …; runtime CPU dispatch) and executables. Also `whisper-blas-bin-x64.zip` (21.7 MB, adds `ggml-blas.dll` and a 51 MB `libopenblas.dll`), `whisper-bin-win-cuda-12.4.0-x64.zip` (685 MB) and `-cuda-11.8.0-x64.zip` (285 MB) |
| Footprint | A Python 3.11 interpreter plus about 24 packages, plus the model directory | A DLL set of a few MB plus one model file |
| Third-party bundles | Purfview's `whisper-standalone-win` is listed in the faster-whisper README as standalone executables, and as a source of the NVIDIA libraries for Windows (VERIFIED as documentation: `README.md#L94-L96`, `#L244`) | — |
| Vulkan on Windows | n/a | Not among the b5454 Windows x64 assets (P62). It must be built from source with `-DGGML_VULKAN=1` (VERIFIED: P62 asset list; README §Vulkan) |
| Java artefact | n/a | Gradle project `bindings/java` (group `io.github.ggerganov`, version `1.4.0`, JNA 5.13.0: `bindings/java/build.gradle#L9-L10,L97`). Maven Central has only `io.github.ggerganov:whispercpp:1.4.0`, last updated 2023-09-15 (VERIFIED: `repo1.maven.org/.../maven-metadata.xml`, accessed 2026-10-17). Release v1.8.3 attached a `whispercpp.jar.zip` (1.66 MB); b5454 does not (P62) |

### 4. GPU options

| | faster-whisper / CTranslate2 4.8.3 | whisper.cpp |
|---|---|---|
| NVIDIA | CUDA 12 + cuDNN 9 only for current ctranslate2. Older stacks need ctranslate2 3.24.0 (CUDA 11/cuDNN 8) or 4.4.0 (CUDA 12/cuDNN 8) (VERIFIED as documentation: `README.md#L67-L70`). **Locally:** `get_cuda_device_count()` = 1 and the CUDA compute types are listed, but loading on `device="cuda"` fails with `RuntimeError: Library cublas64_12.dll is not found or cannot be loaded` (VERIFIED: P64) | CUDA back end (`-DGGML_CUDA=1`). The README gives a separate `CMAKE_CUDA_ARCHITECTURES` line for RTX 5000 series. Prebuilt CUDA 11.8 and 12.4 zips (P62) |
| Other GPUs | None | Vulkan (cross-vendor), ROCm, SYCL, Moore Threads, OpenVINO (Intel CPU/iGPU encoder), Core ML / Metal (Apple) (VERIFIED as documentation: README sections) |
| CPU acceleration | Intel MKL / oneDNN inside the wheel. Compute types here: `int8`, `int8_float32`, `int16`, `float32` (VERIFIED: `ctranslate2.get_supported_compute_types('cpu')`) | Per-ISA CPU DLLs, optional OpenBLAS |

An NVIDIA GPU (RTX 5070 Laptop, 8 GB, driver 616.56) is present on the development machine. Its availability on target lab machines is **UNKNOWN**: survey it in A02/A04. For `tiny.en`/`base.en` command-length clips, CPU latency is already below 0.3 s (P60), so a GPU is not needed for the command use case (INFERRED).

### 5. Models, formats, sizes, quantisation

| Model | Params (Radford et al. Table 1) | CT2 `model.bin` (Systran, FP16) | ggml `.bin` (FP16) | ggml q5_1 / q8_0 |
|---|---|---|---|---|
| tiny / tiny.en | 39 M | 75,537,502 B (tiny.en) | 77,704,715 B | 32,166,155 / 43,550,795 B |
| base / base.en | 74 M | 145,216,508 B (base.en) | 147,964,211 B | 59,721,011 / 81,781,811 B |
| small / small.en | 244 M | 483,545,366 B (small.en) | 487,614,201 B | 190,098,681 / 264,477,561 B |
| medium / medium.en | 769 M | 1,527,904,330 B | 1,533,774,781 B | q5_0 539,225,533 / q8_0 823,382,461 B |
| large-v3 | 1550 M | 3,087,284,237 B | 3,095,033,483 B | q5_0 1,081,140,203 B |

Sizes are VERIFIED from the Hugging Face API trees of `Systran/faster-whisper-*` and `ggerganov/whisper.cpp` (accessed 2026-10-17). None was downloaded.

- **Formats.** CT2 is a directory (`model.bin`, `config.json`, `tokenizer.json`, `vocabulary.txt`), converted with `ct2-transformers-converter`; its weight type is chosen at load time through `compute_type` (VERIFIED: `external/models/tiny.en/README.md`). whisper.cpp uses a single-file legacy **ggml** binary. `src/whisper.cpp` contains no `gguf` reference (VERIFIED: grep count 0), so Whisper models are not GGUF. Quantised files are produced offline with `whisper-quantize` (q5_0, q5_1, q8_0) (README §Quantization).
- **English-only versus multilingual.** `.en` variants exist for tiny to medium. large and turbo are multilingual only (VERIFIED as documentation: openai/whisper README). faster-whisper forces `language="en"` for English-only models and warns if `multilingual=True` (`transcribe.py#L868-L870`, `#L954`).

### 6. Decoding features relevant to commands

| Feature | faster-whisper 1.2.1 `WhisperModel.transcribe` | whisper.cpp `whisper_full_params` |
|---|---|---|
| Initial prompt | `initial_prompt: str \| Iterable[int]` (`transcribe.py#L772`). The tokens are prepended to `all_tokens` (`#L1143-L1149`), so they act as `<|startofprev|>` context | `initial_prompt` plus `carry_initial_prompt` (re-prepend on every window), or `prompt_tokens` (`whisper.h#L527-L530`) |
| Hotwords | **Present** at v1.2.1: `hotwords: Optional[str] = None` (`#L788`), "Has no effect if prefix is not None" (`#L855-L856`). Implementation: `get_prompt` appends `sot_prev` and then the hotword tokens, then any previous tokens (`#L1542-L1550`). This is the same mechanism as the prompt, not a separate biasing algorithm | No separate hotword option |
| Grammar | **None** | **GBNF grammar**: `grammar_rules`, `n_grammar_rules`, `i_start_rule`, `grammar_penalty` (`whisper.h#L581-L584`). `whisper_suppress_invalid_grammar` subtracts `grammar_penalty` (default 100.0, `src/whisper.cpp#L6169`) from the logits of every token the grammar rejects (`#L5999-L6034`). It is a soft constraint, not a hard mask: EOT penalisation is commented out (`#L6036-L6038`). CLI: `--grammar`, `--grammar-rule`, `--grammar-penalty` (`examples/cli/cli.cpp#L212-L214`). Sample grammars: `grammars/assistant.gbnf`, `chess.gbnf`, `colors.gbnf` |
| Closed command list | None | `whisper-command` "guided mode" (`-cmd commands.txt`): it scores each allowed command by averaged token probabilities after a fixed prompt (`examples/command/command.cpp#L256`, `#L386-L414`) |
| Prefix / forced text | `prefix` (first window) | — |
| Token suppression | `suppress_tokens` (ids) | `suppress_regex`, `suppress_blank`, `suppress_nst` |
| VAD | `vad_filter` (default **False** on `WhisperModel.transcribe` (`#L782`) and **True** on `BatchedInferencePipeline.transcribe` (`#L289`)), plus `vad_parameters` | `vad`, `vad_model_path`, `vad_params` (threshold, min speech/silence ms, max speech s, pad ms, overlap) (`whisper.h#L192-L199`) |
| Word timestamps | `word_timestamps=True` (cross-attention alignment, `add_word_timestamps`). `hallucination_silence_threshold` applies only with word timestamps (`#L787`, `#L852-L854`) | `token_timestamps` (experimental, `#L505`) and DTW token timestamps (`dtw_token_timestamps`, experimental, `#L121-L127`) |
| Partial results / streaming | `transcribe` returns a lazy generator of segments (one per 30 s window or VAD chunk). There is no incremental microphone streaming in the library; the README points to third-party `ufal/whisper_streaming` and `speaches` (VERIFIED as documentation: `README.md#L240`, `#L249`) | `new_segment_callback` and `progress_callback` (`whisper.h#L562-L567`). `whisper-stream` example (sliding window `--step`/`--length`/`--keep`, `examples/stream/stream.cpp#L20-L22`). `abort_callback` for cancellation |
| Confidence | `Segment.avg_logprob`, `no_speech_prob`, `compression_ratio`; `Word.probability` | `whisper_token_data.p`, `plog`; `no_speech_thold`, `logprob_thold`, `entropy_thold` |

### 7. Licences

| Item | Licence | Evidence |
|---|---|---|
| faster-whisper code | MIT, © 2023 SYSTRAN | VERIFIED: `LICENSE` at 65882eee |
| CTranslate2 | MIT | INFERRED from upstream repo; check `ctranslate2-4.8.3.dist-info` licence in A28 (the licence audit) |
| whisper.cpp + ggml | MIT, © 2023-2026 The ggml authors | VERIFIED: `LICENSE` at 0c7be696 |
| OpenAI Whisper weights | "Whisper's code and model weights are released under the MIT License" (openai/whisper README). The Hugging Face card `openai/whisper-tiny.en`@87c71024 declares `apache-2.0` | VERIFIED as documentation: both sources. **The sources conflict**; record both in A28 |
| Systran CT2 conversions | MIT (card front matter); recorded as `model_license: MIT` in `external/stt.lock.json` | VERIFIED |
| ggerganov ggml conversions | MIT (Hugging Face card) | VERIFIED as documentation |
| Silero VAD | MIT (upstream snakers4/silero-vad) | INFERRED: verify in A28 |
| onnxruntime | MIT | INFERRED: verify in A28 |
| OpenBLAS (only in the blas zip) | BSD-3-Clause | INFERRED: verify if that build is chosen |
| whisper.cpp contribution policy | `AGENTS.md` says the project "does **not** accept pull requests that are fully or predominantly AI-generated". This matters if the project upstreams fixes such as the Java struct mapping | VERIFIED: `AGENTS.md#L1-L8` |

### 8. Model properties (Radford et al., 2022)

- **Training data.** 680,000 hours of weakly supervised, web-sourced audio–transcript pairs. Of these, 117,000 hours cover 96 non-English languages and 125,000 hours are X→en translation (§1, p. 1–2). Heuristics removed machine-generated transcripts (§2.1). Microscopy jargon is presumably rare in this data (INFERRED); P61 shows "ROI" failing without a prompt.
- **Robustness.** Zero-shot Whisper makes 55.2% fewer errors on average than a LibriSpeech-supervised model with matching LibriSpeech accuracy (§3.3, Table 2). Under additive pub noise below 10 dB SNR it outperforms all 14 LibriSpeech-trained comparison models (§3.7, Fig. 5). This is relevant to noisy microscope rooms; no local test (UNKNOWN).
- **English-only versus multilingual.** "For small models … there is indeed negative transfer between tasks and languages: joint models underperform English-only models trained for the same amount of compute"; for the largest models joint training is slightly better (§4.3, Fig. 9). The openai/whisper README: ".en models … tend to perform better, especially for the tiny.en and base.en models."
- **Hallucination.** §6 lists "getting stuck in repeat loops, not transcribing the first or last few words of an audio segment, or complete hallucination where the model will output a transcript entirely unrelated to the actual audio". The no-speech probability threshold of 0.6 combined with an average log-probability threshold of −1 is used as voice-activity detection (§4.5, Table 7).
- **Local confirmation (VERIFIED, P61).** On 5 s of digital silence with VAD off, `tiny.en` emitted the text **"You"** with `no_speech_prob` 0.944. With Silero VAD on, `duration_after_vad` was 0.0 and nothing was emitted. Gaussian noise (σ = 0.05) produced no text either way.

### 9. Measured evidence (this machine; Intel Core Ultra 9 285H, 16 cores; Windows 11)

**P60** (5 fresh processes plus a matrix; `fixtures/invert-command.wav`, 1.944 s synthetic speech; settings as `speech/worker.py`: CPU, int8, `cpu_threads=4`, `beam_size=5`, VAD on). All values VERIFIED: `probes/P60-faster-whisper-timing.out.txt`.

| Stage | Min | Max | Notes |
|---|---|---|---|
| `import faster_whisper` | 0.246 s | 0.269 s | Python import |
| `decode_audio` (PyAV) | 0.0128 s | 0.0159 s | |
| `WhisperModel(...)` load | 0.196 s | 0.210 s | OS file cache warm after the first run; a cold-disk first load was not isolated (UNKNOWN) |
| First `transcribe` | 0.263 s | 0.291 s | Includes the Silero ONNX session start |
| Warm `transcribe` (20 calls) | 0.176 s | 0.206 s | Real-time factor about 0.1 |
| int8 vs float32, warm, VAD on | 0.189–0.198 s vs 0.327–0.408 s | | int8 is about 1.8× faster |
| VAD off, int8 | 0.196–0.260 s | | VAD costs nothing measurable on a clip that is all speech |

All 26 transcriptions returned "Invert the image."

**P63** (unmodified `speech/worker.py`, 5 runs): end-to-end subprocess wall time was **942–1008 ms**. The per-call SHA-256 of all model files costs 0.032 s. So of about 1 s per request, roughly 0.25 s is imports, 0.2 s model load and 0.27 s transcription. The rest is interpreter start-up and VAD/ONNX initialisation (INFERRED from P60/P63 differences). A persistent worker would remove about 0.7 s per request (INFERRED).

**P61** (8 synthetic en-GB TTS command phrases; VERIFIED: `probes/P61-vocabulary-biasing-and-silence.out.txt`):

| Clip (spoken) | Baseline | With `initial_prompt` (identical output with `hotwords`) |
|---|---|---|
| "…inside the ROI." | "inside the **other eye**" | "inside the **ROI**." |
| "…Gaussian blur with sigma two point five." | "…Gaussian blur with Sigma 2.5" | "…Gaussian Blur with sigma 2.5." |
| "…median filter with radius three on channel two." | "radius **3** on channel **2**." | "radius **three** on channel **two**." |
| "…rolling ball radius of fifty pixels." | "50 **pike cells**" | "50 **pike cells**" (unchanged) |
| "…zero point one five microns per pixel." | "0.15 microns per **pixel**." | "0.15 microns per **cycle**." (regression) |
| Otsu / Z projection / Analyze particles | correct words, lower-case command names | command names capitalised as in the menu |

The prompt raised mean `avg_logprob` from about −0.43 to about −0.13. Findings:

- prompting helps rare acronyms but can introduce substitutions;
- number formatting (digits versus words) changes with the prompt;
- the outputs of `hotwords` and `initial_prompt` were byte-identical, consistent with the shared mechanism in §6.

TTS speech is not human speech, so accuracy on real users is **UNKNOWN**.

### 10. Third-party benchmarks (not reproduced here)

These numbers come from the faster-whisper README at v1.2.1 (`README.md#L11-L54`). They are third-party figures for 13 min of audio, measured with faster-whisper v1.1.0 and whisper.cpp v1.7.2.

- **small model, CPU, Intel Core i7-12700K, 8 threads, beam 5.** openai/whisper fp32 6m58s; whisper.cpp fp32 **2m05s** (1049 MB RAM); whisper.cpp with OpenVINO 1m45s; faster-whisper fp32 2m37s (2257 MB); faster-whisper **int8 1m42s** (1477 MB); faster-whisper int8 with `batch_size=8` 51s.
- **large-v2, GPU, RTX 3070 Ti, CUDA 12.4.** whisper.cpp with Flash Attention, fp16: 1m05s; faster-whisper fp16: 1m03s; faster-whisper int8: 59s.

This is the faster-whisper project's own benchmark, so the comparison is not independent. whisper.cpp points to community encoder benchmarks in [issue #89](https://github.com/ggml-org/whisper.cpp/issues/89) (accessed 2026-10-17). A local whisper.cpp measurement was not made: no binary was executed (P62 only lists archive contents). Its latency on this machine is **UNKNOWN**. To verify, run `whisper-cli.exe -m ggml-tiny.en-q5_1.bin -f fixtures/invert-command.wav` (a 32 MB model) from the P62 zip, in a scratch directory.

## Alternatives and Trade-offs

| Criterion | faster-whisper (in a worker) | whisper.cpp (DLL or CLI/server) | Evidence |
|---|---|---|---|
| Already integrated and tested in the prototype | Yes (subprocess worker, checksum-pinned model) | No | VERIFIED |
| Latency for 2 s commands, CPU | 0.18–0.29 s transcription; about 1 s per cold request | UNKNOWN locally; third-party CPU numbers are comparable | P60, P63; §10 |
| Install footprint | Python plus about 24 wheels | 9 MB zip plus a model | P62 |
| Java/Fiji in-process | Not possible (Python) | JNA bindings exist; stale Maven artefact; struct drift | VERIFIED (§3, Risks) |
| Vocabulary biasing | Prompt/hotwords (same mechanism) | Prompt + carry-prompt + GBNF grammar + guided command list | VERIFIED |
| GPU on Windows | Needs separately obtained CUDA 12 cuBLAS (P64); cuDNN 9 bundled | Prebuilt CUDA zips (285/685 MB); Vulkan by source build | P62, P64 |
| Fault isolation | Separate process (prototype pattern) | Same, if run as CLI/server; a crash inside the Fiji JVM if loaded by JNA | INFERRED |
| Licences | MIT code; weights MIT/Apache-2.0 per source | MIT | §7 |

**Recommendation (PROPOSED), serving CR-A16-01…08.**

1. Keep faster-whisper behind the prototype's process-isolated worker boundary ([speech.py](../../../backend/src/fidelis/adapters/speech.py)), changed from one process per request to a persistent worker that loads the model once. `tiny.en` stays the default; `base.en` is an option, with selection by evaluation on recorded user speech (A30).
2. Enable Silero VAD for all microphone input (P61 shows its effect on silence). Pass a short, generated command-vocabulary prompt drawn from the command catalogue (`docs/data/commands.csv`). Never pass whole catalogues: the prompt is limited to `max_length // 2` tokens (`transcribe.py#L1546`).
3. The transcript is shown as editable text, then parsed and validated by the A17 layer and confirmed by the user. Speech never triggers an operation directly.

**Conditions favouring whisper.cpp:**
- (a) the final architecture is Java-resident, for example a Fiji plugin with no Python sidecar;
- (b) closed-vocabulary command recognition with GBNF grammar or guided mode is shown in A30 to beat prompted free text;
- (c) packaging must avoid shipping a Python runtime;
- (d) GPU acceleration on non-NVIDIA hardware (Vulkan) is required.

In case (a), the worker pattern still applies: run `whisper-server.exe` or `whisper-cli.exe` as a child process instead of loading `whisper.dll` into the JVM, because of the struct-drift risk (R-A16-03).

## Requirement implications

- **CR-A16-01** — The speech subsystem shall run speech recognition fully locally, with no audio leaving the machine. *Rationale:* both engines run offline (VERIFIED), and microscopy sessions may be confidential.
- **CR-A16-02** — The speech engine shall run in a separate operating-system process from Fiji and the UI, with a bounded request size and duration. *Rationale:* fault isolation, as already shown by `speech/worker.py` (30 s cap) and `speech.py` (180 s timeout).
- **CR-A16-03** — The speech worker shall keep the model loaded between requests, so that the transcription latency of a 2 s command on the reference CPU is at most 0.5 s after the first request. *Rationale:* P63 shows about 0.7 s of each 1 s request is start-up overhead. The 0.5 s target (PROPOSED) is about twice the measured warm figure (P60).
- **CR-A16-04** — Voice activity detection shall be applied to all microphone input, and a clip in which VAD detects no speech shall produce no transcript. *Rationale:* P61 shows "You" emitted on silence without VAD; Radford et al. §6 documents hallucination.
- **CR-A16-05** — A transcript shall be presented to the user as editable text and passed to the A17 command layer. It shall never directly execute a Fiji operation. *Rationale:* hallucination and substitution errors (P61: "other eye", "pike cells", "cycle").
- **CR-A16-06** — The speech subsystem shall accept a vocabulary prompt generated from the command catalogue and the current context (open image names, channel names), and shall record the prompt used with each transcript. *Rationale:* P61 shows that prompting fixes "ROI" but can introduce errors, so it must be traceable and evaluable.
- **CR-A16-07** — The model files shall be pinned by revision and SHA-256 and loaded with downloads disabled. *Rationale:* reproducibility; the prototype already does this (`external/stt.lock.json`, `worker.py#L16-L22`).
- **CR-A16-08** — Speech recognition shall work on CPU only, with GPU acceleration optional. *Rationale:* CPU latency is sufficient for commands (P60); GPU set-up needs extra NVIDIA libraries (P64), and lab GPU availability is UNKNOWN.
- **CR-A16-09** — Transcript post-processing shall normalise spoken numbers ("three", "two point five") and digits to the same numeric value before parameter extraction. *Rationale:* P61 shows digit/word output varies with the prompt.
- **CR-A16-10** — An evaluation set of recorded human utterances of Fiji commands (several speakers, accents, room noise) shall be built, and command-level accuracy shall be reported per model and prompt configuration before a default is fixed. *Rationale:* all local evidence is synthetic TTS.

## Risks

| ID | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R-A16-01 | Accuracy on real users' speech (accents, microscope-room noise, masks) is lower than on TTS fixtures | High | High | CR-A16-10; editable transcript (CR-A16-05); keyboard path always available |
| R-A16-02 | The vocabulary prompt introduces substitutions (P61 "pixel"→"cycle") | Medium | Medium | Evaluate with and without the prompt; keep prompts short; the validator rejects unknown values |
| R-A16-03 | whisper.cpp Java bindings at 0c7be696 map `whisper_full_params` without the trailing `vad`, `vad_model_path` and `vad_params` fields (`bindings/java/.../params/WhisperFullParams.java#L335-L352` vs `include/whisper.h#L586-L591`). The struct is passed by value (`WhisperFullParams.ByValue`), so the native side may read undefined memory, and in-JVM crashes can take Fiji down | Medium (INFERRED; not run) | High | Use a CLI/server child process; or generate bindings from the header and test before any in-process use |
| R-A16-04 | GPU path needs CUDA 12 cuBLAS DLLs that the ctranslate2 wheel does not bundle (P64), giving support burden and large redistributables | Medium | Low | CPU-only default (CR-A16-08) |
| R-A16-05 | Model licence ambiguity (MIT in openai/whisper README vs Apache-2.0 on the Hugging Face card) | Low | Medium | Record both in A28; both are permissive; obtain owner decision |
| R-A16-06 | faster-whisper has had no release since v1.2.1 (2025-10-31), almost a year before this review, while whisper.cpp releases monthly | Medium | Medium | Pin versions; keep the worker interface engine-neutral (text plus segments plus confidences) so the engine can be swapped |
| R-A16-07 | Cold first load after reboot is slower than the measured 0.2 s (file-cache effect) | Medium | Low | Pre-load the worker at application start; measure in A30 |

## References

- Radford, A., Kim, J. W., Xu, T., Brockman, G., McLeavey, C., Sutskever, I. (2022). *Robust Speech Recognition via Large-Scale Weak Supervision*. [arXiv:2212.04356](https://arxiv.org/abs/2212.04356) (accessed 2026-10-17).
- SYSTRAN, faster-whisper v1.2.1 (65882eee): [github.com/SYSTRAN/faster-whisper](https://github.com/SYSTRAN/faster-whisper) (accessed 2026-10-17). Local cache `fiji-research-cache/faster-whisper`.
- ggml-org, whisper.cpp master 0c7be696; release v1.9.5 / b5454 (d1be6fde): [github.com/ggml-org/whisper.cpp](https://github.com/ggml-org/whisper.cpp), [releases/tag/b5454](https://github.com/ggml-org/whisper.cpp/releases/tag/b5454) (accessed 2026-10-17).
- OpenAI, whisper README (licence, model table): [github.com/openai/whisper](https://github.com/openai/whisper) (accessed 2026-10-17).
- Hugging Face model trees: [Systran/faster-whisper-tiny.en](https://huggingface.co/Systran/faster-whisper-tiny.en) (rev 0d3d19a3), [ggerganov/whisper.cpp](https://huggingface.co/ggerganov/whisper.cpp), [openai/whisper-tiny.en](https://huggingface.co/openai/whisper-tiny.en) (rev 87c71024) (accessed 2026-10-17).
- Maven Central metadata, `io.github.ggerganov:whispercpp`: [maven-metadata.xml](https://repo1.maven.org/maven2/io/github/ggerganov/whispercpp/maven-metadata.xml) (accessed 2026-10-17).
- whisper.cpp benchmark issue: [ggml-org/whisper.cpp#89](https://github.com/ggml-org/whisper.cpp/issues/89) (accessed 2026-10-17).
- Probes: `probes/P60-faster-whisper-timing.*`, `probes/P61-*`, `probes/P62-whispercpp-windows-packaging.*`, `probes/P63-worker-end-to-end.*`, `probes/P64-ctranslate2-cuda-check.*`; index `probes/README-G5.md`.

## Change log

- 2026-10-17: Initial draft (agent G5).
