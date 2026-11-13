package com.roomscan.app

import java.nio.FloatBuffer
import java.nio.IntBuffer
import org.junit.Assert.*
import org.junit.Test

class SparsePointFusionTest {
    private fun add(fusion: SparsePointFusion, time: Long, values: FloatArray, vararg ids: Int) =
        fusion.integrate(time, FloatBuffer.wrap(values), IntBuffer.wrap(ids))

    @Test fun worldCoordinatesAreCopiedAndPersistentIdsUpdateInsteadOfDuplicating() {
        val fusion = SparsePointFusion()
        val points = floatArrayOf(1f, 2f, -3f, .8f, 4f, 5f, -6f, 1f)
        assertEquals(2, add(fusion, 1, points, 10, 20))
        points[0] = 100f
        assertEquals(0, add(fusion, 2, floatArrayOf(2f, 3f, -4f, .9f), 10))
        assertEquals(2, fusion.pointCount)
        assertArrayEquals(floatArrayOf(2f, 3f, -4f, 4f, 5f, -6f), fusion.snapshot().points, 0f)
        assertTrue(fusion.snapshot().colors.all { it in 0f..1f })
    }

    @Test fun duplicateOlderAndZeroTimestampsDoNotReintegrate() {
        val fusion = SparsePointFusion()
        val point = floatArrayOf(1f, 2f, 3f, 1f)
        assertEquals(0, add(fusion, 0, point, 1))
        assertEquals(1, add(fusion, 2, point, 1))
        assertEquals(0, add(fusion, 2, point, 2))
        assertEquals(0, add(fusion, 1, point, 3))
        assertEquals(1, fusion.pointCount)
    }

    @Test fun rejectsLowInvalidConfidenceNonFiniteAndOutOfRangeCoordinates() {
        val fusion = SparsePointFusion()
        val points = floatArrayOf(0f, 0f, 0f, .49f, Float.NaN, 0f, 0f, 1f,
            0f, Float.POSITIVE_INFINITY, 0f, 1f, 1001f, 0f, 0f, 1f,
            0f, 0f, 0f, Float.NaN, 0f, 0f, 0f, 1.1f, 0f, 0f, 0f, .5f)
        assertEquals(1, add(fusion, 1, points, 1, 2, 3, 4, 5, 6, 7))
        assertEquals(1, fusion.pointCount)
    }

    @Test fun respectsBufferPositionsAndDoesNotConsumeBuffers() {
        val points = FloatBuffer.wrap(floatArrayOf(99f, 1f, 2f, 3f, 1f, 99f)).apply { position(1); limit(5) }
        val ids = IntBuffer.wrap(intArrayOf(99, 4, 99)).apply { position(1); limit(2) }
        val fusion = SparsePointFusion()
        assertEquals(1, fusion.integrate(1, points, ids))
        assertEquals(1, points.position()); assertEquals(1, ids.position())
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), fusion.snapshot().points, 0f)
    }

    @Test fun pointAndPerFrameCapsBoundStorageAndWork() {
        val fusion = SparsePointFusion(maxPoints = 2, maxSamplesPerFrame = 2)
        val points = FloatArray(16) { if (it % 4 == 3) 1f else it.toFloat() }
        assertEquals(2, add(fusion, 1, points, 1, 2, 3, 4))
        assertTrue(fusion.full)
        assertEquals(0, add(fusion, 2, floatArrayOf(9f, 9f, 9f, 1f), 5))
        assertEquals(0, add(fusion, 3, floatArrayOf(8f, 8f, 8f, 1f), 1))
        assertEquals(2, fusion.pointCount)
        assertEquals(8f, fusion.snapshot().points[0], 0f)
    }

    @Test fun emptyAndInvalidInputs() {
        val fusion = SparsePointFusion()
        assertEquals(0, add(fusion, 1, floatArrayOf()))
        assertTrue(fusion.snapshot().points.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { add(fusion, 2, floatArrayOf(1f), 1) }
        assertThrows(IllegalArgumentException::class.java) { add(fusion, 2, floatArrayOf(1f, 2f, 3f, 1f)) }
        assertThrows(IllegalArgumentException::class.java) { SparsePointFusion(maxPoints = 0) }
        assertThrows(IllegalArgumentException::class.java) { SparsePointFusion(confidenceThreshold = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { SparsePointFusion(maxSamplesPerFrame = 0) }
    }
}
