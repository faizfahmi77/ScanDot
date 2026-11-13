package com.roomscan.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.io.DataOutputStream
import java.util.UUID

class LocalCloudStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store() = LocalCloudStore(temporary.root)
    private val points = floatArrayOf(-1f, 0f, -2f, 1f, 0f, -2f)
    private val colors = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)
    private fun save() = store().save(" Project ", "Room", "Desk", points, colors)
    private fun file(id: String) = File(temporary.root, "local-clouds/$id.rsc")

    @Test fun savedModelReopensThroughNewStoreWithoutServerOrIndex() {
        val id = save()
        val restored = store()
        val inventory = restored.inventory()
        assertEquals(0, inventory.unreadable)
        val record = inventory.records.single()
        assertEquals(id, record.id)
        assertEquals("Project", record.project)
        assertEquals(2, record.pointCount)
        assertEquals(CaptureMode.DEPTH, record.mode)
        val geometry = restored.load(id)
        assertEquals(2, geometry.pointCount)
        assertEquals(1f, geometry.vertices[3], 0f)
        assertEquals(1f, geometry.vertices[10], 0f)
    }

    @Test fun fullDepthModeSurvivesSaveAndReopen() {
        val id = store().save("P", "L", "Full", points, colors, CaptureMode.FULL_DEPTH)
        assertEquals(CaptureMode.FULL_DEPTH, store().inventory().records.single().mode)
        assertEquals(2, store().load(id).pointCount)
    }

    @Test fun sparseModeSurvivesNewStoreAndViewerLoad() {
        val id = store().save("P", "L", "Features", points, colors, CaptureMode.SPARSE_TRACKING)
        assertEquals(CaptureMode.SPARSE_TRACKING, store().inventory().records.single().mode)
        assertEquals(2, store().load(id).pointCount)
        RandomAccessFile(file(id), "r").use { it.seek(4); assertEquals(2, it.readInt()) }
    }

    @Test fun legacyVersionOneReopensAsDepthWithoutRewritingTheFile() {
        val id = UUID.randomUUID().toString()
        val source = file(id)
        source.parentFile!!.mkdirs()
        DataOutputStream(source.outputStream()).use { out ->
            out.writeInt(0x5253434C); out.writeInt(1)
            listOf("Old project", "Old room", "Old depth").forEach(out::writeUTF)
            out.writeLong(123L); out.writeInt(2)
            for (offset in points.indices step 3) {
                for (axis in 0..2) out.writeFloat(points[offset + axis])
                for (axis in 0..2) out.writeFloat(colors[offset + axis])
            }
        }
        val before = source.readBytes()
        val record = store().inventory().records.single()
        assertEquals(CaptureMode.DEPTH, record.mode)
        assertEquals("Old depth", record.label)
        assertEquals(2, store().load(id).pointCount)
        assertArrayEquals(before, source.readBytes())
    }

    @Test fun unknownModeIsRejectedAndFileRetained() {
        val id = save()
        RandomAccessFile(file(id), "rw").use { data ->
            data.readInt(); data.readInt()
            repeat(3) { data.readUTF() }
            data.readLong(); data.readInt()
            data.writeUTF("other")
        }
        assertEquals(1, store().inventory().unreadable)
        assertThrows(IllegalArgumentException::class.java) { store().load(id) }
        assertTrue(file(id).exists())
    }

    @Test fun openingPreviewDoesNotAlterStoredMetricCoordinates() {
        val id = save()
        val before = file(id).readBytes()
        store().load(id)
        assertArrayEquals(before, file(id).readBytes())
        assertArrayEquals(floatArrayOf(-1f, 0f, -2f, 1f, 0f, -2f), points, 0f)
    }

    @Test fun arWorldCoordinatesBecomeUprightWithNearerPointsInFront() {
        val id = store().save("P", "L", "N",
            floatArrayOf(0f, 1f, -1f, 0f, -1f, -3f, 0f, 0f, -2f), FloatArray(9) { 0.5f })
        val cloud = store().load(id)
        // cloudProjection flips image-space Y; upright AR points must reach it as negative Y.
        assertTrue(cloud.vertices[1] < cloud.vertices[7])
        // The depth-tested viewer treats smaller camera Z as nearer.
        assertTrue(cloud.vertices[2] < cloud.vertices[8])
    }

    @Test fun invalidGeometryNeverPublishesAFile() {
        val invalid = listOf(floatArrayOf(), floatArrayOf(0f), floatArrayOf(Float.NaN, 0f, 1f), floatArrayOf(Float.POSITIVE_INFINITY, 0f, 1f))
        for (value in invalid) assertThrows(IllegalArgumentException::class.java) {
            store().save("P", "L", "N", value, FloatArray(value.size))
        }
        assertThrows(IllegalArgumentException::class.java) { store().save("P", "L", "N", points, floatArrayOf(1f)) }
        assertThrows(IllegalArgumentException::class.java) { store().save("P", "L", "N", points, FloatArray(points.size) { 2f }) }
        assertTrue(store().inventory().records.isEmpty())
    }

    @Test fun pointCapAndRequiredDetailsAreEnforced() {
        assertThrows(IllegalArgumentException::class.java) { store().save(" ", "L", "N", points, colors) }
        assertThrows(IllegalArgumentException::class.java) { store().save("P", "L", "N".repeat(201), points, colors) }
        val overLimit = FloatArray((CloudGeometry.MAX_POINTS + 1) * 3)
        assertThrows(IllegalArgumentException::class.java) { store().save("P", "L", "N", overLimit, overLimit) }
    }

    @Test fun interruptedAndDamagedFilesDoNotHideGoodModels() {
        val good = save()
        val damaged = save()
        RandomAccessFile(file(damaged), "rw").use { it.setLength(it.length() - 1) }
        val pending = File(temporary.root, "local-clouds/unfinished.partial").apply { writeText("partial") }
        val inventory = store().inventory()
        assertEquals(listOf(good), inventory.records.map { it.id })
        assertEquals(1, inventory.unreadable)
        assertThrows(IllegalArgumentException::class.java) { store().load(damaged) }
        assertTrue(file(damaged).exists())
        assertTrue(pending.exists())
    }

    @Test fun unsupportedVersionAndInvalidIdFailWithoutPathTraversal() {
        val id = save()
        RandomAccessFile(file(id), "rw").use { it.seek(4); it.writeInt(900) }
        assertEquals(1, store().inventory().unreadable)
        assertThrows(IllegalArgumentException::class.java) { store().load(id) }
        assertThrows(IllegalArgumentException::class.java) { store().load("../../outside") }
    }

    @Test fun corruptColorsAndExtraBytesAreRejected() {
        val id = save()
        RandomAccessFile(file(id), "rw").use { it.seek(it.length() - 4); it.writeFloat(Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { store().load(id) }
        val other = save()
        file(other).appendBytes(byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { store().load(other) }
    }

    @Test fun sameNamesProduceIndependentModels() {
        val first = save()
        val second = save()
        assertNotEquals(first, second)
        assertEquals(2, store().inventory().records.size)
        assertEquals(2, store().load(first).pointCount)
        assertEquals(2, store().load(second).pointCount)
    }
}
