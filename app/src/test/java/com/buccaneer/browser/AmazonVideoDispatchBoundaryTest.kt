package com.buccaneer.browser

import android.util.Printer
import android.os.Handler
import android.os.Message
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.ServiceConnection
import android.os.IBinder
import com.amazon.webview.awvdeploymentservice.client.ProviderClientFixture
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class AmazonVideoDispatchBoundaryTest {
    private lateinit var fixtureContext: RecordingContext

    private class RecordingContext(base: Context, private val fixturePackageName: String = "com.buccaneer.fixture") : ContextWrapper(base) {
        lateinit var client: ProviderClientFixture
        var expectedConnection: ServiceConnection? = null
        var unbindFailure: RuntimeException? = null
        override fun getPackageName(): String {
            ProviderClientFixture.observations += "package:$fixturePackageName"
            return fixturePackageName
        }
        override fun unbindService(connection: ServiceConnection) {
            assertSame(expectedConnection, connection)
            ProviderClientFixture.observations += "unbind"
            ProviderClientFixture.observations += if (client.mArcusFeatureFlagsUpdateListener == null)
                "unbind:listener=null" else "unbind:listener=same"
            ProviderClientFixture.observations += if (client.mTimer == null) "unbind:timer=null" else "unbind:timer=present"
            unbindFailure?.let { throw it }
        }
    }

    private class Connection : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit
        override fun onServiceDisconnected(name: ComponentName?) = Unit
    }

    @Before fun resetProviderFixture() {
        ProviderClientFixture.reset()
        fixtureContext = RecordingContext(RuntimeEnvironment.getApplication())
        org.chromium.base.ContextUtils.setApplicationContextForTests(fixtureContext)
    }

    private fun interceptResponse(message: Message, handler: Handler, client: ProviderClientFixture): Boolean {
        val method = AmazonVideoComposition::class.java.getDeclaredMethod("interceptResponse",
            Message::class.java, Handler::class.java, Any::class.java, Class::class.java, String::class.java)
        method.isAccessible = true
        return method.invoke(AmazonVideoComposition, message, handler, client,
            ProviderClientFixture::class.java, "fixture-default") as Boolean
    }

    private fun interceptTimeout(message: Message, client: ProviderClientFixture): Boolean {
        val method = AmazonVideoComposition::class.java.getDeclaredMethod("interceptTimeout",
            Message::class.java, Any::class.java, Class::class.java, String::class.java)
        method.isAccessible = true
        return method.invoke(AmazonVideoComposition, message, client,
            ProviderClientFixture::class.java, "fixture-default") as Boolean
    }

    private fun message(what: Int = 1, remote: String? = null): Message = Message.obtain().apply {
        this.what = what
        if (remote != null) data.putString("CONFIG_JSON", remote)
    }

    private fun client(): Pair<ProviderClientFixture, ProviderClientFixture.RecordingListener> {
        val client = ProviderClientFixture()
        val listener = ProviderClientFixture.RecordingListener(client)
        client.mArcusFeatureFlagsUpdateListener = listener
        client.mServiceConnection = Connection()
        client.mTimer = ProviderClientFixture.RecordingTimer(client)
        ProviderClientFixture.observedClient = client
        fixtureContext.client = client
        fixtureContext.expectedConnection = client.mServiceConnection
        return client to listener
    }
    private fun observer(original: Printer?, deadline: Long, now: () -> Long,
                         arm: () -> Boolean, restore: (Printer?) -> Unit): Printer {
        val method = AmazonVideoComposition::class.java.methods.firstOrNull {
            it.name == "dispatchBoundaryObserver"
        }
        assertNotNull("Missing bounded API25 dispatch-boundary observer", method)
        return method!!.invoke(AmazonVideoComposition, original, deadline, now, arm, restore) as Printer
    }

    @Test fun completedRunConnectionArmsInlineThenRestoresExactPrinter() {
        val calls = mutableListOf<String>()
        val original = Printer { calls += "original:$it" }
        var restored: Printer? = null
        val wrapper = observer(original, 200, { 100 }, { calls += "arm"; true }) {
            restored = it; calls += "restore"
        }

        wrapper.println("<<<<< Finished to Handler android.app.LoadedApk\$ServiceDispatcher\$RunConnection@1")

        assertSame(original, restored)
        assertEquals(listOf(
            "original:<<<<< Finished to Handler android.app.LoadedApk\$ServiceDispatcher\$RunConnection@1",
            "arm", "restore"), calls)
    }

    @Test fun unrelatedMessagesNeverInspectProviderState() {
        var arms = 0
        val wrapper = observer(null, 200, { 100 }, { arms++; true }) { fail("restore") }
        wrapper.println(">>>>> Dispatching to Handler null: 1")
        wrapper.println("<<<<< Finished to Handler null")
        assertEquals(0, arms)
    }

    @Test fun failedCandidateRemainsPassiveUntilLaterServiceBoundary() {
        var arms = 0
        var restores = 0
        val wrapper = observer(null, 200, { 100 }, { ++arms == 2 }) { restores++ }
        val marker = "<<<<< Finished to Handler android.app.LoadedApk\$ServiceDispatcher\$RunConnection@"
        wrapper.println(marker + "1")
        wrapper.println(marker + "2")
        assertEquals(2, arms)
        assertEquals(1, restores)
    }

    @Test fun expiredObserverRestoresWithoutInspectingProvider() {
        var arms = 0
        var restores = 0
        val wrapper = observer(null, 99, { 100 }, { arms++; true }) { restores++ }
        wrapper.println("<<<<< Finished to Handler null")
        assertEquals(0, arms)
        assertEquals(1, restores)
    }

    @Test fun originalPrinterExceptionIsPreservedAndRepairIsNotAttempted() {
        var arms = 0
        val exact = IllegalStateException("printer")
        val wrapper = observer(Printer { throw exact }, 200, { 100 }, { arms++; true }) { fail("restore") }
        try {
            wrapper.println("<<<<< Finished to Handler android.app.LoadedApk\$ServiceDispatcher\$RunConnection@1")
            fail("expected original exception")
        } catch (actual: IllegalStateException) {
            assertSame(exact, actual)
        }
        assertEquals(0, arms)
    }

    @Test fun armingFailureRestoresExactOriginalAndBecomesInactiveWithoutEscapingLooper() {
        val original = Printer { }
        var restored: Printer? = null
        var arms = 0
        val wrapper = observer(original, 200, { 100 }, {
            arms++
            throw ReflectiveOperationException("arm")
        }) { restored = it }
        val marker = "<<<<< Finished to Handler android.app.LoadedApk\$ServiceDispatcher\$RunConnection@1"

        wrapper.println(marker)
        wrapper.println(marker)

        assertSame(original, restored)
        assertEquals(1, arms)
    }

    @Test fun restoreFailureBecomesInactiveAndDoesNotEscapeLooper() {
        var arms = 0
        var restores = 0
        val wrapper = observer(null, 200, { 100 }, { arms++; true }) {
            restores++
            throw ReflectiveOperationException("restore")
        }
        val marker = "<<<<< Finished to Handler android.app.LoadedApk\$ServiceDispatcher\$RunConnection@1"

        wrapper.println(marker)
        wrapper.println(marker)

        assertEquals(1, arms)
        assertEquals(1, restores)
    }

    @Test fun transactionalTwoCallbackInstallRollsBackBothOnSecondFailure() {
        val method = AmazonVideoComposition::class.java.methods.firstOrNull {
            it.name == "installCallbacksTransactionally"
        }
        assertNotNull("Missing two-existing-Handler transaction", method)
        val firstOriginal = Any()
        val secondOriginal = Any()
        var first: Any? = firstOriginal
        var second: Any? = secondOriginal
        val installed = method!!.invoke(AmazonVideoComposition,
            firstOriginal, secondOriginal, Any(), Any(),
            { value: Any? -> first = value },
            { _: Any? -> throw IllegalStateException("second") },
            { first }, { second }) as Boolean
        assertFalse(installed)
        assertSame(firstOriginal, first)
        assertSame(secondOriginal, second)
    }

    @Test fun exactFlagFilterPreservesEveryOtherEntryIdentity() {
        val method = AmazonVideoComposition::class.java.methods.firstOrNull { it.name == "removeForcedOverlay" }
        assertNotNull("Missing exact merged-map filter", method)
        val retained = Any()
        val map = hashMapOf<String, Any>("force-video-overlays" to Any(), "force-video-overlays-x" to retained)
        method!!.invoke(AmazonVideoComposition, map)
        assertFalse(map.containsKey("force-video-overlays"))
        assertSame(retained, map["force-video-overlays-x"])
        assertEquals(1, map.size)
    }

    @Test fun packagedDefaultIsByteExactToPinnedProviderDexLiteral() {
        val asset = sequenceOf(
            java.io.File("src/main/assets/awv_api25_default.json"),
            java.io.File("app/src/main/assets/awv_api25_default.json")
        ).first { it.isFile }.readText().removeSuffix("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(asset.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(5382, asset.toByteArray().size)
        assertEquals("9b5a374a4df7cd87e40dbf6f4775594146a7cb8600328261eba40a0c10fb0b4b", digest)
    }

    @Test fun packagedDefaultOpensThroughApplicationAssetManager() {
        val value = AmazonVideoComposition.readPinnedDefaultJson(RuntimeEnvironment.getApplication())
        assertEquals(5382, value.toByteArray().size)
        assertTrue(value.contains("\"force-video-overlays\""))
    }

    @Test fun mergeDifferentialCoversNullEmptyValidAndMalformedRemote() {
        fun merge(remote: String?): HashMap<String, Any> =
            AmazonVideoComposition.mergeMaps("default", remote) { map, json ->
                when (json) {
                    "default" -> map["base"] = "kept"
                    "" -> Unit
                    "valid" -> map["remote"] = "merged"
                    else -> throw IllegalArgumentException("malformed:$json")
                }
            }

        assertEquals(mapOf("base" to "kept"), merge(null))
        assertEquals(mapOf("base" to "kept"), merge(""))
        assertEquals(mapOf("base" to "kept", "remote" to "merged"), merge("valid"))
        val exact = assertThrows(IllegalArgumentException::class.java) { merge("{") }
        assertEquals("malformed:{", exact.message)
    }

    @Test fun actualResponseEntryCoversNullEmptyMalformedAndValidRemoteWithRealCleanup() {
        for (remote in listOf<String?>(null, "fixture-empty", "fixture-malformed", "fixture-valid")) {
            resetProviderFixture()
            val (client, listener) = client()
            val timer = client.mTimer as ProviderClientFixture.RecordingTimer
            val handler = ProviderClientFixture.responseHandler(client)
            assertTrue(interceptResponse(message(remote = remote), handler, client))
            val expected = mutableMapOf<String, Any>("default" to "kept", "override" to
                if (remote == "fixture-valid") "remote" else "default")
            if (remote == "fixture-valid") expected["remote"] = "kept"
            assertEquals(listOf(expected), listener.deliveries)
            assertFalse(listener.deliveries.single().containsKey("force-video-overlays"))
            assertTrue(timer.cancelled)
            assertNull(client.mTimer)
            assertSame(listener, client.mArcusFeatureFlagsUpdateListener)
            assertEquals(0, handler.originalHandleCalls)
            assertEquals(listOf("listener", "cancel", "unbind"),
                ProviderClientFixture.observations.filter { it in listOf("listener", "cancel", "unbind") })
            assertFalse(ProviderClientFixture.observations.any { it.endsWith("listener=null") || it.endsWith("listener=changed") })
            assertTrue(ProviderClientFixture.observations.contains("unbind:timer=null"))
        }
    }

    @Test fun actualResponsePerformsExactlyTwoUnconditionalMergesWithFreshReflectiveInputs() {
        for (remote in listOf<String?>(null, "fixture-valid")) {
            resetProviderFixture()
            val (client, listener) = client()
            val first = RecordingContext(RuntimeEnvironment.getApplication(), "com.buccaneer.first")
            val second = RecordingContext(RuntimeEnvironment.getApplication(), "com.buccaneer.second")
            org.chromium.base.ContextUtils.setApplicationContextsForTests(first, second, fixtureContext)
            ProviderClientFixture.setChromiumVersions("chromium-first", "chromium-second")

            assertTrue(interceptResponse(message(remote = remote), ProviderClientFixture.responseHandler(client), client))

            assertEquals(listOf(
                "applicationContext", "package:com.buccaneer.first",
                "chromium", "chromium:listener=same",
                "merge:fixture-default:com.buccaneer.first:chromium-first", "merge:listener=same",
                "applicationContext", "package:com.buccaneer.second",
                "chromium", "chromium:listener=same",
                "merge:${remote}:com.buccaneer.second:chromium-second", "merge:listener=same",
                "listener", "listener:listener=same", "cancel", "cancel:listener=same",
                "applicationContext", "unbind", "unbind:listener=same", "unbind:timer=null"
            ), ProviderClientFixture.observations)
            assertEquals(1, listener.deliveries.size)
        }
    }

    @Test fun actualTimeoutPerformsExactlyOneDefaultMergeWithOneReflectiveInputSet() {
        val (client, listener) = client()
        val first = RecordingContext(RuntimeEnvironment.getApplication(), "com.buccaneer.timeout")
        val unused = RecordingContext(RuntimeEnvironment.getApplication(), "com.buccaneer.unused")
        org.chromium.base.ContextUtils.setApplicationContextsForTests(first, unused)
        ProviderClientFixture.setChromiumVersions("chromium-timeout", "chromium-unused")

        assertTrue(interceptTimeout(message(remote = "fixture-valid"), client))

        assertEquals(listOf(
            "applicationContext", "package:com.buccaneer.timeout",
            "chromium", "chromium:listener=same",
            "merge:fixture-default:com.buccaneer.timeout:chromium-timeout", "merge:listener=same",
            "listener", "listener:listener=same"
        ), ProviderClientFixture.observations)
        assertEquals(listOf(mapOf("default" to "kept", "override" to "default")), listener.deliveries)
    }

    @Test fun nullRemoteMergerFailurePreservesIdentityAndSkipsResponseCleanup() {
        val (client, listener) = client()
        val timer = client.mTimer as ProviderClientFixture.RecordingTimer
        val exact = IllegalStateException("fixture-null-merger")
        ProviderClientFixture.mergerFailureJson = null
        ProviderClientFixture.mergerFailure = exact

        val thrown = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
            interceptResponse(message(remote = null), ProviderClientFixture.responseHandler(client), client)
        }

        assertSame(exact, thrown.cause)
        assertTrue(listener.deliveries.isEmpty())
        assertSame(timer, client.mTimer)
        assertFalse(timer.cancelled)
        assertFalse(ProviderClientFixture.observations.contains("cancel"))
        assertFalse(ProviderClientFixture.observations.contains("unbind"))
        assertSame(listener, client.mArcusFeatureFlagsUpdateListener)
    }

    @Test fun actualResponseEntryRejectsUnknownOwnerMismatchAndNullListenerWithoutSideEffects() {
        val (client, _) = client()
        val correct = ProviderClientFixture.responseHandler(client)
        assertFalse(interceptResponse(message(99, "fixture-valid"), correct, client))
        assertFalse(interceptResponse(message(remote = "fixture-valid"),
            ProviderClientFixture.responseHandler(ProviderClientFixture()), client))
        client.mArcusFeatureFlagsUpdateListener = null
        assertFalse(interceptResponse(message(remote = "fixture-valid"), correct, client))
        assertTrue(ProviderClientFixture.observations.isEmpty())
        assertNotNull(client.mTimer)
    }

    @Test fun actualTimeoutEntryCoversUnknownTimerAbsentNullListenerAndDefaultDeliveryOnly() {
        val (client, listener) = client()
        assertFalse(interceptTimeout(message(99, "fixture-valid"), client))
        client.mTimer = null
        assertFalse(interceptTimeout(message(remote = "fixture-valid"), client))
        client.mTimer = ProviderClientFixture.RecordingTimer(client)
        client.mArcusFeatureFlagsUpdateListener = null
        assertFalse(interceptTimeout(message(remote = "fixture-valid"), client))
        client.mArcusFeatureFlagsUpdateListener = listener
        val timer = client.mTimer
        assertTrue(interceptTimeout(message(remote = "fixture-valid"), client))
        assertEquals(listOf(mapOf("default" to "kept", "override" to "default")), listener.deliveries)
        assertSame(timer, client.mTimer)
        assertFalse((timer as ProviderClientFixture.RecordingTimer).cancelled)
        assertFalse(ProviderClientFixture.observations.contains("unbind"))
        assertSame(listener, client.mArcusFeatureFlagsUpdateListener)
    }

    @Test fun actualEntriesPreserveRuntimeExceptionAndErrorIdentityAndShortCircuitCleanup() {
        for (response in listOf(true, false)) for (error in listOf(false, true)) {
            resetProviderFixture()
            val (client, listener) = client()
            val exact: Throwable = if (error) AssertionError("listener-error") else IllegalStateException("listener-runtime")
            if (exact is Error) listener.errorFailure = exact else listener.runtimeFailure = exact as RuntimeException
            val method = if (response) "interceptResponse" else "interceptTimeout"
            val thrown = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                if (response) interceptResponse(message(remote = "fixture-valid"), ProviderClientFixture.responseHandler(client), client)
                else interceptTimeout(message(remote = "fixture-valid"), client)
            }
            assertSame("$method must preserve listener throwable identity", exact, thrown.cause)
            assertNotNull(client.mTimer)
            assertFalse((client.mTimer as ProviderClientFixture.RecordingTimer).cancelled)
            assertFalse(ProviderClientFixture.observations.contains("unbind"))
            assertSame(listener, client.mArcusFeatureFlagsUpdateListener)
        }
    }

    @Test fun actualResponseUnbindFailureIsSwallowedAfterCancelAndNull() {
        val (client, listener) = client()
        val timer = client.mTimer as ProviderClientFixture.RecordingTimer
        fixtureContext.unbindFailure = IllegalStateException("fixture-unbind")
        assertTrue(interceptResponse(message(remote = "fixture-valid"), ProviderClientFixture.responseHandler(client), client))
        assertTrue(timer.cancelled)
        assertNull(client.mTimer)
        assertSame(listener, client.mArcusFeatureFlagsUpdateListener)
        assertEquals(listOf("listener", "cancel", "unbind"),
            ProviderClientFixture.observations.filter { it in listOf("listener", "cancel", "unbind") })
    }

    @Test fun actualResponseTimerAbsentStillUnbindsAndPreservesListener() {
        val (client, listener) = client()
        client.mTimer = null
        assertTrue(interceptResponse(message(remote = "fixture-valid"), ProviderClientFixture.responseHandler(client), client))
        assertEquals(listOf("listener", "unbind"),
            ProviderClientFixture.observations.filter { it in listOf("listener", "cancel", "unbind") })
        assertNull(client.mTimer)
        assertSame(listener, client.mArcusFeatureFlagsUpdateListener)
    }

    @Test fun actualResponseCancelFailureShortCircuitsNullAndUnbindWithIdentity() {
        val (client, listener) = client()
        val timer = client.mTimer as ProviderClientFixture.RecordingTimer
        val exact = IllegalStateException("fixture-cancel")
        timer.cancelFailure = exact
        val thrown = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
            interceptResponse(message(remote = "fixture-valid"), ProviderClientFixture.responseHandler(client), client)
        }
        assertSame(exact, thrown.cause)
        assertSame(timer, client.mTimer)
        assertFalse(ProviderClientFixture.observations.contains("unbind"))
        assertSame(listener, client.mArcusFeatureFlagsUpdateListener)
    }

    @Test fun chainedCallbackDifferentialPreservesTrueFalseAndOriginalException() {
        var repairs = 0
        assertTrue(AmazonVideoComposition.dispatchChained({ true }, { repairs++; false }))
        assertEquals(0, repairs)
        assertTrue(AmazonVideoComposition.dispatchChained({ false }, { repairs++; true }))
        assertEquals(1, repairs)
        val exact = IllegalStateException("provider callback")
        val thrown = assertThrows(IllegalStateException::class.java) {
            AmazonVideoComposition.dispatchChained({ throw exact }, { repairs++; true })
        }
        assertSame(exact, thrown)
        assertEquals(1, repairs)
    }

    @Test fun responseDeliveryFiltersBeforeListenerThenContinuesTimerCancelAndNull() {
        val calls = mutableListOf<String>()
        var timer: Any? = Any()
        val map = hashMapOf<String, Any>("force-video-overlays" to Any(), "other" to 1)

        assertTrue(AmazonVideoComposition.deliverFiltered(map, {
            assertFalse(it.containsKey("force-video-overlays"))
            assertEquals(1, it["other"])
            calls += "listener"
        }) {
            calls += "cancel"
            timer = null
        })

        assertEquals(listOf("listener", "cancel"), calls)
        assertNull(timer)
    }

    @Test fun responseAndTimeoutListenerExceptionsPropagateWithoutContinuation() {
        for (branch in listOf("response", "timeout")) {
            var continued = false
            val exact = IllegalArgumentException(branch)
            val thrown = assertThrows(IllegalArgumentException::class.java) {
                AmazonVideoComposition.deliverFiltered(hashMapOf("force-video-overlays" to Any()),
                    { throw exact }, { continued = true })
            }
            assertSame(exact, thrown)
            assertFalse(continued)
        }
    }

    @Test fun installedDexResponseCleanupCancelsNullsThenUnbindsAndSwallowsOnlyUnbindFailure() {
        val calls = mutableListOf<String>()
        var timer: Any? = Any()
        val unbindFailure = IllegalStateException("already unbound")
        var printed: Throwable? = null
        AmazonVideoComposition.completeResponse(
            { timer }, { calls += "cancel" }, { calls += "null"; timer = null },
            { calls += "unbind"; throw unbindFailure }, { printed = it })
        assertEquals(listOf("cancel", "null", "unbind"), calls)
        assertNull(timer)
        assertSame(unbindFailure, printed)
    }

    @Test fun installedDexResponseCleanupPropagatesCancelFailureWithoutNullOrUnbind() {
        val exact = IllegalStateException("cancel")
        var cleared = false
        var unbound = false
        val thrown = assertThrows(IllegalStateException::class.java) {
            AmazonVideoComposition.completeResponse(
                { Any() }, { throw exact }, { cleared = true }, { unbound = true }, { fail("no unbind") })
        }
        assertSame(exact, thrown)
        assertFalse(cleared)
        assertFalse(unbound)
    }

    @Test fun productUsesExistingHandlersWithoutReplacingProviderObjectsOrSchedulingWork() {
        val source = sequenceOf(
            java.io.File("src/main/java/com/buccaneer/browser/AmazonVideoComposition.kt"),
            java.io.File("app/src/main/java/com/buccaneer/browser/AmazonVideoComposition.kt")
        ).first { it.isFile }.readText()
        assertTrue(source.contains("mClientMessenger"))
        assertTrue(source.contains("mTimerTaskHandler"))
        assertTrue(source.contains("getDeclaredField(\"mCallback\")"))
        for (forbidden in listOf("postDelayed(", "TimerTask(", ".bindService(", "mServiceConnection\").set",
            "listenerField.set", "mArcusFeatureFlagsUpdateListener\").set", "mClientMessenger\").set",
            "mTimerTaskHandler\").set"))
            assertFalse("Forbidden scheduling/replacement seam: $forbidden", source.contains(forbidden))
    }
}
