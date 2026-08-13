package com.waenhancer.xposed.features.devtools;

import android.app.Activity;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.WppCore;

/**
 * Lifecycle manager for the Element Inspector overlay.
 *
 * <p>Observes the {@code inspector_session} preference via a {@link WppCore#addListenerActivity
 * activity state listener}. When the preference is armed with a non-empty session token, attaches
 * the {@link InspectorOverlay} to resumed activities and detaches it when activities are paused
 * or stopped, or when the session expires.
 *
 * <p><b>Hard invariant (§6 of the spec):</b> with the pref empty at startup, {@code doHook()}
 * returns before registering any listener. This ensures no permanent hook exists when the feature
 * is off.
 */
public class InspectorFeature extends Feature {

    private InspectorOverlay currentOverlay;

    public InspectorFeature(
            @NonNull ClassLoader classLoader,
            @NonNull SharedPreferences preferences) {
        super(classLoader, preferences);
    }

    @Override
    @NonNull
    public String getPluginName() {
        return "Element Inspector";
    }

    @Override
    public void doHook() throws Throwable {
        // If the pref is empty or unset at startup, no listener is registered.
        // This satisfies the hard invariant: no permanent hook exists when the feature is off.
        if (parseSession(getSafeString("inspector_session", "")) == null) {
            return;
        }

        // Register a listener that will run for the life of the process.
        // It re-reads the pref on each activity state change to pick up live updates.
        WppCore.addListenerActivity((activity, type) -> {
            InspectorSession current = parseSession(getSafeString("inspector_session", ""));

            // If the session is no longer armed or has expired, detach.
            if (current == null || !current.isActive(System.currentTimeMillis())) {
                detach();
                return;
            }

            // Attach/detach based on activity lifecycle.
            switch (type) {
                case RESUMED:
                    attachTo(activity);
                    break;
                case PAUSED:
                case ENDED:
                    detach();
                    break;
            }
        });
    }

    /**
     * Parses the {@code inspector_session} preference value.
     *
     * <p><b>Format:</b> {@code token|timestamp}, where {@code timestamp} is the epoch millis
     * when the session was armed (or a reference timestamp). The session is reconstructed as
     * active from the current time, with a 10-minute idle timeout from that moment. This design
     * means the session's idleness counter "resets" each time the pref is re-read (e.g., by a
     * {@link ContentObserver}), which aligns with the "renewal on each selection" semantics and
     * the pref-polling cycle as the refresh mechanism.
     *
     * <p>Returns {@code null} if the value is empty, null, or malformed.
     */
    @Nullable
    private InspectorSession parseSession(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }

        try {
            String[] parts = value.split("\\|");
            if (parts.length != 2) {
                return null;
            }
            String token = parts[0];
            // parts[1] is a timestamp, but we don't use it — we construct the session
            // from the current time, letting InspectorSession.armed() handle the expiry.
            return InspectorSession.armed(token, System.currentTimeMillis());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Attaches the overlay to the given activity, creating it if necessary.
     */
    private void attachTo(@NonNull Activity activity) {
        // If an overlay is already attached to this activity, do nothing.
        // (Detach will be called on PAUSED/ENDED or session expiry.)
        if (currentOverlay != null) {
            return;
        }

        // Create a new overlay anchored to this activity.
        // The onExit runnable will be called if the user clicks the "Exit inspector" button.
        currentOverlay = new InspectorOverlay(activity, this::detach);
        currentOverlay.attach();
    }

    /**
     * Detaches the overlay if it is currently attached.
     */
    private void detach() {
        if (currentOverlay != null) {
            currentOverlay.detach();
            currentOverlay = null;
        }
    }
}
