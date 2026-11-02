package com.waenhancer.theme;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;

/** Immutable geometry plus a borrowed native bitmap; no View is retained or drawn by the worker. */
public final class WallpaperSnapshot {
    final Bitmap bitmap;
    final Matrix bitmapToWindow;
    final int width, height, generation;
    private WallpaperSnapshot(Bitmap bitmap, Matrix map, int width, int height) {
        this.bitmap=bitmap; bitmapToWindow=new Matrix(map); this.width=width; this.height=height;
        generation=bitmap.getGenerationId();
    }
    static boolean supported(BitmapDrawable d) {
        return d.getBitmap()!=null && !d.getBitmap().isRecycled() && !d.getBitmap().isMutable()
                && !d.isStateful() && d.getAlpha()==255
                && d.getColorFilter()==null && d.getPaint().getColorFilter()==null
                && d.getTileModeX()==null && d.getTileModeY()==null && !d.isAutoMirrored();
    }
    static WallpaperSnapshot create(BitmapDrawable d, Matrix map, int width, int height) {
        if (width<=0 || height<=0 || !map.rectStaysRect()) return null;
        float[] v=new float[9]; map.getValues(v);
        // No CLAMP fill for uncovered insets. Reuse exactly the native continuity transform.
        if (!BackdropGeometry.covers(v,d.getBitmap().getWidth(),d.getBitmap().getHeight(),width,height)) return null;
        return new WallpaperSnapshot(d.getBitmap(),map,width,height);
    }
    public static WallpaperSnapshot read(View wallpaper, View window) {
        try { return WallpaperProjection.snapshot(wallpaper, window); }
        catch (RuntimeException | LinkageError unsupported) { return null; }
    }
    @Override public boolean equals(Object value) {
        return value instanceof WallpaperSnapshot && same((WallpaperSnapshot)value);
    }
    @Override public int hashCode() { return System.identityHashCode(bitmap)*31+generation; }
    boolean same(WallpaperSnapshot other) {
        return other!=null && bitmap==other.bitmap && generation==other.generation
                && width==other.width && height==other.height && bitmapToWindow.equals(other.bitmapToWindow);
    }
}
