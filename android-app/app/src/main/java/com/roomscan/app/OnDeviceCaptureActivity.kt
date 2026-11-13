package com.roomscan.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.Image
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.PlaybackStatus
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

/** Live, local-only capture. Each activity owns one mode and one ARCore coordinate space. */
class OnDeviceCaptureActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PROJECT = "project"
        const val EXTRA_LOCATION = "location"
        const val EXTRA_LABEL = "label"
        const val EXTRA_SCAN_ID = "scan_id"
        const val EXTRA_CAPTURE_MODE = "capture_mode"
    }

    // Mode is fixed for this Activity. To change it, return home (discard/save first)
    // and open a new Activity/session, so world coordinates can never be mixed.
    private val captureMode by lazy {
        intent.getStringExtra(EXTRA_CAPTURE_MODE)?.let(CaptureMode::fromStored) ?: CaptureMode.DEPTH
    }
    private val sparseMode get() = captureMode == CaptureMode.SPARSE_TRACKING
    private val fullDepthMode get() = captureMode == CaptureMode.FULL_DEPTH
    private val replay by lazy { BuildConfig.DEBUG && intent.getBooleanExtra(DebugReplay.EXTRA_ENABLED, false) }
    private var replayRun = ReplayRun()
    private val fusion = DepthFusion()
    private val sparseFusion = SparsePointFusion()
    private val depthAvailability = DepthAvailability()
    private val pointCount get() = if (sparseMode) sparseFusion.pointCount else fusion.pointCount
    private val maxPoints get() = if (sparseMode) sparseFusion.maxPoints else fusion.maxPoints
    private val full get() = if (sparseMode) sparseFusion.full else fusion.full
    private fun snapshot() = if (sparseMode) sparseFusion.snapshot() else fusion.snapshot()
    private val captureLock = Any()
    private val writer = Executors.newSingleThreadExecutor()
    private lateinit var preview: GLSurfaceView
    private lateinit var renderer: CaptureRenderer
    private lateinit var status: TextView
    private lateinit var captureButton: Button
    private lateinit var saveButton: Button
    private lateinit var discardButton: Button
    @Volatile private var arSession: Session? = null
    @Volatile private var sessionRunning = false
    @Volatile private var capturing = false
    @Volatile private var saving = false
    @Volatile private var destroyed = false
    private var resumed = false
    private var userRequestedInstall = true
    private var permissionRequested = false
    private var hasStarted = false
    private var availabilityPending = false
    @Volatile private var lastMessage = "Checking camera and ARCore…"
    @Volatile private var statusRevision = 0L
    private var previousCaptureLost = false

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && resumed) startSession()
        else showStatus("Camera permission is required. Tap Retry to grant access or open app settings.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previousCaptureLost = savedInstanceState?.getBoolean("had_unsaved_points") == true
        if (replay) replayRun = ReplayRun(restored = savedInstanceState != null)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        renderer = CaptureRenderer()
        preview = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            contentDescription = if (replay) "Public recording replay for ${captureMode.title.lowercase()}" else "Live camera preview for ${captureMode.title.lowercase()}"
        }
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF101828.toInt()) }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(0xE6101828.toInt())
        }
        panel.addView(TextView(this).apply {
            text = if (replay) "Public replay · ${captureMode.title}" else captureMode.title
            textSize = 21f
            setTextColor(0xFF53DBC5.toInt())
        })
        panel.addView(TextView(this).apply {
            text = if (replay) "Replaying the public Pixel9 AR recording, not the live camera. Capture starts automatically and stops at the end. Save the result before leaving. Backgrounding interrupts this test; a new test must start from the beginning.\n" + if (sparseMode) "Sparse visual feature points, not reconstructed surfaces. Coverage and dimensions are unverified." else "Experimental depth estimate. Coverage and dimensions are unverified." else if (sparseMode) "Capture sparse visual feature points by moving slowly around textured, well-lit objects. These points show camera tracking details, not reconstructed surfaces. Blank walls may have few or no points.\nExperimental preview: gaps and tracking drift are possible. Not validated for measurements. Saved only on this phone. Save before closing; unsaved points can be lost." else "Move slowly around static surfaces, about 0.5–5 m away. Revisit details from different angles. Glass, blank walls and moving objects may be missing.\nExperimental point cloud: incomplete surfaces and tracking drift are possible. Not validated for measurements. Saved only on this phone. Save before closing: unsaved points are lost if Android stops the app."
            textSize = 13f
            setTextColor(0xFFEAF0F6.toInt())
        })
        status = TextView(this).apply { setTextColor(0xFFFFC876.toInt()); textSize = 14f; setPadding(0, dp(10), 0, dp(8)) }
        panel.addView(status)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        captureButton = Button(this).apply { text = "Start"; setOnClickListener { toggleCapture() } }
        saveButton = Button(this).apply { text = "Save"; setOnClickListener { saveScan() } }
        discardButton = Button(this).apply { text = "Discard"; setOnClickListener { confirmDiscard() } }
        listOf(captureButton, saveButton, discardButton).forEach { buttons.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        panel.addView(buttons)
        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (!saving) confirmDiscard() }
        })
        refreshButtons()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        startSession()
    }

    override fun onPause() {
        resumed = false
        val interrupted = synchronized(captureLock) {
            capturing = false
            replay && replayRun.state == ReplayRun.State.RUNNING && replayRun.stop(ReplayRun.State.INTERRUPTED)
        }
        // Stop the GL thread before pausing the session: update() must never race pause().
        preview.onPause()
        if (sessionRunning) {
            arSession?.pause()
            sessionRunning = false
        }
        if (interrupted) renderer.logReplaySummary("INTERRUPTED")
        if (replay && replayRun.terminal && !saving) showStatus(replayEndMessage())
        else if (hasStarted && !saving) showStatus("Scan paused. Return to a familiar view, wait for tracking, then tap Resume. Tracking changes may cause drift.")
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("had_unsaved_points", pointCount > 0)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        destroyed = true
        arSession?.close()
        arSession = null
        writer.shutdown()
        super.onDestroy()
    }

    private fun startSession() {
        if (!resumed || destroyed || saving || sessionRunning) return
        if (replay && replayRun.terminal) { showStatus(replayEndMessage()); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!permissionRequested) {
                permissionRequested = true
                permission.launch(Manifest.permission.CAMERA)
            } else showStatus("Camera permission is required. Tap Retry to grant access or open app settings.")
            return
        }
        if (arSession == null) {
            val availability = ArCoreApk.getInstance().checkAvailability(this)
            if (availability.isTransient) {
                if (!availabilityPending) {
                    availabilityPending = true
                    preview.postDelayed({ availabilityPending = false; if (resumed && !destroyed) startSession() }, 500)
                }
                showStatus("Checking ARCore support. First-time setup may need an internet connection.")
                return
            }
            if (!availability.isSupported) {
                showStatus("ARCore is unavailable on this device. Phone capture requires supported camera tracking.")
                return
            }
        }
        try {
            if (arSession == null) {
                if (ArCoreApk.getInstance().requestInstall(this, userRequestedInstall) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    userRequestedInstall = false
                    showStatus("Install or update Google Play Services for AR, then return here.")
                    return
                }
                val created = Session(this)
                try {
                    // The dataset's camera profile must be applied before support/config queries.
                    if (replay) created.setPlaybackDatasetUri(Uri.fromFile(DebugReplay.fixture(filesDir)))
                    val requestedDepthMode = if (fullDepthMode) Config.DepthMode.AUTOMATIC else Config.DepthMode.RAW_DEPTH_ONLY
                    if (!sparseMode && !created.isDepthModeSupported(requestedDepthMode)) {
                        created.close()
                        if (replay) stopReplay(ReplayRun.State.FAILED, "Depth is unsupported for this recording's camera profile. Launch a separate sparse_tracking replay test.")
                        else showStatus("This phone does not support this depth mode. Try the other scan setting or a supported device.")
                        return
                    }
                    created.configure(Config(created).apply {
                        depthMode = if (sparseMode) Config.DepthMode.DISABLED else requestedDepthMode
                        focusMode = Config.FocusMode.AUTO
                        updateMode = Config.UpdateMode.BLOCKING
                        planeFindingMode = Config.PlaneFindingMode.DISABLED
                        lightEstimationMode = Config.LightEstimationMode.DISABLED
                    })
                } catch (error: Exception) { created.close(); throw error }
                arSession = created
            }
            arSession!!.resume()
            sessionRunning = true
            if (replay) synchronized(captureLock) {
                check(replayRun.start()) { "Replay cannot resume. Launch a new test from the beginning." }
                capturing = true; hasStarted = true; depthAvailability.reset()
            }
            renderer.cameraFailed = false
            preview.onResume()
            showStatus(when {
                replay -> "Replaying public Pixel9 recording. Capturing ${captureMode.title.lowercase()} automatically."
                previousCaptureLost -> "Android restarted this screen; the previous unsaved capture was lost. Tap Start for a new scan."
                hasStarted -> "Paused. Wait for tracking, then tap Resume. Tracking changes may cause drift."
                else -> "Aim at a textured surface in good light. Tap Start when ready."
            })
        } catch (error: Exception) {
            if (replay) {
                Log.e("RoomScanReplay", "Replay setup failed: mode=${captureMode.storedValue}", error)
                stopReplay(ReplayRun.State.FAILED, "Public recording replay could not start: ${error.localizedMessage ?: error.javaClass.simpleName}. Verify the ARCore recording fixture and launch a new test. Live capture was not started.")
                return
            }
            val message = when (error) {
                is UnavailableUserDeclinedInstallationException -> "ARCore installation was declined. Tap Retry to try setup again."
                is UnavailableArcoreNotInstalledException, is UnavailableApkTooOldException -> "Install or update Google Play Services for AR, then tap Retry."
                is UnavailableSdkTooOldException -> "This app's ARCore SDK needs an update for this phone."
                is UnavailableDeviceNotCompatibleException -> "This device is not compatible with ARCore."
                is CameraNotAvailableException -> "Camera is unavailable. Close other camera apps, then tap Retry."
                else -> "Could not start capture: ${error.localizedMessage ?: error.javaClass.simpleName}"
            }
            showStatus(message)
        }
    }

    private fun toggleCapture() {
        if (saving) return
        if (replay) {
            if (replayRun.state == ReplayRun.State.RUNNING) stopReplay(ReplayRun.State.STOPPED, replayEndMessage(ReplayRun.State.STOPPED))
            return
        }
        if (!sessionRunning) {
            userRequestedInstall = true
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) permission.launch(Manifest.permission.CAMERA)
                else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } else startSession()
            return
        }
        synchronized(captureLock) {
            if (full) { showStatus("Point limit reached. Save this scan and start another for more coverage."); return }
            capturing = !capturing
            if (capturing) { hasStarted = true; previousCaptureLost = false; depthAvailability.reset() }
        }
        showStatus(if (capturing) if (sparseMode) "Capturing sparse feature points. Move slowly around textured objects." else "Capturing. Move slowly sideways to build depth." else "Paused. Tap Resume to continue or Save to keep this cloud.")
    }

    private fun saveScan() {
        if (replay && replayRun.state == ReplayRun.State.RUNNING) stopReplay(ReplayRun.State.STOPPED, replayEndMessage(ReplayRun.State.STOPPED))
        synchronized(captureLock) {
            if (saving || pointCount == 0) return
            capturing = false
            saving = true
        }
        showStatus("Saving this point cloud on the phone…")
        val project = intent.getStringExtra(EXTRA_PROJECT).orEmpty().ifBlank { "Room test" }
        val location = intent.getStringExtra(EXTRA_LOCATION).orEmpty().ifBlank { "My room" }
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { captureMode.title }
        writer.execute {
            val outcome = runCatching {
                // captureLock prevents any integration from starting after this frozen snapshot.
                val cloud = synchronized(captureLock) { snapshot() }
                LocalCloudStore(filesDir).save(project, location, label, cloud.points, cloud.colors, captureMode)
            }
            runOnUiThread {
                if (!destroyed && !isFinishing) {
                    saving = false
                    outcome.fold(onSuccess = { id ->
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_SCAN_ID, id))
                        finish()
                    }, onFailure = { error ->
                        showStatus("Save failed: ${error.localizedMessage ?: "storage unavailable"}. Your points remain here. Free space and tap Save again.")
                    })
                }
            }
        }
    }

    private fun confirmDiscard() {
        if (saving) return
        if (replay && replayRun.state == ReplayRun.State.RUNNING) stopReplay(ReplayRun.State.STOPPED, replayEndMessage(ReplayRun.State.STOPPED))
        synchronized(captureLock) { capturing = false }
        refreshButtons()
        if (pointCount == 0) { setResult(RESULT_CANCELED); finish(); return }
        showStatus("Paused. Save your scan or explicitly discard it.")
        AlertDialog.Builder(this).setTitle("Discard this scan?")
            .setMessage("${pointCount} captured points have not been saved.")
            .setPositiveButton("Discard") { _, _ -> setResult(RESULT_CANCELED); finish() }
            .setNegativeButton("Keep scanning", null).show()
    }

    private fun showStatus(message: String) {
        if (destroyed) return
        statusRevision++
        lastMessage = message
        val details = if (sparseMode) "sparse feature tracks · neutral color" else "2.5 cm sampling · 0.25–5 m depth"
        status.text = "$message\n$pointCount / $maxPoints points · $details"
        refreshButtons()
    }

    private fun refreshButtons() {
        captureButton.text = if (replay) if (replayRun.terminal) "Test stopped" else "Stop test" else when { !sessionRunning -> "Retry"; capturing -> "Pause"; hasStarted -> "Resume"; else -> "Start" }
        captureButton.isEnabled = !saving && !full && (!replay || replayRun.state == ReplayRun.State.RUNNING)
        saveButton.isEnabled = !saving && pointCount > 0
        discardButton.isEnabled = !saving
    }

    private fun replayEndMessage(state: ReplayRun.State = replayRun.state): String = when (state) {
        ReplayRun.State.FINISHED -> "Public recording finished. Save the captured points or discard this test."
        ReplayRun.State.INTERRUPTED -> "Public replay interrupted by leaving or restarting this screen. Save any partial points, or discard and launch a new test from the beginning."
        ReplayRun.State.STOPPED -> "Public replay stopped early. Save any partial points, or discard and launch a new test from the beginning."
        else -> "Public replay failed. Save any partial points, or discard and verify the recording before launching a new test."
    }

    private fun stopReplay(reason: ReplayRun.State, message: String) {
        synchronized(captureLock) {
            if (!replayRun.stop(reason)) return
            capturing = false
        }
        runOnUiThread {
            if (!destroyed) {
                preview.onPause()
                if (sessionRunning) { arSession?.pause(); sessionRunning = false }
                renderer.logReplaySummary(reason.name)
                showStatus(message)
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private inner class CaptureRenderer : GLSurfaceView.Renderer {
        private var texture = 0
        private var program = 0
        private var positionAttribute = 0
        private var uvAttribute = 0
        private var width = 1
        private var height = 1
        private var lastDepthAttempt = 0L
        private var lastUiUpdate = 0L
        private var lastNewDepth = 0L
        private var receivedDepthFrames = 0L
        private var unavailableDepthAttempts = 0L
        private var repeatedDepthFrames = 0L
        private var validDepthPixels = 0L
        private var sampledDepthPixels = 0L
        private var replayFrames = 0L
        private var replayTrackingFrames = 0L
        private var firstReplayTimestamp = 0L
        private var lastReplayTimestamp = 0L
        private var lastReplayLog = 0L
        private var sparseClouds = 0L
        private var freshSparseClouds = 0L
        private var sparseSamples = 0L
        @Volatile var cameraFailed = false
        private val vertices = floats(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        private val uv = floats(FloatArray(8))
        private val textureCorners = floats(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f))
        private val imageCorners = floats(FloatArray(6))
        private val poseMatrix = FloatArray(16)
        private val power by lazy { getSystemService(PowerManager::class.java) }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            try {
                val textures = IntArray(1)
                GLES20.glGenTextures(1, textures, 0)
                texture = textures[0]
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                program = linkProgram()
                positionAttribute = GLES20.glGetAttribLocation(program, "position")
                uvAttribute = GLES20.glGetAttribLocation(program, "cameraUv")
            } catch (error: RuntimeException) { failCamera("Camera preview could not start: ${error.message}") }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            this.width = width.coerceAtLeast(1)
            this.height = height.coerceAtLeast(1)
            GLES20.glViewport(0, 0, width, height)
        }

        @Suppress("DEPRECATION")
        override fun onDrawFrame(gl: GL10?) {
            val session = arSession ?: return
            if (!sessionRunning || cameraFailed || program == 0) return
            if (replay && replayRun.terminal) return
            try {
                if (replay && checkReplayEnd(session)) return
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                // Query rotation every frame, including rotations that preserve the view size.
                session.setDisplayGeometry(windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_0, width, height)
                session.setCameraTextureName(texture)
                val frame = session.update()
                if (replay && checkReplayEnd(session)) return
                if (frame.timestamp == 0L) return
                if (replay && frame.timestamp != lastReplayTimestamp) {
                    if (firstReplayTimestamp == 0L) firstReplayTimestamp = frame.timestamp
                    lastReplayTimestamp = frame.timestamp
                    replayFrames++
                    if (frame.camera.trackingState == TrackingState.TRACKING) replayTrackingFrames++
                }
                vertices.position(0); uv.position(0)
                frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, vertices, Coordinates2d.TEXTURE_NORMALIZED, uv)
                drawBackground()
                val now = android.os.SystemClock.elapsedRealtime()
                if (replay && now - lastReplayLog >= 2000) {
                    lastReplayLog = now
                    logReplaySummary("RUNNING")
                }
                val revision = statusRevision
                var message = lastMessage
                val wasCapturing = capturing
                synchronized(captureLock) {
                    if (capturing) {
                        message = when {
                            Build.VERSION.SDK_INT >= 29 && power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> {
                                capturing = false
                                "Phone is hot. Capture paused; allow it to cool before resuming."
                            }
                            frame.camera.trackingState != TrackingState.TRACKING -> {
                                depthAvailability.update(now, tracking = false)
                                "Tracking paused (${frame.camera.trackingFailureReason}). Return to a familiar textured view; no points added."
                            }
                            else -> {
                                var freshDepth = false
                                if (now - lastDepthAttempt >= 200) {
                                    lastDepthAttempt = now
                                    if (sparseMode) integrateSparse(frame)
                                    else if (integrate(frame)) { lastNewDepth = now; freshDepth = true }
                                }
                                val depthTimedOut = !sparseMode && depthAvailability.update(now, tracking = true, freshDepth = freshDepth)
                                when {
                                    full -> { capturing = false; "Point limit reached. Save this scan; start another for more coverage." }
                                    depthTimedOut -> {
                                        capturing = false
                                        Log.w("RoomScanCapture", "No fresh depth for 15s tracking: received=$receivedDepthFrames unavailable=$unavailableDepthAttempts repeated=$repeatedDepthFrames points=$pointCount")
                                        "No fresh depth for 15 seconds of tracking. Capture paused. Try brighter light and slow sideways motion, then Resume. If depth remains unavailable, try the other scan setting."
                                    }
                                    sparseMode && pointCount == 0 -> "Waiting for sparse feature points. Move slowly around textured objects in good light."
                                    sparseMode -> "Capturing sparse feature points, not reconstructed surfaces. Move slowly around textured objects."
                                    lastNewDepth == 0L || now - lastNewDepth > 3000 -> "Waiting for fresh depth. Move slowly sideways near textured, well-lit static surfaces."
                                    fullDepthMode -> "Capturing interpolated depth. Move slowly around static surfaces; geometry may be inaccurate."
                                    else -> "Capturing depth and available camera colors. Move slowly around the surfaces."
                                }
                            }
                        }
                    }
                }
                if (replay && wasCapturing && !capturing) {
                    stopReplay(ReplayRun.State.STOPPED, "$message Public replay stopped early; save any partial points or launch a new test.")
                    return
                }
                if ((wasCapturing && !capturing) || now - lastUiUpdate >= 500) {
                    lastUiUpdate = now
                    val update = message
                    runOnUiThread { if (!destroyed && !saving && resumed && statusRevision == revision) showStatus(update) }
                }
            } catch (_: CameraNotAvailableException) {
                failCamera("Camera is unavailable. Close other camera apps, then tap Retry. Captured points remain here.")
            } catch (error: RuntimeException) {
                failCamera("Capture stopped: ${error.localizedMessage ?: error.javaClass.simpleName}. Save the existing points or retry.")
            }
        }

        private fun integrateSparse(frame: Frame) {
            frame.acquirePointCloud().use { cloud ->
                sparseClouds++
                sparseSamples += cloud.points.remaining() / 4
                if (cloud.timestamp > sparseFusion.lastTimestamp) freshSparseClouds++
                sparseFusion.integrate(cloud.timestamp, cloud.points, cloud.ids)
            }
        }

        private fun checkReplayEnd(session: Session): Boolean {
            when (session.playbackStatus) {
                PlaybackStatus.FINISHED -> stopReplay(ReplayRun.State.FINISHED, replayEndMessage(ReplayRun.State.FINISHED))
                PlaybackStatus.IO_ERROR -> stopReplay(ReplayRun.State.FAILED, "Public recording playback stopped with a read error. Save any partial points; verify the ARCore recording and launch a new test.")
                PlaybackStatus.NONE -> stopReplay(ReplayRun.State.FAILED, "Public recording playback did not activate. This test is stopped. Verify the ARCore recording and launch a new test.")
                else -> return false
            }
            return true
        }

        fun logReplaySummary(outcome: String) {
            Log.i("RoomScanReplay", "outcome=$outcome mode=${captureMode.storedValue} frames=$replayFrames trackingFrames=$replayTrackingFrames recordingSpanNs=${lastReplayTimestamp - firstReplayTimestamp} sparseClouds=$sparseClouds freshSparseClouds=$freshSparseClouds sparseSamples=$sparseSamples depthReceived=$receivedDepthFrames depthUnavailable=$unavailableDepthAttempts depthRepeated=$repeatedDepthFrames validDepthPixels=$validDepthPixels sampledDepthPixels=$sampledDepthPixels points=$pointCount")
        }

        private fun integrate(frame: Frame): Boolean {
            try {
                (if (fullDepthMode) frame.acquireDepthImage16Bits() else frame.acquireRawDepthImage16Bits()).use { depth ->
                    // Raw timestamps identify new source data. Images are reprojected to this
                    // frame's viewpoint, even when their source timestamp differs from the frame.
                    receivedDepthFrames++
                    if (depth.timestamp <= fusion.lastTimestamp) { repeatedDepthFrames++; return false }
                    (if (fullDepthMode) null else frame.acquireRawDepthConfidenceImage()).use { confidence ->
                        if (confidence != null && (depth.width != confidence.width || depth.height != confidence.height)) return false
                        val intrinsics = frame.camera.textureIntrinsics
                        val dimensions = intrinsics.imageDimensions
                        val focal = intrinsics.focalLength
                        val principal = intrinsics.principalPoint
                        val scaled = DepthFusion.Intrinsics(
                            focal[0] * depth.width / dimensions[0], focal[1] * depth.height / dimensions[1],
                            principal[0] * depth.width / dimensions[0], principal[1] * depth.height / dimensions[1],
                        )
                        frame.camera.pose.toMatrix(poseMatrix, 0)
                        var cameraImage: Image? = null
                        try {
                            val colorAt = try {
                                frame.acquireCameraImage().also { cameraImage = it }.let { image ->
                                    colorSampler(frame, image, depth.width, depth.height)
                                }
                            } catch (_: NotYetAvailableException) { null }
                            fusion.integrate(depth.timestamp, depth.width, depth.height,
                                depth.planes[0].buffer, depth.planes[0].rowStride, depth.planes[0].pixelStride,
                                confidence?.planes?.get(0)?.buffer, confidence?.planes?.get(0)?.rowStride ?: 0, confidence?.planes?.get(0)?.pixelStride ?: 0,
                                scaled, poseMatrix, colorAt)
                            fusion.lastFrameStats?.let { stats ->
                                validDepthPixels += stats.validPixels
                                sampledDepthPixels += stats.sampledPixels
                                if (replay && validDepthPixels == stats.validPixels.toLong()) {
                                    Log.i("RoomScanReplay", "depthSize=${stats.width}x${stats.height} inspected=${stats.pixelsInspected} inspectionLimited=${stats.inspectionLimited}")
                                }
                            }
                        } finally { cameraImage?.close() }
                        return true
                    }
                }
            } catch (_: NotYetAvailableException) { unavailableDepthAttempts++; return false }
        }

        private fun colorSampler(frame: Frame, image: Image, depthWidth: Int, depthHeight: Int): (Int, Int) -> Int {
            textureCorners.position(0); imageCorners.position(0)
            frame.transformCoordinates2d(Coordinates2d.TEXTURE_NORMALIZED, textureCorners, Coordinates2d.IMAGE_PIXELS, imageCorners)
            val x0 = imageCorners.get(0); val y0 = imageCorners.get(1)
            val dxX = imageCorners.get(2) - x0; val dxY = imageCorners.get(3) - y0
            val dyX = imageCorners.get(4) - x0; val dyY = imageCorners.get(5) - y0
            val planes = image.planes
            val buffers = planes.map { it.buffer.duplicate() }
            val bases = buffers.map { it.position() }
            return { u, v ->
                val s = u.toFloat() / depthWidth; val t = v.toFloat() / depthHeight
                val x = (x0 + dxX * s + dyX * t).roundToInt().coerceIn(0, image.width - 1)
                val y = (y0 + dxY * s + dyY * t).roundToInt().coerceIn(0, image.height - 1)
                fun channel(index: Int, px: Int, py: Int): Int {
                    val offset = bases[index] + py * planes[index].rowStride + px * planes[index].pixelStride
                    return if (offset in bases[index] until buffers[index].limit()) buffers[index].get(offset).toInt() and 255 else 128
                }
                val luminance = (channel(0, x, y) - 16).coerceAtLeast(0) * 1.164f
                val cb = channel(1, x / 2, y / 2) - 128f
                val cr = channel(2, x / 2, y / 2) - 128f
                val r = (luminance + 1.596f * cr).roundToInt().coerceIn(0, 255)
                val g = (luminance - 0.392f * cb - 0.813f * cr).roundToInt().coerceIn(0, 255)
                val b = (luminance + 2.017f * cb).roundToInt().coerceIn(0, 255)
                (r shl 16) or (g shl 8) or b
            }
        }

        private fun failCamera(message: String) {
            if (replay) {
                stopReplay(ReplayRun.State.FAILED, "$message Public replay failed; verify the recording and launch a new test.")
                return
            }
            cameraFailed = true
            synchronized(captureLock) { capturing = false }
            runOnUiThread {
                if (!destroyed) {
                    preview.onPause()
                    if (sessionRunning) { arSession?.pause(); sessionRunning = false }
                    showStatus(message)
                }
            }
        }

        private fun drawBackground() {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "cameraTexture"), 0)
            vertices.position(0); uv.position(0)
            GLES20.glVertexAttribPointer(positionAttribute, 2, GLES20.GL_FLOAT, false, 0, vertices)
            GLES20.glVertexAttribPointer(uvAttribute, 2, GLES20.GL_FLOAT, false, 0, uv)
            GLES20.glEnableVertexAttribArray(positionAttribute)
            GLES20.glEnableVertexAttribArray(uvAttribute)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(positionAttribute)
            GLES20.glDisableVertexAttribArray(uvAttribute)
        }

        private fun linkProgram(): Int {
            fun shader(type: Int, source: String): Int {
                val id = GLES20.glCreateShader(type)
                GLES20.glShaderSource(id, source); GLES20.glCompileShader(id)
                val result = IntArray(1)
                GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, result, 0)
                if (result[0] == 0) { val log = GLES20.glGetShaderInfoLog(id); GLES20.glDeleteShader(id); error(log) }
                return id
            }
            val vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec2 position; attribute vec2 cameraUv; varying vec2 uv; void main(){ gl_Position=vec4(position,0.0,1.0); uv=cameraUv; }")
            val fragment = shader(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES cameraTexture; varying vec2 uv; void main(){ gl_FragColor=texture2D(cameraTexture,uv); }")
            val id = GLES20.glCreateProgram()
            GLES20.glAttachShader(id, vertex); GLES20.glAttachShader(id, fragment); GLES20.glLinkProgram(id)
            GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
            val result = IntArray(1)
            GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, result, 0)
            if (result[0] == 0) { val log = GLES20.glGetProgramInfoLog(id); GLES20.glDeleteProgram(id); error(log) }
            return id
        }

        private fun floats(values: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
    }
}
