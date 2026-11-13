package com.roomscan.app

import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.abs

/** Bounded visual feature tracks in one ARCore session's world coordinates, in metres. */
internal class SparsePointFusion(
    val maxPoints: Int = 50_000,
    private val confidenceThreshold: Float = 0.5f,
    private val maxSamplesPerFrame: Int = 5_000,
) {
    private val indices = HashMap<Int, Int>()
    private val positions: FloatArray
    @Volatile var pointCount: Int = 0
        private set
    var lastTimestamp: Long = 0L
        private set
    val full: Boolean get() = pointCount >= maxPoints

    init {
        require(maxPoints in 1..200_000)
        require(confidenceThreshold.isFinite() && confidenceThreshold in 0f..1f)
        require(maxSamplesPerFrame in 1..20_000)
        positions = FloatArray(maxPoints * 3)
    }

    /** Copies xyz before ARCore releases the buffers. Reobserved IDs update their track. */
    @Synchronized
    fun integrate(timestamp: Long, points: FloatBuffer, ids: IntBuffer): Int {
        require(points.remaining() % 4 == 0 && points.remaining() / 4 == ids.remaining()) {
            "Point coordinates and IDs differ in length."
        }
        if (timestamp <= lastTimestamp) return 0
        lastTimestamp = timestamp
        val previousCount = pointCount
        val count = ids.remaining()
        val step = ((count.toLong() + maxSamplesPerFrame - 1) / maxSamplesPerFrame).toInt().coerceAtLeast(1)
        for (sample in 0 until count step step) {
            val offset = points.position() + sample * 4
            val confidence = points.get(offset + 3)
            if (!confidence.isFinite() || confidence !in confidenceThreshold..1f) continue
            val x = points.get(offset); val y = points.get(offset + 1); val z = points.get(offset + 2)
            if (!x.isFinite() || !y.isFinite() || !z.isFinite() || abs(x) > 1000f || abs(y) > 1000f || abs(z) > 1000f) continue
            val id = ids.get(ids.position() + sample)
            val existing = indices[id]
            if (existing == null && full) continue
            val index = existing ?: (pointCount++).also { indices[id] = it }
            positions[index * 3] = x; positions[index * 3 + 1] = y; positions[index * 3 + 2] = z
        }
        return pointCount - previousCount
    }

    @Synchronized
    fun snapshot() = DepthFusion.Snapshot(positions.copyOf(pointCount * 3), FloatArray(pointCount * 3) { axis ->
        when (axis % 3) { 0 -> 0.33f; 1 -> 0.86f; else -> 0.77f }
    })
}
