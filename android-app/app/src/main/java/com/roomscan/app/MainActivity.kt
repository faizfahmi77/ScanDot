package com.roomscan.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.MediaController
import android.widget.VideoView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val Navy = Color(0xFF101828)
private val Slate = Color(0xFF1B293D)
private val Teal = Color(0xFF53DBC5)
private val Amber = Color(0xFFFFC876)
private val Muted = Color(0xFFABB9CD)
private val AppColors = darkColorScheme(primary = Teal, onPrimary = Navy, secondary = Amber, background = Navy, surface = Slate, onSurface = Color(0xFFEEF4FF), surfaceVariant = Color(0xFF26364C))

class MainActivity : ComponentActivity() {
    private lateinit var camera: CameraController
    private val replayLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        ViewModelProvider(this)[AppModel::class.java].reloadLocal()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = ViewModelProvider(this)[AppModel::class.java]
        camera = CameraController(this, model)
        setContent { MaterialTheme(colorScheme = AppColors) { RoomScan(model, camera, this) } }
        if (savedInstanceState == null && BuildConfig.DEBUG && intent.hasExtra(DebugReplay.EXTRA_MODE)) {
            runCatching { DebugReplay.requestedMode(BuildConfig.DEBUG, intent.getStringExtra(DebugReplay.EXTRA_MODE)) }
                .onSuccess { mode ->
                    if (mode != null) replayLauncher.launch(Intent(this, OnDeviceCaptureActivity::class.java)
                        .putExtra(DebugReplay.EXTRA_ENABLED, true)
                        .putExtra(OnDeviceCaptureActivity.EXTRA_CAPTURE_MODE, mode.storedValue)
                        .putExtra(OnDeviceCaptureActivity.EXTRA_PROJECT, "Public video tests")
                        .putExtra(OnDeviceCaptureActivity.EXTRA_LOCATION, "Pixel9 sample")
                        .putExtra(OnDeviceCaptureActivity.EXTRA_LABEL, "Public replay · ${mode.title}"))
                }.onFailure { Toast.makeText(this, "Replay mode must be depth, full_depth or sparse_tracking.", Toast.LENGTH_LONG).show() }
        }
    }
    override fun onStop() { camera.stop("Recording stopped because the app left the foreground. Review the saved portion."); super.onStop() }
}

