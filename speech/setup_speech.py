"""Run with .venv-stt Python; download only the explicitly pinned model revision."""

import hashlib
import json
from pathlib import Path

from huggingface_hub import snapshot_download

root = Path(__file__).resolve().parents[1]
lock = json.loads((root / "external/stt.lock.json").read_text())
snapshot_download(
    repo_id=lock["model"],
    revision=lock["revision"],
    local_dir=root / "external/models/tiny.en",
    allow_patterns=["config.json", "model.bin", "tokenizer.json", "vocabulary.txt", "README.md"],
)
expected = json.loads((root / "external/stt-model.sha256.json").read_text())
for name, digest in expected.items():
    with (root / "external/models/tiny.en" / name).open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != digest:
            raise RuntimeError(f"Speech model checksum mismatch: {name}")
print("Pinned speech model installed and checksums verified")
