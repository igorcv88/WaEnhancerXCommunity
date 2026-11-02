package com.waenhancer.theme;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * Optical contour of a native message bubble, measured from WhatsApp's own drawable.
 *
 * <p>The body is the rounded rectangle the rim and refraction follow; anything the native
 * silhouette paints outside it (the tail) is plain glass. Measuring rasterizes only four
 * one-pixel strips of the drawable on the CPU, once per drawable size, never per frame.</p>
 */
public final class BubbleSilhouette {
    /** Body rectangle inside the drawable bounds, in bounds-local pixels. */
    public final int left, top, right, bottom;
    public final float radius;
    /** Opaque RGB of the native fill (it already carries Bubble Colors), or 0 if unknown. */
    public final int color;

    BubbleSilhouette(int left, int top, int right, int bottom, float radius, int color) {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        this.radius = radius; this.color = color;
    }

    public int width() { return right - left; }
    public int height() { return bottom - top; }

    /** Reads ARGB (non-premultiplied) strips of the drawable in bounds-local coordinates. */
    interface Sampler {
        int[] row(int y);
        int[] column(int x);
    }

    static final int OPAQUE = 128;

    /** Pure contour logic, shared with the host-JVM tests. Returns null when no body is found. */
    static BubbleSilhouette measure(int width, int height, Sampler sampler, float fallbackRadius) {
        if (width < 2 || height < 2) return null;
        int[] middle = sampler.row(height / 2);
        int l = first(middle), r = last(middle);
        if (l < 0 || r <= l) return null;
        int cx = (l + r) / 2;
        int[] centre = sampler.column(cx);
        int t = first(centre), b = last(centre);
        if (t < 0 || b <= t) return null;
        int right = r + 1, bottom = b + 1;
        // Bottom corners never carry the tail: the opaque run of the outermost body column ends
        // where the corner arc starts. At the edge pixel the arc covers about sqrt(r) more, so
        // invert e = r - sqrt(r) approximately.
        float radius = -1f;
        for (int x : new int[]{l, r}) {
            int end = last(sampler.column(x));
            if (end < 0 || end > b) continue;
            float e = b - end;
            float estimate = e <= 0f ? 0f : e + (float) Math.sqrt(e);
            radius = radius < 0f ? estimate : Math.min(radius, estimate);
        }
        if (radius < 0f) radius = fallbackRadius;
        radius = Math.max(0f, Math.min(radius, Math.min(right - l, bottom - t) / 2f));
        int fill = middle[cx];
        int color = (fill >>> 24) >= OPAQUE ? (fill | 0xFF000000) : 0;
        return new BubbleSilhouette(l, t, right, bottom, radius, color);
    }

    private static int first(int[] strip) {
        for (int i = 0; i < strip.length; i++) if ((strip[i] >>> 24) >= OPAQUE) return i;
        return -1;
    }

    private static int last(int[] strip) {
        for (int i = strip.length - 1; i >= 0; i--) if ((strip[i] >>> 24) >= OPAQUE) return i;
        return -1;
    }

    /**
     * Rasterizes strips of {@code drawable} at its current bounds. The drawable's bounds and
     * state are never modified; the draw goes through {@link NativeBubbleGlass#drawNative}.
     */
    static BubbleSilhouette measure(final Drawable drawable, final Rect bounds, float fallbackRadius) {
        final int w = bounds.width(), h = bounds.height();
        Sampler sampler = new Sampler() {
            @Override public int[] row(int y) { return strip(drawable, bounds, w, 1, 0, y); }
            @Override public int[] column(int x) { return strip(drawable, bounds, 1, h, x, 0); }
        };
        return measure(w, h, sampler, fallbackRadius);
    }

    private static int[] strip(Drawable drawable, Rect bounds, int w, int h, int x, int y) {
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.translate(-bounds.left - x, -bounds.top - y);
            NativeBubbleGlass.drawNative(drawable, canvas);
            int[] pixels = new int[w * h];
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
            return pixels;
        } finally {
            bitmap.recycle();
        }
    }
}
