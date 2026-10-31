package com.waenhancer.theme;

/** Native geometry settling; never changes the recorder's own state or timing. */
public final class ComposerTransition {
    public enum State { NORMAL, RECORDING_START, RECORDING_STABLE, RECORDING_END, DETACHED }
    private State state = State.NORMAL;
    private String candidate, committed;
    private long changedAt;
    private int frames;
    private boolean voice;
    private boolean pending;

    public State state() { return state; }
    public boolean pending() { return pending; }

    /** Initial input needs two matching pre-draws; subsequent animations need 80ms quiet. */
    public boolean sample(boolean recording, String geometry, long now) {
        if (state == State.DETACHED) state = State.NORMAL;
        boolean starting = recording && !voice;
        if (recording != voice) {
            voice = recording;
            state = recording ? State.RECORDING_START : State.RECORDING_END;
            candidate = null;
            committed = null;
        }
        String key = recording + ":" + geometry;
        if (!key.equals(candidate)) {
            candidate = key;
            changedAt = now;
            frames = 1;
        } else frames++;
        if (starting) {
            // Show measured native recorder geometry immediately, then freeze reservation
            // until its expansion settles. There is no dependency on elapsed timer text.
            committed = key;
            pending = true;
            return true;
        }
        long quiet = state == State.NORMAL && committed == null ? 0L : 80L;
        pending = frames < 2 || now - changedAt < quiet;
        if (pending) return false;
        state = recording ? State.RECORDING_STABLE : State.NORMAL;
        // Return true for unchanged geometry as well: callers may update material/source,
        // while their layout setters are idempotent.
        committed = key;
        return true;
    }

    public void detach() {
        state = State.DETACHED;
        candidate = committed = null;
        voice = pending = false;
        frames = 0;
    }
}
