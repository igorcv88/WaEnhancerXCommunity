package com.waenhancer.xposed.features.devtools;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.WppCore;

import de.robv.android.xposed.XSharedPreferences;

/**
 * Lifecycle manager for the Element Inspector overlay.
 *
 * <p>Observes the {@code inspector_session} preference via a {@link WppCore#addListenerActivity
 * activity state listener}. When the preference is armed with a non-empty session token, attaches
 * the {@link InspectorOverlay} to resumed activities and detaches it when activities are paused,
 * stopped, or when the session expires due to inactivity.
 *
 * <p>The overlay manages its own session state via touch-based renewal ({@link
 * InspectorSession#touched(long)}). This feature's job is to carry that live session across the
 * destroy/recreate of {@link InspectorOverlay} instances that happens on every Activity
 * transition — see {@link #retainedSession} — so the idle clock is driven only by real
 * selections, never by navigation.
 *
 * <p><b>Hard invariant (§6 of the spec):</b> with the pref empty at startup, {@code doHook()}
 * returns before registering any listener. This ensures no permanent hook exists when the feature
 * is off. This is intentional and asymmetric: disarming propagates live (see {@link
 * #clearSessionPref()} and the expiry path below), but arming a previously-off inspector requires
 * reopening WhatsApp, because no listener may exist while the pref is empty.
 */
public class InspectorFeature extends Feature {

    /** How often we re-check the live session for idle expiry while no lifecycle event fires. */
    private static final long POLL_INTERVAL_MILLIS = 30_000L;

    private InspectorOverlay currentOverlay;

    /**
     * The real, ongoing session — read from {@link InspectorOverlay#getSession()} just before an
     * overlay instance is torn down on {@code PAUSED}/{@code ENDED}, and used to seed the next
     * overlay instance on the following {@code RESUMED}. This is what makes the idle clock survive
     * Activity transitions: without it, every re-attach would re-derive a fresh 10-minute window
     * from {@link #parseSession}, and the timeout would never advance as long as the user keeps
     * navigating.
     */
    private InspectorSession retainedSession;

    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private Runnable pollRunnable;

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
            // Check if the live session (retained across transitions, renewed via real
            // selections) has expired. This is the actual idle-timeout gate.
            InspectorSession liveSession = currentOverlay != null
                    ? currentOverlay.getSession()
                    : retainedSession;
            if (liveSession != null && !liveSession.isActive(System.currentTimeMillis())) {
                endSession(true);
                return;
            }

            // Check if the pref is still armed (module may have disarmed it).
            String prefValue = getSafeString("inspector_session", "");
            InspectorSession parsed = parseSession(prefValue);

            if (parsed == null) {
                // Pref already empty — module disarmed it. Nothing to write back.
                endSession(false);
                return;
            }

