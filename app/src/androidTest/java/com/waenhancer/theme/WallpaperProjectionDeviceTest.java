package com.waenhancer.theme;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.annotation.UiThreadTest;
import org.junit.Test;
import static org.junit.Assert.*;
import android.widget.FrameLayout;
import android.widget.ImageView;

/** Synthetic bitmap only: both native and underlay must paint the same projected coordinates. */
public class WallpaperProjectionDeviceTest {
    private Context getContext() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    @Test @UiThreadTest
    public void testSharedProjectionAndRestore() {
        Bitmap source = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 200; y++) for (int x = 0; x < 100; x++)
            source.setPixel(x, y, Color.rgb(x * 2, y, 150));
        FrameLayout root = new FrameLayout(getContext());
        ImageView nativeView = new ImageView(getContext()) {
            @Override public boolean isShown() { return true; }
        };
        nativeView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        BitmapDrawable original = new BitmapDrawable(getContext().getResources(), source);
        nativeView.setImageDrawable(original);
        WallpaperUnderlay band = new WallpaperUnderlay(getContext());
        root.addView(band); root.addView(nativeView);
        root.layout(0, 0, 100, 200);
        band.layout(0, 0, 100, 200);
        nativeView.layout(0, 20, 100, 200);
        WallpaperProjection projection = new WallpaperProjection();
        projection.prepare(nativeView, band);
        Bitmap extension = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888);
        assertTrue(projection.draw(new Canvas(extension)));
        Bitmap nativePixels = Bitmap.createBitmap(100, 180, Bitmap.Config.ARGB_8888);
        nativeView.draw(new Canvas(nativePixels));
        for (int y = 25; y < 150; y += 25) for (int x = 10; x < 90; x += 20)
            assertEquals(extension.getPixel(x, y + 20), nativePixels.getPixel(x, y));
        assertEquals(255, Color.alpha(extension.getPixel(50, 0)));
        assertFalse(extension.getPixel(50, 0) == extension.getPixel(50, 19));
        projection.release();
        assertSame(original, nativeView.getDrawable());
        projection.prepare(nativeView, band);
        BitmapDrawable replacement = new BitmapDrawable(getContext().getResources(), source);
        nativeView.setImageDrawable(replacement);
        projection.release();
        assertSame(replacement, nativeView.getDrawable());
        extension.recycle(); nativePixels.recycle(); source.recycle();
    }
}
