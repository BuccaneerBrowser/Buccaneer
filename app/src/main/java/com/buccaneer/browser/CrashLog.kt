package com.buccaneer.browser

import android.annotation.SuppressLint

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * Remembers the last problem (an app crash, or the web page's process dying) so that
 * Menu > Settings > Diagnostics can show it after the app has restarted. Nothing is sent anywhere.
 */
object CrashLog {

    private const val PREFS = "crashlog"
    private const val KEY = "last"
    private const val TAG = "Buccaneer"

    @Volatile private var installed = false

    /** Remember uncaught exceptions, then let Android handle the crash as usual. */
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                record(app, "Crash in thread " + thread.name, Log.getStackTraceString(error))
                DiagnosticLog.recordCritical(app, "uncaught_exception", JSONObject().apply {
                    put("thread", thread.name.take(120))
                    put("type", error.javaClass.name.take(200))
                    put("message", (error.message ?: "").take(1000))
                })
            } catch (t: Throwable) {
                // never hide the original crash
            }
            if (previous != null) previous.uncaughtException(thread, error)
        }
    }

    // Called by the uncaught-exception handler: the process may terminate before apply flushes.
    @SuppressLint("ApplySharedPref")
    fun record(context: Context, what: String, details: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = stamp + "  " + what + "\n" + details.take(3000)
        Log.e(TAG, text)
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, text)
            .commit()
    }

    fun last(context: Context): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
}
