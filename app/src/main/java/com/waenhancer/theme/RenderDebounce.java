package com.waenhancer.theme;

/**
 * One scheduling policy for wallpaper-texture renders (review on #134).
 *
 * <p>The first texture renders at once. While a texture is already presented, a render may start
 * only after the source has been quiet for {@code settleMs}; that holds for every entry point,
 * including a render that finishes stale while geometry keeps changing (keyboard animation).</p>
 */
final class RenderDebounce {
    private final long settleMs;
    private long lastChange = Long.MIN_VALUE;

    RenderDebounce(long settleMs) { this.settleMs = settleMs; }

    /** A new source revision was requested at {@code now}. */
    void changed(long now) { lastChange = now; }

    /** Milliseconds to wait before a render may start; 0 means start now. */
    long delay(long now, boolean hasTexture) {
        if (!hasTexture || lastChange == Long.MIN_VALUE) return 0L;
        return Math.max(0L, lastChange + settleMs - now);
    }
}
