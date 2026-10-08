package com.waenhancer.theme;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * A pane of glass placed beneath an existing surface, transmitting what lies behind its own rect.
 *
 * <p>Ported from WaThemer (GPL-3.0, ayane-04/wathemer@d39b293) {@code GlassView}/{@code BackdropCapture}. The surface being themed is
 * never touched: this view is inserted under it, the way a sheet of glass is laid under a label.
 * Each pre-draw it records only the region behind itself, from a named {@link #setSource source}
 * subtree (usually the message list) drawn over its {@link #setUnderlay underlay} (the wallpaper),
 * all on the GPU. {@link LiquidLens} then refracts that sharp recording and blurs it in one pass.</p>
 *
 * <p>The recording references the source's live display lists, so it must never reach this pane:
 * {@link GlassPaneGraph} refuses a source that contains the pane, directly or through another
 * pane, and the pane then transmits its underlay only. Without that guard HWUI recurses on the
 * RenderThread and the host process dies natively.</p>
 *
 * <p>Below Android 13 there is no runtime shader, so the pane paints the static layered material
 * and records nothing.</p>
 */
public final class GlassPane extends FrameLayout {

    private static final String TAG = "WaEnhancerX/GlassPane";

    /** Recording resolution divisor. The lens blurs on its own, so a quarter keeps enough to bend. */
    private static final float DOWNSAMPLE = 4f;
    /** Recapture every frame for this long after motion; scrolling is what the glass is for. */
    private static final long ACTIVE_WINDOW_MS = 350L;
    /** At rest the content behind rarely changes; slow down, never stop, so late changes show. */
    private static final long IDLE_INTERVAL_MS = 250L;

    /** Main thread only. True while any pane is drawing WhatsApp's views into its own recording. */
    private static boolean capturing;
    /** Off when the touch-feedback guards could not be installed: recording would crash on ripples. */
    private static boolean captureAllowed = true;
    private static final List<GlassPane> LIVE = new ArrayList<>();
    private static final GlassPaneGraph.Tree<View> TREE = view -> {
        ViewParent parent = view.getParent();
        return parent instanceof View ? (View) parent : null;
    };

    private final RenderNode node = new RenderNode("WAEX glass pane backdrop");
    private final RenderNode glassNode = new RenderNode("WAEX glass pane");
    private final int[] hostLocation = new int[2];
    private final int[] otherLocation = new int[2];
    private final ViewTreeObserver.OnPreDrawListener preDraw = this::onPreDrawCapture;
    private final ViewTreeObserver.OnScrollChangedListener scrolled =
            () -> lastActivityMs = SystemClock.uptimeMillis();

    private View source;
    private List<View> underlay = Collections.emptyList();
    private Supplier<GlassSpec> spec = () -> null;
    private float cornerRadiusPx;

    private boolean hasContent;
    private int nodeWidth, nodeHeight;
    private RuntimeShader lens;
    private boolean lensFailed;
    private GlassSpec effectSpec;
    private int effectWidth = -1, effectHeight = -1;
    private float effectRadius = -1f;

    private Drawable fallback;
    private GlassSpec fallbackSpec;
    private int fallbackWidth = -1, fallbackHeight = -1;
    private float fallbackRadius = -1f;

    private long lastActivityMs;
    private long lastCaptureMs;
    private int lastScreenX = Integer.MIN_VALUE, lastScreenY = Integer.MIN_VALUE;
    private View refusedFor;
    private boolean loggedFailure;

    public GlassPane(Context context) {
        super(context);
        setWillNotDraw(false);
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius());
            }
        });
    }

    /** Main thread only: whether a pane is recording right now. Guards must hold back touch feedback. */
    public static boolean isCapturing() {
        return capturing;
    }

    /** Every pane falls back to the static material; see {@link #captureAllowed}. */
    public static void disableCapture() {
        captureAllowed = false;
    }

    /** The subtree transmitted through this pane, or null for an underlay-only pane. */
    public void setSource(View view) {
        if (source == view) return;
        source = view;
        refusedFor = null;
        lastActivityMs = SystemClock.uptimeMillis();
        invalidate();
    }

    public View source() {
        return source;
    }

    /** Views drawn beneath the source at their own screen positions: the wallpaper. */
    public void setUnderlay(List<View> views) {
        underlay = views == null ? Collections.emptyList() : new ArrayList<>(views);
        lastActivityMs = SystemClock.uptimeMillis();
        invalidate();
    }

    /** Where the material comes from, asked again on every draw so settings and night mode apply. */
    public void setSpec(Supplier<GlassSpec> value) {
        spec = value == null ? () -> null : value;
        invalidate();
    }

    public void setCornerRadius(float px) {
        if (cornerRadiusPx == px) return;
        cornerRadiusPx = px;
        invalidateOutline();
        invalidate();
    }

    private float radius() {
        return Math.min(cornerRadiusPx, Math.min(getWidth(), getHeight()) / 2f);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!LIVE.contains(this)) LIVE.add(this);
        getViewTreeObserver().addOnPreDrawListener(preDraw);
        getViewTreeObserver().addOnScrollChangedListener(scrolled);
        lastActivityMs = SystemClock.uptimeMillis();
    }

    @Override protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(preDraw);
        getViewTreeObserver().removeOnScrollChangedListener(scrolled);
        LIVE.remove(this);
        node.discardDisplayList();
        glassNode.discardDisplayList();
        hasContent = false;
        super.onDetachedFromWindow();
    }

    /** Capture in pre-draw, before this frame's draw; the invalidate keeps moving glass live. */
    private boolean onPreDrawCapture() {
        // Guarded whole: capture draws WhatsApp's own views, and an escape here would crash the UI
        // thread on every frame.
        try {
            if (!isAttachedToWindow() || getWidth() <= 0 || getHeight() <= 0 || capturing) return true;
            if (!lensAvailable() || spec.get() == null) return true;
            long now = SystemClock.uptimeMillis();
            getLocationOnScreen(hostLocation);
            if (hostLocation[0] != lastScreenX || hostLocation[1] != lastScreenY) {
                lastScreenX = hostLocation[0];
                lastScreenY = hostLocation[1];
                lastActivityMs = now;
            }
            boolean moving = now - lastActivityMs < ACTIVE_WINDOW_MS;
            long minGap = moving ? 0L : IDLE_INTERVAL_MS;
            if (now - lastCaptureMs < minGap) return true;
            lastCaptureMs = now;
            if (capture()) {
                if (moving) invalidate(); else postInvalidateDelayed(IDLE_INTERVAL_MS);
            }
        } catch (Throwable error) {
            if (!loggedFailure) {
                loggedFailure = true;
                Log.w(TAG, "pre-draw capture failed; frame skipped", error);
            }
        }
        return true; // never cancel the host's frame
    }

    private boolean lensAvailable() {
        return captureAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && LiquidLens.isSupported() && !lensFailed;
    }

    private View safeSource() {
        View src = source;
        if (src == null || !src.isAttachedToWindow() || !src.isLaidOut()) return null;
        List<GlassPaneGraph.Pane<View>> live = new ArrayList<>(LIVE.size());
        for (GlassPane pane : LIVE) live.add(new GlassPaneGraph.Pane<>(pane, pane.source));
        String reason = GlassPaneGraph.refusal(TREE, this, src, live);
        if (reason == null) {
            refusedFor = null;
            return src;
        }
        if (refusedFor != src) {
            refusedFor = src;
            Log.w(TAG, "source " + src.getClass().getName() + " refused: " + reason + "; underlay only");
        }
        return null;
    }

    /** Records the region behind this pane at 1/DOWNSAMPLE, then the full-size lensed node. */
    private boolean capture() {
        View src = safeSource();
        if (src == null && underlay.isEmpty()) {
            hasContent = false;
            return false;
        }
        int w = (int) Math.ceil(getWidth() / DOWNSAMPLE);
        int h = (int) Math.ceil(getHeight() / DOWNSAMPLE);
        if (w < 1 || h < 1) return false;

        // A scaled ancestor shrinks the pane on screen; the span behind it shrinks with it.
        float sx = 1f, sy = 1f;
        for (View v = this; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            sx *= v.getScaleX();
            sy *= v.getScaleY();
        }
        if (sx < 0.01f || sy < 0.01f) return false;

        node.setPosition(0, 0, w, h);
        RecordingCanvas canvas = node.beginRecording(w, h);
        capturing = true;
        try {
            canvas.scale(1f / DOWNSAMPLE, 1f / DOWNSAMPLE);
            canvas.scale(1f / sx, 1f / sy);
            for (View u : underlay) {
                if (u == null || u.getWidth() <= 0 || u.getHeight() <= 0
                        || u.getVisibility() != View.VISIBLE || !u.isAttachedToWindow()) continue;
                if (src != null && GlassPaneGraph.isAncestor(TREE, src, u)) continue; // drawn by the source
                u.getLocationOnScreen(otherLocation);
                int save = u.getAlpha() < 1f
                        ? canvas.saveLayerAlpha(0f, 0f, getWidth() * sx, getHeight() * sy,
                                Math.round(Math.max(0f, Math.min(1f, u.getAlpha())) * 255))
                        : canvas.save();
                canvas.translate(otherLocation[0] - hostLocation[0], otherLocation[1] - hostLocation[1]);
                u.draw(canvas);
                canvas.restoreToCount(save);
            }
            if (src != null) {
                src.getLocationOnScreen(otherLocation);
                canvas.translate(otherLocation[0] - hostLocation[0], otherLocation[1] - hostLocation[1]);
                src.draw(canvas);
            }
        } finally {
            capturing = false;
            // endRecording must run even if draw() throws, or every later beginRecording throws.
            node.endRecording();
        }

        int fw = getWidth(), fh = getHeight();
        glassNode.setPosition(0, 0, fw, fh);
        RecordingCanvas glass = glassNode.beginRecording(fw, fh);
        try {
            glass.scale(fw / (float) w, fh / (float) h);
            glass.drawRenderNode(node);
        } finally {
            glassNode.endRecording();
        }
        nodeWidth = fw;
        nodeHeight = fh;
        if (!applyLens(fw, fh)) return false;
        hasContent = true;
        return true;
    }

    /** Rebuilds the lens only when the material or geometry changed; per-frame effects allocate. */
    private boolean applyLens(int width, int height) {
        GlassSpec material = spec.get();
        if (material == null) return false;
        float radius = radius();
        if (lens != null && material == effectSpec && width == effectWidth && height == effectHeight
                && radius == effectRadius) {
            return true;
        }
        try {
            if (lens == null) lens = LiquidLens.newMaterialShader();
            LiquidLens.updateMaterialUniforms(lens, material, width, height, radius,
                    getResources().getDisplayMetrics().density);
            glassNode.setRenderEffect(RenderEffect.createRuntimeShaderEffect(lens, "content"));
        } catch (RuntimeException | LinkageError error) {
            lensFailed = true;
            lens = null;
            glassNode.setRenderEffect(null);
            Log.w(TAG, "lens unavailable; static material from now on", error);
            return false;
        }
        effectSpec = material;
        effectWidth = width;
        effectHeight = height;
        effectRadius = radius;
        return true;
    }

    @Override protected void onDraw(Canvas canvas) {
        GlassSpec material = spec.get();
        if (material == null) return;
        // Another pane's recording draws this one through its display list; drawing here then
        // would be recorded into that pane, so only the static material may be painted.
        if (!capturing && hasContent && canvas instanceof RecordingCanvas && canvas.isHardwareAccelerated()
                && nodeWidth == getWidth() && nodeHeight == getHeight() && lensAvailable()) {
            ((RecordingCanvas) canvas).drawRenderNode(glassNode);
            return;
        }
        drawFallback(canvas, material);
    }

    private void drawFallback(Canvas canvas, GlassSpec material) {
        float radius = radius();
        if (fallback == null || fallbackSpec != material || fallbackWidth != getWidth()
                || fallbackHeight != getHeight() || fallbackRadius != radius) {
            fallback = GlassRenderer.background(material.withoutOptics(), radius,
                    getResources().getDisplayMetrics().density);
            fallbackSpec = material;
            fallbackWidth = getWidth();
            fallbackHeight = getHeight();
            fallbackRadius = radius;
        }
        fallback.setBounds(0, 0, getWidth(), getHeight());
        fallback.draw(canvas);
    }
}
