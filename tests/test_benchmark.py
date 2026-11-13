"""Behavior checks using synthetic files and a fake tool process boundary."""

import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from processing import benchmark


class BenchmarkTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="photogrammetry test ")
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name).resolve()
        self.video = self.base / "test source video.mp4"
        self.video.write_bytes(b"synthetic test input")
        self.tool = self.base / "fake tool.exe"
        self.tool.touch()
        self.output = self.base / "prepared run"

    def prepare_args(self):
        return benchmark.parser().parse_args([
            "prepare", str(self.video), "--output", str(self.output),
            "--ffmpeg", str(self.tool), "--ffprobe", str(self.tool),
        ])

    def fake_prepare(self, args, **kwargs):
        self.assertFalse(kwargs["shell"])
        if "-show_streams" in args:
            kwargs["stdout"].write(json.dumps({"streams": [{"codec_type": "video", "width": 1920, "height": 1080}]}))
        elif "-vf" in args:
            for i in range(1, 5):
                Path(args[-1].replace("%06d", f"{i:06d}")).write_bytes(b"synthetic jpeg")
        return subprocess.CompletedProcess(args, 0)

    def make_prepared(self):
        with patch.object(benchmark.subprocess, "run", side_effect=self.fake_prepare):
            benchmark.prepare(self.prepare_args())

    def test_positive_numbers_reject_invalid_values(self):
        for value in ["0", "-1", "nan", "inf", "bad"]:
            with self.subTest(value=value), self.assertRaises(argparse.ArgumentTypeError):
                benchmark.positive_float(value)
        for value in ["0", "-2", "1.5", "bad"]:
            with self.subTest(value=value), self.assertRaises(argparse.ArgumentTypeError):
                benchmark.positive_int(value)
        self.assertEqual(benchmark.positive_float("0.5"), 0.5)
        self.assertEqual(benchmark.positive_int("1600"), 1600)

    def test_prepare_records_sampling_provenance_and_preserves_source(self):
        self.make_prepared()
        manifest = json.loads((self.output / "manifest.json").read_text())
        self.assertEqual(manifest["status"], "complete")
        self.assertEqual(manifest["frame_count"], 4)
        self.assertEqual([frame["sample_time_seconds"] for frame in manifest["frames"]], [0, 0.5, 1, 1.5])
        self.assertFalse(manifest["timestamp_provenance"]["source_pts_exact"])
        self.assertEqual(manifest["source"]["sha256"], benchmark.sha256(self.video))
        for frame in manifest["frames"]:
            self.assertEqual(frame["sha256"], benchmark.sha256(self.output / frame["path"]))
        self.assertEqual(self.video.read_bytes(), b"synthetic test input")
        self.assertEqual(len(manifest["stages"]), 4)
        for stage in manifest["stages"]:
            self.assertGreaterEqual(stage["duration_seconds"], 0)
            self.assertTrue((self.output / stage["stderr"]).is_file())
        extraction = manifest["stages"][-1]["command"]
        self.assertIn(str(self.video), extraction)
        self.assertIn("-n", extraction)

    def test_existing_output_is_not_overwritten(self):
        self.output.mkdir()
        marker = self.output / "keep.txt"
        marker.write_text("keep")
        with self.assertRaises(FileExistsError):
            benchmark.prepare(self.prepare_args())
        self.assertEqual(marker.read_text(), "keep")

    def test_missing_video_creates_no_run(self):
        self.video.unlink()
        with self.assertRaises(benchmark.PipelineError):
            benchmark.prepare(self.prepare_args())
        self.assertFalse(self.output.exists())

    def test_failed_subprocess_is_logged_with_failure_state(self):
        def failed(args, **kwargs):
            kwargs["stderr"].write("tool error")
            return subprocess.CompletedProcess(args, 7)
        with patch.object(benchmark.subprocess, "run", side_effect=failed):
            with self.assertRaises(benchmark.PipelineError):
                benchmark.prepare(self.prepare_args())
        manifest = json.loads((self.output / "manifest.json").read_text())
        self.assertEqual(manifest["status"], "failed")
        self.assertEqual(manifest["stages"][0]["returncode"], 7)
        self.assertEqual(manifest["stages"][0]["status"], "failed")
        self.assertIn("exit code 7", manifest["error"])

    def test_probe_without_video_fails(self):
        def no_video(args, **kwargs):
            if "-show_streams" in args:
                kwargs["stdout"].write('{"streams": []}')
            return subprocess.CompletedProcess(args, 0)
        with patch.object(benchmark.subprocess, "run", side_effect=no_video):
            with self.assertRaisesRegex(benchmark.PipelineError, "no usable video"):
                benchmark.prepare(self.prepare_args())
        manifest = json.loads((self.output / "manifest.json").read_text())
        self.assertEqual(manifest["status"], "failed")

    def test_analyzer_count_parsing_and_unknown_output(self):
        self.assertEqual(benchmark.registered_images("Cameras: 1\nImages: 12\nPoints: 80"), 12)
        self.assertEqual(benchmark.registered_images("Registered images: 3"), 3)
        with self.assertRaises(benchmark.PipelineError):
            benchmark.registered_images("unrecognized output")

    def test_analyzer_actual_colmap_4_2_1_stderr(self):
        fixture = Path(__file__).parent / "fixtures" / "model_analyzer_4_2_1.stderr.log"
        report = fixture.read_text(encoding="utf-8")
        self.assertEqual(benchmark.registered_images(report), 10)
        self.assertEqual(benchmark.registered_images(report.replace("Images: 10", "Images: 12")), 10)
        for report in ["Points: 598", "Mean observations per image: 231", "some text Images: 10",
                       "I20261029 12:12:30.414078 36796 model.cc:442] Points: 598"]:
            with self.subTest(report=report), self.assertRaises(benchmark.PipelineError):
                benchmark.registered_images(report)
        with self.assertRaisesRegex(benchmark.PipelineError, "Conflicting"):
            benchmark.registered_images("Registered images: 10\nRegistered images: 11")

    def test_reconstruct_selects_largest_model_and_keeps_variants(self):
        self.make_prepared()
        args = benchmark.parser().parse_args(["reconstruct", str(self.output), "--colmap", str(self.tool), "--dense"])

        def fake_colmap(command, **kwargs):
            self.assertFalse(kwargs["shell"])
            action = command[1]
            if "-h" in command:
                kwargs["stderr"].write("--FeatureExtraction.num_threads arg\n--FeatureMatching.num_threads arg\n"
                                       "--SequentialMatching.num_threads arg\n--Mapper.num_threads arg\n"
                                       "--num_threads arg\n--StereoFusion.num_threads arg\n")
                return subprocess.CompletedProcess(command, 0)
            if action == "mapper":
                sparse = Path(command[command.index("--output_path") + 1])
                (sparse / "0").mkdir()
                (sparse / "1").mkdir()
            elif action == "model_analyzer":
                count = 2 if command[-1].endswith("0") else 4
                kwargs["stderr"].write(f"Images: {count}\n")
            elif action == "image_undistorter":
                self.assertEqual(Path(command[command.index("--input_path") + 1]).name, "1")
                Path(command[command.index("--output_path") + 1]).mkdir()
            elif action == "stereo_fusion":
                Path(command[command.index("--output_path") + 1]).write_bytes(
                    b"ply\nformat ascii 1.0\nelement vertex 1\nproperty float x\nproperty float y\nproperty float z\nend_header\n0 0 0\n")
            return subprocess.CompletedProcess(command, 0)

        with patch.object(benchmark.subprocess, "run", side_effect=fake_colmap):
            root = benchmark.reconstruct(args)
        manifest = json.loads((root / "manifest.json").read_text())
        self.assertEqual(manifest["status"], "complete")
        self.assertEqual(Path(manifest["summary"]["selected_model"]).name, "1")
        self.assertEqual(manifest["summary"]["registered_images"], 4)
        self.assertEqual(manifest["summary"]["aligned_frame_proportion"], 1)
        self.assertEqual(manifest["summary"]["metric_accuracy"], "not evaluated")
        self.assertTrue((root / "sparse" / "0").exists())
        self.assertTrue((root / "dense" / "fused.ply").exists())
        self.assertEqual(manifest["summary"]["dense_vertex_count"], 1)
        self.assertEqual(manifest["settings"]["matcher"], "sequential")
        self.assertTrue(any("2 sparse models" in warning and "whole-room" in warning
                            for warning in manifest["warnings"]))
        self.assertTrue(any(stage["name"] == "sequential_matcher" for stage in manifest["stages"]))
        self.assertFalse(any(stage["name"] == "exhaustive_matcher" for stage in manifest["stages"]))
        for stage in manifest["stages"]:
            if stage["name"] == "feature_extractor":
                self.assertIn("--FeatureExtraction.num_threads", stage["command"])
        with self.assertRaises(FileExistsError):
            benchmark.reconstruct(args)

    def test_changed_frame_set_rejected_before_starting_reconstruction(self):
        self.make_prepared()
        (self.output / "frames" / "unexpected.jpg").touch()
        args = benchmark.parser().parse_args(["reconstruct", str(self.output)])
        with self.assertRaisesRegex(benchmark.PipelineError, "does not match"):
            benchmark.reconstruct(args)
        self.assertFalse((self.output / "reconstruction").exists())

    def test_no_sparse_model_records_actionable_failure(self):
        self.make_prepared()
        args = benchmark.parser().parse_args(["reconstruct", str(self.output), "--colmap", str(self.tool)])
        with patch.object(benchmark.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)):
            with self.assertRaisesRegex(benchmark.PipelineError, "no sparse models"):
                benchmark.reconstruct(args)
        manifest = json.loads((self.output / "reconstruction" / "manifest.json").read_text())
        self.assertEqual(manifest["status"], "failed")
        self.assertIn("no sparse models", manifest["error"])

    def test_modified_frame_content_is_rejected_before_reconstruction(self):
        self.make_prepared()
        (self.output / "frames" / "frame_000001.jpg").write_bytes(b"changed jpeg content")
        args = benchmark.parser().parse_args(["reconstruct", str(self.output)])
        with self.assertRaisesRegex(benchmark.PipelineError, "checksum mismatch: frame_000001.jpg"):
            benchmark.reconstruct(args)
        self.assertFalse((self.output / "reconstruction").exists())

    def test_missing_frame_checksum_requires_new_preparation(self):
        self.make_prepared()
        manifest_path = self.output / "manifest.json"
        manifest = json.loads(manifest_path.read_text())
        del manifest["frames"][0]["sha256"]
        manifest_path.write_text(json.dumps(manifest))
        args = benchmark.parser().parse_args(["reconstruct", str(self.output)])
        with self.assertRaisesRegex(benchmark.PipelineError, "checksum missing or invalid"):
            benchmark.reconstruct(args)

    def test_ply_header_accepts_ascii_and_binary_payloads(self):
        cloud = self.base / "cloud.ply"
        for format_name, payload in [("ascii", b"0 0 0\n"), ("binary_little_endian", b"\x00" * 12),
                                     ("binary_big_endian", b"\x00" * 12)]:
            with self.subTest(format_name=format_name):
                cloud.write_bytes((f"ply\r\nformat {format_name} 1.0\r\ncomment test\r\nelement vertex 1\r\n"
                                   "property float x\r\nproperty float y\r\nproperty float z\r\nend_header\r\n").encode("ascii") + payload)
                self.assertEqual(benchmark.ply_vertex_count(cloud), 1)

    def test_ply_header_rejects_empty_missing_malformed_and_unbounded_headers(self):
        cloud = self.base / "cloud.ply"
        invalid = [b"", b"not a ply\n", b"ply\nformat ascii 1.0\nelement vertex 0\nend_header\n",
                   b"ply\nformat ascii 1.0\nend_header\n", b"ply\nelement vertex 1\nend_header\nx",
                   b"ply\nformat unknown 1.0\nelement vertex 1\nend_header\nx",
                   b"ply\nformat ascii 1.0\nelement vertex -1\nend_header\nx",
                   b"ply\nformat ascii 1.0\nelement vertex 1\nelement vertex 2\nend_header\nx",
                   b"ply\nformat ascii 1.0\nelement vertex 1\nend_header\n",
                   b"ply\nformat ascii 1.0\nelement vertex 1\n", b"ply\ncomment \xff\n",
                   b"ply\ncomment " + b"x" * 65536 + b"\nend_header\nx"]
        for index, content in enumerate(invalid):
            with self.subTest(index=index):
                cloud.write_bytes(content)
                with self.assertRaises(benchmark.PipelineError):
                    benchmark.ply_vertex_count(cloud)

    def test_empty_dense_cloud_records_failed_run(self):
        self.make_prepared()
        args = benchmark.parser().parse_args(["reconstruct", str(self.output), "--colmap", str(self.tool), "--dense"])

        def fake_colmap(command, **kwargs):
            action = command[1]
            if "-h" in command:
                return subprocess.CompletedProcess(command, 0)
            if action == "mapper":
                (Path(command[command.index("--output_path") + 1]) / "0").mkdir()
            elif action == "model_analyzer":
                kwargs["stdout"].write("Images: 4\n")
            elif action == "image_undistorter":
                Path(command[command.index("--output_path") + 1]).mkdir()
            elif action == "stereo_fusion":
                Path(command[command.index("--output_path") + 1]).write_bytes(
                    b"ply\nformat binary_little_endian 1.0\nelement vertex 0\nend_header\n")
            return subprocess.CompletedProcess(command, 0)

        with patch.object(benchmark.subprocess, "run", side_effect=fake_colmap):
            with self.assertRaisesRegex(benchmark.PipelineError, "zero vertices"):
                benchmark.reconstruct(args)
        manifest = json.loads((self.output / "reconstruction" / "manifest.json").read_text())
        self.assertEqual(manifest["status"], "failed")
        self.assertIn("zero vertices", manifest["error"])
        self.assertNotIn("dense_point_cloud", manifest["summary"])

    def test_incomplete_preparation_cannot_reconstruct(self):
        self.output.mkdir()
        (self.output / "manifest.json").write_text(json.dumps({"kind": "prepare", "status": "failed"}))
        args = benchmark.parser().parse_args(["reconstruct", str(self.output)])
        with self.assertRaisesRegex(benchmark.PipelineError, "completed preparation"):
            benchmark.reconstruct(args)
        self.assertFalse((self.output / "reconstruction").exists())

    def test_exhaustive_matcher_dispatch_and_thread_flags(self):
        self.make_prepared()
        original_manifest = (self.output / "manifest.json").read_bytes()
        original_hashes = {path.name: benchmark.sha256(path) for path in (self.output / "frames").iterdir()}
        args = benchmark.parser().parse_args(["reconstruct", str(self.output), "--colmap", str(self.tool),
                                              "--matcher", "exhaustive"])

        def fake_colmap(command, **kwargs):
            action = command[1]
            if "-h" in command:
                kwargs["stderr"].write("--FeatureExtraction.num_threads arg\n--FeatureMatching.num_threads arg\n"
                                       "--SequentialMatching.num_threads arg\n--Mapper.num_threads arg\n")
            elif action == "mapper":
                (Path(command[command.index("--output_path") + 1]) / "0").mkdir()
            elif action == "model_analyzer":
                kwargs["stdout"].write("Registered images: 4\n")
            return subprocess.CompletedProcess(command, 0)

        with patch.object(benchmark.subprocess, "run", side_effect=fake_colmap):
            root = benchmark.reconstruct(args)
        manifest = json.loads((root / "manifest.json").read_text())
        self.assertEqual(manifest["status"], "complete")
        self.assertEqual(manifest["settings"]["matcher"], "exhaustive")
        matcher_stages = [stage for stage in manifest["stages"] if stage["name"] == "exhaustive_matcher"]
        self.assertEqual(len(matcher_stages), 1)
        self.assertIn("--FeatureMatching.num_threads", matcher_stages[0]["command"])
        self.assertNotIn("--SequentialMatching.num_threads", matcher_stages[0]["command"])
        self.assertFalse(any(stage["name"].startswith("sequential_matcher") for stage in manifest["stages"]))
        self.assertNotIn("warnings", manifest)
        self.assertEqual(manifest["summary"]["registered_images"], 4)
        self.assertEqual((self.output / "manifest.json").read_bytes(), original_manifest)
        self.assertEqual({path.name: benchmark.sha256(path) for path in (self.output / "frames").iterdir()}, original_hashes)
        self.assertFalse((root / "dense").exists())
        with self.assertRaises(FileExistsError):
            benchmark.reconstruct(args)

    def test_windows_wrapper_resolves_binary_without_shell(self):
        wrapper = self.base / "COLMAP.bat"
        wrapper.touch()
        (self.base / "bin").mkdir()
        binary = self.base / "bin" / "colmap.exe"
        binary.touch()
        (self.base / "plugins").mkdir()
        resolved, env = benchmark.colmap_executable(str(wrapper))
        self.assertEqual(resolved, binary)
        self.assertTrue(env["PATH"].startswith(str(binary.parent)))
        self.assertEqual(env["QT_PLUGIN_PATH"], str(self.base / "plugins"))
        binary.unlink()
        with self.assertRaisesRegex(benchmark.PipelineError, "no sibling"):
            benchmark.colmap_executable(str(wrapper))


if __name__ == "__main__":
    unittest.main()
