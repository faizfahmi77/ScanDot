"""Authenticated, persistent single-worker local reconstruction API."""
from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
from datetime import datetime, timezone
import hmac
import json
import os
import re
from pathlib import Path
import secrets
import shutil
import signal
import subprocess
import sys
import threading
from typing import Annotated
import uuid

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse

from backend.ply import read_cloud
from processing.model_view import camera_rotation

ROOT = Path(__file__).resolve().parents[1]
MAX_UPLOAD = 2 * 1024**3
RESERVE = 256 * 1024**2
ACTIVE = {"preparing", "reconstructing"}
TERMINAL = {"completed", "failed", "cancelled", "interrupted"}


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def atomic_json(path: Path, value: dict) -> None:
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, indent=2), encoding="utf-8")
    temporary.replace(path)


def tool(name: str) -> str:
    configured = os.environ.get("ROOMSCAN_" + name.upper())
    if configured:
        return configured
    found = shutil.which(name)
    if found:
        return found
    if name == "colmap":
        candidates = sorted((ROOT / "tools").glob("colmap*/bin/colmap.exe"))
        if candidates:
            return str(candidates[-1])
    return name


def summary(manifest: dict, point_count: int) -> dict:
    result = dict(manifest.get("summary", {}))
    result.update(model_count=len(result.get("models", [])), point_count=point_count,
                  warnings=manifest.get("warnings", []))
    return result


def public_summary(stored: dict) -> dict:
    result = dict(stored)
    candidates = result.get("model_count")
    result["candidate_model_count"] = candidates if type(candidates) is int and candidates >= 0 else None
    # Replace only the processor's known multi-model warning; retain all other diagnostics.
    warnings = [warning for warning in (result.get("warnings") or [])
                if not (isinstance(warning, str) and re.match(r"^COLMAP produced \d+ sparse models\.", warning))]
    if result["candidate_model_count"] is not None and candidates > 1:
        warnings.append(f"Alignment produced {candidates} candidate models, which may share images. "
                        "The preview uses the selected model; candidate count does not measure room sections or coverage.")
    registered, inputs = result.get("registered_images"), result.get("input_frames")
    if type(registered) is int and type(inputs) is int and 0 <= registered < inputs:
        warnings.append(f"The selected model aligned {registered} of {inputs} input images. "
                        "Some camera views could not be aligned; overlapping views may help.")
    result["warnings"] = warnings
    return result


def total_elapsed_seconds(job: dict) -> float | None:
    """Wall time since job creation, including queueing and any retries, for terminal jobs."""
    if (job.get("status") not in TERMINAL or "_derived_from" in job
            or job.get("client_id") == "roomscan-public-demo-v1"):
        return None
    try:
        start = datetime.fromisoformat(job["created_at"])
        end = datetime.fromisoformat(job["updated_at"])
        if start.utcoffset() is None or end.utcoffset() is None:
            return None
        elapsed = (end - start).total_seconds()
        return elapsed if elapsed >= 0 else None
    except (KeyError, TypeError, ValueError, OverflowError):
        return None


