package com.buccaneer.browser

/** Pure retry policy, independent of page-finished timing and WebView availability. */
internal object CrashRecoveryPolicy {
    const val WINDOW_MS = 600_000L
    fun expired(started: Long, now: Long): Boolean = started <= 0L || now - started !in 0L..WINDOW_MS
    // Redirects must not turn every renderer crash into a new retry budget.
    @Suppress("UNUSED_PARAMETER")
    fun nextCount(url: String, previousUrl: String?, started: Long, count: Int, now: Long): Int =
        if (!expired(started, now)) count.coerceIn(0, 2) + 1 else 1
}
