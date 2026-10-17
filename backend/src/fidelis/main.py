"""Local Fidelis API: constrained commands, observable jobs and speech previews."""

import asyncio
import copy
import csv
import io
import json
import tempfile
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse, Response
from PIL import Image, UnidentifiedImageError
from pydantic import BaseModel, Field
from starlette.middleware.trustedhost import TrustedHostMiddleware

from fidelis.adapters import fiji, speech
from fidelis.commands import Workflow, parse_command, recorded_macro
from fidelis.config import ROOT

app = FastAPI(title="Fidelis", version="0.2.0")
app.add_middleware(TrustedHostMiddleware, allowed_hosts=["127.0.0.1", "localhost", "testserver"])
jobs: dict[str, dict] = {}
mutex = threading.Lock()
executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="fidelis-fiji")
speech_slot = threading.Lock()
OUTPUTS = ROOT / "outputs"
INPUTS = ROOT / "data/inputs"
FIXTURE = ROOT / "fixtures/two-squares.png"


@app.middleware("http")
async def local_origin(request: Request, call_next):
    origin = request.headers.get("origin")
    allowed = {
        f"http://{host}:{port}"
        for host in ("localhost", "127.0.0.1")
        for port in (5173, 8000, 4173)
    }
    if origin and origin not in allowed:
        return JSONResponse({"detail": "Only the local Fidelis interface may access this API"}, 403)
    response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    return response


def identifier(value: str) -> str:
    try:
        return uuid.UUID(value).hex
    except ValueError:
        raise HTTPException(404, "Unknown identifier") from None


def source_path(value: str):
    if value == "demo":
        return FIXTURE
    path = INPUTS / f"{identifier(value)}.png"
    if not path.is_file():
        raise HTTPException(404, "Image not found")
    return path


async def bounded_body(request: Request, limit: int) -> bytes:
    data = bytearray()
    async for chunk in request.stream():
        data.extend(chunk)
        if len(data) > limit:
            raise HTTPException(413, "Upload exceeds size limit")
    if not data:
        raise HTTPException(422, "Empty upload")
    return bytes(data)


@app.get("/api/health")
def health():
    return {"status": "ok", "service": "fidelis"}


class CommandRequest(BaseModel):
    text: str = Field(min_length=1, max_length=500)


@app.post("/api/commands/preview")
def command_preview(command: CommandRequest):
    try:
        workflow = parse_command(command.text)
    except ValueError as error:
        raise HTTPException(422, str(error)) from error
    return {**workflow.model_dump(), "macro": recorded_macro(workflow)}


@app.post("/api/images", status_code=201)
async def upload_image(request: Request):
    data = await bounded_body(request, 10 * 1024 * 1024)
    try:
        with Image.open(io.BytesIO(data)) as image:
            if image.format != "PNG" or image.mode != "L" or getattr(image, "n_frames", 1) != 1:
                raise ValueError("This milestone accepts single-frame 8-bit grayscale PNG images")
            if image.width * image.height > 4_000_000:
                raise ValueError("Image exceeds 4 million pixels")
            image.load()
            name = uuid.uuid4().hex
            INPUTS.mkdir(parents=True, exist_ok=True)
            image.save(INPUTS / f"{name}.png")
    except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError) as error:
        raise HTTPException(422, str(error)) from error
    return {"image_id": name}


@app.get("/api/images/{image_id}")
def get_image(image_id: str):
    return FileResponse(source_path(image_id), media_type="image/png")


@app.post("/api/speech/transcribe")
async def transcribe_audio(request: Request):
    data = await bounded_body(request, 10 * 1024 * 1024)
    if not speech_slot.acquire(blocking=False):
        raise HTTPException(429, "Speech worker is busy")
    try:
        with tempfile.TemporaryDirectory(prefix="fidelis-speech-") as temporary:
            path = Path(temporary) / "audio.bin"
            path.write_bytes(data)
            return await asyncio.to_thread(speech.transcribe, path)
    except Exception as error:
        raise HTTPException(503, str(error)) from error
    finally:
        speech_slot.release()


class RunRequest(Workflow):
    image_id: str = "demo"


def run_job(job_id: str, workflow: Workflow, source):
    directory = OUTPUTS / job_id

    def emit(kind, message, stage=None):
        with mutex:
            event = {
                "kind": kind,
                "message": message,
                "stage": stage,
                "time": datetime.now(timezone.utc).isoformat(),
            }
            jobs[job_id]["events"].append(event)
            if stage is not None:
                jobs[job_id]["latest_stage"] = stage

    with mutex:
        jobs[job_id]["status"] = "running"
    try:
        provenance = fiji.execute(workflow, source, directory, emit)
        with (directory / "measurements.csv").open() as stream:
            measurements = list(csv.DictReader(stream))
        with mutex:
            jobs[job_id].update(
                status="completed", provenance=provenance, measurements=measurements
            )
        emit("completed", "Workflow completed")
    except Exception as error:
        emit("failed", str(error))
        with mutex:
            jobs[job_id].update(status="failed", error=str(error))
    finally:
        with mutex:
            snapshot = copy.deepcopy(jobs[job_id])
        (directory / "manifest.json").write_text(json.dumps(snapshot, indent=2), encoding="utf-8")


@app.post("/api/jobs", status_code=202)
def create_job(request: RunRequest):
    source = source_path(request.image_id)
    workflow = Workflow(actions=request.actions)
    with mutex:
        if len(jobs) >= 100:
            raise HTTPException(429, "Demo session limit reached; restart the API")
        if any(job["status"] in {"queued", "running"} for job in jobs.values()):
            raise HTTPException(409, "Fiji is busy; wait for the current workflow")
        job_id = uuid.uuid4().hex
        directory = OUTPUTS / job_id
        directory.mkdir(parents=True)
        macro = recorded_macro(workflow)
        (directory / "recording.ijm").write_text(macro, encoding="utf-8")
        jobs[job_id] = {
            "id": job_id,
            "status": "queued",
            "actions": workflow.model_dump()["actions"],
            "image_id": request.image_id,
            "macro": macro,
            "events": [],
            "latest_stage": None,
            "measurements": [],
        }
        executor.submit(run_job, job_id, workflow, source)
    return {"id": job_id}


@app.get("/api/jobs/{job_id}")
def get_job(job_id: str):
    job_id = identifier(job_id)
    with mutex:
        if job_id not in jobs:
            raise HTTPException(404, "Job not in this session; saved outputs remain on disk")
        return copy.deepcopy(jobs[job_id])


@app.post("/api/jobs/{job_id}/replay", status_code=202)
def replay(job_id: str):
    original = get_job(job_id)
    return create_job(RunRequest(actions=original["actions"], image_id=original["image_id"]))


@app.get("/api/jobs/{job_id}/artifacts/{name}")
def artifact(job_id: str, name: str):
    job = get_job(job_id)
    allowed = {"recording.ijm", "measurements.csv", "fiji.log", "manifest.json"}
    allowed.update(f"stage-{i}.png" for i in range(len(job["actions"]) + 1))
    if name not in allowed:
        raise HTTPException(404, "Artifact not found")
    path = OUTPUTS / job["id"] / name
    if not path.is_file():
        raise HTTPException(404, "Artifact not ready")
    return FileResponse(path, media_type="image/png" if name.endswith(".png") else "text/plain")


@app.post("/api/macros/preview")
def macro_preview(workflow: Workflow):
    return Response(recorded_macro(workflow), media_type="text/plain")
