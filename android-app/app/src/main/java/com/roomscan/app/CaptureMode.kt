package com.roomscan.app

internal enum class CaptureMode(val storedValue: String, val title: String) {
    DEPTH("depth", "Depth scan"),
    FULL_DEPTH("full_depth", "Full depth (experimental)"),
    SPARSE_TRACKING("sparse_tracking", "Sparse tracking preview");

    companion object {
        fun fromStored(value: String): CaptureMode = entries.firstOrNull { it.storedValue == value }
            ?: throw IllegalArgumentException("Unsupported capture mode.")
    }
}
