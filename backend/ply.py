"""Bounded preview reader for COLMAP ASCII and binary PLY files."""
import math
import struct
from pathlib import Path

TYPES = {"char": "b", "uchar": "B", "short": "h", "ushort": "H", "int": "i", "uint": "I", "float": "f", "double": "d",
         "int8": "b", "uint8": "B", "int16": "h", "uint16": "H", "int32": "i", "uint32": "I", "float32": "f", "float64": "d"}


def read_cloud(path: Path, limit: int = 200000) -> dict:
    if not 1 <= limit <= 200000:
        raise ValueError("Preview limit must be between 1 and 200000")
    with path.open("rb") as stream:
        if stream.readline(64) != b"ply\n":
            stream.seek(0)
            if stream.readline(64).strip() != b"ply":
                raise ValueError("Invalid PLY signature")
        count, fmt, props, element = None, None, [], None
        used = 4
        while True:
            raw = stream.readline(65537)
            used += len(raw)
            if not raw or used > 65536:
                raise ValueError("Invalid PLY header")
            fields = raw.decode("ascii").strip().split()
            if fields[:1] == ["format"]:
                fmt = fields[1]
            elif fields[:1] == ["element"]:
                element = fields[1]
                if element == "vertex":
                    count = int(fields[2])
                elif count is None:
                    raise ValueError("Vertices must be the first PLY element")
            elif fields[:1] == ["property"] and element == "vertex":
                if len(fields) != 3 or fields[1] not in TYPES:
                    raise ValueError("Unsupported PLY vertex property")
                props.append((fields[2], TYPES[fields[1]]))
            elif fields == ["end_header"]:
                break
        if count is None or count < 1 or count > 100_000_000 or len(props) > 64:
            raise ValueError("Invalid PLY vertex count or properties")
        names = [name for name, _ in props]
        if not all(name in names for name in ("x", "y", "z")):
            raise ValueError("PLY lacks coordinates")
        if fmt not in ("ascii", "binary_little_endian", "binary_big_endian"):
            raise ValueError("Unsupported PLY format")
        decoder = struct.Struct(("<" if fmt == "binary_little_endian" else ">") + "".join(code for _, code in props))
        sample_count = min(count, limit)
        sample_index = 0
        next_index = 0
        points = []
        for index in range(count):
            if fmt == "ascii":
                raw = stream.readline(16385)
                if not raw or len(raw) > 16384:
                    raise ValueError("Truncated or oversized PLY vertex")
                values = raw.split()
                if len(values) != len(props):
                    raise ValueError("Invalid PLY vertex")
                if index != next_index:
                    continue
                values = [float(v) for v in values]
            else:
                raw = stream.read(decoder.size)
                if len(raw) != decoder.size:
                    raise ValueError("Truncated PLY vertex")
                if index != next_index:
                    continue
                values = decoder.unpack(raw)
            sample_index += 1
            next_index = sample_index * count // sample_count
            vertex = dict(zip(names, values))
            xyz = [float(vertex[name]) for name in ("x", "y", "z")]
            rgb = [float(vertex.get(name, 200)) for name in ("red", "green", "blue")]
            if all(math.isfinite(v) for v in xyz + rgb):
                points.append(xyz + [max(0, min(255, int(v))) for v in rgb])
        if not points:
            raise ValueError("PLY has no usable finite preview vertices")
        return {"points": points, "total_points": count, "units": "arbitrary", "sampled": len(points) < count}
