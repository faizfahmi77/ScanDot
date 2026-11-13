package com.roomscan.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

class AppModel(application: Application) : AndroidViewModel(application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Mutex()
    val store = ScanStore(application)
    private val localStore = LocalCloudStore(application.filesDir)
    internal var localClouds by mutableStateOf<List<LocalCloudRecord>>(emptyList()); private set
    private val prefs = application.getSharedPreferences("connection", 0)
    var scans by mutableStateOf<List<Scan>>(emptyList()); private set
    var jobs by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var connection by mutableStateOf(Connection()); private set
    var loaded by mutableStateOf(false); private set
    var message by mutableStateOf("")
    var busy by mutableStateOf(""); private set
    var transferId by mutableStateOf(""); private set
    var progress by mutableStateOf(0f); private set
    var refreshing by mutableStateOf(false); private set
    var freeBytes by mutableStateOf(0L); private set
    internal var cloud by mutableStateOf<CloudGeometry?>(null); private set
    var cloudJob by mutableStateOf<JSONObject?>(null); private set

    init {
        scope.launch {
            try {
                val restored = withContext(Dispatchers.IO) {
                    var initial = Connection()
                    if (BuildConfig.DEBUG) runCatching {
                        val json = JSONObject(application.assets.open("connection.json").bufferedReader().use { it.readText() })
                        initial = Connection(json.optString("server_url", initial.url), json.optString("token"))
                    }
                    val savedConnection = Connection(prefs.getString("url", initial.url) ?: initial.url, prefs.getString("token", initial.token) ?: initial.token)
                    val savedJobs = runCatching { JSONArray(prefs.getString("jobs", "[]")) }.getOrDefault(JSONArray())
                    Triple(store.load(), savedConnection, (0 until savedJobs.length()).map { savedJobs.getJSONObject(it) })
                }
                scans = restored.first; connection = restored.second; jobs = restored.third
                freeBytes = withContext(Dispatchers.IO) { store.freeBytes() }
                reloadLocalInternal()
            } catch (e: Exception) { message = "Saved scans could not be loaded: ${e.message}. Files have been retained." }
            finally { loaded = true }
        }
    }
    private suspend fun change(transform: (List<Scan>) -> List<Scan>) = lock.withLock {
        val next = transform(scans)
        withContext(Dispatchers.IO) { store.save(next) }
        scans = next
    }
    private suspend fun update(scan: Scan) = change { list -> list.map { if (it.id == scan.id) scan else it } }
    fun launchAction(label: String, task: suspend () -> Unit) {
        if (busy.isNotEmpty()) return
        scope.launch {
            busy = label
            try { task() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "Something went wrong. Please try again." }
            finally { busy = ""; freeBytes = withContext(Dispatchers.IO) { store.freeBytes() } }
        }
    }
    fun start(project: String, location: String, label: String, ready: (Scan) -> Unit) = launchAction("Preparing recording") {
        require(project.isNotBlank() && location.isNotBlank() && label.isNotBlank()) { "Add a project, location and scan name first." }
        require(withContext(Dispatchers.IO) { store.freeBytes() } > 250L * 1024 * 1024) { "Storage is low. Free at least 250 MB before recording." }
        val scan = Scan(project = project.trim(), location = location.trim(), label = label.trim())
        change { listOf(scan) + it }; ready(scan)
    }
    fun finish(scan: Scan, error: String?) {
        scope.launch {
            try {
                val inspected = withContext(Dispatchers.IO) { store.inspect(scan) }
                update(inspected.copy(status = if (error == null) "ready" else "interrupted", error = error ?: ""))
                message = if (error == null) "Recording saved on this phone." else "Recording interrupted. Review the saved portion before keeping it."
            } catch (e: Exception) {
                runCatching { update(scan.copy(status = "failed", error = error ?: e.message ?: "Recording could not be finalized.")) }
                message = "Recording could not be finalized. ${error ?: e.message.orEmpty()}"
            }
            freeBytes = withContext(Dispatchers.IO) { store.freeBytes() }
        }
    }
    fun recover(scan: Scan) = launchAction("Checking saved video") {
        val checked = withContext(Dispatchers.IO) { store.inspect(scan) }
        update(checked.copy(status = "ready", error = "")); message = "Recovered video kept. Check playback before processing."
    }
    fun importVideo(uri: Uri, project: String, location: String, label: String) = launchAction("Saving imported video") {
        val draft = Scan(project = project.ifBlank { "Imported scans" }, location = location.ifBlank { "Selected video" }, label = label.ifBlank { "Imported walkthrough" })
        val scan = withContext(Dispatchers.IO) { store.import(uri, draft) { ensureActive() } }
        change { listOf(scan) + it }; message = "Video saved on this phone. Ready to review or process."
    }
    fun rename(scan: Scan, project: String, location: String, label: String) = launchAction("Saving details") {
        require(project.isNotBlank() && location.isNotBlank() && label.isNotBlank()) { "All three fields are required." }
        require(!scan.uploadAttempted) { "Details are locked after the first transfer to keep retries consistent." }
        update(scan.copy(project = project.trim(), location = location.trim(), label = label.trim()))
        message = "Scan details saved."
    }
    fun saveConnection(url: String, token: String, test: Boolean) = launchAction(if (test) "Testing connection" else "Saving settings") {
        val next = Connection(validServer(url), token.trim())
        withContext(Dispatchers.IO) { prefs.edit().putString("url", next.url).putString("token", next.token).commit() }
        connection = next
        if (test) {
            withContext(Dispatchers.IO) { Api(next).request("/api/health"); Api(next).request("/api/jobs") }
            message = "Connected. Server and access token are working."
        } else message = "Connection settings saved."
    }
    private suspend fun keepJobs(list: List<JSONObject>) {
        withContext(Dispatchers.IO) { prefs.edit().putString("jobs", JSONArray(list).toString()).commit() }
        jobs = list
    }
    private suspend fun keepJob(job: JSONObject) = keepJobs(listOf(job) + jobs.filter { it.optString("id") != job.optString("id") })
    fun refresh(quiet: Boolean = false) {
        if (refreshing || !loaded || transferId.isNotEmpty()) return
        scope.launch {
            refreshing = true
            try {
                val data = withContext(Dispatchers.IO) { Api(connection).request("/api/jobs").getJSONArray("jobs") }
                keepJobs((0 until data.length()).map { data.getJSONObject(it) })
            } catch (e: Exception) { if (!quiet) message = "Could not refresh: ${e.message}. Saved recordings are available offline." }
            finally { refreshing = false }
        }
    }
    fun upload(scan: Scan) = launchAction("Sending video") {
        require(scan.status == "ready" || scan.status == "submitted") { "Review and keep this recording before processing." }
        transferId = scan.id; progress = 0f
        val attempted = scan.copy(uploadAttempted = true)
        try {
            update(attempted.copy(status = "uploading", error = ""))
            val api = Api(connection)
            val result = withContext(Dispatchers.IO) { api.upload(scan, store.file(scan)) { value -> scope.launch { progress = value } } }
            update(attempted.copy(status = "submitted", jobId = result.getString("id"), error = ""))
            keepJob(result); message = "Video received. Follow its processing stage in Results."
        } catch (e: Exception) {
            update(attempted.copy(status = if (scan.jobId.isBlank()) "ready" else "submitted", error = "Transfer failed. Original video retained. ${e.message}"))
            throw e
        } finally { transferId = "" }
    }
    fun setDetailed(scan: Scan, dense: Boolean) = launchAction("Saving processing choice") {
        require(!scan.uploadAttempted) { "Processing choice is locked after the first transfer." }
        update(scan.copy(dense = dense))
    }
    fun demo() = launchAction("Opening public sample") {
        val job = withContext(Dispatchers.IO) { Api(connection).request("/api/demo", "POST") }
        keepJob(job); openCloudInternal(job)
    }
    private suspend fun openCloudInternal(job: JSONObject) {
        require(job.optString("status") == "completed") { "This result is not ready yet." }
        val data = withContext(Dispatchers.IO) { parseCloud(Api(connection).request("/api/jobs/${job.getString("id")}/cloud")) }
        cloudJob = job; cloud = data
    }
    fun openCloud(job: JSONObject) = launchAction("Loading 3D preview") { openCloudInternal(job) }
    private suspend fun reloadLocalInternal() {
        val inventory = withContext(Dispatchers.IO) { localStore.inventory() }
        localClouds = inventory.records.filter { ExampleVisibility.isVisible(it.id) }
        if (inventory.unreadable > 0) message = "${inventory.unreadable} saved model file(s) could not be read. Their files have been retained."
    }
    fun reloadLocal() = launchAction("Loading phone scans") { reloadLocalInternal() }
    internal fun openLocal(record: LocalCloudRecord) = launchAction("Opening saved 3D scan") {
        val geometry = withContext(Dispatchers.IO) { localStore.load(record.id) }
        cloudJob = JSONObject().put("id", record.id).put("label", record.label).put("local", true).put("capture_mode", record.mode.storedValue)
        cloud = geometry
    }
    fun closeCloud() { cloud = null; cloudJob = null }
    fun cancel(job: JSONObject) = launchAction("Cancelling processing") {
        keepJob(withContext(Dispatchers.IO) { Api(connection).request("/api/jobs/${job.getString("id")}/cancel", "POST") })
    }
    fun retry(job: JSONObject) = launchAction("Retrying processing") {
        keepJob(withContext(Dispatchers.IO) { Api(connection).request("/api/jobs/${job.getString("id")}/retry", "POST") })
    }
    override fun onCleared() { scope.cancel() }
}
