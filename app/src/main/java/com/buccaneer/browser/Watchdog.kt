package com.buccaneer.browser

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Binder
import android.os.IBinder

/**
 * Brings the browser back after a crash that kills the whole process.
 *
 * The GPU-driver crash (PowerVR, on Chromium's in-process GPU thread) is native, so no Java
 * handler inside the app can see it: the process simply dies. Nothing inside a dead process
 * can restart it, so this service runs in its own process and watches the main process's
 * binder. When that binder dies while the app was in use, it starts the browser again;
 * MainActivity reopens the page it was on.
 *
 * A clean exit (Menu > Exit, Back > Exit) sets cleanExit, and a backgrounded app is not
 * restarted - only a process death while the app was in the foreground.
 */
class WatchdogService : Service() {

    private var watched: IBinder? = null

    override fun onCreate() {
        super.onCreate()
        watchMainProcess()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent): IBinder? = null

    private fun watchMainProcess() {
        if (watched != null) return
        try {
            bindService(Intent(this, MainProcessService::class.java), object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    watched = service
                    try { service.linkToDeath({ mainProcessGone() }, 0) } catch (_: Throwable) {}
                }

                override fun onServiceDisconnected(name: ComponentName) { watched = null }
            }, Context.BIND_AUTO_CREATE)
        } catch (_: Throwable) {}
    }

    private fun mainProcessGone() {
        watched = null
        val f = flags()
        if (f.getBoolean("cleanExit", true)) return          // the user exited; not a crash
        if (!f.getBoolean("wasForeground", false)) return    // killed while backgrounded
        if (!allowRestart(f)) return
        f.edit().putBoolean("watchdogRestart", true).apply()
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {}
    }

    /** Stop a crash loop: at most three restarts in ten minutes. */
    private fun allowRestart(f: SharedPreferences): Boolean {
        val now = System.currentTimeMillis()
        val started = f.getLong("watchdogWindowStartedAt", 0L)
        if (started <= 0L || now - started > 600_000L) {
            f.edit().putLong("watchdogWindowStartedAt", now).putInt("watchdogRestartCount", 1).apply()
            return true
        }
        val count = f.getInt("watchdogRestartCount", 0)
        if (count >= 3) return false
        f.edit().putInt("watchdogRestartCount", count + 1).apply()
        return true
    }

    // MODE_MULTI_PROCESS so a flag written by the main process is read fresh here.
    @Suppress("DEPRECATION")
    private fun flags(): SharedPreferences = getSharedPreferences("settings", Context.MODE_MULTI_PROCESS)
}

/** Lives in the main process; the binder it hands out is what the watchdog watches. */
class MainProcessService : Service() {

    override fun onBind(intent: Intent): IBinder = Binder()

    override fun onUnbind(intent: Intent): Boolean {
        stopSelf()
        return false
    }
}
