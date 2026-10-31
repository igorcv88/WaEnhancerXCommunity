package com.waenhancer.theme;

/**
 * Composer decoration state for one conversation footer. Pure logic, caller's clock.
 *
 * <p>It never changes the native recorder: it only decides when the persistent composer
 * {@link GlassPane} follows a measured native geometry ({@link #PRESENT}) and when the
 * comparatively expensive message-list reservation is committed ({@link #RESERVE}).</p>
 *
 * <p>Device evidence (PR #107 trace) showed the native recorder growing 155→429→444 px and
 * 323→609→624 px within ~1.3 s, and cancellation passing through frames where neither the
 * recorder nor the input row is measurable. The previous policy committed the first transient
 * recorder rect, then held the pane at that stale size until two identical frames and 80 ms of
 * quiet, and hid the pane entirely while neither route resolved. Here placement follows every
 * valid measured geometry immediately, transient unresolved frames keep the last presented
 * geometry visible ({@link #HOLD}), and only the list reservation waits for stability.</p>
 */
public final class ComposerTransition {
    public enum State {
        NORMAL, NORMAL_LAYOUT_PENDING,
        RECORD_START_SIGNAL, RECORD_LAYOUT_PENDING, RECORD_STABLE,
        RECORD_END_SIGNAL, DETACHED
    }

    /** What the footer currently exposes. */
    public enum Route { INPUT, VOICE, UNRESOLVED }

    /** Place the pane at this geometry in this frame. */
    public static final int PRESENT = 1;
    /** Commit the list margin/padding reservation for this geometry. */
    public static final int RESERVE = 2;
    /** Nothing measurable; keep the last presented geometry visible. */
    public static final int HOLD = 4;
    /** Unresolved for longer than {@link #BRIDGE_MAX_MS}: remove the decoration, keep native UI. */
    public static final int HIDE = 8;

    /** Geometry must be unchanged this long before the list reservation follows it. */
    public static final long QUIET_MS = 80L;
    /**
     * Safety bound for the unresolved bridge, not a debounce on the normal path: a build whose
     * recorder cannot be identified must not keep an input-sized pane over a native recorder.
     */
    public static final long BRIDGE_MAX_MS = 700L;

    private State state = State.NORMAL;
    private boolean voice;
    private long epoch;
    private String candidate, presented, reserved;
    private long changedAt, bridgeSince = -1L;
    private int frames;
    private boolean pending;
    private long nativeStart = -1L, firstMeasured = -1L, nativeEnd = -1L, normalReady = -1L;

    public State state() { return state; }
    /** Whether another frame is needed to settle the reservation (no touch/timer needed). */
    public boolean pending() { return pending; }
    /** Incremented on every recorder start/end; callbacks from an older epoch are stale. */
    public long epoch() { return epoch; }
    public boolean recording() { return voice; }
    /** Milliseconds from the native recorder signal (or first discovery) to its first measured geometry; -1 if unknown. */
    public long startToMeasuredMs() { return nativeStart < 0 || firstMeasured < 0 ? -1L : firstMeasured - nativeStart; }
    /** Milliseconds from the native recorder end to the first measured normal input; -1 if unknown. */
    public long endToNormalMs() { return nativeEnd < 0 || normalReady < 0 ? -1L : normalReady - nativeEnd; }

    /**
     * A native recorder visibility/inflation event. Returns true when it opened a new epoch, so
     * the caller wakes exactly one decoration frame. Repeated signals in one epoch are ignored.
     */
    public boolean signal(boolean recorderVisible, long now) {
        if (state == State.DETACHED) reset();
        if (recorderVisible == voice) return false;
        voice = recorderVisible;
        epoch++;
        candidate = null;
        frames = 0;
        if (recorderVisible) {
            state = State.RECORD_START_SIGNAL;
            nativeStart = now;
            firstMeasured = -1L;
        } else {
            state = State.RECORD_END_SIGNAL;
            nativeEnd = now;
            normalReady = -1L;
        }
        pending = true;
        return true;
    }

