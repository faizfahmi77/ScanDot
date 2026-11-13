package com.roomscan.app

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException

/** Separate from room scans. The previous completed result survives failed/cancelled analysis. */
internal class PoseStore(filesDir: File) {
    private val directory = File(filesDir, "pose-results")
    private val latest = File(directory, "latest.pose")

    fun save(clip: PoseClip, cancelled: () -> Boolean = { false }) = synchronized(lock) {
        clip.validate()
        require(clip.detectedCount > 0) { "No person was detected; the previous saved result is retained." }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create skeleton storage." }
        require(directory.usableSpace > MAX_BYTES + 1024 * 1024) { "Storage is low. Free space and analyze again." }
        val pending = File(directory, "latest.partial")
        try {
            FileOutputStream(pending).use { stream ->
                val out = DataOutputStream(BufferedOutputStream(stream))
                out.writeInt(MAGIC); out.writeInt(VERSION)
                out.writeUTF(clip.sourceLabel)
                out.writeLong(clip.sourceDurationMs); out.writeLong(clip.createdMs)
                out.writeLong(clip.analysisElapsedMs); out.writeLong(clip.inferenceElapsedMs)
                out.writeInt(clip.frames.size)
                for (frame in clip.frames) {
                    if (cancelled()) throw CancellationException()
                    out.writeLong(frame.timestampMs)
                    out.writeByte(frame.missing?.let { it.ordinal + 1 } ?: 0)
                    frame.landmarks?.forEach { point ->
                        out.writeFloat(point.x); out.writeFloat(point.y); out.writeFloat(point.z)
                        for (score in listOf(point.visibility, point.presence)) {
                            out.writeBoolean(score != null)
                            if (score != null) out.writeFloat(score)
                        }
                    }
                }
                out.flush(); stream.fd.sync()
            }
            require(pending.length() <= MAX_BYTES) { "Skeleton result exceeds its storage limit." }
            if (cancelled()) throw CancellationException()
            Files.move(pending.toPath(), latest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { pending.delete() }
    }

    fun load(): PoseClip = synchronized(lock) {
        require(latest.isFile) { "No saved skeleton yet. Choose a video and analyze it first." }
        require(latest.length() in 1..MAX_BYTES) { "Saved skeleton is damaged or too large. Analyze a video to replace it." }
        DataInputStream(BufferedInputStream(latest.inputStream())).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == VERSION) { "Unsupported skeleton file." }
            val label = input.readUTF()
            val duration = input.readLong()
            val created = input.readLong()
            val elapsed = input.readLong()
            val inference = input.readLong()
            val count = input.readInt()
            require(count in 1..PoseSampling.MAX_FRAMES) { "Invalid skeleton frame count." }
            val frames = List(count) {
                val time = input.readLong()
                val kind = input.readUnsignedByte()
                require(kind in 0..PoseMissing.entries.size) { "Invalid skeleton frame status." }
                if (kind == 0) {
                    val points = List(PoseSampling.LANDMARK_COUNT) {
                        PoseLandmark(input.readFloat(), input.readFloat(), input.readFloat(),
                            if (input.readBoolean()) input.readFloat() else null,
                            if (input.readBoolean()) input.readFloat() else null)
                    }
                    PoseFrame(time, points, null)
                } else PoseFrame(time, null, PoseMissing.entries[kind - 1])
            }
            require(input.read() == -1) { "Unexpected data after saved skeleton." }
            PoseClip(label, duration, created, elapsed, inference, frames).also {
                it.validate()
                require(it.detectedCount > 0) { "Saved skeleton contains no detected person." }
            }
        }
    }

    private companion object {
        val lock = Any()
        const val MAGIC = 0x504F5345
        const val VERSION = 1
        const val MAX_BYTES = 512_000L
    }
}
