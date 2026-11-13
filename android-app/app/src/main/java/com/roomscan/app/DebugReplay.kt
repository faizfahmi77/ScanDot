package com.roomscan.app

import java.io.File

/** Only a debug build can request the fixed private ARCore test recording. */
internal object DebugReplay {
    const val EXTRA_MODE = "replay_mode"
    const val EXTRA_ENABLED = "public_recording_replay"

    fun requestedMode(debug: Boolean, value: String?): CaptureMode? =
        if (debug && value != null) CaptureMode.fromStored(value) else null

    fun fixture(filesDir: File): File {
        val file = File(filesDir.canonicalFile, "public-tests/replay.mp4")
        require(file.canonicalFile == file.absoluteFile && file.isFile && file.canRead() && file.length() > 0L) {
            "Public ARCore recording is missing or unreadable. Copy the test fixture to files/public-tests/replay.mp4 and launch a new replay test."
        }
        return file
    }
}

/** A replay is a single run. Pausing must never resume after skipped recorded sensor data. */
internal class ReplayRun(restored: Boolean = false) {
    enum class State { READY, RUNNING, FINISHED, INTERRUPTED, STOPPED, FAILED }
    @Volatile var state = if (restored) State.INTERRUPTED else State.READY
        private set
    val terminal get() = state != State.READY && state != State.RUNNING

    @Synchronized fun start(): Boolean {
        if (state != State.READY) return false
        state = State.RUNNING
        return true
    }

    @Synchronized fun stop(reason: State): Boolean {
        require(reason != State.READY && reason != State.RUNNING)
        if (terminal) return false
        state = reason
        return true
    }
}