class Service:
    def __init__(self, directory: Path):
        self.directory = directory.resolve()
        self.directory.mkdir(parents=True, exist_ok=True)
        (self.directory / "jobs").mkdir(exist_ok=True)
        self.lock = threading.RLock()
        self.jobs: dict[str, dict] = {}
        self.process: subprocess.Popen | None = None
        self.process_job: str | None = None
        self.worker_job: str | None = None
        self.stopping = threading.Event()
        token_path = self.directory / "token.txt"
        self.token = os.environ.get("ROOMSCAN_TOKEN", "").strip()
        if not self.token:
            if not token_path.exists():
                try:
                    with token_path.open("x", encoding="utf-8") as stream:
                        stream.write(secrets.token_urlsafe(32))
                    token_path.chmod(0o600)
                except FileExistsError:
                    pass
            self.token = token_path.read_text(encoding="utf-8").strip()
        if not self.token:
            raise RuntimeError("RoomScan token must not be empty")
        for path in (self.directory / "jobs").glob("*/job.json"):
            job = read_json(path)
            if str(uuid.UUID(job["id"])) != path.parent.name:
                continue
            self.jobs[job["id"]] = job
            if job["status"] in ACTIVE:
                self.update(job, status="interrupted", stage="Server restarted", error="Processing was interrupted by server restart.")

    def path(self, job: dict) -> Path:
        return self.directory / "jobs" / job["id"]

    def attempt_path(self, job: dict) -> Path:
        attempt = job.get("_attempt", 0)
        return self.path(job) / ("attempt" if attempt == 0 else f"attempt-{attempt}")

    def update(self, job: dict, **values) -> None:
        with self.lock:
            job.update(values, updated_at=now())
            atomic_json(self.path(job) / "job.json", job)

    def get(self, job_id: str) -> dict:
        with self.lock:
            job = self.jobs.get(job_id)
            if job is None:
                raise HTTPException(404, "Job not found")
            return job

    def public(self, job: dict) -> dict:
        with self.lock:
            result = {k: v for k, v in job.items() if not k.startswith("_")}
            result["total_elapsed_seconds"] = total_elapsed_seconds(job)
            if result.get("summary"):
                result["summary"] = public_summary(result["summary"])
            if job["status"] in ACTIVE:
                prepared = self.attempt_path(job) / "prepared"
                manifest_path = prepared / ("reconstruction/manifest.json" if job["status"] == "reconstructing" else "manifest.json")
                try:
                    manifest = read_json(manifest_path)
                    stages = manifest.get("stages", [])
                    if stages:
                        result["stage"] = stages[-1]["name"].replace("_", " ")
                except (OSError, ValueError):
                    pass
            return result

    def stop_process(self) -> None:
        with self.lock:
            proc = self.process
            if proc and proc.poll() is None:
                if os.name == "nt":
                    subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
                else:
                    try:
                        os.killpg(proc.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass

    def command(self, job: dict, args: list[str], log_name: str, env: dict | None = None) -> None:
        with (self.path(job) / log_name).open("wb") as log:
            with self.lock:
                if job["status"] == "cancelled" or self.stopping.is_set():
                    raise RuntimeError("Cancelled")
                self.process_job = job["id"]
                self.process = subprocess.Popen(args, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                                                start_new_session=os.name != "nt", env=env or os.environ.copy())
                proc = self.process
            code = proc.wait()
            with self.lock:
                self.process = None
                self.process_job = None
            if code:
                raise RuntimeError("Processing command failed; inspect the job's local logs.")

    def process_job_once(self, job: dict) -> None:
        prepared = self.attempt_path(job) / "prepared"
        prepared.parent.mkdir(exist_ok=True)
        self.command(job, [sys.executable, "-m", "processing.benchmark", "prepare", str(self.path(job) / "input.video"),
                           "--output", str(prepared), "--fps", "5", "--max-size", "1280", "--threads", "4",
                           "--ffmpeg", tool("ffmpeg"), "--ffprobe", tool("ffprobe")], "prepare.log")
        with self.lock:
            if job["status"] == "cancelled":
                return
            self.update(job, status="reconstructing", stage="Reconstructing")
        count = read_json(prepared / "manifest.json")["frame_count"]
        args = [sys.executable, "-m", "processing.benchmark", "reconstruct", str(prepared), "--colmap", tool("colmap"),
                "--matcher", "exhaustive" if count <= 300 else "sequential", "--threads", "4", "--max-size", "1280"]
        if job["dense"]:
            args.append("--dense")
        self.command(job, args, "reconstruct.log")
        recon = prepared / "reconstruction"
        manifest = read_json(recon / "manifest.json")
        if job["dense"]:
            model = recon / "dense" / "fused.ply"
        else:
            model = recon / "selected-sparse.ply"
            from processing.benchmark import colmap_executable
            binary, env = colmap_executable(tool("colmap"))
            # Keep COLMAP's bundled runtime DLLs available to the export command.
            args = [str(binary), "model_converter", "--input_path", str(recon / manifest["summary"]["selected_model"]),
                    "--output_path", str(model), "--output_type", "PLY"]
            self.command(job, args, "export.log", env)
        cloud = read_cloud(model)
        rotation = camera_rotation(recon / manifest["summary"]["selected_model"] / "images.bin") if (
            recon / manifest["summary"]["selected_model"] / "images.bin").exists() else None
        with self.lock:
            if job["status"] != "cancelled" and not self.stopping.is_set():
                self.update(job, status="completed", stage="Completed", summary=summary(manifest, cloud["total_points"]),
                            _model=str(model), _view_rotation=rotation)

    def worker(self) -> None:
        while not self.stopping.wait(0.2):
            with self.lock:
                job = next((j for j in self.jobs.values() if j["status"] == "queued"), None)
                if job is None:
                    continue
                self.worker_job = job["id"]
                self.update(job, status="preparing", stage="Inspecting video and extracting frames")
            try:
                self.process_job_once(job)
            except Exception:
                with self.lock:
                    if job["status"] != "cancelled" and not self.stopping.is_set():
                        self.update(job, status="failed", stage="Failed", error="Video processing failed. Check the local job logs and verify video format, tool installation, and capture overlap.")
            finally:
                with self.lock:
                    self.worker_job = None


class BodyLimitExceeded(Exception):
    pass


def create_app(directory: Path | None = None, *, start_worker: bool = True) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI):
        service = Service(directory or Path(os.environ.get("ROOMSCAN_DATA_DIR", ROOT / "data" / "server")))
        app.state.service = service
        thread = threading.Thread(target=service.worker, daemon=True)
        if start_worker:
            thread.start()
        yield
        service.stopping.set()
        service.stop_process()
        if start_worker:
            await asyncio.to_thread(thread.join, 10)

    app = FastAPI(title="RoomScan", version="0.1.0", lifespan=lifespan)

    @app.exception_handler(RequestValidationError)
    async def validation_error(request, error):
        return JSONResponse({"detail": "Invalid request fields: " + "; ".join(".".join(str(v) for v in item["loc"]) + " " + item["msg"] for item in error.errors())}, status_code=422)

    @app.middleware("http")
    async def authorize(request, call_next):
        if request.url.path != "/api/health":
            supplied = request.headers.get("authorization", "")
            if not hmac.compare_digest(supplied.encode(), ("Bearer " + app.state.service.token).encode()):
                return JSONResponse({"detail": "Invalid or missing bearer token"}, status_code=401)
        if request.method == "POST" and request.url.path == "/api/jobs":
            available = shutil.disk_usage(app.state.service.directory).free - RESERVE
            limit = min(MAX_UPLOAD + 1024**2, max(0, available // 2))
            try:
                content_length = int(request.headers.get("content-length", "0"))
            except ValueError:
                return JSONResponse({"detail": "Invalid Content-Length"}, status_code=400)
            if content_length > limit:
                return JSONResponse({"detail": "Upload exceeds size or available disk limit"}, status_code=413)
            receive = request._receive
            consumed = 0
            async def bounded_receive():
                nonlocal consumed
                message = await receive()
                consumed += len(message.get("body", b""))
                if consumed > limit:
                    raise BodyLimitExceeded()
                return message
            request._receive = bounded_receive
        try:
            response = await call_next(request)
        except BodyLimitExceeded:
            return JSONResponse({"detail": "Upload exceeds size or available disk limit"}, status_code=413)
        if request.method == "POST" and request.url.path == "/api/jobs" and consumed > limit:
            return JSONResponse({"detail": "Upload exceeds size or available disk limit"}, status_code=413)
        return response

    @app.get("/api/health")
    def health():
        return {"status": "ok", "version": "0.1.0", "service": "roomscan"}

    @app.get("/api/jobs")
    def list_jobs():
        service = app.state.service
        with service.lock:
            return {"jobs": [service.public(j) for j in sorted(service.jobs.values(), key=lambda j: j["created_at"], reverse=True)]}

    @app.post("/api/jobs", status_code=202)
    def upload(file: Annotated[UploadFile, File()], project: Annotated[str, Form()], location: Annotated[str, Form()],
                     label: Annotated[str, Form()], client_id: Annotated[str, Form()], dense: Annotated[bool, Form()] = False):
        service = app.state.service
        if not client_id.strip() or any(len(v) > 1000 for v in (project, location, label, client_id)):
            raise HTTPException(422, "Metadata is empty or too long")
        metadata = dict(project=project, location=location, label=label, dense=dense, client_id=client_id)
        size = file.size
        if size is None or size <= 0 or size > MAX_UPLOAD:
            raise HTTPException(413, "Video is empty or exceeds the 2 GiB upload limit")
        with service.lock:
            existing = next((j for j in service.jobs.values() if j["client_id"] == client_id), None)
            if existing:
                if any(existing[k] != v for k, v in metadata.items()) or existing.get("_size") != size:
                    raise HTTPException(409, "client_id already exists with different metadata or file size")
                return service.public(existing)
            if shutil.disk_usage(service.directory).free < size + RESERVE:
                raise HTTPException(507, "Insufficient free disk space")
            job_id = str(uuid.uuid4())
            folder = service.directory / "jobs" / job_id
            folder.mkdir()
            # UploadFile is already spooled; copying under this lock reserves client_id atomically.
            try:
                with (folder / "input.video").open("xb") as output:
                    shutil.copyfileobj(file.file, output, 1024 * 1024)
                if (folder / "input.video").stat().st_size != size:
                    raise HTTPException(400, "Incomplete upload")
                job = dict(id=job_id, **metadata, status="queued", stage="Waiting for processor", created_at=now(),
                           updated_at=now(), error=None, summary=None, _size=size)
                service.update(job)
                service.jobs[job_id] = job
            except BaseException:
                shutil.rmtree(folder)
                raise
            return service.public(job)

    @app.get("/api/jobs/{job_id}")
    def get_job(job_id: str):
        service = app.state.service
        return service.public(service.get(job_id))

    @app.post("/api/jobs/{job_id}/cancel")
    def cancel(job_id: str):
        service = app.state.service
        with service.lock:
            job = service.get(job_id)
            if job["status"] not in TERMINAL:
                service.update(job, status="cancelled", stage="Cancelled")
                if service.process_job == job_id:
                    service.stop_process()
            return service.public(job)

    @app.post("/api/jobs/{job_id}/retry", status_code=202)
    def retry(job_id: str):
        service = app.state.service
        with service.lock:
            job = service.get(job_id)
            if job["status"] == "queued" or job["status"] in ACTIVE:
                return service.public(job)
            if job["status"] not in {"failed", "cancelled", "interrupted"}:
                raise HTTPException(409, "Only failed, cancelled, or interrupted jobs can be retried")
            if not (service.path(job) / "input.video").is_file():
                raise HTTPException(409, "Retained input video is unavailable")
            # Cancellation can return before the worker has reaped the old process.
            if service.worker_job == job_id or service.process_job == job_id:
                raise HTTPException(409, "Previous processing attempt is still stopping; retry shortly")
            service.update(job, status="queued", stage="Waiting for processor", error=None, summary=None,
                           _model=None, _attempt=job.get("_attempt", 0) + 1)
            return service.public(job)

    def model_path(job_id: str) -> Path:
        job = app.state.service.get(job_id)
        if job["status"] != "completed":
            raise HTTPException(409, "Model is available only for completed jobs")
        path = Path(job.get("_model", ""))
        if not path.is_file():
            raise HTTPException(404, "Model file is unavailable")
        return path

    @app.get("/api/jobs/{job_id}/model")
    def model(job_id: str):
        return FileResponse(model_path(job_id), media_type="application/octet-stream", filename="roomscan.ply")

    @app.get("/api/jobs/{job_id}/cloud")
    def cloud(job_id: str):
        try:
            result = read_cloud(model_path(job_id))
            rotation = app.state.service.get(job_id).get("_view_rotation")
            if rotation is not None:
                result["view_rotation"] = rotation
            return result
        except (OSError, ValueError, UnicodeError):
            raise HTTPException(422, "Model point cloud could not be read") from None

    @app.post("/api/demo")
    def demo():
        service = app.state.service
        preferred = service.directory / "default-demo.json"
        if preferred.is_file():
            job = service.get(read_json(preferred)["job_id"])
            model_path(job["id"])
            return service.public(job)
        recon = ROOT / "data" / "tum-room-5fps-exhaustive" / "reconstruction"
        model = recon / "selected-sparse.ply"
        if not model.is_file():
            raise HTTPException(404, "Public demo reconstruction is not installed")
        with service.lock:
            existing = next((j for j in service.jobs.values() if j["client_id"] == "roomscan-public-demo-v1"), None)
            if existing:
                return service.public(existing)
            manifest = read_json(recon / "manifest.json")
            job = dict(id=str(uuid.uuid4()), client_id="roomscan-public-demo-v1", project="Public sample", location="TUM Freiburg room",
                       label="Public RGB reconstruction", dense=False, status="completed", stage="Completed", created_at=now(), updated_at=now(),
                       error=None, summary=summary(manifest, read_cloud(model)["total_points"]), _model=str(model))
            service.path(job).mkdir()
            service.update(job)
            service.jobs[job["id"]] = job
            return service.public(job)

    @app.get("/api/demo/video")
    def demo_video():
        service = app.state.service
        preferred = service.directory / "default-demo.json"
        if preferred.is_file():
            job = service.get(read_json(preferred)["job_id"])
            source = Path(job.get("_source_video", ""))
            if source.is_file():
                return FileResponse(source, media_type="video/mp4", filename="roomscan-public-sample.mp4")
        path = ROOT / "data" / "public" / "tum-room-mobile.mp4"
        if not path.is_file():
            path = ROOT / "data" / "public" / "tum-freiburg1-room-rgb.avi"
        if not path.is_file():
            raise HTTPException(404, "Public demo video is not installed")
        return FileResponse(path, media_type="video/mp4" if path.suffix == ".mp4" else "video/x-msvideo", filename=path.name)

    return app


app = create_app()
