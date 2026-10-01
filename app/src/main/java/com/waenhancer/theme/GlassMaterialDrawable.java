package com.waenhancer.theme;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.function.Supplier;

/** Glass in an existing background slot: no reparenting, layout changes or filtering of text. */
public final class GlassMaterialDrawable extends Drawable implements Drawable.Callback {
    private final Drawable original;
    private final WeakReference<View> owner;
    private final Supplier<SharedGlassBackdrop> backdrop;
    private final Supplier<GlassSpec> spec;
    private final float radiusDp;
    private final boolean nativeMask;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final int[] position = new int[2];
    private Bitmap sampledBitmap;
    private BitmapShader input;
    private RuntimeShader lens;
    private Drawable fallback;
    private String materialKey;
    private boolean shaderFailed;
    private int alpha = 255;

    public GlassMaterialDrawable(View owner, Drawable original,
                                 Supplier<SharedGlassBackdrop> backdrop,
                                 Supplier<GlassSpec> spec, float radiusDp, boolean nativeMask) {
        this.owner = new WeakReference<>(owner);
        this.original = original;
        this.backdrop = backdrop;
        this.spec = spec;
        this.radiusDp = radiusDp;
        this.nativeMask = nativeMask;
        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        if (original != null) original.setCallback(this);
    }

    @Override public void draw(Canvas canvas) {
        if (SharedGlassBackdrop.isCapturing()) return;
        View view = owner.get();
        if (view == null && getCallback() instanceof View) view = (View) getCallback();
        if (view == null) { drawOriginal(canvas); return; }
        SharedGlassBackdrop provider = backdrop.get();
        GlassSpec material = spec.get();
        Rect b = getBounds();
        if (provider == null || material == null || b.isEmpty()) { drawOriginal(canvas); return; }
        provider.request();
        if (nativeMask) SharedGlassBackdrop.exclude(view, true);
        float density = view.getResources().getDisplayMetrics().density;
        float radius = Math.min(radiusDp * density, Math.min(b.width(), b.height()) / 2f);
        String key = b.width() + ":" + b.height() + ":" + radius + ":" + material.fillColor
                + ":" + material.lensStrength + ":" + material.blurRadius;
        if (!key.equals(materialKey)) {
            materialKey = key;
            fallback = GlassRenderer.background(material, radius, density);
            lens = null;
            if (Build.VERSION.SDK_INT >= 33 && !shaderFailed && LiquidLens.isActiveFor(material)) {
                try {
                    lens = LiquidLens.materialShader(material, b.width(), b.height(), radius, density);
                } catch (Throwable error) {
                    shaderFailed = true;
                    android.util.Log.w("WaEnhancerX/Glass", "Drawable lens unavailable", error);
                }
            }
        }
        int save = canvas.save();
        canvas.translate(b.left, b.top);
        int layer = -1;
        if (nativeMask && original != null) {
            layer = canvas.saveLayer(0, 0, b.width(), b.height(), null);
        }
        try {
            Bitmap bitmap = provider.bitmap();
            boolean optical = bitmap != null && lens != null && canvas.isHardwareAccelerated()
                    && provider.allowShader(this, b.width(), b.height());
            if (optical) {
                try {
                    if (sampledBitmap != bitmap) {
                        sampledBitmap = bitmap;
                        input = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    }
                    // Both windows use SCREEN coordinates: popup/window offsets must not be mixed.
                    view.getLocationOnScreen(position);
                    matrix.setScale(provider.scaleX(), provider.scaleY());
                    matrix.postTranslate(provider.screenX() - position[0] - b.left,
                            provider.screenY() - position[1] - b.top);
                    input.setLocalMatrix(matrix);
                    lens.setInputShader("content", input);
                    paint.setShader(lens);
                    paint.setAlpha(alpha);
                    canvas.drawRect(0, 0, b.width(), b.height(), paint);
                } catch (Throwable error) {
                    shaderFailed = true;
                    lens = null;
                    optical = false;
                }
            }
            if (!optical && fallback != null) {
                fallback.setBounds(0, 0, b.width(), b.height());
                fallback.setAlpha(alpha);
                fallback.draw(canvas);
            }
            if (layer >= 0) {
                // Isolate the native silhouette before DST_IN; drawing a sparse mask directly
                // would leave the parts it never touched intact, including square corners.
                int mask = canvas.saveLayer(0, 0, b.width(), b.height(), maskPaint);
                canvas.translate(-b.left, -b.top);
                drawOriginal(canvas);
                canvas.restoreToCount(mask);
            }
        } finally {
            if (layer >= 0) canvas.restoreToCount(layer);
            canvas.restoreToCount(save);
        }
    }

    private void drawOriginal(Canvas canvas) {
        if (original != null) { original.setBounds(getBounds()); original.draw(canvas); }
    }

    public Drawable original() { return original; }
    public void restoreCallback() { if (original != null) original.setCallback(getCallback()); }
    @Override public boolean getPadding(Rect padding) {
        if (original != null) return original.getPadding(padding);
        padding.setEmpty(); return false;
    }
    @Override public int getIntrinsicWidth() { return original == null ? -1 : original.getIntrinsicWidth(); }
    @Override public int getIntrinsicHeight() { return original == null ? -1 : original.getIntrinsicHeight(); }
    @Override public int getMinimumWidth() { return original == null ? 0 : original.getMinimumWidth(); }
    @Override public int getMinimumHeight() { return original == null ? 0 : original.getMinimumHeight(); }
    @Override public boolean isStateful() { return original != null && original.isStateful(); }
    @Override protected boolean onStateChange(int[] state) {
        if (original != null) original.setState(state);
        invalidateSelf(); return true;
    }
    @Override protected boolean onLevelChange(int level) {
        return original != null && original.setLevel(level);
    }
    @Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
    @Override public int getAlpha() { return alpha; }
    @Override public void setColorFilter(ColorFilter filter) { /* Native tint must not flatten optics. */ }
    @Override public void setTintList(ColorStateList tint) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public void getOutline(Outline outline) {
        if (nativeMask && original != null) {
            original.getOutline(outline);
            return;
        }
        View view = owner.get();
        float density = view == null ? 1f : view.getResources().getDisplayMetrics().density;
        Rect bounds = getBounds();
        float radius = Math.min(radiusDp * density, Math.min(bounds.width(), bounds.height()) / 2f);
        outline.setRoundRect(bounds, radius);
        outline.setAlpha(alpha / 255f);
    }
    @Override public void jumpToCurrentState() { if (original != null) original.jumpToCurrentState(); }
    @Override public boolean setVisible(boolean visible, boolean restart) {
        if (original != null) original.setVisible(visible, restart);
        return super.setVisible(visible, restart);
    }
    @Override public void setHotspot(float x, float y) { if (original != null) original.setHotspot(x, y); }
    @Override public void invalidateDrawable(Drawable who) { invalidateSelf(); }
    @Override public void scheduleDrawable(Drawable who, Runnable what, long when) { scheduleSelf(what, when); }
    @Override public void unscheduleDrawable(Drawable who, Runnable what) { unscheduleSelf(what); }
}
