"""Opt-in real upstream tests. No mocks of Fiji or speech recognition."""

import io
import json
import os
import time

import pytest
from fastapi.testclient import TestClient
from PIL import Image, ImageChops, ImageStat

from fidelis.config import ROOT
from fidelis.main import app

pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(
        os.environ.get("FIDELIS_INTEGRATION") != "1",
        reason="Set FIDELIS_INTEGRATION=1 for real runtime tests",
    ),
]
client = TestClient(app)


def wait_for_job(job_id):
    deadline = time.monotonic() + 150
    states = set()
    while time.monotonic() < deadline:
        response = client.get(f"/api/jobs/{job_id}")
        assert response.status_code == 200
        job = response.json()
        states.add(job["status"])
        if job["status"] in ("completed", "failed"):
            assert job["status"] == "completed", job
            assert "running" in states
            return job
        time.sleep(0.2)
    pytest.fail("Job did not finish within 150s")


def image(job_id, stage):
    response = client.get(f"/api/jobs/{job_id}/artifacts/stage-{stage}.png")
    assert response.status_code == 200
    return Image.open(io.BytesIO(response.content)).convert("L")


def test_real_audio_to_command_to_fiji_and_macro_replay():
    audio = (ROOT / "fixtures/invert-command.wav").read_bytes()
    transcript = client.post("/api/speech/transcribe", content=audio)
    assert transcript.status_code == 200, transcript.text
    preview = client.post("/api/commands/preview", json={"text": transcript.json()["text"]})
    assert preview.status_code == 200, preview.text
    actions = preview.json()["actions"]
    assert [a["operation"] for a in actions] == ["invert"]
    response = client.post("/api/jobs", json={"actions": actions})
    assert response.status_code == 202
    job = wait_for_job(response.json()["id"])
    original, processed = image(job["id"], 0), image(job["id"], 1)
    assert ImageStat.Stat(original).mean[0] == 18.75
    assert ImageStat.Stat(processed).mean[0] == 236.25
    assert ImageChops.difference(ImageChops.invert(original), processed).getbbox() is None
    assert [e["stage"] for e in job["events"] if e["kind"] == "stage"] == [0, 1]
    assert float(job["measurements"][-1]["mean"]) == 236.25
    macro = client.get(f"/api/jobs/{job['id']}/artifacts/recording.ijm")
    assert 'run("Invert");' in macro.text
    replay_response = client.post(f"/api/jobs/{job['id']}/replay")
    assert replay_response.status_code == 202
    repeated = wait_for_job(replay_response.json()["id"])
    assert repeated["id"] != job["id"]
    assert ImageChops.difference(processed, image(repeated["id"], 1)).getbbox() is None
    evidence = {"transcription": transcript.json(), "first_job": job, "replay_job": repeated}
    (ROOT / "outputs/e2e-evidence.json").write_text(json.dumps(evidence, indent=2))


def test_real_blur_threshold_measure_and_uploaded_input():
    upload = client.post("/api/images", content=(ROOT / "fixtures/two-squares.png").read_bytes())
    assert upload.status_code == 201
    response = client.post(
        "/api/jobs",
        json={
            "image_id": upload.json()["image_id"],
            "actions": [
                {"operation": "blur", "sigma": 2},
                {"operation": "threshold", "level": 128},
                {"operation": "measure"},
            ],
        },
    )
    assert response.status_code == 202
    job = wait_for_job(response.json()["id"])
    original, blurred, mask = [image(job["id"], i) for i in range(3)]
    assert ImageChops.difference(original, blurred).getbbox() is not None
    assert mask.getpixel((48, 48)) == 255
    assert mask.getpixel((16, 16)) == 0
    assert set(mask.getdata()) == {0, 255}
    assert ImageChops.difference(mask, image(job["id"], 3)).getbbox() is None
    assert float(job["measurements"][-1]["area"]) == 4096
    assert float(job["measurements"][-1]["mean"]) == pytest.approx(
        ImageStat.Stat(mask).mean[0], abs=0.00001
    )
    assert (ROOT / "fixtures/two-squares.png").read_bytes() == client.get(
        "/api/images/demo"
    ).content
