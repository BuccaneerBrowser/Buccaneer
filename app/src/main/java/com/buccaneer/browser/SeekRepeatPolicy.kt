package com.buccaneer.browser

/** Remote repeat frequency varies by model. Use elapsed hold time, not repeat count. */
internal class SeekRepeatPolicy {
    private var key = -1
    private var started = -1L
    private var lastStep = -1L
    fun reset() { key = -1; started = -1L; lastStep = -1L }
    fun step(code: Int, repeat: Int, downTime: Long, eventTime: Long): Int {
        if (repeat == 0) {
            key = code; started = downTime; lastStep = eventTime
            return 10
        }
        if (key != code || started != downTime || eventTime < lastStep) return 0
        val held = (eventTime - started).coerceAtLeast(0L)
        if (held < 600L || eventTime - lastStep < 900L) return 0
        lastStep = eventTime
        return if (held >= 2500L) 60 else 30
    }
}
