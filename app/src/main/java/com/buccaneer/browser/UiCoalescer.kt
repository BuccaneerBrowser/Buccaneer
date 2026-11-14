package com.buccaneer.browser

/**
 * One queued UI callback and one pending value, even during a bridge burst.
 * [post] must enqueue asynchronously (Handler.post), not invoke inline.
 * Merge is for replaceable snapshots/counts only, never ordered player commands.
 */
internal class UiCoalescer<T : Any>(
    private val post: (Runnable) -> Boolean,
    private val remove: (Runnable) -> Unit,
    private val merge: (T, T) -> T = { _, newer -> newer },
    private val consume: (T) -> Unit
) {
    private val lock = Any()
    private var pending: T? = null
    private var scheduled = false
    private val drain = Runnable {
        val value = synchronized(lock) {
            val next = pending
            pending = null
            scheduled = false
            next
        }
        // Consumption may re-enter offer; that must enqueue a separate drain.
        if (value != null) consume(value)
    }

    fun offer(value: T) {
        synchronized(lock) {
            pending = pending?.let { merge(it, value) } ?: value
            if (!scheduled) {
                scheduled = true
                if (!post(drain)) {
                    pending = null
                    scheduled = false
                }
            }
        }
    }

    /** Also remove the queued drain so navigation/renderer teardown cannot strand it. */
    fun cancel() {
        synchronized(lock) {
            remove(drain)
            pending = null
            scheduled = false
        }
    }
}
