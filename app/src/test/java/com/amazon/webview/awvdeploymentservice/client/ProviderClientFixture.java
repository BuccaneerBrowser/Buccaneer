package com.amazon.webview.awvdeploymentservice.client;

import android.content.ServiceConnection;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Timer;

/**
 * Provider-shaped host fixture grounded in the archived installed DEX field/method contract.
 * It deliberately models merge outcomes, not the vendor JSON parser; vendor DEX does not run on host.
 */
public final class ProviderClientFixture {
    public static final List<String> observations = new ArrayList<>();
    public static ProviderClientFixture observedClient;
    private static final Deque<String> chromiumVersions = new ArrayDeque<>();
    public static RuntimeException mergerFailure;
    public static String mergerFailureJson;

    public Object mArcusFeatureFlagsUpdateListener;
    public Timer mTimer;
    public ServiceConnection mServiceConnection;

    public static String getChromiumVersion() {
        observations.add("chromium");
        observeListener("chromium");
        return chromiumVersions.isEmpty() ? "118.fixture" : chromiumVersions.removeFirst();
    }

    public static void setChromiumVersions(String... versions) {
        chromiumVersions.clear();
        chromiumVersions.addAll(Arrays.asList(versions));
    }

    public static void getFeatureFlagsForApp(HashMap<String, Object> result, String json,
                                             String packageName, String chromiumVersion) {
        observations.add("merge:" + String.valueOf(json) + ":" + packageName + ":" + chromiumVersion);
        observeListener("merge");
        if (mergerFailure != null && java.util.Objects.equals(mergerFailureJson, json)) {
            throw mergerFailure;
        }
        if ("fixture-default".equals(json)) {
            result.put("default", "kept");
            result.put("override", "default");
            result.put("force-video-overlays", "1");
        } else if ("fixture-valid".equals(json)) {
            result.put("remote", "kept");
            result.put("override", "remote");
        } else if (json == null || "fixture-empty".equals(json) || "fixture-malformed".equals(json)) {
            // Installed merger logs and retains the prior map for these cases.
        } else {
            throw new IllegalArgumentException("unexpected fixture input: " + json);
        }
    }

    public static void reset() {
        observations.clear();
        observedClient = null;
        chromiumVersions.clear();
        mergerFailure = null;
        mergerFailureJson = null;
    }

    public static void observeListener(String phase) {
        if (observedClient != null) {
            observations.add(phase + ":listener=" + (observedClient.mArcusFeatureFlagsUpdateListener == null ? "null" : "same"));
        }
    }

    public static final class RecordingListener {
        public final ProviderClientFixture client;
        public final List<HashMap<String, Object>> deliveries = new ArrayList<>();
        public RuntimeException runtimeFailure;
        public Error errorFailure;

        public RecordingListener(ProviderClientFixture client) {
            this.client = client;
        }

        public void onArcusFeatureFlagsUpdate(HashMap<String, Object> flags) {
            observations.add("listener");
            observations.add("listener:listener=" + (client.mArcusFeatureFlagsUpdateListener == this ? "same" : "changed"));
            deliveries.add(new HashMap<>(flags));
            if (runtimeFailure != null) throw runtimeFailure;
            if (errorFailure != null) throw errorFailure;
        }
    }

    public static final class RecordingTimer extends Timer {
        public final ProviderClientFixture client;
        public boolean cancelled;
        public RuntimeException cancelFailure;

        public RecordingTimer(ProviderClientFixture client) {
            super(true);
            this.client = client;
        }

        @Override public void cancel() {
            observations.add("cancel");
            observations.add("cancel:listener=" + (client.mArcusFeatureFlagsUpdateListener == null ? "null" : "same"));
            if (cancelFailure != null) throw cancelFailure;
            cancelled = true;
            super.cancel();
        }
    }

    public static final class OwnedHandler extends Handler {
        public final Object this$0;
        public int originalHandleCalls;

        public OwnedHandler(Object owner) {
            super(Looper.getMainLooper());
            this.this$0 = owner;
        }

        @Override public void handleMessage(Message message) {
            originalHandleCalls++;
            observations.add("original-handler:listener=" +
                    (observedClient.mArcusFeatureFlagsUpdateListener == null ? "null" : "same"));
        }
    }

    public static OwnedHandler responseHandler(ProviderClientFixture client) {
        return new OwnedHandler(new WeakReference<>(client));
    }
}
