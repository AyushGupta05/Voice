"""Standalone CPU speech worker. Run with the isolated speech interpreter."""

import hashlib
import json
import sys
from pathlib import Path

from faster_whisper import WhisperModel
from faster_whisper.audio import decode_audio

ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / "external/models/tiny.en"


def main():
    if not (MODEL / "model.bin").is_file():
        raise RuntimeError("Pinned speech model missing; run speech/setup_speech.py")
    expected = json.loads((ROOT / "external/stt-model.sha256.json").read_text())
    for name, digest in expected.items():
        with (MODEL / name).open("rb") as stream:
            if hashlib.file_digest(stream, "sha256").hexdigest() != digest:
                raise RuntimeError(f"Speech model checksum mismatch: {name}")
    audio = decode_audio(sys.argv[1], sampling_rate=16000)
    if len(audio) == 0 or len(audio) > 30 * 16000:
        raise ValueError("Speech clips must be between 0 and 30 seconds")
    model = WhisperModel(
        str(MODEL), device="cpu", compute_type="int8", cpu_threads=4, local_files_only=True
    )
    segments, info = model.transcribe(
        audio, language="en", beam_size=5, vad_filter=True, condition_on_previous_text=False
    )
    text = " ".join(segment.text.strip() for segment in segments).strip()
    print(
        json.dumps(
            {
                "text": text,
                "language": info.language,
                "duration": info.duration,
                "model": "Systran/faster-whisper-tiny.en",
                "device": "cpu",
            }
        )
    )


if __name__ == "__main__":
    main()
