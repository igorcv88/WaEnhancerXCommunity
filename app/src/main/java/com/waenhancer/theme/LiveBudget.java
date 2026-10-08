package com.waenhancer.theme;

import android.view.View;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * One window's budget for live glass, in effective pixel-passes (LG-12).
 *
 * <p>The cost of a live surface is not its visible area. With filtering, its recording carries a
 * blur margin on every side and the effect runs three passes (two lenses and a Gaussian), so a
 * 24×24 surface with a 38 px margin costs (24+76)² × 3 = 30,000 against 576 visible pixels. The
 * cost here is {@code (w + 2·pad)(h + 2·pad) × passes}. It is a proportional estimate, not a
 * measured GPU time: what the driver actually spends is for a device trace.</p>
 *
 * <p>Capacity is one window of the filtered pipeline ({@link #CAPACITY_PASSES} × window area).
 * Panes (the conversation header and composer) are admitted first, in the order they record, and
 * are refused like anything else once the window is spent; drawables get what panes leave, taken
 * top to bottom. A refused surface paints its static material.</p>
 */
public final class LiveBudget {

    /** Window areas of single-pass work the budget allows: one window at the filtered pipeline. */
    public static final int CAPACITY_PASSES = 3;

    private static final Map<View, LiveBudget> WINDOWS = new WeakHashMap<>();

    private final long capacity;
    private final Map<Object, Long> panes = new WeakHashMap<>();

    public LiveBudget(long capacity) {
        this.capacity = Math.max(0L, capacity);
    }

    /** The ledger for {@code root}'s window, sized to it. Main thread only. */
    public static LiveBudget forWindow(View root) {
        long capacity = (long) CAPACITY_PASSES * Math.max(0, root.getWidth()) * Math.max(0, root.getHeight());
        LiveBudget budget = WINDOWS.get(root);
        if (budget == null || budget.capacity != capacity) {
            LiveBudget resized = new LiveBudget(capacity);
            if (budget != null) resized.panes.putAll(budget.panes);
            budget = resized;
            WINDOWS.put(root, budget);
        }
        return budget;
    }

    /** Passes a surface's effect runs: three with the Gaussian graph, one otherwise. */
    public static int passes(GlassOptics optics, GlassSpec spec, float density) {
        return optics.corrected && optics.filtering && LensModel.sigmaPx(spec, density) > 0f ? 3 : 1;
    }

    /** Margin the recording carries, as {@link LiveBackdrop} computes it. */
    public static int padding(GlassOptics optics, GlassSpec spec, float density) {
        return optics.corrected && optics.filtering ? LensModel.padding(LensModel.sigmaPx(spec, density)) : 0;
    }

    /** Effective cost of one surface. */
    public static long cost(int width, int height, int padding, int passes) {
        return (long) (width + 2L * padding) * (height + 2L * padding) * passes;
    }

    /** Cost of a surface of this size and material under these switches. */
    public static long cost(int width, int height, GlassSpec spec, float density, GlassOptics optics) {
        return cost(width, height, padding(optics, spec, density), passes(optics, spec, density));
    }

    public long capacity() {
        return capacity;
    }

    /**
     * Admits {@code pane} at {@code cost} if what the other panes spend leaves room for it;
     * otherwise withdraws it. Idempotent: re-admitting updates its cost.
     */
    public boolean admitPane(Object pane, long cost) {
        long others = 0;
        for (Map.Entry<Object, Long> entry : panes.entrySet()) {
            if (entry.getKey() != pane) others += entry.getValue();
        }
        if (others + cost > capacity) {
            panes.remove(pane);
            return false;
        }
        panes.put(pane, cost);
        return true;
    }

    public void releasePane(Object pane) {
        panes.remove(pane);
    }

    /** What the admitted panes spend; the drawables get the rest. */
    public long paneSpend() {
        long total = 0;
        for (Long cost : panes.values()) total += cost;
        return total;
    }

    public int paneCount() {
        return panes.size();
    }
}
