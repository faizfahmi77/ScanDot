import json
import struct
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient
from backend.app import Service, create_app, public_summary, total_elapsed_seconds
from backend.ply import read_cloud


class BackendTests(unittest.TestCase):
    def test_candidate_models_do_not_imply_separate_sections(self):
        stored = {"model_count": 3, "models": [{"registered_images": n} for n in (6, 11, 50)],
                  "registered_images": 50, "input_frames": 50,
                  "warnings": ["COLMAP produced 3 sparse models. Inspect capture overlap and registration failures.",
                               "Unrelated diagnostic"]}
        service = self.client.app.state.service
        job = {"status": "completed", "summary": stored}
        result = service.public(job)["summary"]
        self.assertEqual(result["candidate_model_count"], 3)
        self.assertEqual(result["registered_images"], 50)
        self.assertEqual(result["input_frames"], 50)
        self.assertIn("Unrelated diagnostic", result["warnings"])
        text = " ".join(result["warnings"])
        self.assertIn("may share images", text)
        self.assertNotIn("separate sections", text)
        self.assertNotIn("could not be aligned", text)
        self.assertEqual(len(stored["warnings"]), 2)
        self.assertTrue(stored["warnings"][0].startswith("COLMAP"))

    def test_partial_registration_warning_requires_valid_counts(self):
        self.assertEqual(public_summary({"warnings": None})["warnings"], [])
        result = public_summary({"registered_images": 11, "input_frames": 50, "warnings": ["Keep me"]})
        self.assertIn("Keep me", result["warnings"])
        self.assertIn("11 of 50", " ".join(result["warnings"]))
        for registered, inputs in ((None, 50), (11, None), (-1, 50), (51, 50), (True, 50), (11, 0), ("11", 50)):
            with self.subTest(registered=registered, inputs=inputs):
                result = public_summary({"registered_images": registered, "input_frames": inputs,
                                         "warnings": ["COLMAP produced unexpected output", "Keep me"]})
                self.assertEqual(result["warnings"], ["COLMAP produced unexpected output", "Keep me"])

    def test_total_job_elapsed_time(self):
        job = {"status": "completed", "created_at": "2026-10-29T05:49:50.351+00:00",
               "updated_at": "2026-10-29T06:02:18.627+00:00"}
        self.assertAlmostEqual(total_elapsed_seconds(job), 748.276)
        for status in ("failed", "cancelled", "interrupted"):
            self.assertAlmostEqual(total_elapsed_seconds({**job, "status": status}), 748.276)
        for changes in ({"status": "queued"}, {"status": "reconstructing"}, {"created_at": None},
                        {"updated_at": "bad"}, {"updated_at": "2026-10-29T05:00:00+00:00"},
                        {"created_at": "2026-10-29T05:49:50.351"}):
            self.assertIsNone(total_elapsed_seconds({**job, **changes}))
        self.assertIsNone(total_elapsed_seconds({"status": "completed"}))
        self.assertEqual(total_elapsed_seconds({**job, "updated_at": job["created_at"]}), 0)
        self.assertIsNone(total_elapsed_seconds({**job, "_derived_from": "retained-source"}))
        self.assertIsNone(total_elapsed_seconds({**job, "_derived_from": None}))
        self.assertIsNone(total_elapsed_seconds({**job, "client_id": "roomscan-public-demo-v1"}))
        self.assertAlmostEqual(total_elapsed_seconds({**job, "client_id": "uploaded-scan"}), 748.276)
        response = self.upload().json()
        self.assertIsNone(response["total_elapsed_seconds"])

    def test_preview_limit_validation_and_dense_capacity(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "dense.ply"
            count = 200001
            header = ("ply\nformat binary_little_endian 1.0\nelement vertex " + str(count) +
                      "\nproperty float x\nproperty float y\nproperty float z\nend_header\n")
            path.write_bytes(header.encode() + struct.pack("<fff", 1, 2, 3) * count)
            result = read_cloud(path)
            self.assertEqual(len(result["points"]), 200000)
            self.assertEqual(result["total_points"], count)
            self.assertTrue(result["sampled"])
            for limit in (0, -1, 200001):
                with self.assertRaises(ValueError):
                    read_cloud(path, limit=limit)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)
        self.client = TestClient(create_app(self.directory, start_worker=False))
        self.client.__enter__()
        self.headers = {"Authorization": "Bearer " + self.client.app.state.service.token}

    def tearDown(self):
        self.client.__exit__(None, None, None)
        self.temp.cleanup()

    def upload(self, **changes):
        data = dict(project="Test", location="Room", label="Walkthrough", client_id="arbitrary-safe-client", dense="false")
        data.update(changes)
        return self.client.post("/api/jobs", data=data, files={"file": ("../../evil.mp4", b"video-content", "video/mp4")}, headers=self.headers)

    def test_auth_and_missing_job(self):
        self.assertEqual(self.client.get("/api/health").status_code, 200)
        for path in ("/api/jobs", "/api/demo/video", "/api/jobs/missing/cloud"):
            self.assertEqual(self.client.get(path).status_code, 401)
        result = self.client.get("/api/jobs/missing", headers=self.headers)
        self.assertEqual(result.status_code, 404)
        self.assertIsInstance(result.json()["detail"], str)

    def test_upload_idempotency_and_cancel(self):
        response = self.upload()
        self.assertEqual(response.status_code, 202, response.text)
        job = response.json()
        self.assertEqual(job["status"], "queued")
        self.assertNotIn("_size", job)
        self.assertEqual(self.upload().json()["id"], job["id"])
        self.assertEqual(self.upload(label="Changed").status_code, 409)
        changed_size = self.client.post("/api/jobs", data=dict(project="Test", location="Room", label="Walkthrough", client_id="arbitrary-safe-client"),
                                        files={"file": ("x", b"different")}, headers=self.headers)
        self.assertEqual(changed_size.status_code, 409)
        self.assertEqual(len(list((self.directory / "jobs").iterdir())), 1)
        self.assertTrue((self.directory / "jobs" / job["id"] / "input.video").is_file())
        self.assertEqual(self.client.get(f"/api/jobs/{job['id']}/cloud", headers=self.headers).status_code, 409)
        self.assertEqual(self.client.post(f"/api/jobs/{job['id']}/cancel", headers=self.headers).json()["status"], "cancelled")
        self.assertEqual(self.client.get("/api/jobs", headers=self.headers).json()["jobs"][0]["status"], "cancelled")

    def test_restart_marks_active_interrupted_and_preserves_token(self):
        job_id = self.upload().json()["id"]
        service = self.client.app.state.service
        service.update(service.get(job_id), status="reconstructing")
        restored = Service(self.directory)
        self.assertEqual(restored.token, service.token)
        self.assertEqual(restored.get(job_id)["status"], "interrupted")

    def test_retry_isolated_attempt_and_idempotency(self):
        job_id = self.upload().json()["id"]
        service = self.client.app.state.service
        job = service.get(job_id)
        first = service.attempt_path(job)
        first.mkdir()
        (first / "prepared").mkdir()
        service.update(job, status="failed", error="Failure", summary={"old": True})
        response = self.client.post(f"/api/jobs/{job_id}/retry", headers=self.headers)
        self.assertEqual(response.status_code, 202, response.text)
        self.assertEqual(response.json()["id"], job_id)
        self.assertEqual(response.json()["status"], "queued")
        self.assertIsNone(response.json()["error"])
        self.assertIsNone(response.json()["summary"])
        self.assertEqual(service.attempt_path(job).name, "attempt-1")
        self.assertFalse(service.attempt_path(job).exists())
        self.assertTrue((first / "prepared").is_dir())
        self.assertEqual(self.client.post(f"/api/jobs/{job_id}/retry", headers=self.headers).status_code, 202)
        self.assertEqual(job["_attempt"], 1)
        service.update(job, status="interrupted")
        self.client.post(f"/api/jobs/{job_id}/retry", headers=self.headers)
        self.assertEqual(service.attempt_path(job).name, "attempt-2")
        service.update(job, status="completed")
        self.assertEqual(self.client.post(f"/api/jobs/{job_id}/retry", headers=self.headers).status_code, 409)
        service.update(job, status="cancelled")
        (service.path(job) / "input.video").unlink()
        self.assertEqual(self.client.post(f"/api/jobs/{job_id}/retry", headers=self.headers).status_code, 409)

    def test_size_limit_and_empty_file(self):
        with patch("backend.app.MAX_UPLOAD", 4):
            self.assertEqual(self.upload().status_code, 413)
        response = self.client.post("/api/jobs", data=dict(project="P", location="L", label="V", client_id="empty"), files={"file": ("x", b"")}, headers=self.headers)
        self.assertEqual(response.status_code, 413)

    def test_streamed_limit_and_validation_error_string(self):
        body = b"--test\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.mp4\"\r\n\r\n" + b"x" * (2 * 1024 * 1024) + b"\r\n--test--\r\n"
        with patch("backend.app.MAX_UPLOAD", 4):
            response = self.client.post("/api/jobs", content=(body[i:i+65536] for i in range(0, len(body), 65536)),
                                        headers={**self.headers, "Content-Type": "multipart/form-data; boundary=test"})
        self.assertEqual(response.status_code, 413, response.text)
        response = self.client.post("/api/jobs", headers=self.headers)
        self.assertEqual(response.status_code, 422)
        self.assertIsInstance(response.json()["detail"], str)

    def test_demo_model_cloud(self):
        result = self.client.post("/api/demo", headers=self.headers)
        self.assertEqual(result.status_code, 200, result.text)
        job = result.json()
        self.assertEqual(job["status"], "completed")
        self.assertEqual(self.client.post("/api/demo", headers=self.headers).json()["id"], job["id"])
        cloud = self.client.get(f"/api/jobs/{job['id']}/cloud", headers=self.headers)
        self.assertEqual(cloud.status_code, 200, cloud.text[:100])
        self.assertGreater(cloud.json()["total_points"], 0)
        self.assertLessEqual(len(cloud.json()["points"]), 20000)
        self.assertEqual(self.client.get(f"/api/jobs/{job['id']}/model", headers=self.headers).status_code, 200)

    def test_worker_normal_and_failure(self):
        job_id = self.upload().json()["id"]
        service = self.client.app.state.service
        def fake_process(instance, job):
            instance.update(job, status="completed", stage="Completed")
            instance.stopping.set()
        with patch.object(Service, "process_job_once", fake_process):
            service.worker()
        self.assertEqual(service.get(job_id)["status"], "completed")
        service.stopping.clear()
        failed = self.upload(client_id="failed").json()
        def fail(instance, job):
            instance.stopping.set()
            # Worker should preserve cancellation, including a subprocess failure after kill.
            instance.update(job, status="cancelled")
            raise RuntimeError("cancelled")
        with patch.object(Service, "process_job_once", fail):
            service.worker()
        self.assertEqual(service.get(failed["id"])["status"], "cancelled")

    def test_worker_failure_status(self):
        job_id = self.upload().json()["id"]
        service = self.client.app.state.service
        with patch.object(Service, "process_job_once", side_effect=RuntimeError("secret internal details")):
            thread = threading.Thread(target=service.worker)
            thread.start()
            deadline = time.monotonic() + 3
            while service.get(job_id)["status"] != "failed" and time.monotonic() < deadline:
                time.sleep(0.02)
            service.stopping.set()
            thread.join(3)
        self.assertEqual(service.get(job_id)["status"], "failed")
        self.assertNotIn("secret", service.get(job_id)["error"])

    def test_pipeline_cli_arguments_and_completed_export(self):
        job = self.client.app.state.service.get(self.upload().json()["id"])
        service = self.client.app.state.service
        commands = []
        def fake_command(job, args, log_name, env=None):
            commands.append(args)
            prepared = service.path(job) / "attempt" / "prepared"
            if log_name == "prepare.log":
                prepared.mkdir()
                (prepared / "manifest.json").write_text(json.dumps({"frame_count": 301}))
            elif log_name == "reconstruct.log":
                recon = prepared / "reconstruction"
                recon.mkdir()
                (recon / "manifest.json").write_text(json.dumps({"summary": {"models": [{"registered_images": 8}], "selected_model": "sparse/0"}}))
            else:
                Path(args[args.index("--output_path") + 1]).write_text("ply\nformat ascii 1.0\nelement vertex 1\nproperty float x\nproperty float y\nproperty float z\nend_header\n1 2 3\n")
        with patch.object(service, "command", side_effect=fake_command), patch("processing.benchmark.colmap_executable", return_value=(Path("colmap"), {})):
            service.process_job_once(job)
        self.assertEqual(job["status"], "completed")
        self.assertIn("5", commands[0])
        self.assertIn("1280", commands[0])
        self.assertIn("sequential", commands[1])
        self.assertEqual(job["summary"]["point_count"], 1)
        rotation = [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
        job["_view_rotation"] = rotation
        response = self.client.get(f"/api/jobs/{job['id']}/cloud", headers=self.headers)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["view_rotation"], rotation)
        (service.directory / "default-demo.json").write_text(json.dumps({"job_id": job["id"]}))
        job["_source_video"] = str(service.path(job) / "input.video")
        self.assertEqual(self.client.post("/api/demo", headers=self.headers).json()["id"], job["id"])
        self.assertEqual(self.client.get("/api/demo/video", headers=self.headers).content, b"video-content")


class PlyTests(unittest.TestCase):
    def test_ascii_binary_and_bounds(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "cloud.ply"
            header = "ply\nformat {fmt} 1.0\nelement vertex 3\nproperty float x\nproperty float y\nproperty float z\nproperty uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n"
            path.write_text(header.format(fmt="ascii") + "1 2 3 255 0 0\nnan 0 0 0 0 0\n4 5 6 0 255 0\n")
            cloud = read_cloud(path)
            self.assertEqual(len(cloud["points"]), 2)
            self.assertTrue(cloud["sampled"])
            self.assertEqual(len(read_cloud(path, limit=1)["points"]), 1)
            path.write_bytes(header.format(fmt="binary_little_endian").encode() + struct.pack("<fffBBB", 1, 2, 3, 1, 2, 3) * 3)
            self.assertEqual(read_cloud(path)["points"][0], [1, 2, 3, 1, 2, 3])
            path.write_bytes(path.read_bytes()[:-1])
            with self.assertRaises(ValueError):
                read_cloud(path)
            path.write_text(header.format(fmt="ascii") + "nan 0 0 0 0 0\n" * 3)
            with self.assertRaisesRegex(ValueError, "no usable finite"):
                read_cloud(path)


if __name__ == "__main__":
    unittest.main()
