package com.buccaneer.browser

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.webkit.WebViewCompat
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Opt-in bounded diagnostics. Routine events never perform disk I/O on callers. */
object DiagnosticLog {
    private const val PREFS = "diagnostics"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SESSION = "sessionId"
    private const val FILE_NAME = "diagnostic_session.jsonl"
    private const val CRITICAL_FILE = "diagnostic_critical.jsonl"
    private const val MAX_BYTES = 2 * 1024 * 1024L
    private const val KEEP_BYTES = 1024 * 1024
    private const val FORMAT_VERSION = 3
    private val stateLock = Any()
    private val criticalLock = Any()
    private val pending = ArrayBlockingQueue<Entry>(256)
    private val dropped = AtomicInteger()
    private val writer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "Buccaneer-diagnostics").apply { isDaemon = true }
    }
    @Volatile private var enabled = false
    @Volatile private var sessionId: String? = null
    @Volatile private var generation = 0L
    @Volatile private var appContext: Context? = null
    private var initialized = false
    private var writerSession: String? = null
    // Writer-thread-only. Sampling is triggered by existing events, not another timer.
    private var lastMemorySampleAt = -1L
    private var criticalSession: String? = null
    private var lastBurstEvent = ""
    private var lastBurstAt = 0L
    private var burstCount = 0
    private data class Entry(val session: String, val generation: Long, val ts: String,
        val elapsed: Long, val event: String, val raw: String)

    // Schedule only when there is work. Disabled diagnostics must not wake the CPU 10 times/sec.
    @Volatile private var flushScheduled = false
    private fun scheduleFlushLocked() {
        if (flushScheduled) return
        flushScheduled = true
        try {
            writer.schedule({
                try { flushPending() }
                finally {
                    synchronized(stateLock) {
                        flushScheduled = false
                        // Covers entries queued while this batch was being written.
                        if (pending.isNotEmpty()) scheduleFlushLocked()
                    }
                }
            }, 100, TimeUnit.MILLISECONDS)
        } catch (_: Exception) { flushScheduled = false }
    }

    fun initialize(context: Context) {
        val app = context.applicationContext
        synchronized(stateLock) {
            if (initialized) return
            initialized = true
            appContext = app
            try {
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                sessionId = prefs.getString(KEY_SESSION, null)
                enabled = prefs.getBoolean(KEY_ENABLED, false)
                writerSession = sessionId
                criticalSession = sessionId
            } catch (_: Throwable) { enabled = false }
        }
        if (enabled) {
            if (sessionId.isNullOrBlank()) startNewSession(app, "recovered")
            else record(app, "process_start", metadata(app))
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun isEnabled(context: Context): Boolean = enabled

    fun startNewSession(context: Context, reason: String = "manual"): String {
        val app = context.applicationContext
        val id = UUID.randomUUID().toString()
        // Metadata collection stays on the calling/UI thread; only file work is deferred.
        val details = JSONObject().put("reason", reason.take(80)).put("session", id)
            .put("metadata", metadata(app))
        synchronized(stateLock) {
            appContext = app
            generation++
            pending.clear()
            dropped.set(0)
            sessionId = id
            enabled = true
            lastBurstEvent = ""
            burstCount = 0
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, true).putString(KEY_SESSION, id).apply()
            enqueueLocked("session_start", details, true)
        }
        return id
    }

    fun stop(context: Context) {
        synchronized(stateLock) {
            if (!enabled) return
            enqueueBurstLocked()
            enqueueLocked("session_stop", JSONObject().put("session", sessionId ?: ""), true)
            enabled = false
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, false).apply()
        }
    }

    fun clear(context: Context) {
        val app = context.applicationContext
        synchronized(stateLock) {
            generation++
            val clearGeneration = generation
            pending.clear()
            dropped.set(0)
            enabled = false
            sessionId = null
            lastBurstEvent = ""
            burstCount = 0
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_SESSION).putBoolean(KEY_ENABLED, false).apply()
            writer.execute {
                synchronized(criticalLock) {
                    if (generation == clearGeneration) {
                        try { file(app).delete() } catch (_: Throwable) {}
                        writerSession = null
                        try { File(app.filesDir, CRITICAL_FILE).delete() } catch (_: Throwable) {}
                        criticalSession = null
                    }
                }
            }
        }
    }

    fun hasData(context: Context): Boolean = try {
        pending.isNotEmpty() || file(context.applicationContext).length() > 0L ||
            File(context.filesDir, CRITICAL_FILE).length() > 0L
    } catch (_: Throwable) { false }

    @Suppress("UNUSED_PARAMETER")
    fun currentSessionId(context: Context): String = sessionId ?: ""

    fun record(context: Context, event: String, details: JSONObject? = null) {
        if (!enabled) return
        synchronized(stateLock) {
            if (!enabled) return
            appContext = context.applicationContext
            val name = cleanEventName(event)
            val elapsed = SystemClock.elapsedRealtime()
            // Repeated bursts need counts, not hundreds of disk writes / retained URLs.
            val noisy = name == "tmdb_image_blocked" || name == "subresource_error" ||
                name == "console" || name == "popup_blocked"
            if (noisy && name == lastBurstEvent && elapsed - lastBurstAt < 1000L) {
                burstCount++
                return
            }
            enqueueBurstLocked()
            lastBurstEvent = if (noisy) name else ""
            lastBurstAt = elapsed
            enqueueLocked(name, details ?: JSONObject(), false)
        }
    }

    fun record(context: Context, event: String, details: String?) {
        if (!enabled) return
        val parsed = if (details.isNullOrBlank()) JSONObject() else if (details.length > 16384) {
            JSONObject().put("omitted", "Oversized diagnostic payload").put("characters", details.length)
        } else if (!JsonInputBudget.allows(details)) {
            JSONObject().put("omitted", "Diagnostic nesting exceeded safety budget").put("characters", details.length)
        } else try {
            JSONObject(details)
        } catch (_: Exception) { JSONObject().put("message", details.take(1000)) }
        record(context, event, parsed)
    }

    private fun enqueueBurstLocked() {
        if (burstCount > 0) enqueueLocked("event_burst", JSONObject()
            .put("sourceEvent", lastBurstEvent).put("additionalCount", burstCount), false)
        burstCount = 0
    }

    private fun enqueueLocked(event: String, details: JSONObject, important: Boolean) {
        val raw = details.toString()
        val bounded = if (raw.length <= 32768) raw else JSONObject()
            .put("omitted", "Diagnostic payload exceeded 32 KiB").put("characters", raw.length).toString()
        val entry = Entry(sessionId ?: "", generation, now(), SystemClock.elapsedRealtime(), event, bounded)
        if (!important && pending.remainingCapacity() <= 8) {
            dropped.incrementAndGet()
            return
        }
        if (!pending.offer(entry)) {
            dropped.incrementAndGet()
            if (important) { pending.poll(); pending.offer(entry) }
        }
        scheduleFlushLocked()
    }

    private fun line(entry: Entry): String {
        val details = try { JSONObject(entry.raw) } catch (_: Exception) {
            JSONObject().put("message", entry.raw.take(1000))
        }
        return JSONObject().put("ts", entry.ts).put("elapsedMs", entry.elapsed)
            .put("session", entry.session).put("event", entry.event)
            .put("data", sanitize(details)).toString() + "\n"
    }

    private fun flushPending() {
        try {
            val app = appContext ?: return
            val batch = ArrayList<Entry>()
            pending.drainTo(batch, 256)
            val current = batch.filter { it.generation == generation }.toMutableList()
            if (current.isEmpty()) return
            val elapsed = SystemClock.elapsedRealtime()
            if (enabled && (lastMemorySampleAt < 0L || elapsed - lastMemorySampleAt >= 15000L) &&
                current.any { it.event == "page_started" || it.event == "media_health" || it.event == "session_start" }) {
                lastMemorySampleAt = elapsed
                try {
                    // This can be expensive: never collect PSS on the UI/bridge thread.
                    val memory = android.os.Debug.MemoryInfo()
                    android.os.Debug.getMemoryInfo(memory)
                    val runtime = Runtime.getRuntime()
                    val allocated = runtime.totalMemory()
                    val used = (allocated - runtime.freeMemory()).coerceAtLeast(0L)
                    val details = JSONObject()
                        .put("sampleScope", "host-process-not-dedicated-gpu")
                        .put("sampleElapsedMs", elapsed)
                        .put("processPssKb", memory.totalPss)
                        .put("processPrivateDirtyKb", memory.totalPrivateDirty)
                        .put("javaAllocatedBytes", allocated)
                        .put("javaUsedBytes", used)
                        .put("javaHeadroomBytes", (runtime.maxMemory() - used).coerceAtLeast(0L))
                        .put("nativeHeapAllocatedBytes", android.os.Debug.getNativeHeapAllocatedSize())
                    val owner = current.first()
                    current.add(Entry(owner.session, owner.generation, now(), elapsed, "process_memory", details.toString()))
                } catch (_: Throwable) { /* optional profiling must not interrupt browsing */ }
            }
            synchronized(criticalLock) {
                if (current.first().generation != generation) return
                val id = current.first().session
                val target = file(app)
                if (writerSession != id) {
                    target.delete()
                    writerSession = id
                    if (criticalSession != id && sessionId == id && generation == current.first().generation) {
                        File(app.filesDir, CRITICAL_FILE).delete()
                        criticalSession = id
                    }
                }
                rotateIfNeeded(target)
                val text = StringBuilder()
                for (entry in current) text.append(line(entry))
                val lost = dropped.getAndSet(0)
                if (lost > 0) text.append(line(Entry(id, generation, now(), SystemClock.elapsedRealtime(),
                    "events_dropped", JSONObject().put("count", lost).toString())))
                target.parentFile?.mkdirs()
                target.appendText(text.toString(), Charsets.UTF_8)
                rotateIfNeeded(target)
            }
        } catch (_: Throwable) { /* diagnostics must never interrupt browsing */ }
    }

    // A small separate crash file avoids draining the routine queue.
    // File writes share a lock so a stale crash cannot resurrect cleared sessions.
    // This synchronous, bounded, best-effort path is reserved for imminent crashes.
    fun recordCritical(context: Context, event: String, details: JSONObject? = null) {
        if (!enabled) return
        val raw = (details ?: JSONObject()).toString()
        val bounded = if (raw.length <= 32768) raw else JSONObject().put("omitted", "Oversized critical payload").toString()
        val entry = Entry(sessionId ?: "", generation, now(), SystemClock.elapsedRealtime(), cleanEventName(event), bounded)
        try {
            synchronized(criticalLock) {
                if (!enabled || entry.generation != generation || entry.session != sessionId) return
                val target = File(context.applicationContext.filesDir, CRITICAL_FILE)
                if (criticalSession != entry.session || target.length() > 256 * 1024L) target.delete()
                criticalSession = entry.session
                target.appendText(line(entry), Charsets.UTF_8)
            }
        } catch (_: Throwable) {}
    }

    /** Opens and writes the destination on the writer thread; completion runs there too. */
    fun exportAsync(context: Context, openOutput: () -> OutputStream,
        completion: (Throwable?) -> Unit) {
        val app = context.applicationContext
        writer.execute {
            try {
                synchronized(stateLock) { enqueueBurstLocked() }
                flushPending()
                openOutput().use { exportZip(app, it) }
                completion(null)
            } catch (e: Throwable) { completion(e) }
        }
    }

    // Call only on the serialized writer (exportAsync).
    private fun exportZip(context: Context, output: OutputStream) {
        val exportedSession = writerSession ?: sessionId ?: ""
        ZipOutputStream(output).use { zip ->
            val meta = JSONObject().put("formatVersion", FORMAT_VERSION)
                .put("session", exportedSession).put("exportedAt", now())
                .put("loggingEnabled", enabled)
            addEntry(zip, "session.json", meta.toString(2).toByteArray(Charsets.UTF_8))
            val f = file(context)
            addEntry(zip, "events.jsonl", if (f.exists()) f.readBytes() else ByteArray(0))
            val critical = synchronized(criticalLock) {
                val c = File(context.filesDir, CRITICAL_FILE)
                if (criticalSession == exportedSession && c.exists()) c.readBytes() else ByteArray(0)
            }
            addEntry(zip, "critical.jsonl", critical)
            addEntry(zip, "README.txt", ("Routine events are batched; queue capacity 256. " +
                "event_burst reports additional repeated events; events_dropped reports queue overflow. " +
                "Critical crash events are in critical.jsonl. Both files include timestamps/session IDs. " +
                "URL paths, queries, fragments, and secret-like fields are redacted. Other free text may still be sensitive; inspect before sharing.\n").toByteArray(Charsets.UTF_8))
        }
    }

    private fun rotateIfNeeded(target: File) {
        if (!target.exists() || target.length() <= MAX_BYTES) return
        val all = target.readBytes()
        val start = Math.max(0, all.size - KEEP_BYTES)
        val cut = all.indexOfFrom(10.toByte(), start)
        target.writeBytes(all.copyOfRange(if (cut >= 0) cut + 1 else start, all.size))
    }

    private fun ByteArray.indexOfFrom(value: Byte, start: Int): Int {
        for (i in start until size) if (this[i] == value) return i
        return -1
    }

    fun metadata(context: Context): JSONObject = JSONObject().apply {
        put("appVersion", appVersion(context))
        put("manufacturer", android.os.Build.MANUFACTURER)
        put("model", android.os.Build.MODEL)
        put("androidRelease", android.os.Build.VERSION.RELEASE)
        put("api", android.os.Build.VERSION.SDK_INT)
        put("compileSdk", 34)
        try {
            val info = context.packageManager.getApplicationInfo(context.packageName, 0)
            put("targetSdk", info.targetSdkVersion)
        } catch (_: Throwable) { put("targetSdk", -1) }
        put("webViewProvider", webViewProvider(context))
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            put("deviceTotalMemMb", mi.totalMem / 1048576L)
            put("deviceAvailMemMb", mi.availMem / 1048576L)
            put("deviceLowMemory", mi.lowMemory)
            put("deviceLowMemoryThresholdMb", mi.threshold / 1048576L)
            val runtime = Runtime.getRuntime()
            val allocated = runtime.totalMemory()
            val free = runtime.freeMemory()
            val maximum = runtime.maxMemory()
            val used = (allocated - free).coerceAtLeast(0L)
            put("javaMaxHeapMb", maximum / 1048576L)
            put("javaFreeHeapMb", free / 1048576L)
            put("javaAllocatedBytes", allocated)
            put("javaUsedBytes", used)
            put("javaHeadroomBytes", (maximum - used).coerceAtLeast(0L))
            put("nativeHeapAllocatedBytes", android.os.Debug.getNativeHeapAllocatedSize())
            put("supportedAbis", android.os.Build.SUPPORTED_ABIS.joinToString(","))
            if (android.os.Build.VERSION.SDK_INT >= 23) put("process64Bit", android.os.Process.is64Bit())
        } catch (_: Throwable) { }
        try { put("userAgent", android.webkit.WebSettings.getDefaultUserAgent(context).take(500)) } catch (_: Throwable) { }
    }

    fun safeUrl(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return try {
            val uri = Uri.parse(raw)
            val scheme = uri.scheme?.lowercase(Locale.US)
            if (scheme != "http" && scheme != "https") return scheme ?: "other"
            ((scheme + "://" + (uri.host ?: "") + "/")).take(2048)
        } catch (_: Throwable) { "invalid-url" }
    }

    private fun sanitize(input: JSONObject): JSONObject {
        val out = JSONObject()
        val keys = input.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val lower = key.lowercase(Locale.US)
            if (lower.contains("cookie") || lower.contains("authorization") || lower.contains("token") ||
                lower.contains("signature") || lower.contains("password") || lower.contains("secret") ||
                lower.contains("apikey") || lower.contains("api_key") || lower == "hash" ||
                lower == "query" || lower == "fragment") {
                out.put(key, "[redacted]")
                continue
            }
            val value = input.opt(key)
            out.put(key, when (value) {
                is String -> if (lower == "url" || lower == "src" || lower == "href" || lower.contains("url")) {
                    safeUrl(value)
                } else redactText(value)
                is JSONObject -> sanitize(value)
                is org.json.JSONArray -> sanitizeArray(value)
                else -> value
            })
        }
        return out
    }

    private fun sanitizeArray(input: org.json.JSONArray): org.json.JSONArray {
        val out = org.json.JSONArray()
        for (i in 0 until input.length()) {
            val value = input.opt(i)
            out.put(when (value) {
                is JSONObject -> sanitize(value)
                is org.json.JSONArray -> sanitizeArray(value)
                is String -> if (value.startsWith("https://") || value.startsWith("http://")) safeUrl(value) else redactText(value)
                else -> value
            })
        }
        return out
    }

    private fun redactText(value: String): String = Regex("""https?://[^\s"<>]+""", RegexOption.IGNORE_CASE)
        .replace(value.take(2000)) { safeUrl(it.value) }

    private fun cleanEventName(raw: String): String = raw.trim().take(120).ifEmpty { "event" }
    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)
    private fun addEntry(zip: ZipOutputStream, name: String, data: ByteArray) {
        zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
    }
    private fun now(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(Date())
    private fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Throwable) { "?" }
    private fun webViewProvider(context: Context): String = try {
        val pkg = WebViewCompat.getCurrentWebViewPackage(context)
        if (pkg == null) "unknown" else pkg.packageName + "/" + pkg.versionName
    } catch (_: Throwable) { "unknown" }
}
