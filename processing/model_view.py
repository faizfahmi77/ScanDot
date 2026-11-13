"""Read an initial viewing rotation from COLMAP's reconstructed RGB camera pose."""
import math
from pathlib import Path
import struct


def camera_rotation(images: Path) -> list[list[float]]:
    with images.open("rb") as stream:
        if struct.unpack("<Q", stream.read(8))[0] < 1:
            raise ValueError("Model has no cameras")
        _, w, x, y, z = struct.unpack("<idddd", stream.read(36))
    length = math.sqrt(w*w + x*x + y*y + z*z)
    if not math.isfinite(length) or length < 1e-12:
        raise ValueError("Invalid camera quaternion")
    w, x, y, z = (v / length for v in (w, x, y, z))
    return [[1-2*y*y-2*z*z, 2*x*y-2*w*z, 2*x*z+2*w*y],
            [2*x*y+2*w*z, 1-2*x*x-2*z*z, 2*y*z-2*w*x],
            [2*x*z-2*w*y, 2*y*z+2*w*x, 1-2*x*x-2*y*y]]
