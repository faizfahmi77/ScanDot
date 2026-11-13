package com.roomscan.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID

data class Scan(
    val id: String = UUID.randomUUID().toString(), val project: String, val location: String,
    val label: String, val created: Long = System.currentTimeMillis(), val status: String = "recording",
    val duration: Long = 0, val bytes: Long = 0, val jobId: String = "", val error: String = "",
    val rotation: Int = 0, val width: Int = 0, val height: Int = 0,
    val dense: Boolean = true, val uploadAttempted: Boolean = false,
) {
    fun json() = JSONObject().put("id", id).put("project", project).put("location", location)
        .put("label", label).put("created", created).put("status", status).put("duration", duration)
        .put("bytes", bytes).put("job_id", jobId).put("error", error).put("rotation", rotation)
        .put("width", width).put("height", height).put("dense", dense).put("upload_attempted", uploadAttempted)
    companion object {
        fun parse(j: JSONObject) = Scan(j.getString("id"), j.optString("project"), j.optString("location"),
            j.optString("label"), j.optLong("created"), j.optString("status"), j.optLong("duration"),
            j.optLong("bytes"), j.optString("job_id"), j.optString("error"), j.optInt("rotation"),
            j.optInt("width"), j.optInt("height"), j.optBoolean("dense"), j.optBoolean("upload_attempted"))
    }
}

class ScanStore(private val context: Context) {
    private val dir = File(context.filesDir, "scans").apply { mkdirs() }
    private val index = AtomicFile(File(dir, "index.json"))
    fun file(scan: Scan) = File(dir, "${scan.id}.mp4")
    @Synchronized fun load(): List<Scan> {
        removeAbandonedImports(dir)
        val saved = if (!index.baseFile.exists() && !File(index.baseFile.path + ".bak").exists()) emptyList() else try {
            val arr = JSONArray(index.openRead().bufferedReader().use { it.readText() })
            (0 until arr.length()).map { Scan.parse(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            // Keep damaged metadata for recovery; never remove source recordings.
            if (index.baseFile.exists()) index.baseFile.copyTo(File(dir, "index-recovery-${System.currentTimeMillis()}.json"))
            emptyList()
        }
        val restored = saved.map {
            when (it.status) {
                "recording" -> it.copy(status = "interrupted", error = "Recording was interrupted. Review the saved video before keeping it.")
                "uploading" -> it.copy(status = "ready", error = "Transfer interrupted. Retry safely; the original video is saved.")
                else -> it
            }
        }
        val known = saved.map { it.id }.toSet()
        val orphaned = dir.listFiles().orEmpty().filter { it.extension == "mp4" && it.nameWithoutExtension !in known && runCatching { UUID.fromString(it.nameWithoutExtension) }.isSuccess }.map {
            Scan(id = it.nameWithoutExtension, project = "Recovered scans", location = "This phone", label = "Recovered recording", created = it.lastModified(), status = "interrupted", bytes = it.length(), error = "Details were interrupted while saving. Review this video, then check and keep it.")
        }
        return (restored + orphaned).sortedByDescending { it.created }
    }
    @Synchronized fun save(scans: List<Scan>) {
        val stream = index.startWrite()
        try { stream.write(JSONArray(scans.map { it.json() }).toString(2).toByteArray()); index.finishWrite(stream) }
        catch (e: Exception) { index.failWrite(stream); throw e }
    }
    fun inspect(scan: Scan): Scan {
        val source = file(scan)
        require(source.exists() && source.length() > 0) { "No saved video was produced. Please record again." }
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(source.absolutePath)
            val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val width = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            require(duration > 0 && width > 0) { "The video is incomplete and cannot be processed. Please record again." }
            return scan.copy(duration = duration, bytes = source.length(), width = width,
                height = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                rotation = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0)
        } finally { reader.release() }
    }
    fun import(uri: Uri, scan: Scan, checkActive: () -> Unit): Scan {
        val target = file(scan)
        return context.contentResolver.openInputStream(uri)?.use { input ->
            saveImportedVideo(input, target, { dir.usableSpace }, checkActive) {
                inspect(scan).copy(status = "ready")
            }
        } ?: error("This video could not be opened. Choose it again.")
    }
    fun freeBytes() = dir.usableSpace
}

data class Connection(val url: String = "http://127.0.0.1:8765", val token: String = "")
fun validServer(value: String): String {
    val uri = URI(value.trim())
    require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
        "Enter an http:// or https:// server address without credentials or query parameters."
    }
    return value.trim().trimEnd('/')
}

class Api(private val connection: Connection) {
    private fun open(path: String, method: String = "GET"): HttpURLConnection {
        val conn = URL(validServer(connection.url) + path).openConnection() as HttpURLConnection
        conn.requestMethod = method; conn.connectTimeout = 12_000; conn.readTimeout = 90_000
        conn.instanceFollowRedirects = false
        if (connection.token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${connection.token}")
        conn.setRequestProperty("Accept", "application/json")
        return conn
    }
    private fun response(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val detail = runCatching { JSONObject(body).optString("detail") }.getOrDefault("")
            error(if (code == 401 || code == 403) "Access denied. Check the token in Settings." else "Server $code: ${detail.take(500).ifBlank { "Request failed" }}")
        }
        return JSONObject(body)
    }
    fun request(path: String, method: String = "GET"): JSONObject {
        val conn = open(path, method)
        return try { if (method == "POST") { conn.doOutput = true; conn.setFixedLengthStreamingMode(0); conn.outputStream.close() }; response(conn) }
        finally { conn.disconnect() }
    }
    fun upload(scan: Scan, file: File, progress: (Float) -> Unit): JSONObject {
        require(file.exists() && file.length() > 0) { "The saved video is missing." }
        val boundary = "RoomScan${UUID.randomUUID()}"
        val fields = linkedMapOf("client_id" to scan.id, "project" to scan.project, "location" to scan.location, "label" to scan.label, "dense" to scan.dense.toString())
        val prefix = buildString {
            fields.forEach { (key, value) -> append("--$boundary\r\nContent-Disposition: form-data; name=\"$key\"\r\n\r\n$value\r\n") }
            append("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${scan.id}.mp4\"\r\nContent-Type: video/mp4\r\n\r\n")
        }.toByteArray()
        val suffix = "\r\n--$boundary--\r\n".toByteArray()
        val conn = open("/api/jobs", "POST")
        conn.doOutput = true; conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.setFixedLengthStreamingMode(prefix.size.toLong() + file.length() + suffix.size)
        return try {
            conn.outputStream.use { out ->
                out.write(prefix)
                file.inputStream().use { input ->
                    val buffer = ByteArray(256 * 1024); var sent = 0L
                    while (true) { val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n); sent += n; progress(sent.toFloat() / file.length()) }
                }
                out.write(suffix)
            }
            response(conn)
        } finally { conn.disconnect() }
    }
}
