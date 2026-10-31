package com.waenhancer.theme;

/**
 * Pure rectangle constraints for the native voice recorder decoration. Rects are
 * {@code {left, top, right, bottom}} in one coordinate space (the conversation footer).
 *
 * <p>Only the small red recording indicator may be translated; the waveform, timer, trash,
 * pause/send and action controls are obstacles and are never moved. When no placement meets
 * every constraint the result is {@code null} and the caller leaves WhatsApp's layout native.</p>
 */
public final class RecorderLayout {
    private RecorderLayout() { }

    /** Bounded search: never walk an unbounded number of obstacles leftwards. */
    static final int MAX_STEPS = 6;

    public static boolean empty(int[] r) {
        return r == null || r[2] <= r[0] || r[3] <= r[1];
    }

    /** Strict overlap of two half-open rects. */
    public static boolean intersects(int[] a, int[] b) {
        return !empty(a) && !empty(b) && a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3];
    }

    public static boolean contains(int[] outer, int[] inner) {
        return !empty(outer) && !empty(inner) && inner[0] >= outer[0] && inner[1] >= outer[1]
                && inner[2] <= outer[2] && inner[3] <= outer[3];
    }

    public static int[] expand(int[] r, int by) {
        return new int[]{r[0] - by, r[1] - by, r[2] + by, r[3] + by};
    }

    public static int[] union(int[] a, int[] b) {
        if (empty(a)) return empty(b) ? null : b.clone();
        if (empty(b)) return a.clone();
        return new int[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]),
                Math.max(a[2], b[2]), Math.max(a[3], b[3])};
    }

    /** {@code r} limited to {@code bounds}; null if nothing remains. */
    public static int[] clamp(int[] r, int[] bounds) {
        if (empty(r) || empty(bounds)) return null;
        int[] out = {Math.max(r[0], bounds[0]), Math.max(r[1], bounds[1]),
                Math.min(r[2], bounds[2]), Math.min(r[3], bounds[3])};
        return empty(out) ? null : out;
    }

    static int centerY(int[] r) {
        return (r[1] + r[3]) / 2;
    }

    /**
     * Translation {@code {dx, dy}} for the recording indicator {@code mic} (its untranslated,
     * native rect) so that it sits on the start side of {@code wave} with at least {@code gap},
     * is vertically centred on the waveform row within {@code tolerance}, stays inside
     * {@code bounds}, and keeps {@code gap} from every obstacle. In LTR the start side is left.
     *
     * <p>If the slot immediately before the waveform is taken by an obstacle (for example the
     * elapsed timer), the indicator is packed before that obstacle instead, up to
     * {@link #MAX_STEPS} times. A native layout that already satisfies the constraints yields
     * {@code {0, 0}}.</p>
     */
    public static int[] micOffset(int[] mic, int[] wave, int[][] obstacles, int[] bounds,
                                  int gap, int tolerance, boolean rtl) {
        if (empty(mic) || empty(wave) || empty(bounds)) return null;
        int w = mic[2] - mic[0], h = mic[3] - mic[1];
        int cy = centerY(wave);
        int top = Math.abs(centerY(mic) - cy) <= tolerance ? mic[1] : cy - h / 2;
        boolean sideOk = rtl ? mic[0] >= wave[2] + gap : mic[2] + gap <= wave[0];
        if (sideOk && top == mic[1] && clear(mic, obstacles, gap) && contains(bounds, mic)) {
            return new int[]{0, 0};
        }
        // Start next to the waveform; keep the native X when it is already on the right side
        // and only the vertical centre was wrong.
        int left = sideOk ? mic[0] : rtl ? wave[2] + gap : wave[0] - gap - w;
        for (int step = 0; step <= MAX_STEPS; step++) {
            int[] candidate = {left, top, left + w, top + h};
            if (!contains(bounds, candidate)) return null;
            int[] blocker = firstBlocker(candidate, obstacles, gap);
            if (blocker == null) return new int[]{left - mic[0], top - mic[1]};
            // Pack before the blocking control on the start side.
            left = rtl ? blocker[2] + gap : blocker[0] - gap - w;
        }
        return null;
    }

    private static boolean clear(int[] r, int[][] obstacles, int gap) {
        return firstBlocker(r, obstacles, gap) == null;
    }

    private static int[] firstBlocker(int[] r, int[][] obstacles, int gap) {
        if (obstacles == null) return null;
        int[] padded = expand(r, Math.max(0, gap));
        for (int[] obstacle : obstacles) {
            if (!empty(obstacle) && intersects(padded, obstacle)) return obstacle;
        }
        return null;
    }

    /**
     * The quote lane is above the recording row: {@code quote.bottom + gap <= min(wave.top,
     * timer.top)} for whichever of the row's rects are present.
     */
    public static boolean quoteAbove(int[] quote, int[] wave, int[] timer, int gap) {
        if (empty(quote)) return true;
        int rowTop = Integer.MAX_VALUE;
        if (!empty(wave)) rowTop = Math.min(rowTop, wave[1]);
        if (!empty(timer)) rowTop = Math.min(rowTop, timer[1]);
        return rowTop == Integer.MAX_VALUE || quote[3] + gap <= rowTop;
    }

    /** Diagnostic form; geometry only. */
    public static String describe(int[] r) {
        return empty(r) ? "none" : r[0] + "," + r[1] + "," + r[2] + "," + r[3];
    }
}
