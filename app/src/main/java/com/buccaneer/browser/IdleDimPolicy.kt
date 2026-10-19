package com.buccaneer.browser

internal class IdleDimPolicy {
    companion object { const val TIMEOUT_MS = 20 * 60 * 1000L }
    private var lastActivity = 0L
    fun activity(now: Long) { lastActivity = now }
    fun shouldDim(now: Long, playing: Boolean): Boolean {
        if (playing) { activity(now); return false }
        return now - lastActivity >= TIMEOUT_MS
    }
}
