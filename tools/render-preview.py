"""Render a diagnostic COLMAP PLY preview; optional NumPy/Matplotlib required."""

from __future__ import annotations

import argparse
from pathlib import Path
import struct

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np


def read_sample(path: Path, limit: int = 20000) -> tuple[np.ndarray, np.ndarray, int]:
    types = {"float": "f", "float32": "f", "double": "d", "float64": "d",
             "uchar": "B", "uint8": "B", "int": "i", "uint": "I"}
    with path.open("rb") as stream:
        if stream.readline().strip() != b"ply":
            raise ValueError("Expected PLY file")
        fields: list[tuple[str, str]] = []
        count, file_format, in_vertices = 0, "", False
        for _ in range(256):
            line = stream.readline(4096).decode("ascii").strip()
            words = line.split()
            if line == "end_header":
                break
            if words[:1] == ["format"]:
                file_format = words[1]
            elif words[:1] == ["element"]:
                in_vertices = words[1] == "vertex"
                if in_vertices:
                    count = int(words[2])
            elif words[:1] == ["property"] and in_vertices:
                if len(words) != 3 or words[1] not in types:
                    raise ValueError("Unsupported vertex property")
                fields.append((words[2], types[words[1]]))
        else:
            raise ValueError("Invalid or oversized PLY header")
        names = [name for name, _ in fields]
        required = [names.index(name) for name in ("x", "y", "z", "red", "green", "blue")]
        if count <= 0:
            raise ValueError("No vertices")
        stride = max(1, (count + limit - 1) // limit)
        records = []
        if file_format in {"binary_little_endian", "binary_big_endian"}:
            layout = struct.Struct(("<" if file_format == "binary_little_endian" else ">") + "".join(t for _, t in fields))
            vertex_start = stream.tell()
            for index in range(0, count, stride):
                stream.seek(vertex_start + index * layout.size)
                row = layout.unpack(stream.read(layout.size))
                records.append([row[i] for i in required])
        elif file_format == "ascii":
            for index in range(count):
                row = stream.readline().split()
                if index % stride == 0:
                    records.append([float(row[i]) for i in required])
        else:
            raise ValueError("Unsupported PLY encoding")
    values = np.asarray(records, dtype=float)
    values = values[np.isfinite(values).all(axis=1)]
    if not len(values):
        raise ValueError("No finite points")
    return values[:, :3], np.clip(values[:, 3:] / 255.0, 0, 1), count


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("cloud", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--title", default="Photogrammetry reconstruction — diagnostic preview")
    parser.add_argument("--attribution", default="")
    args = parser.parse_args()
    points, colors, count = read_sample(args.cloud)
    center = (points.max(axis=0) + points.min(axis=0)) / 2
    radius = max(float(np.ptp(points, axis=0).max()) / 2, 1e-6)
    figure = plt.figure(figsize=(13, 6), facecolor="#f8fafc")
    for index, angle in enumerate((25, 115), 1):
        axis = figure.add_subplot(1, 2, index, projection="3d")
        axis.scatter(*points.T, c=colors, s=1, depthshade=False)
        for setter, coordinate in zip((axis.set_xlim, axis.set_ylim, axis.set_zlim), center):
            setter(coordinate - radius, coordinate + radius)
        axis.set_box_aspect((1, 1, 1))
        axis.view_init(elev=20, azim=angle)
        axis.set_xlabel("X (arbitrary units)")
        axis.set_ylabel("Y (arbitrary units)")
        axis.set_zlabel("Z (arbitrary units)")
    figure.suptitle(args.title, fontsize=15)
    figure.text(0.5, 0.035,
                f"{count:,} vertices; {len(points):,} displayed. Unscaled; coverage and dimensional accuracy not validated.\n"
                + args.attribution,
                ha="center", fontsize=9)
    figure.subplots_adjust(bottom=0.15, top=0.9, wspace=0.1)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    figure.savefig(args.output, dpi=140)
    plt.close(figure)


if __name__ == "__main__":
    main()
