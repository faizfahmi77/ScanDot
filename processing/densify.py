"""Create an isolated dense RGB reconstruction from an existing sparse model."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time

from processing.benchmark import colmap_executable, ply_vertex_count


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("prepared", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--colmap", required=True)
    parser.add_argument("--stride", type=int, default=3)
    parser.add_argument("--max-size", type=int, default=640)
    parser.add_argument("--neighbors", type=int, default=10)
    parser.add_argument("--pairing", choices=("temporal", "auto"), default="temporal")
    args = parser.parse_args()
    if args.stride < 1:
        parser.error("stride must be positive")
    if args.max_size < 64 or not 2 <= args.neighbors <= 50:
        parser.error("max-size must be at least 64 and neighbors must be between 2 and 50")
    if args.pairing == "auto" and args.stride != 1:
        parser.error("auto pairing requires stride 1 so selected source images are available")
    source = args.prepared.resolve()
    previous = json.loads((source / "reconstruction/manifest.json").read_text())
    if previous["status"] != "complete":
        raise ValueError("Sparse reconstruction must be complete")
    preparation = json.loads((source / "manifest.json").read_text())
    if preparation.get("status") != "complete" or not preparation.get("frames"):
        raise ValueError("Frame preparation must be complete")
    for entry in preparation["frames"]:
        frame = source / entry["path"]
        if hashlib.sha256(frame.read_bytes()).hexdigest() != entry.get("sha256"):
            raise ValueError(f"Source frame changed: {frame.name}")
    model = source / "reconstruction" / previous["summary"]["selected_model"]
    output = args.output.resolve()
    frames = sorted((source / "frames").glob("*.jpg"))[::args.stride]
    if len(frames) < 3:
        raise ValueError("At least three source images are required")
    output.mkdir(parents=True, exist_ok=False)
    image_list = output / "images.txt"
    image_list.write_text("\n".join(p.name for p in frames) + "\n")
    binary, env = colmap_executable(args.colmap)
    manifest = {"status": "running", "source": str(source), "source_manifest_sha256": hashlib.sha256(
        (source / "reconstruction/manifest.json").read_bytes()).hexdigest(),
        "image_stride": args.stride, "selected_images": len(frames), "depth_input": False,
        "max_image_size": args.max_size, "neighbors": args.neighbors, "pairing": args.pairing,
        "preparation_manifest_sha256": hashlib.sha256((source / "manifest.json").read_bytes()).hexdigest(),
        "stages": [], "scale": "arbitrary", "metric_accuracy": "not evaluated"}
    start = time.monotonic()

    def save() -> None:
        (output / "manifest.json").write_text(json.dumps(manifest, indent=2))

    def run(command: str, *options: str) -> None:
        argv = [str(binary), command, *options]
        stage = {"name": command, "command": argv, "status": "running"}
        manifest["stages"].append(stage)
        save()
        began = time.monotonic()
        with (output / f"{command}.log").open("wb") as log:
            result = subprocess.run(argv, env=env, stdout=log, stderr=subprocess.STDOUT)
        stage.update(returncode=result.returncode, duration_seconds=time.monotonic() - began,
                     status="completed" if result.returncode == 0 else "failed")
        save()
        result.check_returncode()

    try:
        run("image_undistorter", "--image_path", str(source / "frames"), "--input_path", str(model),
            "--output_path", str(output / "dense"), "--output_type", "COLMAP",
            "--image_list_path", str(image_list), "--max_image_size", str(args.max_size),
            "--num_patch_match_src_images", str(args.neighbors), "--num_threads", "4")
        # Auto-selection can reference poses excluded by image_list_path. Restrict
        # sources to the actually undistorted neighbours for this video sequence.
        names = [p.name for p in frames if (output / "dense/images" / p.name).is_file()]
        if len(names) < 3:
            raise ValueError("At least three selected images must exist in the sparse model")
        manifest["undistorted_images"] = len(names)
        save()
        if args.pairing == "temporal":
            config = []
            for index, name in enumerate(names):
                neighbours = sorted((i for i in range(len(names)) if i != index),
                                    key=lambda i: abs(i - index))[:args.neighbors]
                config.extend((name, ", ".join(names[i] for i in neighbours)))
            (output / "dense/stereo/patch-match.cfg").write_text("\n".join(config) + "\n")
        run("patch_match_stereo", "--workspace_path", str(output / "dense"),
            "--workspace_format", "COLMAP", "--PatchMatchStereo.geom_consistency", "true")
        run("stereo_fusion", "--workspace_path", str(output / "dense"), "--workspace_format", "COLMAP",
            "--input_type", "geometric", "--output_path", str(output / "dense/fused.ply"),
            "--StereoFusion.num_threads", "4")
        manifest.update(status="completed", point_count=ply_vertex_count(output / "dense/fused.ply"))
    except BaseException as error:
        manifest.update(status="failed", error=str(error))
        raise
    finally:
        manifest["duration_seconds"] = time.monotonic() - start
        save()
    print(json.dumps({"output": str(output), "points": manifest["point_count"]}))


if __name__ == "__main__":
    main()
