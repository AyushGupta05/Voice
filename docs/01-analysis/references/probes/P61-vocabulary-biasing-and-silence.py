"""P61 (part 2): effect of initial_prompt / hotwords on scientific vocabulary, and output on
non-speech input, using faster-whisper 1.2.1 + tiny.en (CPU, int8) exactly as installed.

Usage (repository root, isolated speech interpreter):
    .venv-stt/Scripts/python.exe -I docs/01-analysis/references/probes/P61-vocabulary-biasing-and-silence.py <clip dir>

<clip dir> holds s01..s08.wav from P61-synthesise-scientific-phrases.ps1. The script also writes
silence.wav (5 s zeros) and noise.wav (5 s Gaussian noise, sd 0.05, seed 0) there.
"""

import json
import sys
import wave
from pathlib import Path

import numpy as np
from faster_whisper import WhisperModel
from faster_whisper.audio import decode_audio

ROOT = Path.cwd()
clips = Path(sys.argv[1])

for name, data in (
    ("silence", np.zeros(5 * 16000)),
    ("noise", np.random.default_rng(0).normal(0, 0.05, 5 * 16000)),
):
    with wave.open(str(clips / f"{name}.wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        w.writeframes((np.clip(data, -1, 1) * 32767).astype("<i2").tobytes())

VOCAB = (
    "Fiji ImageJ commands: Gaussian Blur, sigma, Otsu, ROI, Convert to Mask, median, "
    "rolling ball, Subtract Background, Z Project, microns, Analyze Particles."
)
model = WhisperModel(
    str(ROOT / "external/models/tiny.en"), device="cpu", compute_type="int8", cpu_threads=4,
    local_files_only=True,
)
base = dict(language="en", beam_size=5, condition_on_previous_text=False)
variants = {
    "baseline": dict(vad_filter=True),
    "initial_prompt": dict(vad_filter=True, initial_prompt=VOCAB),
    "hotwords": dict(vad_filter=True, hotwords=VOCAB),
}
results = {"vocab": VOCAB, "speech": {}, "nonspeech": {}}
for wav in sorted(clips.glob("s*.wav")):
    audio = decode_audio(str(wav), sampling_rate=16000)
    row = {}
    for label, kw in variants.items():
        segs, _ = model.transcribe(audio, **base, **kw)
        segs = list(segs)
        row[label] = {
            "text": " ".join(s.text.strip() for s in segs).strip(),
            "avg_logprob": [round(s.avg_logprob, 3) for s in segs],
        }
    results["speech"][wav.stem] = row

for name in ("silence", "noise"):
    audio = decode_audio(str(clips / f"{name}.wav"), sampling_rate=16000)
    row = {}
    for vad in (False, True):
        segs, info = model.transcribe(audio, **base, vad_filter=vad)
        segs = list(segs)
        row[f"vad={vad}"] = {
            "text": " ".join(s.text.strip() for s in segs).strip(),
            "no_speech_prob": [round(s.no_speech_prob, 3) for s in segs],
            "duration_after_vad": info.duration_after_vad,
        }
    results["nonspeech"][name] = row

print(json.dumps(results, indent=1))
