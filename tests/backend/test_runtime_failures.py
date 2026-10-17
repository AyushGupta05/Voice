import json
import subprocess
import sys
import time

import pytest
from fastapi.testclient import TestClient

from fidelis import main
from fidelis.adapters import fiji
from fidelis.commands import parse_command


def test_runtime_changes_are_rejected(tmp_path, monkeypatch):
    home = tmp_path / "Fiji"
    home.mkdir()
    exe = home / "fiji.exe"
    exe.write_bytes(b"original")
    (tmp_path / "external").mkdir()
    lock = {"launcher": exe.name, "files": {"fiji.exe": fiji.sha256(exe)}}
    (tmp_path / "external/fiji.lock.json").write_text(json.dumps(lock))
    monkeypatch.setattr(fiji, "ROOT", tmp_path)
    fiji.verify_runtime(exe)
    exe.write_bytes(b"changed")
    with pytest.raises(RuntimeError, match="differs"):
        fiji.verify_runtime(exe)


def test_timeout_terminates_a_real_worker_process(tmp_path, monkeypatch):
    original_popen = subprocess.Popen
    processes = []

    def start_sleeping_worker(args, **kwargs):
        process = original_popen([sys.executable, "-c", "import time; time.sleep(60)"], **kwargs)
        processes.append(process)
        return process

    monkeypatch.setattr(fiji, "verify_runtime", lambda executable: "test")
    monkeypatch.setattr(fiji, "fiji_executable", lambda: tmp_path / "fiji.exe")
    monkeypatch.setattr(fiji.subprocess, "Popen", start_sleeping_worker)
    # taskkill also uses Popen internally, so restore it before the cleanup action.
    original_terminate = fiji.terminate_tree

    def terminate(process):
        monkeypatch.setattr(fiji.subprocess, "Popen", original_popen)
        original_terminate(process)

    monkeypatch.setattr(fiji, "terminate_tree", terminate)
    with pytest.raises(TimeoutError):
        fiji.execute(
            parse_command("invert"),
            tmp_path / "input.png",
            tmp_path,
            lambda *args: None,
            timeout=0.1,
        )
    assert processes[0].poll() is not None


def test_api_surfaces_worker_failure(tmp_path, monkeypatch):
    def fail(*args):
        raise RuntimeError("Deliberate runtime failure")

    monkeypatch.setattr(main.fiji, "execute", fail)
    monkeypatch.setattr(main, "OUTPUTS", tmp_path)
    with TestClient(main.app) as client:
        response = client.post("/api/jobs", json={"actions": [{"operation": "invert"}]})
        job_id = response.json()["id"]
        for _ in range(100):
            job = client.get(f"/api/jobs/{job_id}").json()
            if job["status"] == "failed":
                break
            time.sleep(0.01)
        assert job["status"] == "failed"
        assert job["events"][-1]["message"] == "Deliberate runtime failure"