            // Pref is armed; handle activity lifecycle.
            switch (type) {
                case RESUMED:
                    if (currentOverlay == null) {
                        attachTo(activity, parsed);
                    }
                    // If overlay already exists, don't recreate it; let it keep managing
                    // its own session state via touch-based renewal.
                    break;
                case PAUSED:
                case ENDED:
                    detachTransient();
                    break;
            }
        });
    }

    /**
     * Parses the {@code inspector_session} preference value.
     *
     * <p><b>Format:</b> {@code token|timestamp} (pipe-delimited), where:
     * <ul>
     *   <li>{@code token} is the unique session identifier (generated by Task B4)</li>
     *   <li>{@code timestamp} is when the session was armed (informational, for detecting
     *       re-arm events if needed)</li>
     * </ul>
     *
     * <p>Returns {@code null} if the value is empty, null, or malformed. On a well-formed value,
     * builds a fresh 10-minute session via {@link InspectorSession#armed(String, long)}. This
     * fresh session is only used to seed the very first attach after arming ({@link #attachTo}
     * prefers {@link #retainedSession} whenever one is still active) — it does not drive expiry
     * on its own.
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
            // The timestamp field (parts[1]) is parsed but not used for expiry computation.
            // Idle-timeout tracking is owned by the live session (see retainedSession).
            return InspectorSession.armed(token, System.currentTimeMillis());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Attaches the overlay to the given activity, seeding its session from {@link
     * #retainedSession} when one is still active (real re-attach after a navigation), or from a
     * freshly armed session otherwise (first attach after arming, or the retained session had
     * genuinely expired).
     */
    private void attachTo(@NonNull Activity activity, @NonNull InspectorSession freshlyParsed) {
        // Should not happen, but guard against double-attach.
        if (currentOverlay != null) {
            return;
        }

        long now = System.currentTimeMillis();
        InspectorSession seed = (retainedSession != null && retainedSession.isActive(now))
                ? retainedSession
                : freshlyParsed;

        // Create a new overlay anchored to this activity.
        // The onExit runnable will be called if the user clicks the "Exit inspector" button.
        currentOverlay = new InspectorOverlay(activity, () -> endSession(true));
        currentOverlay.setSession(seed);
        currentOverlay.attach();
        startPolling();
    }

    /**
     * Tears down the overlay for a transient Activity transition (PAUSED/ENDED), retaining its
     * live session so the next {@link #attachTo} picks up the real idle clock instead of a fresh
     * one. Does not touch the pref — the session is still logically armed.
     */
    private void detachTransient() {
        if (currentOverlay != null) {
            retainedSession = currentOverlay.getSession();
            currentOverlay.detach();
            currentOverlay = null;
        }
        stopPolling();
    }

    /**
     * Ends the session for real: user tapped "Exit inspector", the session expired from
     * inactivity, or the module already disarmed the pref externally. Tears down the overlay,
     * drops the retained session, stops the poll, and — when {@code clearPref} is true — attempts
     * to write the pref back to {@code ""} so a stale armed pref doesn't resurrect the overlay on
     * the next RESUMED.
     */
    private void endSession(boolean clearPref) {
        if (currentOverlay != null) {
            currentOverlay.detach();
            currentOverlay = null;
        }
        retainedSession = null;
        stopPolling();
        if (clearPref) {
            clearSessionPref();
        }
    }

    /**
     * Attempts to write {@code inspector_session} back to {@code ""} from the WhatsApp-process
     * side.
     *
     * <p>Whether this actually reaches the module's own storage depends on which concrete {@link
     * SharedPreferences} implementation was bridged in at {@code FeatureLoader.load()}:
     * <ul>
     *   <li>{@link XSharedPreferences} (the common path, when the prefs file is readable directly
     *       off disk) is a read-only, direct-file-read bridge. Its {@code edit()} throws {@code
     *       UnsupportedOperationException}. Writes from this side are structurally impossible
     *       through this path, so this method is a deliberate no-op when {@link #prefs} is an
     *       {@code XSharedPreferences}.</li>
     *   <li>{@code ProviderSharedPreferences} (the fallback used when the direct file read comes
     *       back empty) does support {@code edit()}/{@code putString()}/{@code apply()}, and its
     *       {@code Editor} forwards every write to the module's content provider via {@code
     *       put_preference} — see {@code ProviderSharedPreferences.ProviderEditor.syncToProvider}.
     *       On that path this write genuinely propagates back to the module's storage.</li>
     * </ul>
     *
     * <p>When the pref cannot be cleared from here, the overlay still detaches and stops reacting
     * to touches locally, so "Exit inspector" and idle-expiry are correct from the user's
     * perspective inside WhatsApp. But the module's own pref (and therefore {@code
     * MainActivity}'s toggle) can be left showing a stale "armed" state until the user manually
     * disarms it there — {@code MainActivity} does not currently listen for this in-process exit
     * event. This is a known, documented limitation, not a bug introduced by this fix.
     */
    private void clearSessionPref() {
        if (prefs instanceof XSharedPreferences) {
            // Read-only bridge: cannot write back from this side. See method javadoc.
            return;
        }
        try {
            prefs.edit().putString("inspector_session", "").apply();
        } catch (Throwable ignored) {
            // Defensive: a write-back failure must never crash the hook.
        }
    }

    private void startPolling() {
        stopPolling();
        pollRunnable = () -> {
            InspectorSession liveSession = currentOverlay != null
                    ? currentOverlay.getSession()
                    : retainedSession;
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