@Composable private fun RoomScan(model: AppModel, camera: CameraController, activity: ComponentActivity) {
    var tab by rememberSaveable { mutableStateOf("Scans") }
    var page by rememberSaveable { mutableStateOf("home") }
    var selected by rememberSaveable { mutableStateOf("") }
    var project by rememberSaveable { mutableStateOf("Room test") }
    var location by rememberSaveable { mutableStateOf("My room") }
    var label by rememberSaveable { mutableStateOf("Object scan") }
    var fullDepth by rememberSaveable { mutableStateOf(false) }
    val localScanner = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        model.reloadLocal()
        if (result.resultCode == android.app.Activity.RESULT_OK) { tab = "Results"; page = "home" }
    }
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(model.getApplication(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) permission = ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) model.importVideo(uri, project, location, label) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(model.message) {
        val message = model.message
        if (message.isNotEmpty()) {
            snackbar.showSnackbar(message, duration = SnackbarDuration.Long)
            if (model.message == message) model.message = ""
        }
    }
    DisposableEffect(camera) { camera.onSaved = { selected = it; page = "review" }; onDispose { camera.onSaved = {} } }
    BackHandler(enabled = model.cloud != null || page != "home") {
        if (model.cloud != null) model.closeCloud()
        else if (page == "camera" && camera.active) camera.stop()
        else { page = "home" }
    }
    Scaffold(containerColor = Navy, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
        if (page == "home" && model.cloud == null) NavigationBar(containerColor = Navy) {
            listOf("Scans", "Results").forEach { name ->
                NavigationBarItem(selected = tab == name, onClick = { tab = name }, icon = { Text(when (name) { "Scans" -> "▣"; "Results" -> "◇"; else -> "⚙" }) }, label = { Text(name) })
            }
        }
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("SCANDOT", color = Teal, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold); Text(if (model.cloud != null) "3D preview" else if (page == "camera") "Capture a walkthrough" else if (page == "review") "Scan details" else when (tab) { "Results" -> "Saved scans"; else -> "Your spaces, captured." }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                if (page != "home" || model.cloud != null) TextButton(onClick = { if (model.cloud != null) model.closeCloud() else if (camera.active) camera.stop() else page = "home" }, enabled = !camera.finalizing) { Text(if (camera.active) "Stop" else "Back") }
            }
            if (!model.loaded) { LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
            if (model.busy.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp)) {
                    Text(if (model.transferId.isNotEmpty()) if (model.progress < 1f) "Sending video · ${(model.progress * 100).toInt()}%" else "Video sent · waiting for server receipt" else model.busy, style = MaterialTheme.typography.labelMedium, color = Teal)
                    if (model.transferId.isNotEmpty()) LinearProgressIndicator(progress = { model.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            }
            when {
                model.cloud != null -> CloudScreen(model, activity)
                page == "camera" -> CameraScreen(model, camera, permission, { permissionRequest.launch(Manifest.permission.CAMERA) }, project, location, label)
                page == "review" -> {
                    val scan = model.scans.find { it.id == selected }
                    if (scan == null) Text("Saving scan…", Modifier.padding(22.dp)) else ReviewScreen(scan, model)
                }
                tab == "Results" -> ResultsScreen(model) { selected = it; page = "review" }
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Hero()
                    Text("New scan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Field("Scan name", label, { label = it })
                    Text("Move slowly around the object in good light. Saved scans stay on this phone.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = fullDepth, onCheckedChange = { fullDepth = it })
                        Column { Text("Experimental fuller depth"); Text("May fill gaps but can distort surfaces.", color = Muted, style = MaterialTheme.typography.bodySmall) }
                    }
                    Button(onClick = {
                        if (label.isBlank() || label.trim().length > 200) model.message = "Enter a scan name (up to 200 characters)."
                        else localScanner.launch(Intent(activity, OnDeviceCaptureActivity::class.java)
                            .putExtra(OnDeviceCaptureActivity.EXTRA_PROJECT, "Object scans")
                            .putExtra(OnDeviceCaptureActivity.EXTRA_LOCATION, "Local")
                            .putExtra(OnDeviceCaptureActivity.EXTRA_LABEL, label.trim())
                            .putExtra(OnDeviceCaptureActivity.EXTRA_CAPTURE_MODE, if (fullDepth) CaptureMode.FULL_DEPTH.storedValue else CaptureMode.DEPTH.storedValue))
                    }, enabled = model.busy.isEmpty(), modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(18.dp)) { Text("Start scan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                    OutlinedButton(onClick = { tab = "Results" }, modifier = Modifier.fillMaxWidth()) { Text("View saved scans (${model.localClouds.size})") }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

@Composable private fun Hero() {
    Card(colors = CardDefaults.cardColors(containerColor = Slate), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("CAPTURE. EXPLORE. REVIEW.", color = Teal, style = MaterialTheme.typography.labelSmall); Spacer(Modifier.height(8.dp)); Text("Scan an object\nin 3D.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                Canvas(Modifier.size(94.dp)) {
                    val a = Offset(size.width * .12f, size.height * .3f); val b = Offset(size.width * .6f, size.height * .1f); val c = Offset(size.width * .9f, size.height * .35f); val d = Offset(size.width * .42f, size.height * .57f); val down = Offset(0f, size.height * .36f)
                    listOf(a to b, b to c, c to d, d to a, a to a + down, c to c + down, d to d + down, a + down to d + down, d + down to c + down).forEach { (x, y) -> drawLine(Teal, x, y, 2.dp.toPx()) }
                    listOf(a,b,c,d,a+down,c+down,d+down).forEach { drawCircle(Amber, 3.dp.toPx(), it) }
                }
            }
            Spacer(Modifier.height(12.dp)); Text("Capture and view a point cloud on your phone.", color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable private fun CameraScreen(model: AppModel, camera: CameraController, permission: Boolean, request: () -> Unit, project: String, location: String, label: String) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("$project / $location", color = Muted)
        if (permission) {
            AndroidView(factory = { ctx -> PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER; camera.bind(this) } }, modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(22.dp)))
            DisposableEffect(Unit) { onDispose { camera.unbind() } }
        } else {
            InfoCard("Camera access needed", "Allow camera access to record your walkthrough. Audio is always off.")
            Button(onClick = request, modifier = Modifier.fillMaxWidth()) { Text("Allow camera") }
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("Open app permissions") }
            Spacer(Modifier.weight(1f))
        }
        if (camera.problem.isNotEmpty()) Text(camera.problem, color = Amber)
        Row(Modifier.fillMaxWidth()) { Text(if (camera.active) "● RECORDING" else "1080p · AUDIO OFF", color = if (camera.active) Amber else Teal, modifier = Modifier.weight(1f)); Text(formatTime(camera.elapsed), style = MaterialTheme.typography.titleMedium) }
        Text("Walk slowly. Move between viewpoints and revisit corners. Keep surfaces in view; avoid quick turns and dark areas.", color = Muted, style = MaterialTheme.typography.bodyMedium)
        Text("Coverage is not measured during recording.", color = Amber, style = MaterialTheme.typography.bodySmall)
        Button(onClick = { if (camera.active) camera.stop() else camera.prepare(project, location, label) }, enabled = permission && camera.ready && !camera.finalizing && model.busy.isEmpty(), colors = ButtonDefaults.buttonColors(containerColor = if (camera.active) Amber else Teal), modifier = Modifier.fillMaxWidth().height(58.dp)) { Text(if (camera.finalizing) "Saving recording…" else if (camera.active) "Stop & save" else "Begin recording", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable private fun ReviewScreen(scan: Scan, model: AppModel) {
    var playing by remember(scan.id) { mutableStateOf(false) }
    var editing by remember(scan.id) { mutableStateOf(false) }
    var project by remember(scan.id) { mutableStateOf(scan.project) }; var location by remember(scan.id) { mutableStateOf(scan.location) }; var label by remember(scan.id) { mutableStateOf(scan.label) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(scan.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("${scan.project} · ${scan.location}", color = Muted)
        InfoCard(scanState(scan.status), if (scan.duration > 0) "${formatTime(scan.duration)} · ${formatBytes(scan.bytes)} · ${scan.width} × ${scan.height}\nOriginal video kept on this phone." else "Recording metadata is saved. Waiting for video finalization.")
        if (scan.error.isNotBlank()) InfoCard("Needs attention", scan.error, true)
        if (playing) {
            AndroidView(factory = { ctx -> VideoView(ctx).apply { val controls = MediaController(ctx); controls.setAnchorView(this); setMediaController(controls); setVideoPath(model.store.file(scan).absolutePath); setOnErrorListener { _, _, _ -> model.message = "This recording could not be played. It may be incomplete."; true }; setOnPreparedListener { start() } } }, modifier = Modifier.fillMaxWidth().height(280.dp), onRelease = { it.stopPlayback() })
        }
        if (scan.status !in listOf("recording", "uploading")) OutlinedButton(onClick = { playing = !playing }, modifier = Modifier.fillMaxWidth()) { Text(if (playing) "Close playback" else "Play saved video") }
        if (scan.status == "interrupted") Button(onClick = { model.recover(scan) }, enabled = model.busy.isEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Check & keep recovered video") }
        Text("This earlier recording is stored on this phone.", color = Muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable private fun SettingsScreen(model: AppModel) {
    var url by remember(model.connection) { mutableStateOf(model.connection.url) }; var token by remember(model.connection) { mutableStateOf(model.connection.token) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        InfoCard("Optional workstation", "Phone scans and their saved 3D results work without these settings. Connect a workstation only to reconstruct recorded or imported videos, or view server results.")
        Field("Server address", url, { url = it })
        OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Access token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { model.saveConnection(url, token, true) }, enabled = model.busy.isEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Save & test connection") }
        OutlinedButton(onClick = { model.saveConnection(url, token, false) }, enabled = model.busy.isEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Save settings") }
        Text("USB connection: use http://127.0.0.1:8765 while your workstation's USB forwarding is active.\n\nUse a trusted local connection. The default development service uses HTTP.", color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable private fun ResultsScreen(model: AppModel, openVideo: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Saved on this phone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Open these models offline. Point clouds can have gaps and tracking drift; dimensions and coverage are unverified.", color = Muted, style = MaterialTheme.typography.bodySmall)
        if (model.localClouds.isEmpty()) InfoCard("No phone scans yet", "Choose Start scan, move slowly around a small area, then save.")
        model.localClouds.forEach { record ->
            Card(colors = CardDefaults.cardColors(containerColor = Slate), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(record.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${record.project} · ${record.location}", color = Muted)
                    Text("${record.pointCount} points · ${record.mode.title} · saved locally", color = Teal)
                    if (record.mode == CaptureMode.SPARSE_TRACKING) Text("Sparse feature points, not reconstructed surfaces.", color = Amber, style = MaterialTheme.typography.bodySmall)
                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(record.created)), color = Muted, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { model.openLocal(record) }, enabled = model.busy.isEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Open saved 3D scan") }
                }
            }
        }
        val earlierVideos = model.scans.filter { ExampleVisibility.isVisible(it.id) }
        if (earlierVideos.isNotEmpty()) {
            Text("Earlier videos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            earlierVideos.forEach { scan ->
                OutlinedButton(onClick = { openVideo(scan.id) }, modifier = Modifier.fillMaxWidth()) { Text(scan.label) }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable private fun JobCard(job: JSONObject, model: AppModel) {
    val status = job.optString("status"); val summary = job.optJSONObject("summary")
    Card(colors = CardDefaults.cardColors(containerColor = Slate), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(job.optString("label", "Walkthrough"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${job.optString("project")} · ${job.optString("location")}", color = Muted)
            Text(status.replaceFirstChar { it.uppercase() }, color = if (status in listOf("failed", "interrupted", "cancelled")) Amber else Teal, fontWeight = FontWeight.Bold)
            Text(processingStageLabel(job.optString("stage")), style = MaterialTheme.typography.bodyMedium)
            totalElapsedLabel(job)?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
            val error = job.optString("error").takeUnless { it == "null" }.orEmpty()
            if (error.isNotEmpty()) Text(error.take(1200), color = Amber, style = MaterialTheme.typography.bodySmall)
            if (summary != null) {
                Text(resultSummaryLabel(summary), color = Muted, style = MaterialTheme.typography.bodySmall)
                val warnings = summary.optJSONArray("warnings")
                if (warnings != null) for (i in 0 until warnings.length()) Text("• ${warnings.optString(i)}", color = Amber, style = MaterialTheme.typography.bodySmall)
            }
            if (status == "completed") Button(onClick = { model.openCloud(job) }, enabled = model.busy.isEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Open 3D preview") }
            if (status in listOf("failed", "cancelled", "interrupted")) OutlinedButton(onClick = { model.retry(job) }, enabled = model.busy.isEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Retry processing") }
            if (status in listOf("queued", "preparing", "reconstructing")) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = { model.cancel(job) }, enabled = model.busy.isEmpty()) { Text("Cancel processing") } }
        }
    }
}

@Composable private fun CloudScreen(model: AppModel, activity: ComponentActivity) {
    val data = model.cloud ?: return
    var viewer by remember(data) { mutableStateOf<CloudView?>(null) }
    var pointSize by remember(data) { mutableFloatStateOf(2f) }
    DisposableEffect(viewer, activity) {
        val view = viewer
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) view?.onResume()
            if (event == Lifecycle.Event.ON_PAUSE) view?.onPause()
        }
        activity.lifecycle.addObserver(observer)
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view?.onResume() else view?.onPause()
        onDispose { activity.lifecycle.removeObserver(observer); view?.onPause() }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(model.cloudJob?.optString("label").orEmpty(), style = MaterialTheme.typography.titleMedium)
        Text("${data.pointCount} shown / ${data.totalPoints} points\nDrag to rotate · pinch to zoom · two fingers to pan", color = Muted, style = MaterialTheme.typography.bodySmall)
        key(data) {
            AndroidView(factory = { ctx -> CloudView(ctx, data).also { viewer = it } }, modifier = Modifier.fillMaxWidth().weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            CloudPreset.entries.forEach { preset -> TextButton(onClick = { viewer?.preset(preset) }, modifier = Modifier.weight(1f)) { Text(preset.name.lowercase().replaceFirstChar { it.titlecase() }) } }
            TextButton(onClick = { viewer?.turnUpright() }, modifier = Modifier.weight(1f)) { Text("Turn 90°") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { viewer?.zoomBy(.8f) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("Zoom −") }
            OutlinedButton(onClick = { viewer?.fit() }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("Fit") }
            OutlinedButton(onClick = { viewer?.reset() }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("Reset") }
            OutlinedButton(onClick = { viewer?.zoomBy(1.25f) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("Zoom +") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Point size", color = Muted, style = MaterialTheme.typography.labelMedium)
            Slider(value = pointSize, onValueChange = { pointSize = it; viewer?.pointSize(it) }, valueRange = 1f..5f, modifier = Modifier.weight(1f))
        }
        Text(if (model.cloudJob?.optBoolean("local") == true && model.cloudJob?.optString("capture_mode") == CaptureMode.SPARSE_TRACKING.storedValue) "Sparse tracking preview: visual feature points, not reconstructed surfaces. Neutral colors. Coverage and dimensions are unverified; tracking drift and gaps may occur." else if (model.cloudJob?.optBoolean("local") == true && model.cloudJob?.optString("capture_mode") == CaptureMode.FULL_DEPTH.storedValue) "Experimental interpolated depth: surfaces may be distorted. Not for measurement. Coverage and dimensions are unverified; tracking drift and gaps may occur." else if (model.cloudJob?.optBoolean("local") == true) "On-device depth estimate. Coverage and dimensions are unverified; tracking drift and gaps may occur." else "Unscaled preview. Coverage and dimensions are unverified.", color = Amber, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable private fun Field(name: String, value: String, change: (String) -> Unit) { OutlinedTextField(value = value, onValueChange = { change(it.take(200)) }, label = { Text(name) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) }
@Composable private fun InfoCard(title: String, text: String, warning: Boolean = false) { Card(colors = CardDefaults.cardColors(containerColor = Slate), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) { Text(title, fontWeight = FontWeight.Bold, color = if (warning) Amber else Teal); Text(text, color = Muted, style = MaterialTheme.typography.bodyMedium) } } }
private fun scanState(status: String) = when (status) { "recording" -> "Saving recording"; "ready" -> "Ready on this phone"; "uploading" -> "Transferring"; "submitted" -> "Sent to workstation"; "interrupted" -> "Review interrupted recording"; "failed" -> "Recording needs attention"; else -> status }
private fun formatTime(ms: Long) = String.format(Locale.ROOT, "%02d:%02d", ms / 60_000, ms / 1000 % 60)
private fun formatBytes(bytes: Long) = if (bytes >= 1024L * 1024 * 1024) String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024)) else "${bytes / (1024 * 1024)} MB"
