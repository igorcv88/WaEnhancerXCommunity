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
import android.graphics.RenderNode;
import android.graphics.RenderEffect;
import android.os.SystemClock;
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
    private Supplier<SharedGlassBackdrop> backdrop;
    private Supplier<GlassSpec> spec;
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
    private GlassSpec appliedMaterial;
    private int materialWidth, materialHeight;
    private float materialRadius, materialDensity;
    private boolean compilationFailed;
    private final GlassRenderPolicy.Retry samplingRetry = new GlassRenderPolicy.Retry();
    private RenderNode opticalNode;
    private RenderEffect opticalEffect;
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
        if (view == null) { drawOriginal(canvas); return; }
        SharedGlassBackdrop provider = backdrop.get();
        if (!SharedGlassBackdrop.canDrawCapturedMaterial(provider)) return;
        GlassSpec material = spec.get();
        Rect b = getBounds();
        if (provider == null || material == null || b.isEmpty()) { drawOriginal(canvas); return; }
        provider.request();
        if (nativeMask) provider.exclude(view, true);
        float density = view.getResources().getDisplayMetrics().density;
        float radius = Math.min(radiusDp * density, Math.min(b.width(), b.height()) / 2f);
        boolean changed = appliedMaterial != material || materialWidth != b.width()
                || materialHeight != b.height() || materialRadius != radius || materialDensity != density;
        if (changed) {
            appliedMaterial = material; materialWidth = b.width(); materialHeight = b.height();
            materialRadius = radius; materialDensity = density;
            // This drawable never uses the BlurView/RenderScript backend modeled by resolveFor.
            fallback = GlassRenderer.background(material.withoutOptics(), radius, density);
            opticalEffect = null;
        }
        if (Build.VERSION.SDK_INT >= 33 && !compilationFailed && (material.lensStrength > 0f && Build.VERSION.SDK_INT >= 33)
                && samplingRetry.ready(SystemClock.uptimeMillis())) {
            if (lens == null) {
                try { lens = LiquidLens.newMaterialShader(); changed = true; }
                catch (IllegalArgumentException invalidProgram) {
                    compilationFailed = true;
                    android.util.Log.w("WaEnhancerX/Glass", "AGSL compilation failed", invalidProgram);
                } catch (RuntimeException | LinkageError transientError) { samplingRetry.failure(SystemClock.uptimeMillis()); }
            }
            if (lens != null && changed) {
                try { LiquidLens.updateMaterialUniforms(lens, material, b.width(), b.height(), radius, density); }
                catch (RuntimeException | LinkageError transientError) {
                    lens = null; opticalEffect = null; samplingRetry.failure(SystemClock.uptimeMillis());
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
            boolean optical = lens != null && (material.lensStrength > 0f && Build.VERSION.SDK_INT >= 33) && canvas.isHardwareAccelerated()
                    && (provider.gpu() != null || bitmap != null)
                    && samplingRetry.ready(SystemClock.uptimeMillis())
                    && provider.allowShader(this, b.width(), b.height(), material, nativeMask);
            if (optical) {
                try {
                    view.getLocationOnScreen(position);
                    if (provider.gpu() != null && Build.VERSION.SDK_INT >= 33) {
                        if (opticalNode == null) {
                            opticalNode = new RenderNode("WA glass material");
                            opticalNode.setClipToBounds(true);
                        }
                        opticalNode.setPosition(0, 0, b.width(), b.height());
                        Canvas recording = opticalNode.beginRecording(b.width(), b.height());
                        try {
                            recording.translate(provider.screenX() - position[0] - b.left,
                                    provider.screenY() - position[1] - b.top);
                            recording.drawRenderNode(provider.gpu());
                        } finally { opticalNode.endRecording(); }
                        if (opticalEffect == null) opticalEffect = RenderEffect.createRuntimeShaderEffect(lens, "content");
                        opticalNode.setRenderEffect(opticalEffect);
                        opticalNode.setAlpha(alpha / 255f);
                        canvas.drawRenderNode(opticalNode);
                    } else {
                        if (sampledBitmap != bitmap) {
                            sampledBitmap = bitmap;
                            input = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                        }
                        matrix.setScale(provider.scaleX(), provider.scaleY());
                        matrix.postTranslate(provider.screenX() - position[0] - b.left,
                                provider.screenY() - position[1] - b.top);
                        input.setLocalMatrix(matrix);
                        lens.setInputShader("content", input);
                        opticalEffect = null;
                        paint.setShader(lens); paint.setAlpha(alpha);
                        canvas.drawRect(0, 0, b.width(), b.height(), paint);
                    }
                    samplingRetry.success();
                } catch (RuntimeException | LinkageError error) {
                    // Input/matrix/recording errors are recoverable; the program may still be valid.
                    samplingRetry.failure(SystemClock.uptimeMillis());
                    sampledBitmap = null; input = null; opticalEffect = null;
                    if (opticalNode != null) opticalNode.discardDisplayList();
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

    public void attachBackdrop(Supplier<SharedGlassBackdrop> source, Supplier<GlassSpec> material) { backdrop = source; spec = material; }
    public boolean belongsTo(SharedGlassBackdrop provider) { return backdrop.get() == provider; }
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
