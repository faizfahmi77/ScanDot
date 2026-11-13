package com.roomscan.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class AppModel(application: Application) : AndroidViewModel(application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Mutex()
    val store = ScanStore(application)
    private val localStore = LocalCloudStore(application.filesDir)
    internal var localClouds by mutableStateOf<List<LocalCloudRecord>>(emptyList()); private set
    var scans by mutableStateOf<List<Scan>>(emptyList()); private set
    var loaded by mutableStateOf(false); private set
    var message by mutableStateOf("")
    var busy by mutableStateOf(""); private set
    internal var cloud by mutableStateOf<CloudGeometry?>(null); private set
    var cloudJob by mutableStateOf<JSONObject?>(null); private set

    init {
        scope.launch {
            try {
                scans = withContext(Dispatchers.IO) { store.load() }
                reloadLocalInternal()
            } catch (error: Exception) {
                message = "Saved scans could not be loaded: ${error.message}. Files have been retained."
            } finally { loaded = true }
        }
    }

    private fun launchAction(label: String, task: suspend () -> Unit) {
        if (busy.isNotEmpty()) return
        scope.launch {
            busy = label
            try { task() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { message = error.message ?: "Something went wrong. Please try again." }
            finally { busy = "" }
        }
    }

    fun recover(scan: Scan) = launchAction("Checking saved video") {
        val checked = withContext(Dispatchers.IO) { store.inspect(scan) }
        lock.withLock {
            val next = scans.map { if (it.id == scan.id) checked.copy(status = "ready", error = "") else it }
            withContext(Dispatchers.IO) { store.save(next) }
            scans = next
        }
        message = "Recovered video kept. Check playback before keeping it."
    }

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
    override fun onCleared() { scope.cancel() }
}
