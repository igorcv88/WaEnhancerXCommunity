package com.waenhancer.theme;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;

/**
 * Reads the average colour of the content a glass surface is floating over.
 *
 * <p>{@link GlassSpec#adaptTo} can retint a surface to its surroundings, but only once something
 * tells it what those surroundings are. That is this class: a small, throttled sample of the host's
 * own view tree, reduced to one colour.</p>
 *
 * <h3>Why it samples above the bar rather than behind it</h3>
 *
 * <p>The obvious thing — draw the root and read the pixels under the bar — draws the bar too,
 * because the bar is part of that root. The surface would then be adapting to its own last frame,
 * which runs away into whatever colour the feedback loop settles on. Hiding the bar for the
 * duration would cost a real layout pass on screen.</p>
 *
 * <p>So the sample is taken from the band immediately above the pill instead. Over a scrolling
 * list that band holds the same content that is about to pass under the bar, which is what the
 * surface should be reacting to anyway, and it contains no part of the bar at any point.</p>
 */
public final class BackdropSampler {

    /** Sample resolution. Large enough to average honestly, small enough to be free to read. */
    private static final int SAMPLE_WIDTH = 24;
    private static final int SAMPLE_HEIGHT = 8;

    /** Shortest gap between two samples, in milliseconds. */
    public static final long MIN_INTERVAL_MS = 250L;

    private final Bitmap bitmap =
            Bitmap.createBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888);
    private final int[] pixels = new int[SAMPLE_WIDTH * SAMPLE_HEIGHT];
    private final Rect region = new Rect();

    private long lastSampleAt;
    private int lastColor;

    /** The most recent sample, or {@code 0} before the first one succeeds. */
    public int lastColor() {
        return lastColor;
    }

    /** True when enough time has passed that another sample is worth taking. */
    public boolean isDue(long nowMs) {
        return nowMs - lastSampleAt >= MIN_INTERVAL_MS;
    }

    /**
     * Samples the band above {@code surface} and returns its average colour.
     *
     * <p>Returns the previous sample — or {@code 0} if there has never been one — whenever the
     * geometry is not yet usable or the draw fails. A surface that cannot see its backdrop keeps
     * the colours it already had rather than flickering to a default.</p>
     *
     * @param root    the view tree to read; usually the window's content root
     * @param surface the glass surface, in the same window
     * @param nowMs   current time, so the caller controls the clock
     */
    public int sample(View root, View surface, long nowMs) {
        if (root == null || surface == null) return lastColor;
        if (!isDue(nowMs)) return lastColor;

        int height = surface.getHeight();
        int width = surface.getWidth();
        if (height <= 0 || width <= 0) return lastColor;

        int[] surfaceLocation = new int[2];
        int[] rootLocation = new int[2];
        surface.getLocationInWindow(surfaceLocation);
        root.getLocationInWindow(rootLocation);

        int left = surfaceLocation[0] - rootLocation[0];
        int top = surfaceLocation[1] - rootLocation[1] - height;
        if (top < 0) {
            // The bar is taller than everything above it: nothing to read.
            return lastColor;
        }
        region.set(left, top, left + width, top + height);
        if (region.right > root.getWidth()) region.right = root.getWidth();
        if (region.isEmpty()) return lastColor;

        lastSampleAt = nowMs;
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
            canvas.scale(SAMPLE_WIDTH / (float) region.width(),
                    SAMPLE_HEIGHT / (float) region.height());
            canvas.translate(-region.left, -region.top);
            canvas.clipRect(region);
            root.draw(canvas);

            bitmap.getPixels(pixels, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT);
            lastColor = average(pixels);
        } catch (Throwable ignored) {
            // Hosts can refuse to draw into a foreign canvas; keep the last good colour.
        }
        return lastColor;
    }

    /** Mean of the opaque pixels, or {@code 0} when the sample was entirely transparent. */
    private static int average(int[] pixels) {
        long r = 0, g = 0, b = 0;
        int counted = 0;
        for (int pixel : pixels) {
            int alpha = (pixel >>> 24) & 0xFF;
            if (alpha == 0) continue;
            r += (pixel >> 16) & 0xFF;
            g += (pixel >> 8) & 0xFF;
            b += pixel & 0xFF;
            counted++;
        }
        if (counted == 0) return 0;
        return 0xFF000000
                | ((int) (r / counted) << 16)
                | ((int) (g / counted) << 8)
                | (int) (b / counted);
    }
}
