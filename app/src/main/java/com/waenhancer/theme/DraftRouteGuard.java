package com.waenhancer.theme;

/** Keeps a measured draft's presentation through a short native reconstruction gap.
 * This is visual ownership, not an inference that audio is paused or recording. */
public final class DraftRouteGuard {
    public static final long BRIDGE_MS = 700L;
    private Object owner;
    private boolean pending;
    public boolean pending() { return pending; }
    private long missingSince = -1;
    public boolean hold(Object current, boolean measured, boolean present, long now) {
        if (measured) { owner = current; missingSince = -1; pending = false; return false; }
        if (!present || current == null) { reset(); return false; }
        if (current != owner) { owner = current; missingSince = now; }
        if (missingSince < 0) missingSince = now;
        pending = now - missingSince <= BRIDGE_MS;
        return pending;
    }
    public void reset() { owner = null; missingSince = -1; pending = false; }
}
