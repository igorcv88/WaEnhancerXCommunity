package com.waenhancer.theme;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;
import java.util.function.IntConsumer;

/** One bounded async sample of the displayed quote surface, with no polling/listener. */
public final class SurfaceContrastSampler {
    private boolean pending;
    private long generation, sampledAt = -1000L;
    public void invalidate() { generation++; sampledAt = -1000L; }

    public void sample(Window window, View quote, IntConsumer result) {
        long now = SystemClock.uptimeMillis();
        if (window == null || pending || now - sampledAt < 250L
                || !quote.isAttachedToWindow() || quote.getWidth() <= 0 || quote.getHeight() <= 0) return;
        int width = quote.getWidth(), height = quote.getHeight();
        int[] at = new int[2];
        quote.getLocationInWindow(at);
        // Exclude the accent stripe and close button; dominant colour rejects sparse glyphs.
        int inset = Math.max(1, quote.getWidth() / 12);
        Rect region = new Rect(at[0] + inset, at[1] + 1,
                at[0] + quote.getWidth() - inset, at[1] + quote.getHeight() - 1);
        if (!region.intersect(0, 0, window.getDecorView().getWidth(), window.getDecorView().getHeight())) return;
        Bitmap bitmap = Bitmap.createBitmap(24, 12, Bitmap.Config.ARGB_8888);
        pending = true;
        sampledAt = now;
        long token = generation;
        try {
            PixelCopy.request(window, region, bitmap, status -> {
                pending = false;
                try {
                    if (status != PixelCopy.SUCCESS || token != generation || !quote.isAttachedToWindow()) return;
                    int[] current = new int[2];
                    quote.getLocationInWindow(current);
                    if (current[0] != at[0] || current[1] != at[1]
                            || quote.getWidth() != width || quote.getHeight() != height) return;
                    int[] pixels = new int[288];
                    bitmap.getPixels(pixels, 0, 24, 0, 0, 24, 12);
                    result.accept(SurfaceSampleColor.dominant(pixels));
                } finally { bitmap.recycle(); }
            }, new Handler(Looper.getMainLooper()));
        } catch (IllegalArgumentException | IllegalStateException failure) {
            pending = false;
            bitmap.recycle();
        }
    }
}
