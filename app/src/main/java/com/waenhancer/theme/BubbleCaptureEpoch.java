package com.waenhancer.theme;

/** Damage and capture attempts are distinct: budget refusal must not create an idle redraw loop. */
public final class BubbleCaptureEpoch {
    private long revision, attempted = -1, captured = -1;
    public long revision() { return revision; }
    public long captured() { return captured; }
    public boolean pending() { return revision != attempted; }
    public void damage() { revision++; captured = -1; }
    public void attempted() { attempted = revision; }
    public void published() { captured = revision; attempted = revision; }
    public void released() { captured = -1; }
}
