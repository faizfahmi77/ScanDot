package com.roomscan.app

import org.json.JSONObject

/** Called on the IO dispatcher so JSON conversion and normalization never block gestures. */
internal fun parseCloud(data: JSONObject): CloudGeometry {
    val points = data.getJSONArray("points")
    val sampledCount = minOf(points.length(), CloudGeometry.MAX_POINTS)
    val vertices = FloatArray(sampledCount * CloudGeometry.STRIDE)
    var written = 0
    for (i in 0 until sampledCount) {
        // Spread the client cap over the whole response rather than keeping its first chunk.
        val index = (i.toLong() * points.length() / sampledCount).toInt()
        val point = points.optJSONArray(index) ?: continue
        if (point.length() < 6) continue
        val x = point.optDouble(0).toFloat(); val y = point.optDouble(1).toFloat(); val z = point.optDouble(2).toFloat()
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) continue
        vertices[written++] = x; vertices[written++] = y; vertices[written++] = z
        for (axis in 3..5) vertices[written++] = point.optInt(axis).coerceIn(0, 255) / 255f
    }
    val rotation = data.optJSONArray("view_rotation")?.takeIf { it.length() == 3 }?.let { rows ->
        FloatArray(9) { index -> rows.optJSONArray(index / 3)?.optDouble(index % 3)?.toFloat() ?: Float.NaN }
    }
    return CloudGeometry.normalized(vertices.copyOf(written), data.optInt("total_points", points.length()), rotation)
}
