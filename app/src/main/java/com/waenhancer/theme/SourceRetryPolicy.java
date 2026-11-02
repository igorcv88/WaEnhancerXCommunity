package com.waenhancer.theme;

/**
 * Bounded retry for the isolated wallpaper producer.
 *
 * <p>{@code HardwareRenderer.syncAndDraw()} can report a dropped frame, a lost surface or a
 * stopped context, and {@code ImageReader.acquireNextImage()} can return {@code null}: these are
 * transient and get a few spaced retries for the same source revision. An exception or an
 * unsupported source is permanent for that revision and stays native without further attempts.
 * A new revision (wallpaper, radius or geometry change) starts a fresh budget.</p>
 */
public final class SourceRetryPolicy {
    public static final int MAX_TRANSIENT_ATTEMPTS = 3;
    private static final long[] DELAYS_MS = {100L, 400L, 1600L};
    private long revision = Long.MIN_VALUE;
    private int transientFailures;
    private boolean permanent;

    public enum Outcome { READY, TRANSIENT, PERMANENT, STALE }

    private void track(long rev) {
        if (rev != revision) { revision = rev; transientFailures = 0; permanent = false; }
    }

    /** Whether a render may start for this revision now. */
    public boolean mayAttempt(long rev) {
        track(rev);
        return !permanent && transientFailures < MAX_TRANSIENT_ATTEMPTS;
    }

    /**
     * Records a result for {@code rev}.
     *
     * @return the delay before the next attempt, or -1 when no retry should be scheduled
     */
    public long onResult(long rev, Outcome outcome) {
        track(rev);
        switch (outcome) {
            case READY:
                transientFailures = 0;
                return -1L;
            case PERMANENT:
                permanent = true;
                return -1L;
            case STALE:
                return -1L; // the newer revision schedules itself
            case TRANSIENT:
            default:
                transientFailures++;
                if (transientFailures >= MAX_TRANSIENT_ATTEMPTS) return -1L;
                return DELAYS_MS[Math.min(transientFailures - 1, DELAYS_MS.length - 1)];
        }
    }

    public boolean exhausted(long rev) {
        track(rev);
        return permanent || transientFailures >= MAX_TRANSIENT_ATTEMPTS;
    }
}
