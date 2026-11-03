package com.waenhancer.xposed.features.devtools;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.waenhancer.config.SafePrefs;
import com.waenhancer.diagnostics.InspectorTrace;
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
    private static final int MAX_ATTACH_ATTEMPTS = 3;
    private static final long ATTACH_RETRY_MILLIS = 120L;

    private InspectorOverlay currentOverlay;
    private Activity attachedActivity;
    private InspectorSession retainedSession;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private Runnable pollRunnable;
    private Runnable pendingAttach;
    private Activity pendingActivity;

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
        retainedSession = initial;

        // The listener must exist even when initially disarmed. Otherwise arming from the
        // companion while WhatsApp remains alive never enables the inspector until a force-stop.
        // Off sessions do no probing, polling or overlay work.

        WppCore.addListenerActivity((activity, type) -> {
            long now = System.currentTimeMillis();
            InspectorSession persisted = parseSession(getSafeString(SESSION_KEY, ""), now);
            if (persisted == null) {
                endSession(false);
                return;
            }

            InspectorSession liveSession = currentOverlay != null
                    ? currentOverlay.getSession() : retainedSession;
            if (liveSession != null && !liveSession.matches(persisted.token())) {
                detachTransient();
                retainedSession = persisted;
                liveSession = persisted;
            }
            if (liveSession != null && !liveSession.isActive(now)) {
                endSession(true);
                return;
            }

            switch (type) {
                case RESUMED:
                    if (currentOverlay == null) scheduleAttach(activity, persisted, 0, 0L);
                    break;
                case PAUSED:
                case ENDED:
                case DESTROYED:
                    // The previous Activity can stop *after* a new Activity resumes.
                    // Its callback must not detach the new inspector window or cancel its retry.
                    if (activity == attachedActivity || activity == pendingActivity)
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

    private void scheduleAttach(Activity activity, InspectorSession session,
                                int attempt, long delayMillis) {
        cancelPendingAttach();
        Runnable task = () -> {
            pendingAttach = null;
            pendingActivity = null;
            if (currentOverlay != null || !session.isActive(System.currentTimeMillis())
                    || activity.isFinishing() || activity.isDestroyed()
                    || WppCore.getActivityState(activity)
                    != WppCore.ActivityChangeState.ChangeType.RESUMED) return;

            Window window = activity.getWindow();
            View decor = window == null ? null : window.getDecorView();
            if (decor == null || !decor.isAttachedToWindow()
                    || decor.getWindowToken() == null) {
                if (attempt + 1 < MAX_ATTACH_ATTEMPTS) {
                    scheduleAttach(activity, session, attempt + 1, ATTACH_RETRY_MILLIS);
                } else {
                    de.robv.android.xposed.XposedBridge.log(
                            "WaEnhancer Inspector: host window token unavailable after bounded retries");
                }
                return;
            }
            attachTo(activity, session, attempt);
        };
        pendingAttach = task;
        pendingActivity = activity;
        pollHandler.postDelayed(task, delayMillis);
    }

    private void cancelPendingAttach() {
        if (pendingAttach != null) {
            pollHandler.removeCallbacks(pendingAttach);
            pendingAttach = null;
        }
        pendingActivity = null;
    }

    private void attachTo(@NonNull Activity activity, @NonNull InspectorSession freshlyParsed,
                          int attempt) {
        if (currentOverlay != null) return;
        long now = System.currentTimeMillis();
        InspectorSession seed = retainedSession != null && retainedSession.isActive(now)
                ? retainedSession : freshlyParsed;
        InspectorTrace.arm(activity);
        InspectorTrace.glassEvent("INSPECTOR_ARM_SEEN", "activity="
                + activity.getClass().getName() + " attempt=" + attempt);
        try {
            currentOverlay = new InspectorOverlay(activity, () -> endSession(true));
            attachedActivity = activity;
            currentOverlay.setSession(seed);
            currentOverlay.attach();
        } catch (Throwable failure) {
            InspectorTrace.glassEvent("INSPECTOR_ATTACH_FAILED", "type="
                    + failure.getClass().getSimpleName() + " cause="
                    + (failure.getCause() == null ? "none"
                    : failure.getCause().getClass().getSimpleName()));
            de.robv.android.xposed.XposedBridge.log("WaEnhancer Inspector overlay failed: " + failure);
            if (currentOverlay != null) {
                try { currentOverlay.detach(); } catch (Throwable ignored) { }
            }
            currentOverlay = null;
            attachedActivity = null;
            InspectorTrace.disarm();
            // An Activity can be RESUMED before its token is accepted by WindowManager.
            // Retry only BadToken failures, and never after a pause or session expiry.
            if (failure instanceof WindowManager.BadTokenException
                    && attempt + 1 < MAX_ATTACH_ATTEMPTS) {
                scheduleAttach(activity, seed, attempt + 1, ATTACH_RETRY_MILLIS);
            }
            return;
        }
        InspectorTrace.glassEvent("INSPECTOR_ATTACH_OK", "activity="
                + activity.getClass().getName());
        // Two bounded snapshots distinguish the home mini-player from the conversation header
        // without relying on UIAutomator's foreground timing or on WhatsApp class names.
        sampleAfter(activity, 400L);
        sampleAfter(activity, 1600L);
        startPolling();
    }

    private void sampleAfter(Activity activity, long delayMs) {
        pollHandler.postDelayed(() -> {
            if (attachedActivity != activity || currentOverlay == null) return;
            InspectorTrace.activitySnapshot(activity, "resume-" + delayMs + "ms");
        }, delayMs);
    }

    private void detachTransient() {
        cancelPendingAttach();
        if (currentOverlay != null) {
            retainedSession = currentOverlay.getSession();
            currentOverlay.detach();
            currentOverlay = null;
        }
        attachedActivity = null;
        InspectorTrace.disarm();
        stopPolling();
    }

    private void endSession(boolean clearPref) {
        cancelPendingAttach();
        if (currentOverlay != null) {
            currentOverlay.detach();
            currentOverlay = null;
        }
        retainedSession = null;
        attachedActivity = null;
        InspectorTrace.disarm();
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
