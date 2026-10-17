"""Tests for speech/server.py. Run with the speech interpreter:

    python -I speech/test_server.py

The pinned model folder is resolved from FIDELIS_STT_MODEL, else this checkout's external/models/tiny.en,
else the click-to-batch checkout. Tests are skipped when no model is available.
"""

import base64
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import wave
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
HASHES = ROOT / "external" / "stt-model.sha256.json"
FIXTURE = ROOT / "fixtures" / "invert-command.wav"


def find_model():
    candidates = [os.environ.get("FIDELIS_STT_MODEL"), ROOT / "external" / "models" / "tiny.en",
                  r"C:\Users\arnav\Documents\dev\click-to-batch\external\models\tiny.en"]
    for c in candidates:
        if c and (Path(c) / "model.bin").is_file():
            return Path(c)
    return None


MODEL = find_model()


class Client:
    def __init__(self, model, hashes=HASHES):
        self.proc = subprocess.Popen(
            [sys.executable, "-I", str(HERE / "server.py"), "--model", str(model), "--hashes", str(hashes)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            text=True, encoding="utf-8")

    def call(self, message):
        self.proc.stdin.write(json.dumps(message) + "\n")
        self.proc.stdin.flush()
        return json.loads(self.proc.stdout.readline())

    def close(self):
        try:
            self.proc.stdin.close()
            self.proc.wait(timeout=20)
        except Exception:
            self.proc.kill()


@unittest.skipIf(MODEL is None, "speech model not available")
class ServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.client = Client(MODEL)
        cls.ready = cls.client.call({"type": "hello"})

    @classmethod
    def tearDownClass(cls):
        cls.client.close()

    def test_handshake_reports_verified_hashes(self):
        self.assertEqual(self.ready["type"], "ready")
        expected = json.loads(HASHES.read_text())
        self.assertEqual(self.ready["sha256"], expected)

    def test_ping(self):
        self.assertEqual(self.client.call({"type": "ping"})["type"], "pong")

    def test_transcribe_file_fixture(self):
        r = self.client.call({"type": "transcribe_file", "id": "f1", "path": str(FIXTURE)})
        self.assertEqual(r["type"], "result", r)
        self.assertEqual(r["id"], "f1")
        self.assertIn("invert the image", r["text"].lower())
        self.assertTrue(r["speech"])
        self.assertTrue(r["segments"])
        self.assertIn("avgLogprob", r["segments"][0])

    def test_transcribe_pcm_matches_file(self):
        import numpy as np
        from faster_whisper.audio import decode_audio

        audio = decode_audio(str(FIXTURE), sampling_rate=16000)
        pcm = (np.clip(audio, -1, 1) * 32767).astype("<i2").tobytes()
        r = self.client.call({"type": "transcribe", "id": "p1", "sampleRate": 16000,
                              "pcm16le_b64": base64.b64encode(pcm).decode(),
                              "prompt": "ImageJ commands: Invert, Gaussian Blur, Measure"})
        self.assertEqual(r["type"], "result", r)
        self.assertIn("invert", r["text"].lower())

    def test_silence_has_no_speech(self):
        pcm = b"\x00\x00" * 16000 * 5
        r = self.client.call({"type": "transcribe", "id": "s1", "sampleRate": 16000,
                              "pcm16le_b64": base64.b64encode(pcm).decode()})
        self.assertEqual(r["type"], "result", r)
        self.assertFalse(r["speech"])
        self.assertEqual(r["text"], "")

    def test_audio_too_long_is_rejected(self):
        pcm = b"\x00\x00" * 16000 * 31
        r = self.client.call({"type": "transcribe", "id": "l1", "sampleRate": 16000,
                              "pcm16le_b64": base64.b64encode(pcm).decode()})
        self.assertEqual((r["type"], r["code"]), ("error", "AUDIO_TOO_LONG"))

    def test_error_cases_keep_worker_alive(self):
        self.assertEqual(self.client.call({"type": "nonsense", "id": "x"})["code"], "UNKNOWN_TYPE")
        self.assertEqual(self.client.call({"type": "transcribe_file", "id": "y", "path": "nope.wav"})["code"],
                         "FILE_NOT_FOUND")
        self.assertEqual(self.client.call({"type": "ping"})["type"], "pong")


@unittest.skipIf(MODEL is None, "speech model not available")
class HashMismatchTest(unittest.TestCase):
    def test_refuses_on_hash_mismatch(self):
        with tempfile.TemporaryDirectory() as tmp:
            bad = Path(tmp) / "hashes.json"
            data = json.loads(HASHES.read_text())
            data["config.json"] = "0" * 64
            bad.write_text(json.dumps(data))
            client = Client(MODEL, hashes=bad)
            try:
                r = client.call({"type": "hello"})
                self.assertEqual((r["type"], r["code"]), ("error", "MODEL_HASH_MISMATCH"))
                self.assertEqual(client.proc.wait(timeout=20), 3)
            finally:
                client.close()


class MissingModelTest(unittest.TestCase):
    def test_refuses_when_model_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            client = Client(Path(tmp))
            try:
                r = client.call({"type": "hello"})
                self.assertEqual((r["type"], r["code"]), ("error", "MODEL_MISSING"))
            finally:
                client.close()


class EofTest(unittest.TestCase):
    def test_exits_on_stdin_eof(self):
        with tempfile.TemporaryDirectory() as tmp:
            client = Client(Path(tmp))
            client.proc.stdin.close()
            self.assertEqual(client.proc.wait(timeout=20), 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
