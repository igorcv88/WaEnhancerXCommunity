package com.waenhancer.theme;

/**
 * When a live surface records its backdrop, and whether it asks for another frame.
 *
 * <p>Legacy (the temporal group off): every frame for {@link #ACTIVE_WINDOW_MS} after motion,
 * then a 250 ms heartbeat that invalidates the surface to keep itself running. That heartbeat
 * spends a recording four times a second on a screen where nothing changes, and a change in the
 * source without motion (a new message, a loaded image) waits for it (LG-10).</p>
 *
 * <p>Damage-driven (the temporal group on): the decision runs in the window's pre-draw, which only
 * happens when something in the window was invalidated. Every such frame is a capture while there
 * is motion. At rest, frames are captured at most every {@link #REST_MIN_GAP_MS}; a frame skipped
 * for that schedules one trailing invalidation (at most one pending), so the last change is never
 * missed. There is no periodic heartbeat: after that trailing frame a still screen draws nothing
 * and costs nothing. A recording the system dropped is captured at once and redrawn.</p>
 *
 * <p>Pure logic, driven by the caller's clock, so it is testable without a device.</p>
 */
public final class CaptureScheduler {

    /** Capture every frame for this long after motion. */
    public static final long ACTIVE_WINDOW_MS = 350L;
    /** Legacy idle heartbeat. */
    public static final long IDLE_INTERVAL_MS = 250L;
    /** Damage-driven: fastest capture rate at rest, against a view that redraws itself forever. */
    public static final long REST_MIN_GAP_MS = 33L;

    /** Nothing to do this frame. */
    public static final int NONE = 0;
    /** Record now. */
    public static final int CAPTURE = 1;
    /** Redraw the surface in the next frame. */
    public static final int INVALIDATE_NOW = 2;
    /** Redraw the surface after {@link #delayMs()}. */
    public static final int INVALIDATE_LATER = 4;

    private long lastActivityMs = Long.MIN_VALUE / 2;
    private long lastCaptureMs = Long.MIN_VALUE / 2;
    private long delayMs;
    private boolean trailingPending;

    /** Motion: a scroll, a layout, a move of the surface. */
    public void activity(long nowMs) {
        lastActivityMs = nowMs;
    }

    public boolean moving(long nowMs) {
        return nowMs - lastActivityMs < ACTIVE_WINDOW_MS;
    }

    /** The delay for {@link #INVALIDATE_LATER}. */
    public long delayMs() {
        return delayMs;
    }

    /**
     * What to do in this pre-draw.
     *
     * @param damageDriven the temporal group
     * @param dropped      the system dropped the last recording
     */
    public int decide(long nowMs, boolean damageDriven, boolean dropped) {
        return decide(nowMs, damageDriven, dropped, false);
    }

    /** A permitted retry may bypass the rest gap, without declaring lost pixels or requesting a redraw. */
    public int decide(long nowMs, boolean damageDriven, boolean dropped, boolean retryDue) {
        if (retryDue && !dropped) {
            lastCaptureMs = nowMs;
            trailingPending = false;
            if (damageDriven) return CAPTURE;
            delayMs = IDLE_INTERVAL_MS;
            return CAPTURE | INVALIDATE_LATER;
        }
        if (!damageDriven) {
            if (dropped) lastActivityMs = nowMs;
            boolean moving = moving(nowMs);
            long minGap = moving ? 0L : IDLE_INTERVAL_MS;
            if (nowMs - lastCaptureMs < minGap) return NONE;
            lastCaptureMs = nowMs;
            delayMs = IDLE_INTERVAL_MS;
            return CAPTURE | (moving ? INVALIDATE_NOW : INVALIDATE_LATER);
        }
        if (dropped) {
            lastCaptureMs = nowMs;
            trailingPending = false;
            return CAPTURE | INVALIDATE_NOW;
        }
        if (moving(nowMs) || nowMs - lastCaptureMs >= REST_MIN_GAP_MS) {
            lastCaptureMs = nowMs;
            trailingPending = false;
            return CAPTURE;
        }
        if (trailingPending) return NONE;
        trailingPending = true;
        delayMs = REST_MIN_GAP_MS - (nowMs - lastCaptureMs);
        return INVALIDATE_LATER;
    }
}
