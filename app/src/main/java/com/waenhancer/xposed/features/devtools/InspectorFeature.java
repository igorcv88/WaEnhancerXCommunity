package com.waenhancer.xposed.features.devtools;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.waenhancer.config.SafePrefs;
import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.FeatureLoader;
import com.waenhancer.xposed.core.WppCore;

import java.util.Collections;

/** Lifecycle manager for the in-process Element Inspector overlay. */
public class InspectorFeature extends Feature {

    private static final String SESSION_KEY = "inspector_session";
    private static final long POLL_INTERVAL_MILLIS = 30_000L;
    private static final long CLOCK_SKEW_MILLIS = 5_000L;
    private static final int MAX_TOKEN_LENGTH = 128;

    private InspectorOverlay currentOverlay;
    private InspectorSession retainedSession;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private Runnable pollRunnable;

    public InspectorFeature(@NonNull ClassLoader classLoader,
                            @NonNull SharedPreferences preferences) {
        super(classLoader, preferences);
    }

    @Override
    @NonNull
    public String getPluginName() {
        return "Element Inspector";
    }

    @Override
    public void doHook() {
        InspectorSession initial = parseSession(getSafeString(SESSION_KEY, ""),
                System.currentTimeMillis());
        // No lifecycle listener is installed while the inspector is off. Arming from the
        // companion app intentionally requires opening/reopening WhatsApp.
        if (initial == null) return;
        retainedSession = initial;

        WppCore.addListenerActivity((activity, type) -> {
            long now = System.currentTimeMillis();
            InspectorSession liveSession = currentOverlay != null
                    ? currentOverlay.getSession() : retainedSession;
            if (liveSession != null && !liveSession.isActive(now)) {
                endSession(true);
                return;
            }

            InspectorSession persisted = parseSession(getSafeString(SESSION_KEY, ""), now);
            if (persisted == null) {
                endSession(false);
                return;
            }

            switch (type) {
                case RESUMED:
                    if (currentOverlay == null) attachTo(activity, persisted);
                    break;
                case PAUSED:
                case ENDED:
                    detachTransient();
                    break;
            }
        });
    }

    /**
     * Parse token|armedAtMillis. The arm timestamp is now enforced, not informational: an old
     * settings backup or stale public preference must never resurrect an inspector session.
     */
    @Nullable
    static InspectorSession parseSession(@Nullable String value, long now) {
        if (value == null || value.isEmpty()) return null;
        try {
            String[] parts = value.split("\\|", -1);
            if (parts.length != 2) return null;
            String token = parts[0].trim();
            if (token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) return null;
            long armedAt = Long.parseLong(parts[1]);
            if (armedAt > now + CLOCK_SKEW_MILLIS) return null;
            if (now - armedAt >= InspectorSession.IDLE_TIMEOUT_MILLIS) return null;
            return InspectorSession.armed(token, now);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void attachTo(@NonNull Activity activity, @NonNull InspectorSession freshlyParsed) {
        if (currentOverlay != null) return;
        long now = System.currentTimeMillis();
        InspectorSession seed = retainedSession != null && retainedSession.isActive(now)
                ? retainedSession : freshlyParsed;
        currentOverlay = new InspectorOverlay(activity, () -> endSession(true));
        currentOverlay.setSession(seed);
        currentOverlay.attach();
        startPolling();
    }

    private void detachTransient() {
        if (currentOverlay != null) {
            retainedSession = currentOverlay.getSession();
            currentOverlay.detach();
            currentOverlay = null;
        }
        stopPolling();
    }

    private void endSession(boolean clearPref) {
        if (currentOverlay != null) {
            currentOverlay.detach();
            currentOverlay = null;
        }
        retainedSession = null;
        stopPolling();
        if (clearPref) clearSessionPref();
    }

    /**
     * Current master has a writable SafePrefs bridge for XSharedPreferences. Route the clear
     * through it instead of keeping the stale branch's no-op for the common read-only path.
     */
    private void clearSessionPref() {
        try {
            SafePrefs.put(FeatureLoader.mApp, prefs,
                    Collections.singletonMap(SESSION_KEY, ""));
        } catch (Throwable ignored) {
            // Exiting the inspector must never crash WhatsApp even if IPC is unavailable.
        }
    }

    private void startPolling() {
        stopPolling();
        pollRunnable = () -> {
            InspectorSession liveSession = currentOverlay != null
                    ? currentOverlay.getSession() : retainedSession;
            if (liveSession != null && !liveSession.isActive(System.currentTimeMillis())) {
                endSession(true);
                return;
            }
            pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MILLIS);
        };
        pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MILLIS);
    }

    private void stopPolling() {
        if (pollRunnable != null) {
            pollHandler.removeCallbacks(pollRunnable);
            pollRunnable = null;
        }
    }
}
