"""P60: faster-whisper 1.2.1 model-load vs transcription timing on the prototype setup.

Run with the isolated speech interpreter, from the repository root:
    .venv-stt/Scripts/python.exe -I docs/01-analysis/references/probes/P60-faster-whisper-timing.py <wav> [mode]

mode = "cold" (default): one fresh process measures import, model load, first transcription,
       then 4 further warm transcriptions of the same clip.
mode = "matrix": additionally times compute_type int8 vs float32 and vad_filter on/off.
Settings mirror speech/worker.py (device=cpu, compute_type=int8, cpu_threads=4, beam_size=5,
language=en, vad_filter=True, condition_on_previous_text=False). speech/worker.py is not modified.
"""

import json
import platform
import sys
import time
from pathlib import Path

t0 = time.perf_counter()
import faster_whisper  # noqa: E402
from faster_whisper import WhisperModel  # noqa: E402
from faster_whisper.audio import decode_audio  # noqa: E402

t_import = time.perf_counter() - t0

ROOT = Path.cwd()
MODEL = ROOT / "external/models/tiny.en"
wav = sys.argv[1]
mode = sys.argv[2] if len(sys.argv) > 2 else "cold"


def transcribe(model, audio, **overrides):
    kwargs = dict(language="en", beam_size=5, vad_filter=True, condition_on_previous_text=False)
    kwargs.update(overrides)
    t = time.perf_counter()
    segments, info = model.transcribe(audio, **kwargs)
    text = " ".join(s.text.strip() for s in segments).strip()  # generator: decoding happens here
    return time.perf_counter() - t, text, info.duration


t = time.perf_counter()
audio = decode_audio(wav, sampling_rate=16000)
t_decode = time.perf_counter() - t

t = time.perf_counter()
model = WhisperModel(str(MODEL), device="cpu", compute_type="int8", cpu_threads=4, local_files_only=True)
t_load = time.perf_counter() - t

runs = [transcribe(model, audio) for _ in range(5)]
out = {
    "python": platform.python_version(),
    "faster_whisper": faster_whisper.__version__,
    "audio_seconds": round(len(audio) / 16000, 3),
    "import_s": round(t_import, 3),
    "decode_audio_s": round(t_decode, 4),
    "model_load_s": round(t_load, 3),
    "transcribe_first_s": round(runs[0][0], 3),
    "transcribe_warm_s": [round(r[0], 3) for r in runs[1:]],
    "text": runs[0][1],
}

if mode == "matrix":
    matrix = {}
    for ct in ("int8", "float32"):
        t = time.perf_counter()
        m = WhisperModel(str(MODEL), device="cpu", compute_type=ct, cpu_threads=4, local_files_only=True)
        load = time.perf_counter() - t
        for vad in (True, False):
            transcribe(m, audio, vad_filter=vad)  # warm-up
            times = [transcribe(m, audio, vad_filter=vad)[0] for _ in range(3)]
            matrix[f"{ct}/vad={vad}"] = {"load_s": round(load, 3), "warm_s": [round(x, 3) for x in times]}
        del m
    out["matrix"] = matrix

print(json.dumps(out))
