package com.roomscan.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DepthFusionTest {
    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    private val intrinsics = DepthFusion.Intrinsics(1f, 1f, 0f, 0f)

    private fun integrate(
        fusion: DepthFusion,
        timestamp: Long = 1,
        width: Int = 1,
        height: Int = 1,
        depths: IntArray = intArrayOf(1000),
        confidence: IntArray = IntArray(depths.size) { 255 },
        pose: FloatArray = identity,
        calibration: DepthFusion.Intrinsics = intrinsics,
        colors: ((Int, Int) -> Int)? = null,
    ): Int {
        val depth = ByteBuffer.allocate(depths.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        depths.forEach { depth.putShort(it.toShort()) }
        depth.flip()
        val certainty = ByteBuffer.wrap(ByteArray(confidence.size) { confidence[it].toByte() })
        return fusion.integrate(timestamp, width, height, depth, width * 2, 2,
            certainty, width, 1, calibration, pose, colors)
    }

    @Test fun fullDepthWithoutConfidenceStillFiltersInvalidDistances() {
        val fusion = DepthFusion()
        val depth = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        listOf(0, 1000, 5001).forEach { depth.putShort(it.toShort()) }
        depth.flip()
        assertEquals(1, fusion.integrate(1, 3, 1, depth, 6, 2, null, 0, 0, intrinsics, identity))
        assertEquals(1, fusion.lastFrameStats!!.validPixels)
    }

    @Test fun projectsDepthUsingArcoreAxisSignsAndMetres() {
        val fusion = DepthFusion()
        assertEquals(4, integrate(fusion, width = 2, height = 2, depths = IntArray(4) { 2000 }))
        assertArrayEquals(floatArrayOf(0f, 0f, -2f, 2f, 0f, -2f, 0f, -2f, -2f, 2f, -2f, -2f), fusion.snapshot().points, 0.00001f)
    }

    @Test fun appliesColumnMajorRotationAndTranslation() {
        val pose = floatArrayOf(0f, 0f, -1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 10f, 20f, 30f, 1f)
        val fusion = DepthFusion()
        integrate(fusion, pose = pose)
        assertArrayEquals(floatArrayOf(9f, 20f, 30f), fusion.snapshot().points, 0.00001f)
    }

    @Test fun usesPrincipalPointAndFocalLength() {
        val fusion = DepthFusion()
        integrate(fusion, calibration = DepthFusion.Intrinsics(2f, 4f, 1f, 2f))
        assertArrayEquals(floatArrayOf(-0.5f, 0.5f, -1f), fusion.snapshot().points, 0.00001f)
    }

    @Test fun rejectsMissingLowConfidenceAndOutOfRangeDepth() {
        val fusion = DepthFusion()
        integrate(fusion, width = 6, depths = intArrayOf(0, 249, 5001, 1000, 250, 5000),
            confidence = intArrayOf(255, 255, 255, 159, 160, 255))
        assertEquals(2, fusion.pointCount)
    }

    @Test fun interpretsUnsigned16BitDepthRatherThanWrapping() {
        val fusion = DepthFusion()
        integrate(fusion, depths = intArrayOf(65535))
        assertEquals(0, fusion.pointCount)
    }

    @Test fun ignoresDuplicateAndOlderFrames() {
        val fusion = DepthFusion()
        integrate(fusion, timestamp = 20)
        assertEquals(0, integrate(fusion, timestamp = 20, depths = intArrayOf(2000)))
        assertEquals(0, integrate(fusion, timestamp = 19, depths = intArrayOf(3000)))
        assertEquals(1, fusion.pointCount)
        assertEquals(20L, fusion.lastTimestamp)
    }

    @Test fun averagesRepeatedVoxelPositionsAndRgb() {
        val fusion = DepthFusion(voxelSize = 0.1f)
        integrate(fusion, depths = intArrayOf(1001), colors = { _, _ -> 0xFF0000 })
        integrate(fusion, timestamp = 2, depths = intArrayOf(1003), colors = { _, _ -> 0x0000FF })
        assertEquals(1, fusion.pointCount)
        assertArrayEquals(floatArrayOf(0f, 0f, -1.002f), fusion.snapshot().points, 0.00001f)
        assertArrayEquals(floatArrayOf(0.5f, 0f, 0.5f), fusion.snapshot().colors, 0.00001f)
    }

    @Test fun negativeVoxelsUseFloorWithoutMergingAcrossOrigin() {
        val fusion = DepthFusion(voxelSize = 0.1f)
        val positive = identity.copyOf().apply { this[12] = 0.01f }
        val negative = identity.copyOf().apply { this[12] = -0.01f }
        integrate(fusion, pose = positive)
        integrate(fusion, timestamp = 2, pose = negative)
        assertEquals(2, fusion.pointCount)
    }

    @Test fun hardCapAndSnapshotCopiesKeepMemoryBounded() {
        val fusion = DepthFusion(maxPoints = 2)
        integrate(fusion, width = 3, depths = intArrayOf(1000, 1000, 1000))
        assertTrue(fusion.full)
        assertEquals(2, fusion.pointCount)
        val snapshot = fusion.snapshot()
        assertEquals(6, snapshot.points.size)
        assertEquals(6, snapshot.colors.size)
        snapshot.points[0] = 999f
        snapshot.colors[0] = 999f
        assertEquals(0f, fusion.snapshot().points[0], 0f)
        assertTrue(fusion.snapshot().colors.all { it in 0f..1f })
        assertEquals(0, integrate(fusion, timestamp = 2, depths = intArrayOf(2000)))
    }

    @Test fun perFrameBudgetAlsoHoldsForThinImages() {
        val fusion = DepthFusion(maxSamplesPerFrame = 3)
        integrate(fusion, width = 100, depths = IntArray(100) { 1000 })
        assertEquals(3, fusion.pointCount)
        assertArrayEquals(floatArrayOf(0f, 0f, -1f, 49f, 0f, -1f, 99f, 0f, -1f), fusion.snapshot().points, 0f)
    }

    @Test fun retainsAllSparseValidDepthThatFallsBetweenTheOldGridPositions() {
        val fusion = DepthFusion()
        val depths = IntArray(100 * 100) { pixel ->
            if (pixel % 100 % 2 == 1 && pixel / 100 % 2 == 1) 1000 else 0
        }
        assertEquals(2500, integrate(fusion, width = 100, height = 100, depths = depths))
        assertEquals(DepthFusion.FrameStats(100, 100, 10_000, 2500, 2500), fusion.lastFrameStats)
        assertFalse(fusion.lastFrameStats!!.inspectionLimited)
        val points = fusion.snapshot().points
        assertArrayEquals(floatArrayOf(1f, -1f, -1f), points.copyOfRange(0, 3), 0f)
        assertArrayEquals(floatArrayOf(99f, -99f, -1f), points.copyOfRange(points.size - 3, points.size), 0f)
    }

    @Test fun denseBudgetDistributesSamplesAcrossValidPixelsAndImageExtent() {
        val fusion = DepthFusion(maxSamplesPerFrame = 5)
        val sampled = mutableListOf<Pair<Int, Int>>()
        assertEquals(5, integrate(fusion, width = 20, height = 10, depths = IntArray(200) { 1000 },
            colors = { u, v -> sampled.add(u to v); 0xFFFFFF }))
        assertEquals(listOf(0 to 0, 9 to 2, 19 to 4, 9 to 7, 19 to 9), sampled)
        assertEquals(DepthFusion.FrameStats(20, 10, 200, 200, 5), fusion.lastFrameStats)
        val other = DepthFusion(maxSamplesPerFrame = 5)
        integrate(other, width = 20, height = 10, depths = IntArray(200) { 1000 })
        assertArrayEquals(fusion.snapshot().points, other.snapshot().points, 0f)
    }

    @Test fun selectionBudgetCountsOnlyConfidenceAndRangeValidPixels() {
        val fusion = DepthFusion(maxSamplesPerFrame = 3)
        integrate(fusion, width = 9, depths = intArrayOf(0, 1000, 5001, 1000, 1000, 249, 1000, 5000, 250),
            confidence = intArrayOf(255, 255, 255, 159, 255, 255, 255, 255, 255))
        assertEquals(DepthFusion.FrameStats(9, 1, 9, 5, 3), fusion.lastFrameStats)
        assertArrayEquals(floatArrayOf(1f, 0f, -1f, 6f, 0f, -1f, 2f, 0f, -.25f), fusion.snapshot().points, 0f)
    }

    @Test fun singleSampleBudgetSelectsMiddleValidPixelAndEmptyFramesHaveZeroSamples() {
        val fusion = DepthFusion(maxSamplesPerFrame = 1)
        integrate(fusion, width = 5, depths = IntArray(5) { 1000 })
        assertArrayEquals(floatArrayOf(2f, 0f, -1f), fusion.snapshot().points, 0f)
        integrate(fusion, timestamp = 2, width = 5, depths = IntArray(5))
        assertEquals(DepthFusion.FrameStats(5, 1, 5, 0, 0), fusion.lastFrameStats)
    }

    @Test fun oversizedFramesBoundInspectionAndIncludeFarImageEdge() {
        val fusion = DepthFusion()
        val width = 2048
        val height = 1024
        val depths = IntArray(width * height).apply { this[lastIndex] = 1000 }
        assertEquals(1, integrate(fusion, width = width, height = height, depths = depths,
            calibration = DepthFusion.Intrinsics(100f, 100f, 0f, 0f)))
        assertEquals(DepthFusion.FrameStats(width, height, 1_048_576, 1, 1), fusion.lastFrameStats)
        assertTrue(fusion.lastFrameStats!!.inspectionLimited)
        assertArrayEquals(floatArrayOf(20.47f, -10.23f, -1f), fusion.snapshot().points, .00001f)
    }

    @Test fun respectsPlanePaddingPixelStridesAndBufferPosition() {
        val depth = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
        depth.position(1)
        depth.putShort(1, 1000); depth.putShort(5, 2000)
        depth.putShort(10, 3000); depth.putShort(14, 4000)
        val confidence = ByteBuffer.allocate(10).apply { position(2) }
        listOf(2, 4, 7, 9).forEach { confidence.put(it, 255.toByte()) }
        val fusion = DepthFusion()
        fusion.integrate(1, 2, 2, depth, 9, 4, confidence, 5, 2, intrinsics, identity)
        assertArrayEquals(floatArrayOf(0f, 0f, -1f, 2f, 0f, -2f, 0f, -3f, -3f, 4f, -4f, -4f), fusion.snapshot().points, 0.00001f)
        assertEquals(1, depth.position())
        assertEquals(2, confidence.position())
        assertEquals(DepthFusion.FrameStats(2, 2, 4, 4, 4), fusion.lastFrameStats)
    }

    @Test fun emptyCloudHasEmptyArrays() {
        val fusion = DepthFusion()
        assertEquals(0, fusion.snapshot().points.size)
        assertEquals(0, fusion.snapshot().colors.size)
        assertFalse(fusion.full)
        assertNull(fusion.lastFrameStats)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsTruncatedPlane() {
        val fusion = DepthFusion()
        fusion.integrate(1, 2, 2, ByteBuffer.allocate(7), 4, 2,
            ByteBuffer.allocate(4), 2, 1, intrinsics, identity)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsNonFiniteIntrinsics() {
        integrate(DepthFusion(), calibration = intrinsics.copy(fx = Float.NaN))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidPose() {
        integrate(DepthFusion(), pose = identity.copyOf().apply { this[15] = 0f })
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsUnboundedConfiguration() {
        DepthFusion(maxPoints = 200_001)
    }
}
