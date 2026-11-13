package com.roomscan.app

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal data class LocalCloudRecord(
    val id: String, val project: String, val location: String, val label: String,
    val created: Long, val pointCount: Int, val mode: CaptureMode = CaptureMode.DEPTH,
)

internal data class LocalCloudInventory(val records: List<LocalCloudRecord>, val unreadable: Int)

/** A model is one versioned file: a process interruption cannot separate its metadata and points. */
internal class LocalCloudStore(filesDir: File) {
    private val dir = File(filesDir, "local-clouds")

    fun save(project: String, location: String, label: String, points: FloatArray, colors: FloatArray, mode: CaptureMode = CaptureMode.DEPTH): String {
        val details = listOf(project, location, label).map { it.trim() }
        require(details.all { it.isNotEmpty() && it.length <= 200 }) { "Add a project, location and scan name (up to 200 characters each)." }
        require(points.isNotEmpty() && points.size % 3 == 0 && points.size / 3 <= CloudGeometry.MAX_POINTS) { "Scan has no usable points or exceeds the point limit." }
        require(colors.size == points.size && points.all { it.isFinite() } && colors.all { it.isFinite() && it in 0f..1f }) { "Scan contains invalid point data." }
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create model storage." }
        require(dir.usableSpace > points.size.toLong() * 8 + 10L * 1024 * 1024) { "Storage is low. Free some space and try saving again." }
        val id = UUID.randomUUID().toString()
        val destination = file(id)
        val pending = File(dir, "$id.partial")
        try {
            FileOutputStream(pending).use { stream ->
                val out = DataOutputStream(BufferedOutputStream(stream))
                out.writeInt(MAGIC); out.writeInt(VERSION)
                details.forEach(out::writeUTF)
                out.writeLong(System.currentTimeMillis()); out.writeInt(points.size / 3)
                out.writeUTF(mode.storedValue)
                for (index in points.indices step 3) {
                    for (axis in 0..2) out.writeFloat(points[index + axis])
                    for (axis in 0..2) out.writeFloat(colors[index + axis])
                }
                out.flush(); stream.fd.sync()
            }
            Files.move(pending.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return id
        } finally { pending.delete() }
    }

    fun inventory(): LocalCloudInventory {
        var unreadable = 0
        val records = dir.listFiles().orEmpty().filter { it.extension == "rsc" }.mapNotNull { source ->
            try {
                file(source.nameWithoutExtension)
                input(source).use { reader ->
                    val record = header(reader, source.nameWithoutExtension)
                    require(reader.available().toLong() == record.pointCount.toLong() * 24) { "Incomplete model" }
                    record
                }
            } catch (_: Exception) { unreadable++; null }
        }
        return LocalCloudInventory(records.sortedByDescending { it.created }, unreadable)
    }

    fun load(id: String): CloudGeometry = input(file(id)).use { reader ->
        val record = header(reader, id)
        require(reader.available().toLong() == record.pointCount.toLong() * 24) { "Saved model is incomplete. Its file has been retained." }
        val vertices = FloatArray(record.pointCount * CloudGeometry.STRIDE) { reader.readFloat() }
        require(vertices.all { it.isFinite() }) { "Saved model contains invalid coordinates." }
        for (index in vertices.indices step CloudGeometry.STRIDE) for (axis in 3..5) {
            require(vertices[index + axis] in 0f..1f) { "Saved model contains invalid colors." }
        }
        // ARCore is Y-up/-Z-forward; the viewer expects image-style Y-down/+Z-forward.
        // Stored coordinates remain in metres. Only the viewer's copy is transformed.
        CloudGeometry.normalized(vertices, record.pointCount, floatArrayOf(1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, -1f))
    }

    private fun file(id: String): File {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "Invalid saved model ID." }
        return File(dir, "$id.rsc")
    }
    private fun input(source: File) = DataInputStream(BufferedInputStream(source.inputStream()))
    private fun header(reader: DataInputStream, id: String): LocalCloudRecord {
        require(reader.readInt() == MAGIC) { "Unsupported saved model format." }
        val version = reader.readInt()
        require(version in 1..VERSION) { "Unsupported saved model format." }
        val details = List(3) { reader.readUTF() }
        require(details.all { it.isNotBlank() && it.length <= 200 }) { "Invalid model details." }
        val created = reader.readLong()
        val count = reader.readInt()
        require(count in 1..CloudGeometry.MAX_POINTS) { "Invalid model point count." }
        val mode = if (version >= 2) CaptureMode.fromStored(reader.readUTF()) else CaptureMode.DEPTH
        return LocalCloudRecord(id, details[0], details[1], details[2], created, count, mode)
    }
    private companion object {
        const val MAGIC = 0x5253434C
        const val VERSION = 2
    }
}