    /** One pre-draw sample of the native footer. */
    public int sample(Route route, String geometry, long now) {
        if (state == State.DETACHED) reset();
        if (route == Route.UNRESOLVED) {
            if (presented == null) {
                pending = false;
                return 0;
            }
            if (bridgeSince < 0L) bridgeSince = now;
            if (now - bridgeSince > BRIDGE_MAX_MS) {
                presented = null;
                reserved = null;
                candidate = null;
                pending = false;
                return HIDE;
            }
            if (state == State.RECORD_STABLE) state = State.RECORD_LAYOUT_PENDING;
            else if (state == State.NORMAL) state = State.NORMAL_LAYOUT_PENDING;
            pending = true;
            return HOLD;
        }
        bridgeSince = -1L;
        boolean recording = route == Route.VOICE;
        // A native signal may precede the layout that swaps input and recorder. Until the
        // other route is measured (bounded by the bridge window), the still-visible old route is
        // a valid geometry to present, not a reversal of the signal.
        boolean awaitingSwap = recording != voice
                && (state == State.RECORD_START_SIGNAL || state == State.RECORD_END_SIGNAL)
                && now - (voice ? nativeStart : nativeEnd) <= BRIDGE_MAX_MS;
        if (recording != voice && !awaitingSwap) {
            // Layout discovery without (or before) a native signal is the same transition.
            voice = recording;
            epoch++;
            candidate = null;
            frames = 0;
            if (recording) { nativeStart = now; firstMeasured = -1L; }
            else { nativeEnd = now; normalReady = -1L; }
            state = recording ? State.RECORD_LAYOUT_PENDING : State.NORMAL_LAYOUT_PENDING;
        }
        if (!awaitingSwap) {
            if (recording && firstMeasured < 0L) firstMeasured = now;
            if (!recording && nativeEnd >= 0L && normalReady < 0L) normalReady = now;
            if (state == State.RECORD_START_SIGNAL) state = State.RECORD_LAYOUT_PENDING;
            if (state == State.RECORD_END_SIGNAL) state = State.NORMAL_LAYOUT_PENDING;
        }

        String key = route + ":" + geometry;
        if (!key.equals(candidate)) {
            candidate = key;
            changedAt = now;
            frames = 1;
        } else if (frames < Integer.MAX_VALUE) frames++;

        int flags = 0;
        // Only the very first input of a session waits for a coherent second frame: an opening
        // chat can expose a mixed input/action layout for one pre-draw. Every later geometry
        // (voice, cancel, reply) already has a live pane identity to move.
        boolean initial = presented == null && reserved == null && !recording;
        if (initial && frames < 2) {
            pending = true;
            return 0;
        }
        if (!key.equals(presented)) {
            presented = key;
            flags |= PRESENT;
        }
        boolean stable = initial || (frames >= 2 && now - changedAt >= QUIET_MS);
        if (awaitingSwap) {
            // Present the old route's measured geometry, but neither reserve the list for it
            // nor leave the signalled state.
            pending = true;
            return flags;
        }
        if (stable) {
            if (!key.equals(reserved)) {
                reserved = key;
                flags |= RESERVE;
            }
            state = recording ? State.RECORD_STABLE : State.NORMAL;
        } else {
            state = recording ? State.RECORD_LAYOUT_PENDING : State.NORMAL_LAYOUT_PENDING;
        }
        pending = !stable;
        return flags;
    }

    public void detach() {
        reset();
        state = State.DETACHED;
        epoch++;
    }

    private void reset() {
        state = State.NORMAL;
        voice = pending = false;
        candidate = presented = reserved = null;
        frames = 0;
        bridgeSince = -1L;
        nativeStart = firstMeasured = nativeEnd = normalReady = -1L;
    }
}
