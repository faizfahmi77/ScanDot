package com.roomscan.app

import android.Manifest
import android.content.pm.PackageManager
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle

class CameraController(private val activity: ComponentActivity, private val model: AppModel) {
    var ready by mutableStateOf(false); private set
    var active by mutableStateOf(false); private set
    var finalizing by mutableStateOf(false); private set
    var elapsed by mutableStateOf(0L); private set
    var problem by mutableStateOf(""); private set
    private var video: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var provider: ProcessCameraProvider? = null
    private var stoppedReason: String? = null
    var onSaved: (String) -> Unit = {}
    private var generation = 0
    private var startGeneration = 0

    fun prepare(project: String, location: String, label: String) {
        val request = generation
        val startRequest = ++startGeneration
        model.start(project, location, label) { scan ->
            if (generation == request && startGeneration == startRequest && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                start(scan)
            } else {
                model.finish(scan, "Recording cancelled before the camera started. Please try again.")
            }
        }
    }

    fun bind(view: PreviewView) {
        val request = ++generation
        ready = false; problem = ""
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            if (generation != request || activity.isDestroyed) return@addListener
            try {
                val owner = future.get(); provider = owner
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.FHD)).build()
                val capture = VideoCapture.withOutput(recorder)
                capture.targetRotation = view.display?.rotation ?: Surface.ROTATION_0
                owner.unbindAll()
                owner.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                video = capture; ready = true
            } catch (e: Exception) { problem = "Camera could not start at 1080p. ${e.message.orEmpty()}" }
        }, ContextCompat.getMainExecutor(activity))
    }
    private fun start(scan: Scan) {
        val capture = video
        if (!ready || capture == null || active || finalizing || ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            model.finish(scan, "Camera was not ready. Please try again."); return
        }
        stoppedReason = null; elapsed = 0; active = true
        try {
            // Audio is intentionally never enabled; the app requests camera permission only.
            recording = capture.output.prepareRecording(activity, FileOutputOptions.Builder(model.store.file(scan)).setFileSizeLimit(2L * 1024 * 1024 * 1024).build())
                .start(ContextCompat.getMainExecutor(activity)) { event ->
                    when (event) {
                        is VideoRecordEvent.Status -> elapsed = event.recordingStats.recordedDurationNanos / 1_000_000
                        is VideoRecordEvent.Finalize -> {
                            recording?.close(); recording = null; active = false; finalizing = false
                            val issue = stoppedReason ?: if (event.hasError()) "Recording stopped (code ${event.error}). ${event.cause?.message.orEmpty()}" else null
                            model.finish(scan, issue); onSaved(scan.id)
                        }
                    }
                }
        } catch (e: Exception) { active = false; finalizing = false; model.finish(scan, e.message ?: "Recording failed.") }
    }
    fun stop(reason: String? = null) {
        startGeneration++
        if (!active || finalizing) return
        stoppedReason = reason; finalizing = true
        recording?.stop()
    }
    fun unbind() { generation++; stop("Recording stopped when the camera screen closed."); ready = false; provider?.unbindAll(); video = null }
}
