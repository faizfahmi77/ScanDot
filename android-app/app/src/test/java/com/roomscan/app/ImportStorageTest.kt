package com.roomscan.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CancellationException

class ImportStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun target() = File(temporary.root, "${UUID.randomUUID()}.mp4")
    private fun partial(target: File) = File(target.path + ".partial")

    @Test fun successfulImportPromotesCompleteBytesBeforeInspection() {
        val target = target()
        val bytes = ByteArray(700_000) { (it % 251).toByte() }
        val result = saveImportedVideo(ByteArrayInputStream(bytes), target, { Long.MAX_VALUE }, {}) {
            assertFalse(partial(target).exists())
            assertArrayEquals(bytes, target.readBytes())
            "ready"
        }
        assertEquals("ready", result)
        assertTrue(target.exists())
    }

    @Test fun storageReserveIncludesTheIncomingChunk() {
        val target = target()
        assertThrows(IllegalArgumentException::class.java) {
            saveImportedVideo(ByteArrayInputStream(ByteArray(100)), target, { IMPORT_RESERVE_BYTES + 99 }, {}) {
                fail("Low-storage imports must not be inspected")
            }
        }
        assertFalse(target.exists())
        assertFalse(partial(target).exists())
    }

    @Test fun cancellationDuringCopyRemovesPartialAndSkipsInspection() {
        val target = target()
        var cancelled = false
        val input = object : ByteArrayInputStream(ByteArray(700_000)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length)
                cancelled = true
                return count
            }
        }
        assertThrows(CancellationException::class.java) {
            saveImportedVideo(input, target, { Long.MAX_VALUE }, {
                if (cancelled) throw CancellationException("Cancelled")
            }) { fail("Cancelled imports must not be inspected") }
        }
        assertFalse(target.exists())
        assertFalse(partial(target).exists())
    }

    @Test fun providerReadFailureRemovesPartial() {
        val target = target()
        val input = object : InputStream() { override fun read(): Int = throw IOException("Provider disconnected") }
        assertThrows(IOException::class.java) {
            saveImportedVideo(input, target, { Long.MAX_VALUE }, {}) { fail("Read failed") }
        }
        assertFalse(target.exists())
        assertFalse(partial(target).exists())
    }

    @Test fun invalidVideoRemovesPromotedFile() {
        val target = target()
        assertThrows(IllegalArgumentException::class.java) {
            saveImportedVideo(ByteArrayInputStream(byteArrayOf(1)), target, { Long.MAX_VALUE }, {}) {
                require(false) { "Not a video" }
            }
        }
        assertFalse(target.exists())
        assertFalse(partial(target).exists())
    }

    @Test fun cancellationAfterInspectionAlsoRemovesPromotedFile() {
        val target = target()
        var cancelled = false
        assertThrows(CancellationException::class.java) {
            saveImportedVideo(ByteArrayInputStream(byteArrayOf(1)), target, { Long.MAX_VALUE }, {
                if (cancelled) throw CancellationException("Cancelled")
            }) { cancelled = true }
        }
        assertFalse(target.exists())
    }

    @Test fun existingVideoAndUnfinishedImportAreRetained() {
        val target = target().apply { writeText("Saved original") }
        assertThrows(IllegalStateException::class.java) {
            saveImportedVideo(ByteArrayInputStream(byteArrayOf(1)), target, { Long.MAX_VALUE }, {}) {}
        }
        assertEquals("Saved original", target.readText())
        val other = target()
        partial(other).writeText("Unfinished original")
        assertThrows(IllegalStateException::class.java) {
            saveImportedVideo(ByteArrayInputStream(byteArrayOf(1)), other, { Long.MAX_VALUE }, {}) {}
        }
        assertEquals("Unfinished original", partial(other).readText())
    }

    @Test fun startupCleanupOnlyRemovesRecognizedUnfinishedImports() {
        val video = target().apply { writeText("Keep") }
        val abandoned = partial(target()).apply { writeText("Remove") }
        val unknown = File(temporary.root, "notes.mp4.partial").apply { writeText("Keep") }
        val index = File(temporary.root, "index.json").apply { writeText("Keep") }
        removeAbandonedImports(temporary.root)
        assertFalse(abandoned.exists())
        listOf(video, unknown, index).forEach { assertEquals("Keep", it.readText()) }
    }
}
