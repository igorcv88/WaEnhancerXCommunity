package com.waenhancer.xposed.core;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Rate-limited counters for the stages of a hook pipeline (adapter tracked, row bound, lookup
 * answered, ...). The first time a stage is reached it is logged at once, so a single physical
 * run shows how far the pipeline got; afterwards the totals are logged at most once per interval,
 * so a stage hit on every scrolled row cannot flood the log. Only stage names and counts are
 * logged, never message content or identifiers.
 */
public final class StageCounter {
    private final String tag;
    private final long intervalMs;
    private final LongSupplier clock;
    private final Consumer<String> log;
    private final ConcurrentHashMap<String, AtomicLong> counts = new ConcurrentHashMap<>();
    private long lastSummary;
    private boolean changed;

    public StageCounter(String tag, long intervalMs, LongSupplier clock, Consumer<String> log) {
        this.tag = tag;
        this.intervalMs = intervalMs;
        this.clock = clock;
        this.log = log;
        this.lastSummary = clock.getAsLong();
    }

    /** Production counter: Xposed log, one summary per minute at most. */
    public static StageCounter xposed(String tag) {
        return new StageCounter(tag, 60_000L, System::currentTimeMillis, line -> {
            try {
                de.robv.android.xposed.XposedBridge.log(line);
            } catch (Throwable ignored) {
                // Diagnostics never break the hooked path.
            }
        });
    }

    public void hit(String stage) {
        long count = counts.computeIfAbsent(stage, ignored -> new AtomicLong()).incrementAndGet();
        if (count == 1) log.accept("[WAEX] " + tag + " stage " + stage + " reached");
        synchronized (this) {
            changed = true;
        }
        summarize(false);
    }

    /** Logs the totals when they changed and the interval elapsed, or at once when forced. */
    public synchronized void summarize(boolean force) {
        if (!changed) return;
        long now = clock.getAsLong();
        if (!force && now - lastSummary < intervalMs) return;
        lastSummary = now;
        changed = false;
        log.accept("[WAEX] " + tag + " stages " + snapshot());
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> out = new TreeMap<>();
        counts.forEach((stage, count) -> out.put(stage, count.get()));
        return out;
    }

    public long count(String stage) {
        AtomicLong count = counts.get(stage);
        return count == null ? 0 : count.get();
    }

    synchronized boolean pending() {
        return changed;
    }
}
