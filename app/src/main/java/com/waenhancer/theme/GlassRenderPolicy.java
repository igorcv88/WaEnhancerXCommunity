package com.waenhancer.theme;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Android-free accounting and recovery rules, shared by every consumer of a recording. */
public final class GlassRenderPolicy {
    public enum Tier { NORMAL, MOTION, CONSERVING }
    public static final class Retry {
        private int failures;
        private long retryAt;
        public boolean ready(long now) { return now >= retryAt; }
        public void success() { failures = 0; retryAt = 0; }
        public void failure(long now) {
            failures = Math.min(8, failures + 1);
            // Repeated failures open a temporary circuit, never a lifetime disable.
            retryAt = now + Math.min(30_000L, 250L << (failures - 1));
        }
        public int failures() { return failures; }
    }
    private final Set<Object> holders = Collections.newSetFromMap(new IdentityHashMap<>());
    private long frame = Long.MIN_VALUE;
    private double spent;
    private double limit;
    private int countLimit;
    private int slowFrames;
    private int healthyFrames;
    private boolean overloaded;
    public void observeFrame(long duration, long deadline) {
        if (duration <= 0 || deadline <= 0) return;
        if (duration > deadline) {
            healthyFrames = 0;
            if (++slowFrames >= 3) overloaded = true;
        } else {
            slowFrames = 0;
            if (++healthyFrames >= 60) overloaded = false;
        }
    }
    public boolean overloaded() { return overloaded; }
    public void beginFrame(long token, long screenPixels, Tier tier) {
        if (frame == token) return;
        frame = token;
        holders.clear(); spent = 0;
        Tier effective = overloaded && tier == Tier.NORMAL ? Tier.MOTION : tier;
        // Weighted work relative to output resolution; initial ceilings require device calibration.
        double screens = effective == Tier.NORMAL ? 12 : effective == Tier.MOTION ? 6 : 3;
        limit = Math.max(1, screenPixels) * screens;
        countLimit = effective == Tier.NORMAL ? 24 : effective == Tier.MOTION ? 12 : 8;
    }
    public boolean allow(Object holder, int width, int height, GlassSpec spec, boolean nativeMask) {
        if (holder == null || spec == null || width <= 0 || height <= 0 || frame == Long.MIN_VALUE) return false;
        if (holders.contains(holder)) return true;
        // Nine blur taps plus channel-separation taps; mask adds two compositing passes separately.
        double taps = spec.blurRadius > 0 ? 9 : 1;
        if (spec.dispersion > 0) taps += 2;
        if (nativeMask) taps += 2;
        double work = (double) width * height * taps;
        if (holders.size() >= countLimit || work > limit - spent) return false;
        spent += work; holders.add(holder); return true;
    }
    void clear() { holders.clear(); spent = 0; frame = Long.MIN_VALUE; }
    public long frameToken() { return frame; }
    public int holders() { return holders.size(); }
}
