import math
from pathlib import Path
import struct
import tempfile
import unittest

from processing.model_view import camera_rotation


class CameraViewTests(unittest.TestCase):
    def test_known_rotation_and_invalid_quaternion(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "images.bin"
            path.write_bytes(struct.pack("<Qidddd", 1, 8, math.sqrt(.5), 0, 0, math.sqrt(.5)))
            matrix = camera_rotation(path)
            self.assertAlmostEqual(matrix[0][1], -1)
            self.assertAlmostEqual(matrix[1][0], 1)
            self.assertAlmostEqual(matrix[2][2], 1)
            path.write_bytes(struct.pack("<Qidddd", 1, 8, 0, 0, 0, 0))
            with self.assertRaises(ValueError):
                camera_rotation(path)
