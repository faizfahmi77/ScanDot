package com.roomscan.app

import kotlin.math.PI
import org.junit.Assert.*
import org.junit.Test

class PoseDataTest {
    private fun landmarks(dx: Float = 0f, dz: Float = 0f) = List(33) { index ->
        PoseLandmark(dx + index * .01f, index * .02f, dz + index * .03f, .9f, .8f)
    }

    @Test fun samplingNeverExceedsThirtySecondsOrTenFramesPerSecond() {
        assertEquals(listOf(0L), PoseSampling.timestamps(1))
        assertEquals(listOf(0L), PoseSampling.timestamps(100))
        assertEquals(listOf(0L, 100L), PoseSampling.timestamps(101))
        for (duration in listOf(30_000L, 60_000L, Long.MAX_VALUE)) {
            val times = PoseSampling.timestamps(duration)
            assertEquals(300, times.size)
            assertEquals(29_900L, times.last())
            assertTrue(times.zipWithNext().all { (first, second) -> second - first == 100L })
        }
        assertThrows(IllegalArgumentException::class.java) { PoseSampling.timestamps(0) }
    }

    @Test fun timelineKeepsMissingFrameInsteadOfUsingAPreviousPose() {
        val frames = listOf(PoseFrame(0, landmarks(), null), PoseFrame(100, null, PoseMissing.NO_PERSON), PoseFrame(200, landmarks(1f), null))
        assertEquals(0, PoseSampling.frameAt(frames, -10))
        assertEquals(0, PoseSampling.frameAt(frames, 99))
        assertEquals(1, PoseSampling.frameAt(frames, 100))
        assertNull(frames[PoseSampling.frameAt(frames, 199)].landmarks)
        assertEquals(2, PoseSampling.frameAt(frames, 200))
        assertEquals(2, PoseSampling.frameAt(frames, 999))
    }

    @Test fun hipCenterRemovesTrajectoryButRetainsThreeDimensionalShape() {
        val original = PoseProjection.centered(landmarks())
        val translated = PoseProjection.centered(landmarks(2f, -4f))
        for (index in original.indices) {
            assertEquals(original[index].x, translated[index].x, .000001f)
            assertEquals(original[index].z, translated[index].z, .000001f)
        }
        assertEquals(0f, (original[23].x + original[24].x) / 2f, .000001f)
        assertTrue(original.any { it.z != 0f })
    }

    @Test fun sideAndPitchUseDepthRatherThanReusingFlatCoordinates() {
        val point = PosePoint3(1f, 2f, 3f)
        val front = PoseProjection.rotate(point, 0f, 0f)
        val side = PoseProjection.rotate(point, (PI / 2).toFloat(), 0f)
        assertEquals(1f, front.x, .000001f)
        assertEquals(3f, side.x, .000001f)
        assertEquals(-1f, side.z, .000001f)
        val pitched = PoseProjection.rotate(point, 0f, (PI / 2).toFloat())
        assertEquals(-3f, pitched.y, .000001f)
        assertEquals(2f, pitched.z, .000001f)
    }

    @Test fun schemaAndConfidenceRejectInvalidDataWithoutInventingScores() {
        val unknown = PoseLandmark(0f, 0f, 0f, null, null)
        assertTrue(unknown.drawable)
        assertFalse(unknown.confidenceKnown)
        assertFalse(unknown.copy(visibility = .49f).drawable)
        assertFalse(unknown.copy(presence = .49f).drawable)
        assertThrows(IllegalArgumentException::class.java) { unknown.copy(x = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { unknown.copy(visibility = 1.1f) }
        assertThrows(IllegalArgumentException::class.java) { PoseFrame(0, landmarks().take(32), null) }
        assertThrows(IllegalArgumentException::class.java) { PoseFrame(0, null, null) }
        val clip = PoseClip("Video", 200, 0, 20, 10, listOf(PoseFrame(0, landmarks(), null)))
        assertThrows(IllegalArgumentException::class.java) { clip.validate() }
    }
}
