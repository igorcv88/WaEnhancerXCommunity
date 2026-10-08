package com.waenhancer.theme;

/** Bounded recovery per source epoch. No Android dependencies; never authorizes stale pixels. */
public final class SurfaceRecovery {
    public enum State { WAITING, LIVE, SUSPENDED, REVALIDATING, DEGRADED, DISABLED, RELEASED }
    public static final int MAX_FAILURES = 3;
    private State state = State.WAITING;
    private int failures;
    private long retryAt;
    private long generation;

    public State state() { return state; }
    public int failures() { return failures; }
    public long generation() { return generation; }
    public boolean mayCapture(long now) {
        return state != State.SUSPENDED && state != State.DISABLED && state != State.RELEASED
                && failures < MAX_FAILURES && now >= retryAt;
    }
    public long retryDelay(long now) {
        return state == State.DEGRADED && failures > 0 && failures < MAX_FAILURES ? Math.max(1L, retryAt - now) : 0L;
    }
    public void ready() {
        if (state == State.DISABLED || state == State.RELEASED || state == State.SUSPENDED) return;
        failures = 0;
        retryAt = 0;
        state = State.LIVE;
    }
    public void failed(long now, boolean fatal) {
        if (state == State.RELEASED || state == State.SUSPENDED || state == State.DISABLED) return;
        failures++;
        retryAt = now + (16L << (2 * Math.min(failures - 1, 2)));
        state = fatal ? State.DISABLED : State.DEGRADED;
    }
    public void suspend() {
        if (state != State.RELEASED && state != State.DISABLED) state = State.SUSPENDED;
    }
    /** A resume/source/geometry event opens a new epoch; permanent capability failures stay off. */
    public void revalidate() {
        if (state == State.RELEASED || state == State.DISABLED) return;
        generation++;
        failures = 0;
        retryAt = 0;
        state = State.REVALIDATING;
    }
    public void release() { state = State.RELEASED; }
}
