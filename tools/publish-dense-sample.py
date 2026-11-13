"""Register a completed local dense experiment while the RoomScan service is stopped."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import socket
import sys
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from backend.ply import read_cloud
from processing.model_view import camera_rotation


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source_job", type=Path)
    parser.add_argument("dense_run", type=Path)
    parser.add_argument("--label", required=True)
    parser.add_argument("--set-demo", action="store_true", help="Use this public result for the app's demo button")
    args = parser.parse_args()
    try:
        with socket.create_connection(("127.0.0.1", 8765), timeout=5):
            raise RuntimeError("Stop the idle RoomScan service before registering this local result")
    except ConnectionRefusedError:
        pass
    source = json.loads(args.source_job.read_text())
    run = args.dense_run.resolve()
    manifest = json.loads((run / "manifest.json").read_text())
    if source["status"] != "completed" or manifest["status"] != "completed":
        raise ValueError("Both source job and dense experiment must be completed")
    model = run / "dense/fused.ply"
    count = read_cloud(model, limit=1)["total_points"]
    prepared = Path(manifest["source"])
    sparse = json.loads((prepared / "reconstruction/manifest.json").read_text())
    rotation = camera_rotation(prepared / "reconstruction" / sparse["summary"]["selected_model"] / "images.bin")
    stamp = datetime.now(timezone.utc).isoformat()
    job_id = str(uuid.uuid4())
    result = dict(source)
    result.update(id=job_id, client_id="dense-experiment-" + job_id, label=args.label,
                  dense=True, status="completed", stage="Detailed reconstruction completed",
                  created_at=stamp, updated_at=stamp, error=None,
                  _model=str(model), _view_rotation=rotation, _derived_from=source["id"],
                  _source_video=str(args.source_job.resolve().parent / "input.video"),
                  _dense_manifest=str(run / "manifest.json"))
    result["summary"] = dict(source["summary"], point_count=count,
                             dense_source_images=manifest.get("undistorted_images", manifest["selected_images"]),
                             warnings=list(source["summary"].get("warnings") or []) +
                             ["Dense colour point cloud; surfaces and dimensions still require visual and measurement checks."])
    destination = args.source_job.resolve().parent.parent / job_id
    destination.mkdir(exist_ok=False)
    (destination / "job.json").write_text(json.dumps(result, indent=2))
    if args.set_demo:
        (destination.parent.parent / "default-demo.json").write_text(json.dumps({"job_id": job_id}))
    print(json.dumps({"id": job_id, "points": count, "job": str(destination / "job.json")}))


if __name__ == "__main__":
    main()
