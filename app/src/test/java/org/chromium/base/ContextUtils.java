package org.chromium.base;

import android.content.Context;

import com.amazon.webview.awvdeploymentservice.client.ProviderClientFixture;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/** Host-only reflection fixture. The installed vendor DEX is not executed by Robolectric. */
public final class ContextUtils {
    private static Context applicationContext;
    private static final Deque<Context> applicationContexts = new ArrayDeque<>();

    private ContextUtils() {}

    public static Context getApplicationContext() {
        ProviderClientFixture.observations.add("applicationContext");
        return applicationContexts.isEmpty() ? applicationContext : applicationContexts.removeFirst();
    }

    public static void setApplicationContextForTests(Context context) {
        applicationContext = context;
        applicationContexts.clear();
    }

    public static void setApplicationContextsForTests(Context... contexts) {
        applicationContexts.clear();
        applicationContexts.addAll(Arrays.asList(contexts));
    }
}
