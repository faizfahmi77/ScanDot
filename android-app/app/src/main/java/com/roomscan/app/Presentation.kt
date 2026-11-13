package com.roomscan.app

import java.util.Locale
import org.json.JSONObject

private fun JSONObject.validCount(key: String): Long? = when (val value = opt(key)) {
    is Int -> value.toLong().takeIf { it >= 0 }
    is Long -> value.takeIf { it >= 0 }
    else -> null
}

internal fun resultSummaryLabel(summary: JSONObject): String {
    val points = summary.validCount("point_count")?.let { "$it points" } ?: "Point count unavailable"
    val registered = summary.validCount("registered_images")
    val inputs = summary.validCount("input_frames")
    val alignment = when {
        registered != null && inputs != null && inputs > 0 && registered <= inputs ->
            "Selected model: $registered / $inputs input images aligned"
        registered != null && inputs == null -> "Selected model: $registered images aligned; input count unavailable"
        else -> "Selected model: aligned image count unavailable"
    }
    val candidates = summary.validCount("candidate_model_count") ?: summary.validCount("model_count")
    val candidateLabel = candidates?.let { "\n$it candidate alignment models (may overlap)" }.orEmpty()
    return "$points\n$alignment$candidateLabel\nRoom coverage is unverified."
}

internal fun totalElapsedLabel(job: JSONObject): String? {
    if (job.optString("status") !in listOf("completed", "failed", "cancelled", "interrupted")) return null
    val seconds = (job.opt("total_elapsed_seconds") as? Number)?.toDouble() ?: return null
    if (!seconds.isFinite() || seconds < 0 || seconds >= Long.MAX_VALUE.toDouble()) return null
    val total = seconds.toLong()
    val duration = if (total >= 3600) "${total / 3600}h ${total / 60 % 60}m ${total % 60}s"
        else "${total / 60}m ${total % 60}s"
    return "Total job time: $duration (includes queueing and retries)"
}

/** Translate known processor stages while keeping future server messages visible. */
internal fun processingStageLabel(stage: String): String {
    val normalized = stage.trim().lowercase(Locale.ROOT).replace('_', ' ').removeSuffix(" help")
    return when (normalized) {
        "exhaustive matcher", "sequential matcher", "vocab tree matcher" -> "Connecting camera views"
        "feature extractor" -> "Finding image details"
        "mapper", "reconstructing" -> "Building 3D structure"
        "model analyzer", "model converter", "colmap version" -> "Preparing preview"
        "image undistorter", "patch match stereo", "stereo fusion" -> "Adding surface detail"
        "inspecting video and extracting frames" -> "Preparing video frames"
        "waiting for processor" -> "Waiting for workstation"
        else -> stage
    }
}
