"""Persistent speech worker for Fidelis (docs D08 section 1.1, IF-06).

JSON lines on stdin/stdout, one message per line. The model is verified against the pinned SHA-256
file and loaded once. Run with the isolated speech interpreter:

    python speech/server.py [--model DIR] [--hashes FILE]

Messages (request -> reply):
  {"type":"hello"}                                   -> {"type":"ready","model":...,"sha256":{...}}
  {"type":"transcribe","id":..,"sampleRate":16000,
   "pcm16le_b64":..,"prompt":..}                     -> {"type":"result","id":..,...}
  {"type":"transcribe_file","id":..,"path":..,"prompt":..}  -> {"type":"result","id":..,...}
  {"type":"ping"}                                    -> {"type":"pong"}
  {"type":"shutdown"}                                -> process exits
Errors: {"type":"error","id":..,"code":..,"message":..}. The worker exits on stdin EOF.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MODEL = Path(os.environ.get("FIDELIS_STT_MODEL", ROOT / "external" / "models" / "tiny.en"))
DEFAULT_HASHES = Path(os.environ.get("FIDELIS_STT_HASHES", ROOT / "external" / "stt-model.sha256.json"))
MODEL_NAME = "Systran/faster-whisper-tiny.en"
SAMPLE_RATE = 16000
MAX_SECONDS = 30
MAX_PROMPT_CHARS = 600

# The protocol owns the real stdout; anything a library prints goes to stderr.
_PROTOCOL_OUT = sys.stdout
sys.stdout = sys.stderr


def send(message: dict) -> None:
    _PROTOCOL_OUT.write(json.dumps(message, ensure_ascii=True) + "\n")
    _PROTOCOL_OUT.flush()


def error(request_id, code: str, message: str) -> dict:
    return {"type": "error", "id": request_id, "code": code, "message": message}


def verify_model(model_dir: Path, hashes_file: Path) -> dict:
    """Return name -> digest for every pinned file, or raise RuntimeError on any mismatch."""
    if not (model_dir / "model.bin").is_file():
        raise RuntimeError(f"MODEL_MISSING: pinned speech model not found in {model_dir}")
    if not hashes_file.is_file():
        raise RuntimeError(f"MODEL_MISSING: hash file not found: {hashes_file}")
    expected = json.loads(hashes_file.read_text(encoding="utf-8"))
    actual = {}
    for name, digest in expected.items():
        path = model_dir / name
        if not path.is_file():
            raise RuntimeError(f"MODEL_HASH_MISMATCH: pinned model file {name} is missing")
        with path.open("rb") as stream:
            found = hashlib.file_digest(stream, "sha256").hexdigest()
        if found != digest:
            raise RuntimeError(f"MODEL_HASH_MISMATCH: SHA-256 of {name} does not match the pinned value")
        actual[name] = found
    return actual


class Worker:
    def __init__(self, model_dir: Path, hashes_file: Path):
        self.model_dir = model_dir
        self.hashes_file = hashes_file
        self.model = None
        self.sha256: dict = {}

    def load(self) -> None:
        if self.model is not None:
            return
        self.sha256 = verify_model(self.model_dir, self.hashes_file)
        from faster_whisper import WhisperModel

        self.model = WhisperModel(
            str(self.model_dir), device="cpu", compute_type="int8", cpu_threads=4, local_files_only=True
        )

    def hello(self) -> dict:
        t0 = time.perf_counter()
        self.load()
        return {
            "type": "ready",
            "protocol": 1,
            "model": MODEL_NAME,
            "device": "cpu",
            "sha256": self.sha256,
            "loadMs": round((time.perf_counter() - t0) * 1000),
        }

    def transcribe_audio(self, request_id, audio, prompt, t_start) -> dict:
        import numpy as np

        if len(audio) == 0:
            return error(request_id, "EMPTY_AUDIO", "No audio samples")
        if len(audio) > MAX_SECONDS * SAMPLE_RATE:
            return error(request_id, "AUDIO_TOO_LONG", f"Clip exceeds {MAX_SECONDS} s")
        self.load()
        prompt = (prompt or "").strip()[:MAX_PROMPT_CHARS] or None
        t_decode = time.perf_counter()
        segments_iter, info = self.model.transcribe(
            np.asarray(audio, dtype=np.float32),
            language="en",
            beam_size=5,
            vad_filter=True,
            condition_on_previous_text=False,
            initial_prompt=prompt,
        )
        segments = [
            {
                "start": round(float(s.start), 3),
                "end": round(float(s.end), 3),
                "text": s.text.strip(),
                "avgLogprob": float(s.avg_logprob),
                "noSpeechProb": float(s.no_speech_prob),
            }
            for s in segments_iter
        ]
        text = " ".join(s["text"] for s in segments if s["text"]).strip()
        now = time.perf_counter()
        return {
            "type": "result",
            "id": request_id,
            "text": text,
            "speech": bool(text),
            "segments": segments,
            "durationSeconds": len(audio) / SAMPLE_RATE,
            "model": MODEL_NAME,
            "prompt": prompt,
            "timingMs": {
                "decode": round((now - t_decode) * 1000),
                "total": round((now - t_start) * 1000),
            },
        }

    def handle(self, message: dict) -> dict | None:
        import numpy as np

        kind = message.get("type")
        request_id = message.get("id")
        t_start = time.perf_counter()
        if kind == "hello":
            reply = self.hello()
            if request_id is not None:
                reply["id"] = request_id
            return reply
        if kind == "ping":
            return {"type": "pong", "id": request_id}
        if kind == "transcribe":
            rate = int(message.get("sampleRate", SAMPLE_RATE))
            try:
                raw = base64.b64decode(message.get("pcm16le_b64", ""), validate=True)
            except Exception:
                return error(request_id, "BAD_AUDIO", "pcm16le_b64 is not valid base64")
            if len(raw) % 2:
                return error(request_id, "BAD_AUDIO", "PCM length is not a multiple of 2 bytes")
            audio = np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0
            if rate != SAMPLE_RATE and len(audio):
                # Java resamples to 16 kHz before sending; this is a safety net for other clients.
                target = int(len(audio) * SAMPLE_RATE / rate)
                audio = np.interp(
                    np.linspace(0, len(audio) - 1, num=target), np.arange(len(audio)), audio
                ).astype(np.float32)
            return self.transcribe_audio(request_id, audio, message.get("prompt"), t_start)
        if kind == "transcribe_file":
            path = message.get("path")
            if not path or not Path(path).is_file():
                return error(request_id, "FILE_NOT_FOUND", f"Audio file not found: {path}")
            from faster_whisper.audio import decode_audio

            try:
                audio = decode_audio(str(path), sampling_rate=SAMPLE_RATE)
            except Exception as exc:  # decoder errors vary by container
                return error(request_id, "BAD_AUDIO", f"Cannot decode audio: {exc}")
            return self.transcribe_audio(request_id, audio, message.get("prompt"), t_start)
        return error(request_id, "UNKNOWN_TYPE", f"Unknown message type: {kind!r}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--hashes", type=Path, default=DEFAULT_HASHES)
    args = parser.parse_args()
    sys.stdin.reconfigure(encoding="utf-8")
    worker = Worker(args.model, args.hashes)
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
            if not isinstance(message, dict):
                raise ValueError("message must be an object")
        except ValueError as exc:
            send(error(None, "BAD_JSON", str(exc)))
            continue
        if message.get("type") == "shutdown":
            return 0
        try:
            reply = worker.handle(message)
        except RuntimeError as exc:
            text = str(exc)
            code, _, detail = text.partition(": ")
            send(error(message.get("id"), code if code.isupper() else "WORKER_ERROR", detail or text))
            if code in ("MODEL_HASH_MISMATCH", "MODEL_MISSING"):
                return 3  # refuse to serve with an unverified or absent model
            continue
        except Exception as exc:  # keep the worker alive on per-request failures
            send(error(message.get("id"), "WORKER_ERROR", f"{type(exc).__name__}: {exc}"))
            continue
        if reply is not None:
            send(reply)
    return 0  # stdin EOF


if __name__ == "__main__":
    sys.exit(main())
