package com.waenhancer.theme;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.function.Supplier;

/**
 * Static glass material in an existing background slot: no reparenting, layout changes or
 * filtering of text.
 *
 * <p>It paints {@link GlassSpec#withoutOptics()} in the view's own shape and samples nothing behind
 * it. Live transmission belongs to {@link GlassPane}, which records only the region behind a pane
 * from a source that cannot contain it. Sampling from inside a view's own background cannot meet
 * that rule: the window recording reaches the view's cached background node, which draws the
 * recording again (a native RenderThread crash), and a software snapshot instead is stale and
 * slow.</p>
 */
public final class GlassMaterialDrawable extends Drawable implements Drawable.Callback {
    private final Drawable original;
    private final WeakReference<View> owner;
    private Supplier<GlassSpec> spec;
    private final float radiusDp;
    private final boolean nativeMask;
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Drawable fallback;
    private GlassSpec appliedMaterial;
    private int materialWidth, materialHeight;
    private float materialRadius, materialDensity;
    private int alpha = 255;

    public GlassMaterialDrawable(View owner, Drawable original, Supplier<GlassSpec> spec,
                                 float radiusDp, boolean nativeMask) {
        this.owner = new WeakReference<>(owner);
        this.original = original;
        this.spec = spec;
        this.radiusDp = radiusDp;
        this.nativeMask = nativeMask;
        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        if (original != null) {
            original.setCallback(this);
            setState(original.getState()); setLevel(original.getLevel());
            setVisible(original.isVisible(), false);
            setLayoutDirection(original.getLayoutDirection());
        }
    }

    @Override public void draw(Canvas canvas) {
        View view = owner.get();
        if (view == null && getCallback() instanceof View) view = (View) getCallback();
        GlassSpec material = spec.get();
        Rect b = getBounds();
        if (view == null || material == null || b.isEmpty()) { drawOriginal(canvas); return; }
        float density = view.getResources().getDisplayMetrics().density;
        float radius = Math.min(radiusDp * density, Math.min(b.width(), b.height()) / 2f);
        if (fallback == null || appliedMaterial != material || materialWidth != b.width()
                || materialHeight != b.height() || materialRadius != radius || materialDensity != density) {
            appliedMaterial = material; materialWidth = b.width(); materialHeight = b.height();
            materialRadius = radius; materialDensity = density;
            fallback = GlassRenderer.background(material.withoutOptics(), radius, density);
        }
        int save = canvas.save();
        canvas.translate(b.left, b.top);
        int layer = -1;
        if (nativeMask && original != null) {
            layer = canvas.saveLayer(0, 0, b.width(), b.height(), null);
        }
        try {
            fallback.setBounds(0, 0, b.width(), b.height());
            fallback.setAlpha(alpha);
            fallback.draw(canvas);
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

    public void attachMaterial(Supplier<GlassSpec> material) { spec = material; }
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
        boolean changed = original != null && original.setState(state);
        invalidateSelf(); return changed;
    }
    @Override protected boolean onLevelChange(int level) {
        boolean changed = original != null && original.setLevel(level);
        if (changed) invalidateSelf(); return changed;
    }
    @Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
    @Override public int getAlpha() { return alpha; }
    @Override public void setColorFilter(ColorFilter filter) { if (original != null) original.setColorFilter(filter); }
    @Override public void setTintList(ColorStateList tint) { if (original != null) original.setTintList(tint); }
    @Override public void setTintMode(PorterDuff.Mode mode) { if (original != null) original.setTintMode(mode); }
    @Override public void setAutoMirrored(boolean mirrored) { if (original != null) original.setAutoMirrored(mirrored); }
    @Override public boolean isAutoMirrored() { return original != null && original.isAutoMirrored(); }
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
        boolean changed = original != null && original.setVisible(visible, restart);
        return super.setVisible(visible, restart) || changed;
    }
    @Override protected void onBoundsChange(Rect bounds) { if (original != null) original.setBounds(bounds); }
    @Override public void setHotspotBounds(int left, int top, int right, int bottom) {
        super.setHotspotBounds(left, top, right, bottom);
        if (original != null) original.setHotspotBounds(left, top, right, bottom);
    }
    @Override public void getHotspotBounds(Rect out) {
        if (original != null) original.getHotspotBounds(out); else super.getHotspotBounds(out);
    }
    @Override public boolean onLayoutDirectionChanged(int direction) {
        return original != null && original.setLayoutDirection(direction);
    }
    @Override public void setHotspot(float x, float y) { if (original != null) original.setHotspot(x, y); }
    @Override public void invalidateDrawable(Drawable who) { invalidateSelf(); }
    @Override public void scheduleDrawable(Drawable who, Runnable what, long when) { scheduleSelf(what, when); }
    @Override public void unscheduleDrawable(Drawable who, Runnable what) { unscheduleSelf(what); }
}
