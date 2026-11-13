package com.roomscan.app

import java.io.File
import java.io.InputStream
import java.util.UUID

internal const val IMPORT_RESERVE_BYTES = 150L * 1024 * 1024

internal fun <T> saveImportedVideo(
    input: InputStream,
    target: File,
    availableBytes: () -> Long,
    checkActive: () -> Unit,
    inspect: () -> T,
): T {
    val partial = File(target.path + ".partial")
    check(!target.exists()) { "A saved video already exists at this location." }
    check(partial.createNewFile()) { "An unfinished import already exists at this location." }
    var promoted = false
    try {
        partial.outputStream().use { output ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                checkActive()
                val count = input.read(buffer)
                checkActive()
                if (count < 0) break
                require(availableBytes() - count >= IMPORT_RESERVE_BYTES) {
                    "Storage is low. Free some space and import again."
                }
                output.write(buffer, 0, count)
            }
        }
        checkActive()
        check(partial.renameTo(target)) { "Could not save the imported video." }
        promoted = true
        val result = inspect()
        checkActive()
        return result
    } catch (failure: Exception) {
        partial.delete()
        if (promoted) target.delete()
        throw failure
    }
}

// Called only during startup, before importing is enabled. These files were never finalized.
internal fun removeAbandonedImports(directory: File) {
    directory.listFiles().orEmpty().filter { file ->
        file.isFile && file.name.endsWith(".mp4.partial") &&
            runCatching { UUID.fromString(file.name.removeSuffix(".mp4.partial")) }.isSuccess
    }.forEach { it.delete() }
}
