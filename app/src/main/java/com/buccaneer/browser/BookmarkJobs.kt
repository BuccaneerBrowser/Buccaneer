package com.buccaneer.browser

import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Transfer ownership is process-wide; a browser renderer/Activity is not its owner. */
object BookmarkJobs {
    data class UiOwner(val activity: String, val interaction: Long)
    data class Result(val message: String, val imported: Boolean = false, val error: Throwable? = null, val owner: UiOwner? = null)
    interface Listener { fun onBookmarkJob(result: Result): Boolean }
    private val busy = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { Thread(it,"Buccaneer-bookmarks").apply { isDaemon=true } }
    private val main = Handler(Looper.getMainLooper())
    private var listener = WeakReference<Listener>(null)
    private var pending: Result? = null
    fun isBusy(): Boolean = busy.get()
    fun attach(value: Listener) { listener=WeakReference(value); deliver() }
    fun detach(value: Listener) { if(listener.get()===value)listener.clear() }
    fun submit(work: () -> Result): Boolean {
        if(!busy.compareAndSet(false,true))return false
        worker.execute {
            val result=try { work() } catch(e: Throwable){Result("Bookmark transfer failed; existing bookmarks kept",error=e)}
            main.post { busy.set(false);pending=result;deliver() }
        }
        return true
    }
    private fun deliver(){val value=listener.get()?:return;val result=pending?:return;if(value.onBookmarkJob(result))pending=null}
}
