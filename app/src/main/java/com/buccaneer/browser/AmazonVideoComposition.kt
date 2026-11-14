package com.buccaneer.browser

import android.util.Printer
import android.os.Handler
import android.os.Message
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest

/** Exact installed-provider receipt pin; unsupported systems retain provider defaults. */
internal object AmazonVideoComposition {
    private const val RUN_CONNECTION = "android.app.LoadedApk\$ServiceDispatcher\$RunConnection@"
    private const val DEFAULT_SHA256 = "9b5a374a4df7cd87e40dbf6f4775594146a7cb8600328261eba40a0c10fb0b4b"
    /** Process-start seam, not a DOM mask. Only the pinned vendor's FORCED-overlay opt-in
     * is removed; hardware decode/composition and normal overlay eligibility remain on.
     * This switch is process-wide, so on this exact provider it affects all hosts.
     * No platform/provider package, settings database, command-line file or app data is written.
     */
    fun prepare() {
        if (android.os.Build.MODEL != "AFTMM" || android.os.Build.VERSION.SDK_INT != 25) return
        try {
            val factory = Class.forName("android.webkit.WebViewFactory")
            val provider = factory.getDeclaredMethod("getProvider").apply { isAccessible = true }.invoke(null)
            val info = factory.getDeclaredMethod("getLoadedPackageInfo").apply { isAccessible = true }
                .invoke(null) as android.content.pm.PackageInfo
            if (!eligible(android.os.Build.MODEL, android.os.Build.VERSION.SDK_INT,
                    info.packageName, info.versionCode, info.versionName ?: "")) return
            if (provider.javaClass.name != "com.android.webview.chromium.WebViewExtAmazonFactoryProvider") return
            val commandClass = Class.forName("org.chromium.base.CommandLine", true, provider.javaClass.classLoader)
            val command = commandClass.getMethod("getInstance").invoke(null)
            val hasMethod = commandClass.getMethod("hasSwitch", String::class.java)
            val removeMethod = commandClass.getMethod("removeSwitch", String::class.java)
            val restoreMethod = commandClass.getMethod("appendSwitch", String::class.java)
            val flag = "force-video-overlays"
            // Loading the library already creates NativeCommandLine. The vendor itself
            // appends deployment switches after that point. Its AwInit lock/state, NOT
            // CommandLine implementation type, bounds pre-browser-start configuration.
            val awInit = provider.javaClass.superclass.getDeclaredField("mAwInit")
                .apply { isAccessible = true }.get(provider)
            val lock = awInit.javaClass.getDeclaredField("mLock").apply { isAccessible = true }.get(awInit)
            val stateField = awInit.javaClass.getDeclaredField("mInitState").apply { isAccessible = true }
            synchronized(lock) {
                val state = stateField.getInt(awInit)
                val result = configure(beforeBrowserStartup(state),
                    { hasMethod.invoke(command, flag) as Boolean },
                    { removeMethod.invoke(command, flag); Unit },
                    { restoreMethod.invoke(command, flag); Unit })
                android.util.Log.w("BuccaneerVideo", "D6 force-video-overlays=$result initState=$state remaining=${hasMethod.invoke(command, flag)}")
            }
            installDispatchBoundaryObserver()
        } catch (e: Exception) {
            // Private seam missing/changed: preserve ordinary WebView startup; no fallback hacks.
            android.util.Log.w("BuccaneerVideo", "D6 composition override unavailable: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
    fun configure(beforeNative: Boolean, has: () -> Boolean, remove: () -> Unit, restore: () -> Unit): String {
        if (!beforeNative) return "skipped"
        var attempted = false
        return try {
            if (!has()) return "skipped"
            attempted = true
            remove()
            if (!has()) "removed" else { restore(); "failed" }
        } catch (_: Exception) {
            if (attempted) try { restore() } catch (_: Exception) { /* caller logs failure */ }
            "failed"
        }
    }
    fun beforeBrowserStartup(state: Int): Boolean = state == 0

    /** Pure policy seam used by the host proof. The original Printer always runs first. */
    fun dispatchBoundaryObserver(original: Printer?, deadline: Long, now: () -> Long,
                                 arm: () -> Boolean, restore: (Printer?) -> Unit): Printer {
        var active = true
        return Printer { line ->
            original?.println(line)
            if (!active) return@Printer
            try {
                if (now() >= deadline) {
                    active = false
                    restore(original)
                } else if (line.startsWith("<<<<< Finished to ") && line.contains(RUN_CONNECTION) && arm()) {
                    active = false
                    restore(original)
                }
            } catch (failure: Throwable) {
                val needsRestore = active
                active = false
                if (needsRestore) try { restore(original) } catch (_: Throwable) { }
                android.util.Log.w("BuccaneerVideo",
                    "D6 dispatch observer inactive after repair failure: ${failure.javaClass.simpleName}: ${failure.message}")
            }
        }
    }

    fun installCallbacksTransactionally(firstOriginal: Any?, secondOriginal: Any?,
                                        firstReplacement: Any, secondReplacement: Any,
                                        writeFirst: (Any?) -> Unit, writeSecond: (Any?) -> Unit,
                                        readFirst: () -> Any?, readSecond: () -> Any?): Boolean {
        return try {
            writeFirst(firstReplacement)
            check(readFirst() === firstReplacement)
            writeSecond(secondReplacement)
            check(readSecond() === secondReplacement)
            true
        } catch (_: Throwable) {
            try { writeSecond(secondOriginal) } catch (_: Throwable) { }
            try { writeFirst(firstOriginal) } catch (_: Throwable) { }
            false
        }
    }

    fun removeForcedOverlay(map: HashMap<String, Any>) {
        map.remove("force-video-overlays")
    }

    private fun installDispatchBoundaryObserver() {
        val looper = android.os.Looper.getMainLooper()
        check(android.os.Looper.myLooper() === looper)
        val loggingField = android.os.Looper::class.java.getDeclaredField("mLogging").apply { isAccessible = true }
        val original = loggingField.get(looper) as Printer?
        val deadline = android.os.SystemClock.uptimeMillis() + 10_000L
        lateinit var wrapper: Printer
        val restore = { printer: Printer? ->
            if (loggingField.get(looper) === wrapper) looper.setMessageLogging(printer)
        }
        wrapper = dispatchBoundaryObserver(original, deadline,
            { android.os.SystemClock.uptimeMillis() },
            { armProviderCallbacks() }, restore)
        looper.setMessageLogging(wrapper)
        check(loggingField.get(looper) === wrapper)
    }

    private fun armProviderCallbacks(): Boolean {
        val factory = Class.forName("android.webkit.WebViewFactory")
        val provider = factory.getDeclaredMethod("getProvider").apply { isAccessible = true }.invoke(null)
        if (provider.javaClass.name != "com.android.webview.chromium.WebViewExtAmazonFactoryProvider") return false
        val loader = provider.javaClass.classLoader
        val clientClass = Class.forName(
            "com.amazon.webview.awvdeploymentservice.client.AWVDeploymentClient", false, loader)
        val client = clientClass.getField("sDeploymentClient").get(null) ?: return false
        val messenger = clientClass.getField("mClientMessenger").get(client) as? android.os.Messenger ?: return false
        val timerHandler = clientClass.getField("mTimerTaskHandler").get(client) as? Handler ?: return false
        val target = android.os.Messenger::class.java.getDeclaredField("mTarget").apply { isAccessible = true }
            .get(messenger)
        if (target.javaClass.name != "android.os.Handler\$MessengerImpl") return false
        val responseHandler = target.javaClass.getDeclaredField("this\$0").apply { isAccessible = true }
            .get(target) as? Handler ?: return false
        val handlerClass = responseHandler.javaClass
        if (responseHandler === timerHandler || handlerClass.name !=
            "com.amazon.webview.awvdeploymentservice.client.AWVDeploymentClient\$2" ||
            timerHandler.javaClass !== handlerClass) return false
        val classId = handlerClass.getDeclaredField("\$r8\$classId").apply { isAccessible = true }
        val owner = handlerClass.getDeclaredField("this\$0").apply { isAccessible = true }
        val responseOwner = (owner.get(responseHandler) as? WeakReference<*>)?.get()
        if (classId.getInt(responseHandler) != 1 || classId.getInt(timerHandler) != 0 ||
            responseOwner !== client || owner.get(timerHandler) !== client ||
            responseHandler.looper !== android.os.Looper.getMainLooper() ||
            timerHandler.looper !== android.os.Looper.getMainLooper()) return false

        val callbackField = Handler::class.java.getDeclaredField("mCallback").apply { isAccessible = true }
        val responseOriginal = callbackField.get(responseHandler) as Handler.Callback?
        val timerOriginal = callbackField.get(timerHandler) as Handler.Callback?
        val defaultJson = pinnedDefaultJson(provider)
        val responseReplacement = chained(responseOriginal) { message ->
            interceptResponse(message, responseHandler, client, clientClass, defaultJson)
        }
        val timerReplacement = chained(timerOriginal) { message ->
            interceptTimeout(message, client, clientClass, defaultJson)
        }
        val installed = installCallbacksTransactionally(responseOriginal, timerOriginal,
            responseReplacement, timerReplacement,
            { callbackField.set(responseHandler, it) }, { callbackField.set(timerHandler, it) },
            { callbackField.get(responseHandler) }, { callbackField.get(timerHandler) })
        if (installed) android.util.Log.w("BuccaneerVideo",
            "D6 handler repair armed response=classId1 timer=classId0 both=readback mainLooper=true")
        return installed
    }

    private fun chained(original: Handler.Callback?, intercept: (Message) -> Boolean) =
        Handler.Callback { message ->
            dispatchChained({ original?.handleMessage(message) == true }, { intercept(message) })
        }

    fun dispatchChained(original: () -> Boolean, repair: () -> Boolean): Boolean =
        original() || repair()

    fun mergeMaps(defaultJson: String, remoteJson: String?, includeRemote: Boolean = remoteJson != null,
                  merger: (HashMap<String, Any>, String?) -> Unit): HashMap<String, Any> {
        val result = HashMap<String, Any>()
        merger(result, defaultJson)
        if (includeRemote) merger(result, remoteJson)
        return result
    }

    fun deliverFiltered(map: HashMap<String, Any>, listener: (HashMap<String, Any>) -> Unit,
                        continueOriginal: (() -> Unit)? = null): Boolean {
        removeForcedOverlay(map)
        listener(map)
        continueOriginal?.invoke()
        return true
    }

    private fun interceptResponse(message: Message, handler: Handler, client: Any,
                                  clientClass: Class<*>, defaultJson: String): Boolean {
        if (message.what != 1) return false
        val owner = handler.javaClass.getDeclaredField("this\$0").apply { isAccessible = true }.get(handler)
        val weakClient = (owner as? WeakReference<*>)?.get() ?: return false
        if (weakClient !== client) return false
        val listenerField = clientClass.getField("mArcusFeatureFlagsUpdateListener")
        val listener = listenerField.get(client) ?: return false
        return interceptArcusMessage(message, true, true,
            { remote -> merge(clientClass, defaultJson, remote, true) },
            { invokeListener(listener, it) }) {
            completeResponse(
                timer = { clientClass.getField("mTimer").get(client) as? java.util.Timer },
                cancel = { it.cancel() },
                clearTimer = { clientClass.getField("mTimer").set(client, null) },
                unbind = {
                    val contextClass = Class.forName("org.chromium.base.ContextUtils", false, clientClass.classLoader)
                    val context = contextClass.getMethod("getApplicationContext").invoke(null) as android.content.Context
                    val connection = clientClass.getField("mServiceConnection").get(client) as android.content.ServiceConnection
                    context.unbindService(connection)
                },
                onUnbindFailure = { it.printStackTrace() })
        }
    }

    fun <T> completeResponse(timer: () -> T?, cancel: (T) -> Unit, clearTimer: () -> Unit,
                             unbind: () -> Unit, onUnbindFailure: (Throwable) -> Unit) {
        timer()?.let {
            cancel(it)
            clearTimer()
        }
        try {
            unbind()
        } catch (failure: Throwable) {
            onUnbindFailure(failure)
        }
    }

    private fun interceptTimeout(message: Message, client: Any, clientClass: Class<*>,
                                 defaultJson: String): Boolean {
        if (message.what != 1) return false
        val timerPresent = clientClass.getField("mTimer").get(client) != null
        if (!timerPresent) return false
        val listener = clientClass.getField("mArcusFeatureFlagsUpdateListener").get(client) ?: return false
        return interceptArcusMessage(message, false, timerPresent,
            { merge(clientClass, defaultJson, it, false) }, { invokeListener(listener, it) })
    }

    fun interceptArcusMessage(message: Message, response: Boolean, timerPresent: Boolean,
                              merge: (String?) -> HashMap<String, Any>,
                              listener: (HashMap<String, Any>) -> Unit,
                              responseCleanup: (() -> Unit)? = null): Boolean {
        if (message.what != 1 || (!response && !timerPresent)) return false
        val remote = if (response) message.data.getString("CONFIG_JSON") else null
        return deliverFiltered(merge(remote), listener, if (response) responseCleanup else null)
    }

    @Suppress("UNCHECKED_CAST")
    private fun merge(clientClass: Class<*>, defaultJson: String, remoteJson: String?,
                      includeRemote: Boolean): HashMap<String, Any> {
        val contextClass = Class.forName("org.chromium.base.ContextUtils", false, clientClass.classLoader)
        val merger = clientClass.getMethod("getFeatureFlagsForApp", HashMap::class.java,
            String::class.java, String::class.java, String::class.java)
        return mergeMaps(defaultJson, remoteJson, includeRemote) { result, json ->
            val context = contextClass.getMethod("getApplicationContext").invoke(null) as android.content.Context
            val packageName = context.packageName
            val chromium = clientClass.getMethod("getChromiumVersion").invoke(null) as String
            invokeExact(merger, null, result, json, packageName, chromium)
        }
    }

    private fun invokeListener(listener: Any, map: HashMap<String, Any>) {
        val method = listener.javaClass.getMethod("onArcusFeatureFlagsUpdate", HashMap::class.java)
        invokeExact(method, listener, map)
    }

    private fun invokeExact(method: java.lang.reflect.Method, receiver: Any?, vararg args: Any?) {
        try {
            method.invoke(receiver, *args)
        } catch (wrapped: InvocationTargetException) {
            val cause = wrapped.cause ?: throw wrapped
            when (cause) {
                is RuntimeException -> throw cause
                is Error -> throw cause
                else -> throw IllegalStateException(cause)
            }
        }
    }

    private fun pinnedDefaultJson(provider: Any): String {
        val contextClass = Class.forName("org.chromium.base.ContextUtils", false, provider.javaClass.classLoader)
        val context = contextClass.getMethod("getApplicationContext").invoke(null) as android.content.Context
        return readPinnedDefaultJson(context)
    }

    fun readPinnedDefaultJson(context: android.content.Context): String {
        val value = context.assets.open("awv_api25_default.json").bufferedReader().use { it.readText() }
            .removeSuffix("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        check(digest == DEFAULT_SHA256)
        return value
    }

    fun eligible(model: String, api: Int, provider: String, code: Int, version: String): Boolean =
        model == "AFTMM" && api == 25 && provider == "com.amazon.webview.chromium" &&
            code == 61446232 && version == "118.amazon-webview-v118-5993-tv.5993.155.160"
}
