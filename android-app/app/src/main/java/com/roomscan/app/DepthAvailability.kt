package com.roomscan.app

/** Counts only tracked capture time without a fresh depth frame, excluding tracking gaps. */
internal class DepthAvailability(private val timeoutMs: Long = 15_000L) {
    private var previousTrackedTime: Long? = null
    private var waitingMs = 0L

    init { require(timeoutMs > 0L) }

    fun reset() { previousTrackedTime = null; waitingMs = 0L }

    fun update(nowMs: Long, tracking: Boolean, freshDepth: Boolean = false): Boolean {
        require(nowMs >= 0L)
        if (freshDepth) waitingMs = 0L
        else if (tracking) previousTrackedTime?.let { waitingMs += (nowMs - it).coerceAtLeast(0L) }
        previousTrackedTime = if (tracking) nowMs else null
        return waitingMs >= timeoutMs
    }
}
