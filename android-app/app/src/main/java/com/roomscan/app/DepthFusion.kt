package com.roomscan.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor

/** A bounded voxel cloud in ARCore's world coordinates (metres, right handed, Y up). */
internal class DepthFusion(
    val maxPoints: Int = 150_000,
    private val voxelSize: Float = 0.025f,
    private val confidenceThreshold: Int = 160,
    private val maxSamplesPerFrame: Int = 5_000,
) {
    data class Intrinsics(val fx: Float, val fy: Float, val cx: Float, val cy: Float)
    data class Snapshot(val points: FloatArray, val colors: FloatArray)
    data class FrameStats(
        val width: Int, val height: Int, val pixelsInspected: Int,
        val validPixels: Int, val sampledPixels: Int,
    ) {
        val inspectionLimited: Boolean get() = pixelsInspected.toLong() < width.toLong() * height
    }
    private data class Voxel(val x: Int, val y: Int, val z: Int)

    private val positions: FloatArray
    private val rgb: FloatArray
    private val observations: IntArray
    private val voxels = HashMap<Voxel, Int>()
    @Volatile var pointCount: Int = 0
        private set
    var lastTimestamp: Long = Long.MIN_VALUE
        private set
    val full: Boolean get() = pointCount >= maxPoints
    /** Last accepted timestamp; counts refer to inspected pixels, before projection/voxel fusion. */
    @Volatile var lastFrameStats: FrameStats? = null
        private set

    init {
        require(maxPoints in 1..200_000)
        require(voxelSize.isFinite() && voxelSize >= 0.001f)
        require(confidenceThreshold in 0..255)
        require(maxSamplesPerFrame in 1..20_000)
        positions = FloatArray(maxPoints * 3)
        rgb = FloatArray(maxPoints * 3)
        observations = IntArray(maxPoints)
    }

    /**
     * Inputs remain owned by the caller and can be released immediately after this call.
     * Intrinsics must already be scaled to the depth dimensions. Pose is column-major.
     * [colorAt] returns 0xRRGGBB; omitted colors use a neutral grey.
     */
    @Synchronized
    fun integrate(
        timestamp: Long,
        width: Int,
        height: Int,
        depth: ByteBuffer,
        depthRowStride: Int,
        depthPixelStride: Int,
        confidence: ByteBuffer?,
        confidenceRowStride: Int,
        confidencePixelStride: Int,
        intrinsics: Intrinsics,
        cameraToWorld: FloatArray,
        colorAt: ((Int, Int) -> Int)? = null,
    ): Int {
        require(width in 1..8192 && height in 1..8192)
        require(intrinsics.fx.isFinite() && intrinsics.fx > 0f && intrinsics.fy.isFinite() && intrinsics.fy > 0f)
        require(intrinsics.cx.isFinite() && intrinsics.cy.isFinite())
        require(cameraToWorld.size == 16 && cameraToWorld.all { it.isFinite() })
        require(abs(cameraToWorld[3]) < 0.0001f && abs(cameraToWorld[7]) < 0.0001f &&
            abs(cameraToWorld[11]) < 0.0001f && abs(cameraToWorld[15] - 1f) < 0.0001f)
        validatePlane(depth, width, height, depthRowStride, depthPixelStride, 2)
        if (confidence != null) validatePlane(confidence, width, height, confidenceRowStride, confidencePixelStride, 1)
        if (timestamp <= lastTimestamp || full) return 0
        lastTimestamp = timestamp
        val depths = depth.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val depthBase = depth.position()
        val confidenceBase = confidence?.position() ?: 0
        val totalPixels = width * height
        // Real depth images are fully inspected. Huge inputs still have bounded read work;
        // spread their inspection across the entire image, including both endpoints.
        val inspectedPixels = totalPixels.coerceAtMost(MAX_PIXELS_PER_FRAME)
        var validPixels = 0
        for (rank in 0 until inspectedPixels) {
            val pixel = inspectedPixel(rank, inspectedPixels, totalPixels)
            val v = pixel / width; val u = pixel % width
            val certainty = confidence?.let { it.get(confidenceBase + v * confidenceRowStride + u * confidencePixelStride).toInt() and 255 } ?: 255
            if (certainty < confidenceThreshold) continue
            val millimetres = depths.getShort(depthBase + v * depthRowStride + u * depthPixelStride).toInt() and 65535
            if (millimetres in 250..5_000) validPixels++
        }
        val sampleCount = validPixels.coerceAtMost(maxSamplesPerFrame)
        var validRank = 0
        var sampledPixels = 0
        var nextSampleRank = if (sampleCount == 1) validPixels / 2 else 0
        val previousCount = pointCount
        if (sampleCount > 0) for (rank in 0 until inspectedPixels) {
            val pixel = inspectedPixel(rank, inspectedPixels, totalPixels)
            val v = pixel / width; val u = pixel % width
            val certainty = confidence?.let { it.get(confidenceBase + v * confidenceRowStride + u * confidencePixelStride).toInt() and 255 } ?: 255
            if (certainty < confidenceThreshold) continue
            val millimetres = depths.getShort(depthBase + v * depthRowStride + u * depthPixelStride).toInt() and 65535
            // Select valid ranks rather than positions on a grid: sparse off-grid depth
            // survives, while dense frames retain a deterministic spread within budget.
            if (millimetres !in 250..5_000 || validRank++ != nextSampleRank) continue
            sampledPixels++
            if (sampledPixels < sampleCount) {
                nextSampleRank = (sampledPixels.toLong() * (validPixels - 1) / (sampleCount - 1)).toInt()
            }
            val distance = millimetres / 1000f
            val x = (u - intrinsics.cx) * distance / intrinsics.fx
            val y = (intrinsics.cy - v) * distance / intrinsics.fy
            val z = -distance
            val worldX = cameraToWorld[0] * x + cameraToWorld[4] * y + cameraToWorld[8] * z + cameraToWorld[12]
            val worldY = cameraToWorld[1] * x + cameraToWorld[5] * y + cameraToWorld[9] * z + cameraToWorld[13]
            val worldZ = cameraToWorld[2] * x + cameraToWorld[6] * y + cameraToWorld[10] * z + cameraToWorld[14]
            if (!worldX.isFinite() || !worldY.isFinite() || !worldZ.isFinite() ||
                abs(worldX) > 1000f || abs(worldY) > 1000f || abs(worldZ) > 1000f) continue
            val voxel = Voxel(floor(worldX / voxelSize).toInt(), floor(worldY / voxelSize).toInt(), floor(worldZ / voxelSize).toInt())
            val existing = voxels[voxel]
            if (existing == null && full) continue
            val index = existing ?: (pointCount++).also { voxels[voxel] = it }
            val offset = index * 3
            val packed = colorAt?.invoke(u, v) ?: 0xB8C3CC
            val red = ((packed ushr 16) and 255) / 255f
            val green = ((packed ushr 8) and 255) / 255f
            val blue = (packed and 255) / 255f
            val weight = 1f / (observations[index].coerceAtMost(63) + 1)
            positions[offset] += (worldX - positions[offset]) * weight
            positions[offset + 1] += (worldY - positions[offset + 1]) * weight
            positions[offset + 2] += (worldZ - positions[offset + 2]) * weight
            rgb[offset] += (red - rgb[offset]) * weight
            rgb[offset + 1] += (green - rgb[offset + 1]) * weight
            rgb[offset + 2] += (blue - rgb[offset + 2]) * weight
            observations[index] = (observations[index] + 1).coerceAtMost(64)
        }
        lastFrameStats = FrameStats(width, height, inspectedPixels, validPixels, sampledPixels)
        return pointCount - previousCount
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(positions.copyOf(pointCount * 3), rgb.copyOf(pointCount * 3))

    private fun inspectedPixel(rank: Int, inspectedPixels: Int, totalPixels: Int): Int =
        if (inspectedPixels == totalPixels) rank
        else (rank.toLong() * (totalPixels - 1) / (inspectedPixels - 1)).toInt()

    private companion object {
        const val MAX_PIXELS_PER_FRAME = 1_048_576
    }

    private fun validatePlane(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int, bytes: Int) {
        require(pixelStride >= bytes)
        require(rowStride.toLong() >= (width - 1L) * pixelStride + bytes)
        val required = (height - 1L) * rowStride + (width - 1L) * pixelStride + bytes
        require(required <= buffer.remaining()) { "Image plane is shorter than its dimensions and strides" }
    }
}
