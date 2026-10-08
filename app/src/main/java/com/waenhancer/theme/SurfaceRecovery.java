package com.waenhancer.theme;

/** Bounded retry rate per source epoch. Never authorizes stale pixels or exhausts transient recovery. */
public final class SurfaceRecovery {
    public enum State { WAITING, LIVE, SUSPENDED, REVALIDATING, DEGRADED, BACKOFF, DISABLED, RELEASED }
    public static final int FAST_FAILURES = 3;
    public static final long MAX_RETRY_MS = 5000L;
    private State state = State.WAITING;
    private int failures;
    private long retryAt;
    private long generation;

    public State state() { return state; }
    public int failures() { return failures; }
    public long generation() { return generation; }
    public boolean mayCapture(long now) {
        return state != State.SUSPENDED && state != State.DISABLED && state != State.RELEASED
                && now >= retryAt;
    }
    public long retryDelay(long now) {
        return state == State.DEGRADED || state == State.BACKOFF ? Math.max(1L, retryAt - now) : 0L;
    }
    public boolean retryDue(long now) {
        return (state == State.DEGRADED || state == State.BACKOFF) && mayCapture(now);
    }
    public void ready() {
        if (state == State.DISABLED || state == State.RELEASED || state == State.SUSPENDED) return;
        failures = 0;
        retryAt = 0;
        state = State.LIVE;
    }
    public void failed(long now, boolean fatal) {
        if (state == State.RELEASED || state == State.SUSPENDED || state == State.DISABLED) return;
        if (failures < Integer.MAX_VALUE) failures++;
        long delay = failures < FAST_FAILURES ? (16L << (2 * (failures - 1)))
                : Math.min(MAX_RETRY_MS, 1000L << Math.min(failures - FAST_FAILURES, 3));
        retryAt = now + delay;
        state = fatal ? State.DISABLED : failures >= FAST_FAILURES ? State.BACKOFF : State.DEGRADED;
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
