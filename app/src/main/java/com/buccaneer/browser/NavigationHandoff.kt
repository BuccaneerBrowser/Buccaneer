package com.buccaneer.browser

/**
 * UI-thread-only, replaceable navigation barrier. Cleanup gets one bounded chance
 * before navigation, not a claim that the decoder/GPU has actually freed memory.
 * Cancellation invalidates late callbacks as well as removing the timeout.
 */
internal class NavigationHandoff(
    private val postDelayed: (Runnable, Long) -> Boolean,
    private val remove: (Runnable) -> Unit,
    private val timeoutMs: Long = 250L
) {
    private class Pending(val commit: (String) -> Unit) {
        lateinit var deadline: Runnable
    }
    private var pending: Pending? = null
    val isPending: Boolean get() = pending != null

    fun cancel() {
        val old = pending ?: return
        pending = null
        remove(old.deadline)
    }

    fun start(cleanup: ((String?) -> Unit) -> Unit, commit: (String) -> Unit) {
        cancel()
        val request = Pending(commit)
        request.deadline = Runnable { complete(request, "cleanup_timeout") }
        pending = request
        if (!postDelayed(request.deadline, timeoutMs)) {
            complete(request, "cleanup_unscheduled")
            return
        }
        try {
            cleanup { result ->
                complete(request, if (result == "\"stopped\"") "cleanup_script_completed" else "cleanup_unconfirmed")
            }
        } catch (error: Exception) {
            if (pending === request) complete(request, "cleanup_unavailable")
            else throw error // Do not mislabel an exception from commit as cleanup failure.
        }
    }

    private fun complete(request: Pending, reason: String) {
        if (pending !== request) return
        pending = null
        remove(request.deadline)
        request.commit(reason)
    }
}
