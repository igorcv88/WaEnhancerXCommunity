package com.waenhancer.theme;

/** Per-surface first-frame timing in one resumed window epoch; clock supplied by the caller. */
public final class ResumeTiming {
    public static final int FIRST_PRESENTATION = 1, FIRST_LIVE = 2;
    private final long resumedAt;
    private long capturedAt = -1, presentedAt = -1, liveAt = -1;
    private long lastFrame = Long.MIN_VALUE;
    private int fallbackFrames;

    public ResumeTiming(long now) { resumedAt = now; }
    public boolean captured(long now) {
        if (capturedAt >= 0) return false;
        capturedAt = now;
        return true;
    }
    public int presented(long now, long frame, boolean live) {
        int events = 0;
        if (presentedAt < 0) { presentedAt = now; events |= FIRST_PRESENTATION; }
        if (liveAt < 0) {
            if (live && capturedAt >= 0) { liveAt = now; events |= FIRST_LIVE; }
            else if (lastFrame != frame) fallbackFrames++;
        }
        lastFrame = frame;
        return events;
    }
    public long captureDelay() { return capturedAt < 0 ? -1 : capturedAt - resumedAt; }
    public long presentationDelay() { return presentedAt < 0 ? -1 : presentedAt - resumedAt; }
    public long liveDelay() { return liveAt < 0 ? -1 : liveAt - resumedAt; }
    public int fallbackFrames() { return fallbackFrames; }
}
