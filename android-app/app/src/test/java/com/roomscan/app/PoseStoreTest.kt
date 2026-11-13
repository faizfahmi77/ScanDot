package com.roomscan.app

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PoseStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val file get() = File(temporary.root, "pose-results/latest.pose")
    private fun store() = PoseStore(temporary.root)
    private fun clip(label: String = "Video") = PoseClip(label, 300, 123, 20, 10, listOf(
        PoseFrame(0, List(33) { PoseLandmark(it * .01f, it * .02f, it * -.03f, if (it % 2 == 0) .8f else null, .9f) }, null),
        PoseFrame(100, null, PoseMissing.NO_PERSON), PoseFrame(200, null, PoseMissing.FRAME_UNAVAILABLE)))

    @Test fun newStoreReopensWorldLandmarksConfidenceAndMissingFramesExactly() {
        val original = clip()
        store().save(original)
        assertEquals(original, store().load())
        assertNull(store().load().frames[1].landmarks)
        assertEquals(PoseMissing.FRAME_UNAVAILABLE, store().load().frames[2].missing)
    }

    @Test fun successfulAnalysisReplacesLatestWithoutTouchingRoomScanData() {
        val room = File(temporary.root, "local-clouds/kept.rsc").apply { parentFile!!.mkdirs(); writeText("existing scan") }
        store().save(clip("First"))
        store().save(clip("Second"))
        assertEquals("Second", store().load().sourceLabel)
        assertEquals(listOf("latest.pose"), file.parentFile!!.listFiles()!!.map { it.name })
        assertEquals("existing scan", room.readText())
    }

    @Test fun cancellationInvalidTimelineAndNoPersonPreservePreviousResult() {
        store().save(clip("Kept"))
        val before = file.readBytes()
        assertThrows(CancellationException::class.java) { store().save(clip("Cancelled")) { true } }
        assertThrows(IllegalArgumentException::class.java) { store().save(clip().copy(frames = clip().frames.take(1))) }
        val empty = clip().copy(frames = PoseSampling.timestamps(300).map { PoseFrame(it, null, PoseMissing.NO_PERSON) })
        assertThrows(IllegalArgumentException::class.java) { store().save(empty) }
        assertArrayEquals(before, file.readBytes())
        assertFalse(File(file.parentFile, "latest.partial").exists())
    }

    @Test fun missingTruncatedOversizedAndUnsupportedFilesFailWithoutDeletingEvidence() {
        assertThrows(IllegalArgumentException::class.java) { store().load() }
        store().save(clip())
        RandomAccessFile(file, "rw").use { it.setLength(it.length() - 1) }
        assertThrows(java.io.EOFException::class.java) { store().load() }
        assertTrue(file.exists())
        file.writeBytes(ByteArray(512_001))
        assertThrows(IllegalArgumentException::class.java) { store().load() }
        store().save(clip())
        RandomAccessFile(file, "rw").use { it.seek(4); it.writeInt(900) }
        assertThrows(IllegalArgumentException::class.java) { store().load() }
    }

    @Test fun nonFiniteCoordinatesAndExtraBytesAreRejected() {
        store().save(clip())
        RandomAccessFile(file, "rw").use { data ->
            data.readInt(); data.readInt(); data.readUTF()
            repeat(4) { data.readLong() }
            data.readInt(); data.readLong(); data.readByte()
            data.writeFloat(Float.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) { store().load() }
        store().save(clip())
        file.appendBytes(byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { store().load() }
    }
}
