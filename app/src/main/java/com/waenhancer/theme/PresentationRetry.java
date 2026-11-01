package com.waenhancer.theme;

/** Limits work for a captured drawable that the native pipeline never presents. */
public final class PresentationRetry {
    private int attempts;
    private long retryAt;
    private long backoff = 1000L;

    public boolean mayCapture(long now, boolean presented) {
        if (presented) { rearm(); return true; }
        return now >= retryAt;
    }
    public boolean published(long now, boolean presented) {
        if (presented) { rearm(); return false; }
        if (++attempts >= 3) {
            retryAt = now + backoff;
            backoff = Math.min(5000L, backoff * 2);
            attempts = 0;
        }
        return true;
    }
    public long retryDelay(long now, boolean presented) {
        return presented ? 0L : retryAt > now ? retryAt - now : 16L;
    }
    public void rearm() { attempts = 0; retryAt = 0L; backoff = 1000L; }
}
