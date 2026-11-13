package com.roomscan.app

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal object PoseSampling {
    const val INTERVAL_MS = 100L
    const val MAX_DURATION_MS = 30_000L
    const val MAX_FRAMES = 300
    const val LANDMARK_COUNT = 33

    fun timestamps(durationMs: Long): List<Long> {
        require(durationMs > 0L) { "The video has no readable duration." }
        val end = minOf(durationMs, MAX_DURATION_MS)
        return List(((end + INTERVAL_MS - 1) / INTERVAL_MS).toInt()) { it * INTERVAL_MS }
    }

    fun frameAt(frames: List<PoseFrame>, timestampMs: Long): Int {
        require(frames.isNotEmpty())
        var low = 0
        var high = frames.lastIndex
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (frames[middle].timestampMs <= timestampMs) low = middle else high = middle - 1
        }
        return low
    }
}

internal data class PoseLandmark(
    val x: Float, val y: Float, val z: Float,
    val visibility: Float?, val presence: Float?,
) {
    init {
        require(listOf(x, y, z).all { it.isFinite() && abs(it) <= 20f }) { "Invalid skeleton coordinates." }
        require(listOfNotNull(visibility, presence).all { it.isFinite() && it in 0f..1f }) { "Invalid landmark confidence." }
    }

    // Absent scores remain unknown. Known low-confidence joints are omitted from the view.
    val drawable: Boolean get() = (visibility == null || visibility >= .5f) && (presence == null || presence >= .5f)
    val confidenceKnown: Boolean get() = visibility != null && presence != null
}

internal enum class PoseMissing { NO_PERSON, FRAME_UNAVAILABLE, INVALID_RESULT }

internal data class PoseFrame(val timestampMs: Long, val landmarks: List<PoseLandmark>?, val missing: PoseMissing?) {
    init {
        require(timestampMs in 0 until PoseSampling.MAX_DURATION_MS)
        require((landmarks == null) == (missing != null)) { "A frame needs either 33 landmarks or a missing-pose reason." }
        require(landmarks == null || landmarks.size == PoseSampling.LANDMARK_COUNT)
    }
}

internal data class PoseClip(
    val sourceLabel: String,
    val sourceDurationMs: Long,
    val createdMs: Long,
    val analysisElapsedMs: Long,
    val inferenceElapsedMs: Long,
    val frames: List<PoseFrame>,
) {
    val analyzedDurationMs: Long get() = minOf(sourceDurationMs, PoseSampling.MAX_DURATION_MS)
    val detectedCount: Int get() = frames.count { it.landmarks != null }

    fun validate() {
        require(sourceLabel.isNotBlank() && sourceLabel.length <= 200)
        require(createdMs >= 0L && analysisElapsedMs in 0..604_800_000L && inferenceElapsedMs in 0..analysisElapsedMs)
        val expected = PoseSampling.timestamps(sourceDurationMs)
        require(frames.size == expected.size && frames.size <= PoseSampling.MAX_FRAMES) { "Invalid skeleton timeline." }
        require(frames.indices.all { frames[it].timestampMs == expected[it] }) { "Invalid skeleton timestamps." }
    }
}

internal data class PosePoint3(val x: Float, val y: Float, val z: Float)

internal object PoseProjection {
    fun centered(landmarks: List<PoseLandmark>): List<PosePoint3> {
        require(landmarks.size == PoseSampling.LANDMARK_COUNT)
        val left = landmarks[23]
        val right = landmarks[24]
        val cx = (left.x + right.x) / 2f
        val cy = (left.y + right.y) / 2f
        val cz = (left.z + right.z) / 2f
        return landmarks.map { PosePoint3(it.x - cx, it.y - cy, it.z - cz) }
    }

    fun rotate(point: PosePoint3, yaw: Float, pitch: Float): PosePoint3 {
        val x = cos(yaw) * point.x + sin(yaw) * point.z
        val z = -sin(yaw) * point.x + cos(yaw) * point.z
        return PosePoint3(x, cos(pitch) * point.y - sin(pitch) * z, sin(pitch) * point.y + cos(pitch) * z)
    }

    fun radius(clip: PoseClip): Float {
        var radius = .5f
        for (frame in clip.frames) frame.landmarks?.let { landmarks ->
            centered(landmarks).forEachIndexed { index, point ->
                if (landmarks[index].drawable) radius = maxOf(radius, sqrt(point.x * point.x + point.y * point.y + point.z * point.z))
            }
        }
        return radius
    }
}
