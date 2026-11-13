"""Prepare video frames and run COLMAP without third-party Python packages.

Usage: python -m processing.benchmark --help
"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
from typing import Any

PIPELINE_VERSION = "0.1.0"


class PipelineError(RuntimeError):
    """An actionable pipeline failure; details are retained in the run."""


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def positive_float(value: str) -> float:
    try:
        result = float(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("must be a positive finite number") from exc
    if not math.isfinite(result) or result <= 0:
        raise argparse.ArgumentTypeError("must be a positive finite number")
    return result


def positive_int(value: str) -> int:
    try:
        result = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("must be a positive integer") from exc
    if result <= 0:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return result


def executable(value: str) -> Path:
    resolved = shutil.which(value)
    path = Path(resolved or value).expanduser().resolve()
    if not path.is_file():
        raise PipelineError(f"Executable not found: {value}")
    return path


def colmap_executable(value: str) -> tuple[Path, dict[str, str]]:
    path = executable(value)
    wrapper_dir = path.parent
    if path.suffix.lower() in {".bat", ".cmd"}:
        # Official Windows bundles use a wrapper to set PATH. Invoke their binary
        # directly so user paths are never interpolated into cmd.exe syntax.
        candidates = [path.parent / "bin" / "colmap.exe", path.parent / "colmap.exe"]
        path = next((candidate for candidate in candidates if candidate.is_file()), None)
        if path is None:
            raise PipelineError("COLMAP wrapper has no sibling bin/colmap.exe or colmap.exe; pass a direct executable")
    env = os.environ.copy()
    env["PATH"] = os.pathsep.join([str(path.parent), str(wrapper_dir), env.get("PATH", "")])
    plugins = path.parent.parent / "plugins" if path.parent.name.lower() == "bin" else wrapper_dir / "plugins"
    if plugins.is_dir():
        env["QT_PLUGIN_PATH"] = str(plugins)
    return path, env


def write_json(path: Path, value: Any) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    temporary.replace(path)


class Run:
    def __init__(self, root: Path, manifest: dict[str, Any], manifest_name: str = "manifest.json"):
        self.root = root
        self.manifest = manifest
        self.manifest_path = root / manifest_name
        self.started = time.monotonic()
        (root / "logs").mkdir(exist_ok=True)
        self.save()

    def save(self) -> None:
        write_json(self.manifest_path, self.manifest)

    def command(self, name: str, args: list[str], env: dict[str, str] | None = None) -> str:
        prefix = f"{len(self.manifest['stages']) + 1:02d}_{name}"
        stdout_path = self.root / "logs" / f"{prefix}.stdout.log"
        stderr_path = self.root / "logs" / f"{prefix}.stderr.log"
        stage: dict[str, Any] = {
            "name": name, "command": args, "started_at": utc_now(), "status": "running",
            "stdout": str(stdout_path.relative_to(self.root)),
            "stderr": str(stderr_path.relative_to(self.root)),
        }
        self.manifest["stages"].append(stage)
        self.save()
        start = time.monotonic()
        try:
            with stdout_path.open("w", encoding="utf-8") as out, stderr_path.open("w", encoding="utf-8") as err:
                result = subprocess.run(args, stdout=out, stderr=err, env=env, shell=False, check=False)
            stage["returncode"] = result.returncode
            if result.returncode != 0:
                raise PipelineError(f"{name} failed with exit code {result.returncode}; see {stderr_path}")
            stage["status"] = "complete"
        except BaseException as exc:
            stage.update(status="failed", error=f"{type(exc).__name__}: {exc}")
            raise
        finally:
            stage.update(duration_seconds=round(time.monotonic() - start, 3), finished_at=utc_now())
            self.save()
        return stdout_path.read_text(encoding="utf-8", errors="replace")

    def finish(self, error: BaseException | None = None) -> None:
        self.manifest.update(
            status="failed" if error else "complete", finished_at=utc_now(),
            duration_seconds=round(time.monotonic() - self.started, 3),
        )
        if error:
            self.manifest["error"] = f"{type(error).__name__}: {error}"
        self.save()


def new_manifest(kind: str) -> dict[str, Any]:
    return {"schema_version": 1, "pipeline_version": PIPELINE_VERSION,
            "kind": kind, "created_at": utc_now(), "status": "running", "stages": []}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def ply_vertex_count(path: Path) -> int:
    """Validate a bounded ASCII PLY header; the payload may be ASCII or binary.

    This checks the declared vertex count and presence of payload, not every
    vertex record or the geometric quality of the cloud.
    """
    limit = 64 * 1024
    used = 0
    vertex_count = None
    format_seen = False
    with path.open("rb") as stream:
        while used < limit:
            raw = stream.readline(limit - used + 1)
            used += len(raw)
            if not raw or used > limit or not raw.endswith(b"\n"):
                raise PipelineError("PLY header is missing, truncated, or exceeds 64 KiB")
            try:
                line = raw.decode("ascii").strip()
            except UnicodeDecodeError as exc:
                raise PipelineError("PLY header must be ASCII") from exc
            if used == len(raw):
                if line != "ply":
                    raise PipelineError("PLY header must start with ply")
                continue
            fields = line.split()
            if fields and fields[0] == "format":
                if format_seen or len(fields) != 3 or fields[1] not in {
                    "ascii", "binary_little_endian", "binary_big_endian"
                } or fields[2] != "1.0":
                    raise PipelineError("PLY header has an invalid format declaration")
                format_seen = True
            elif fields[:2] == ["element", "vertex"]:
                if vertex_count is not None or len(fields) != 3 or not fields[2].isdigit():
                    raise PipelineError("PLY header has an invalid vertex declaration")
                vertex_count = int(fields[2])
            elif line == "end_header":
                if not format_seen or vertex_count is None:
                    raise PipelineError("PLY header is missing format or vertex declaration")
                if vertex_count <= 0:
                    raise PipelineError("Dense point cloud contains zero vertices")
                if not stream.read(1):
                    raise PipelineError("PLY declares vertices but has no payload")
                return vertex_count
    raise PipelineError("PLY header is missing end_header or exceeds 64 KiB")


def prepare(args: argparse.Namespace) -> Path:
    source = Path(args.video).expanduser().resolve()
    if not source.is_file():
        raise PipelineError(f"Video is not a file: {source}")
    root = Path(args.output).expanduser().resolve()
    # Exclusive creation prevents mixing new frames with an earlier run.
    root.mkdir(parents=True, exist_ok=False)
    run = Run(root, new_manifest("prepare"))
    try:
        ffmpeg, ffprobe = executable(args.ffmpeg), executable(args.ffprobe)
        run.manifest["settings"] = {"fps": args.fps, "max_long_side": args.max_size, "threads": args.threads,
                                    "selection": "uniform sampling; adaptive quality filtering is future work"}
        before = source.stat()
        run.manifest["source"] = {"path": str(source), "size_bytes": before.st_size,
                                   "mtime_ns": before.st_mtime_ns, "sha256": sha256(source),
                                   "retention": "original retained at source path; not copied into run"}
        run.save()
        run.command("ffmpeg_version", [str(ffmpeg), "-version"])
        run.command("ffprobe_version", [str(ffprobe), "-version"])
        probe = json.loads(run.command("inspect_video", [str(ffprobe), "-v", "error", "-show_format",
                                                        "-show_streams", "-of", "json", str(source)]))
        run.manifest["probe"] = probe
        videos = [stream for stream in probe.get("streams", []) if stream.get("codec_type") == "video"]
        if not videos or not videos[0].get("width") or not videos[0].get("height"):
            raise PipelineError("Input has no usable video stream")
        frames = root / "frames"
        frames.mkdir()
        filters = (f"fps=fps={args.fps}:start_time=0,"
                   f"scale=w='min({args.max_size},iw)':h='min({args.max_size},ih)':"
                   "force_original_aspect_ratio=decrease:force_divisible_by=2")
        run.command("extract_frames", [str(ffmpeg), "-hide_banner", "-nostdin", "-n", "-threads", str(args.threads),
                                        "-filter_threads", str(args.threads), "-i", str(source),
                                        "-map", "0:v:0", "-an", "-sn", "-vf", filters,
                                        "-fps_mode", "passthrough", "-threads", str(args.threads), "-q:v", "2",
                                        str(frames / "frame_%06d.jpg")])
        paths = sorted(frames.glob("frame_*.jpg"))
        run.manifest["frame_count"] = len(paths)
        run.manifest["timestamp_provenance"] = {
            "type": "output_sampling_grid", "units": "seconds", "origin": "normalized output timeline starting at zero",
            "source_pts_exact": False,
            "limitation": "FFmpeg fps may duplicate/drop source frames. These are sampling targets, not exact decoded source frame PTS; input start_time and stream metadata are retained in probe.",
        }
        run.manifest["frames"] = [{"path": str(path.relative_to(root)), "sample_time_seconds": i / args.fps,
                                   "sha256": sha256(path)}
                                  for i, path in enumerate(paths)]
        if not paths:
            raise PipelineError("FFmpeg produced no frames")
        after = source.stat()
        if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
            raise PipelineError("Source changed during preparation; captured provenance is unreliable")
        if len(paths) < 3:
            run.manifest["warnings"] = ["Fewer than three frames; reconstruction is unlikely to succeed."]
    except BaseException as exc:
        run.finish(exc)
        raise
    run.finish()
    return root


def registered_images(report: str) -> int:
    # Older builds print bare fields; newer builds emit glog-prefixed fields.
    # Match complete fields so point counts and prose cannot become image counts.
    prefix = r"(?:[IWEF]\d{8}\s+\d{2}:\d{2}:\d{2}\.\d+\s+\d+\s+[^\]\r\n]+:\d+\]\s*)?"
    matches = re.findall(r"^\s*" + prefix + r"(Registered images|Images)\s*:\s*(\d+)\s*$",
                         report, re.MULTILINE | re.IGNORECASE)
    registered = [int(count) for label, count in matches if label.lower() == "registered images"]
    counts = registered or [int(count) for _, count in matches]
    if not counts:
        raise PipelineError("Cannot parse COLMAP model_analyzer image count; inspect analyzer logs")
    if len(set(counts)) != 1:
        raise PipelineError("Conflicting COLMAP model_analyzer image counts; inspect analyzer logs")
    return counts[0]


def reconstruct(args: argparse.Namespace) -> Path:
    prepared = Path(args.run).expanduser().resolve()
    manifest_path = prepared / "manifest.json"
    if not manifest_path.is_file():
        raise PipelineError(f"Prepared manifest is missing: {manifest_path}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("kind") != "prepare" or manifest.get("status") != "complete":
        raise PipelineError("Reconstruction requires a completed preparation run")
    entries = manifest.get("frames", [])
    if len(entries) < 3:
        raise PipelineError("At least three prepared frames are required")
    frames = prepared / "frames"
    expected = {f"frame_{i + 1:06d}.jpg" for i in range(len(entries))}
    actual = {path.name for path in frames.iterdir() if path.is_file()} if frames.is_dir() else set()
    if actual != expected or any(entry.get("path") != f"frames/{name}" and entry.get("path") != f"frames\\{name}"
                                 for entry, name in zip(entries, sorted(expected))):
        raise PipelineError("Prepared frame set does not match its manifest")
    for entry, name in zip(entries, sorted(expected)):
        checksum = entry.get("sha256")
        if not isinstance(checksum, str) or not re.fullmatch(r"[0-9a-f]{64}", checksum):
            raise PipelineError("Prepared frame checksum missing or invalid; prepare a new run")
        if sha256(frames / name) != checksum:
            raise PipelineError(f"Prepared frame checksum mismatch: {name}; prepare a new run")
    root = prepared / "reconstruction"
    root.mkdir(exist_ok=False)
    run = Run(root, new_manifest("reconstruct"))
    try:
        colmap, env = colmap_executable(args.colmap)
        env["OMP_NUM_THREADS"] = str(args.threads)
        env["OPENBLAS_NUM_THREADS"] = str(args.threads)
        run.manifest.update(preparation_manifest=str(manifest_path), preparation_manifest_sha256=sha256(manifest_path),
                            settings={"dense": args.dense, "single_camera": True, "dense_max_image_size": args.max_size,
                                      "threads": args.threads, "matcher": args.matcher})
        run.save()

        def command(name: str, *options: str) -> str:
            return run.command(name, [str(colmap), name, *options], env)

        def thread_options(name: str, candidates: list[tuple[str, ...]]) -> list[str]:
            help_text = run.command(name + "_help", [str(colmap), name, "-h"], env)
            stage = run.manifest["stages"][-1]
            help_text += (root / stage["stderr"]).read_text(encoding="utf-8", errors="replace")
            options = []
            for alternatives in candidates:
                supported = next((option for option in alternatives if option + " " in help_text), None)
                if supported:
                    options.extend([supported, str(args.threads)])
                else:
                    run.manifest.setdefault("warnings", []).append(
                        f"{name}: no supported thread option found for {alternatives}; library environment limits applied")
            return options

        run.command("colmap_version_help", [str(colmap), "-h"], env)
        database = root / "database.db"
        sparse = root / "sparse"
        sparse.mkdir()
        command("feature_extractor", "--database_path", str(database), "--image_path", str(frames),
                "--ImageReader.single_camera", "1", *thread_options("feature_extractor", [
                    ("--FeatureExtraction.num_threads", "--SiftExtraction.num_threads")]))
        matcher = f"{args.matcher}_matcher"
        matcher_thread_options = [("--FeatureMatching.num_threads", "--SiftMatching.num_threads")]
        if args.matcher == "sequential":
            matcher_thread_options.append(("--SequentialMatching.num_threads",))
        command(matcher, "--database_path", str(database), *thread_options(matcher, matcher_thread_options))
        command("mapper", "--database_path", str(database), "--image_path", str(frames), "--output_path", str(sparse),
                *thread_options("mapper", [("--Mapper.num_threads",)]))
        models = sorted(path for path in sparse.iterdir() if path.is_dir())
        if not models:
            raise PipelineError("COLMAP produced no sparse models; review capture overlap and mapper logs")
        reports = []
        for model in models:
            report = command("model_analyzer", "--path", str(model))
            # Some builds log analyzer summaries to stderr rather than stdout.
            stage = run.manifest["stages"][-1]
            report += "\n" + (root / stage["stderr"]).read_text(encoding="utf-8", errors="replace")
            reports.append({"path": str(model.relative_to(root)), "registered_images": registered_images(report)})
        selected = max(reports, key=lambda item: item["registered_images"])
        if len(reports) > 1:
            run.manifest.setdefault("warnings", []).append(
                f"COLMAP produced {len(reports)} sparse models. Inspect capture overlap and registration failures; "
                "only the model with most registered images is selected for dense processing. "
                "Multiple models may share images and do not establish a coherent whole-room reconstruction.")
        count = selected["registered_images"]
        if count <= 0 or count > len(entries):
            raise PipelineError(f"Invalid registered image count reported by COLMAP: {count}")
        run.manifest["summary"] = {
            "input_frames": len(entries), "models": reports, "selected_model": selected["path"],
            "registered_images": count, "aligned_frame_proportion": count / len(entries),
            "scale": "arbitrary; no measured reference applied", "metric_accuracy": "not evaluated",
            "coverage": "not evaluated; aligned image proportion does not establish surface coverage",
        }
        run.save()
        if args.dense:
            dense = root / "dense"
            command("image_undistorter", "--image_path", str(frames), "--input_path", str(root / selected["path"]),
                    "--output_path", str(dense), "--output_type", "COLMAP", "--max_image_size", str(args.max_size),
                    *thread_options("image_undistorter", [("--num_threads",)]))
            command("patch_match_stereo", "--workspace_path", str(dense), "--workspace_format", "COLMAP",
                    "--PatchMatchStereo.geom_consistency", "true")
            command("stereo_fusion", "--workspace_path", str(dense), "--workspace_format", "COLMAP",
                    "--input_type", "geometric", "--output_path", str(dense / "fused.ply"),
                    *thread_options("stereo_fusion", [("--StereoFusion.num_threads",)]))
            if not (dense / "fused.ply").is_file():
                raise PipelineError("Stereo fusion returned success but did not produce fused.ply")
            run.manifest["summary"]["dense_vertex_count"] = ply_vertex_count(dense / "fused.ply")
            run.manifest["summary"]["dense_point_cloud"] = "dense/fused.ply"
    except BaseException as exc:
        run.finish(exc)
        raise
    run.finish()
    return root


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description="Phase 0 video photogrammetry benchmark (stdlib Python).")
    commands = result.add_subparsers(dest="command", required=True)
    prep = commands.add_parser("prepare", help="inspect video and sample JPEG frames into a NEW run directory")
    prep.add_argument("video")
    prep.add_argument("--output", required=True)
    prep.add_argument("--fps", type=positive_float, default=2.0)
    prep.add_argument("--max-size", type=positive_int, default=1600, help="maximum frame long side (default: 1600)")
    prep.add_argument("--ffmpeg", default="ffmpeg")
    prep.add_argument("--ffprobe", default="ffprobe")
    prep.add_argument("--threads", type=positive_int, default=4, help="CPU thread limit (default: 4)")
    prep.set_defaults(handler=prepare)
    recon = commands.add_parser("reconstruct", help="run COLMAP in NEW reconstruction subdirectory of a prepared run")
    recon.add_argument("run")
    recon.add_argument("--colmap", default="colmap")
    recon.add_argument("--matcher", choices=("sequential", "exhaustive"), default="sequential",
                       help="image pairing strategy for a controlled comparison (default: sequential)")
    recon.add_argument("--dense", action="store_true", help="also run dense reconstruction; requires a compatible GPU build")
    recon.add_argument("--threads", type=positive_int, default=4, help="CPU thread target; CLI support checked at runtime (default: 4)")
    recon.add_argument("--max-size", type=positive_int, default=1600, help="maximum dense image long side (default: 1600)")
    recon.set_defaults(handler=reconstruct)
    return result


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        output = args.handler(args)
    except (PipelineError, OSError, ValueError) as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("Interrupted; failure state retained in manifest.", file=sys.stderr)
        return 130
    print(f"Complete: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
