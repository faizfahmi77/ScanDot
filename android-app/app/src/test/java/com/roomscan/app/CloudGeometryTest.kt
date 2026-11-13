package com.roomscan.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class CloudGeometryTest {
    private fun geometry(vararg points: FloatArray): CloudGeometry = CloudGeometry.normalized(
        points.flatMap { listOf(it[0], it[1], it[2], 0.2f, 0.4f, 0.6f) }.toFloatArray(), points.size)

    @Test fun fitsPortraitAndLandscapeWithoutDistortingAspect() {
        val cloud = geometry(floatArrayOf(-4f, -1f, 0f), floatArrayOf(4f, 1f, 0f))
        for (aspect in listOf(0.5f, 2f)) {
            val frame = fitCloud(cloud, CloudRotation(), aspect)
            val projection = FloatArray(16)
            cloudProjection(CloudRotation(), frame, aspect, 1f, 0f, 0f, projection)
            for (i in 0 until cloud.pointCount) {
                val x = cloud.vertices[i * 6]; val y = cloud.vertices[i * 6 + 1]
                assertTrue(abs(projection[0] * x + projection[12]) <= 0.9001f)
                assertTrue(abs(projection[5] * y + projection[13]) <= 0.9001f)
            }
            assertEquals(projection[0] * aspect, -projection[5], 0.0001f)
        }
    }

    @Test fun anOutlierDoesNotShrinkTheRoomAndColorsArePreserved() {
        val points = Array(101) { i -> floatArrayOf((i % 10).toFloat(), (i / 10).toFloat(), 0f) }
        points[100] = floatArrayOf(1_000_000f, 1_000_000f, 1_000_000f)
        val cloud = geometry(*points)
        val frame = fitCloud(cloud, CloudRotation(), 1f)
        assertTrue(frame.halfHeight < 2f)
        assertEquals(101, cloud.pointCount)
        assertEquals(0.2f, cloud.vertices[3], 0f)
        assertEquals(0.6f, cloud.vertices[5], 0f)
        assertTrue(cloud.vertices[600] > 10f)
    }

    @Test fun viewRotationsPreserveLengthsAndHaveDifferentDepthAxes() {
        for (rotation in listOf(CloudRotation(), CloudRotation(0.6f, -0.4f, 1.2f))) {
            val matrix = rotation.matrix()
            for (row in 0..2) assertEquals(1f, sqrt((0..2).sumOf { column ->
                (matrix[row * 3 + column] * matrix[row * 3 + column]).toDouble()
            }).toFloat(), 0.0001f)
            assertEquals(0f, (0..2).sumOf { (matrix[it] * matrix[3 + it]).toDouble() }.toFloat(), 0.0001f)
        }
        val side = CloudRotation(CloudPreset.SIDE.yaw, CloudPreset.SIDE.pitch).matrix()
        assertEquals(1f, side[2], 0.0001f)
        assertEquals(-1f, side[6], 0.0001f)
        val top = CloudRotation(CloudPreset.TOP.yaw, CloudPreset.TOP.pitch).matrix()
        assertEquals(-1f, top[5], 0.0001f)
        assertEquals(1f, top[7], 0.0001f)
    }

    @Test fun projectionUsesNearerDepthAndScreenSpacePan() {
        val output = FloatArray(16)
        cloudProjection(CloudRotation(), CloudFrame(2f, 3f, 4f), 0.5f, 2f, 0.2f, -0.3f, output)
        assertTrue(output[10] > 0f)
        assertEquals(0.2f, output[0] * 2f + output[12], 0.0001f)
        assertEquals(-0.3f, output[5] * 3f + output[13], 0.0001f)
        assertEquals(1f, output[15], 0f)
    }

    @Test fun coincidentPointsStillProduceFiniteFraming() {
        val cloud = geometry(floatArrayOf(7f, 7f, 7f), floatArrayOf(7f, 7f, 7f))
        assertTrue(cloud.vertices.all { it.isFinite() })
        assertTrue(fitCloud(cloud, CloudRotation(), 1f).halfHeight > 0f)
    }

    @Test fun cameraOrientationRotatesCoordinatesWithoutChangingColors() {
        val rotation = CloudRotation(CloudPreset.SIDE.yaw).matrix()
        assertTrue(validViewRotation(rotation))
        val vertices = floatArrayOf(-1f, 0f, -2f, 1f, 0f, 0f, 1f, 0f, 2f, 0f, 1f, 0f)
        val cloud = CloudGeometry.normalized(vertices, 2, rotation)
        assertTrue(cloud.vertices[0] < cloud.vertices[6])
        assertTrue(cloud.vertices[2] > cloud.vertices[8])
        assertEquals(1f, cloud.vertices[3], 0f)
        assertEquals(1f, cloud.vertices[10], 0f)
    }

    @Test fun malformedOrientationsAreNotAccepted() {
        assertFalse(validViewRotation(floatArrayOf(1f)))
        assertFalse(validViewRotation(FloatArray(9) { Float.NaN }))
        assertFalse(validViewRotation(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, -1f)))
        assertFalse(validViewRotation(FloatArray(9) { 1f }))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsEmptyGeometry() {
        CloudGeometry.normalized(floatArrayOf(), 0)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsNonfiniteGeometry() {
        geometry(floatArrayOf(Float.NaN, 0f, 0f))
    }
}
