package com.waenhancer.theme;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.RecordingCanvas;
import android.graphics.drawable.Drawable;
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
 * <p>Below Android 13, in power saving or when the lens fails, the pane paints the static
 * material and records nothing.</p>
 */
public final class GlassPane extends FrameLayout {

    private static final String TAG = "WaEnhancerX/GlassPane";

    /** Recapture every frame for this long after motion; scrolling is what the glass is for. */
    static final long ACTIVE_WINDOW_MS = CaptureScheduler.ACTIVE_WINDOW_MS;
    /** Legacy: at rest, slow down, never stop, so late changes show. */
    static final long IDLE_INTERVAL_MS = CaptureScheduler.IDLE_INTERVAL_MS;

    private static final List<GlassPane> LIVE = new ArrayList<>();
    private static final GlassPaneGraph.Tree<View> TREE = view -> {
        ViewParent parent = view.getParent();
        return parent instanceof View ? (View) parent : null;
    };

    private final LiveBackdrop backdrop = new LiveBackdrop();
    private final int[] hostLocation = new int[2];
    private final int[] otherLocation = new int[2];
    private final ViewTreeObserver.OnPreDrawListener preDraw = this::onPreDrawCapture;
    private final CaptureScheduler scheduler = new CaptureScheduler();
    private final ViewTreeObserver.OnScrollChangedListener scrolled =
            () -> scheduler.activity(SystemClock.uptimeMillis());
    private final Matrix paneMatrix = new Matrix();
    private final Matrix inverse = new Matrix();
    private final Matrix other = new Matrix();

    private View source;
    private List<View> underlay = Collections.emptyList();
    private Supplier<GlassSpec> spec = () -> null;
    private float cornerRadiusPx;

    private Drawable fallback;
    private GlassSpec fallbackSpec;
    private int fallbackWidth = -1, fallbackHeight = -1;
    private float fallbackRadius = -1f;

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

    /** Main thread only: whether any live glass is recording right now. Guards hold back touch feedback. */
    public static boolean isCapturing() {
        return LiveBackdrop.isCapturing();
    }

    /** Panes currently attached; read by {@link BehindRecorder} to avoid recording a loop. */
    static List<GlassPane> live() {
        return LIVE;
    }

    /** Every live surface falls back to the static material. */
    public static void disableCapture() {
        LiveBackdrop.disableCapture();
    }

    /** The subtree transmitted through this pane, or null for an underlay-only pane. */
    public void setSource(View view) {
        if (source == view) return;
        source = view;
        refusedFor = null;
        scheduler.activity(SystemClock.uptimeMillis());
        invalidate();
    }

    public View source() {
        return source;
    }

    /** Views drawn beneath the source at their own screen positions: the wallpaper. */
    public void setUnderlay(List<View> views) {
        underlay = views == null ? Collections.emptyList() : new ArrayList<>(views);
        scheduler.activity(SystemClock.uptimeMillis());
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
        scheduler.activity(SystemClock.uptimeMillis());
    }

    @Override protected void onDetachedFromWindow() {
        LiveBudget.forWindow(getRootView()).releasePane(this);
        getViewTreeObserver().removeOnPreDrawListener(preDraw);
        getViewTreeObserver().removeOnScrollChangedListener(scrolled);
        LIVE.remove(this);
        backdrop.release();
        super.onDetachedFromWindow();
    }

    /**
     * Capture in pre-draw, before this frame's draw. Legacy: the invalidate keeps moving glass
     * live and a heartbeat keeps it fresh. Temporal group: only frames the window draws anyway are
     * captured; see {@link CaptureScheduler}.
     */
    private boolean onPreDrawCapture() {
        // Guarded whole: capture draws WhatsApp's own views, and an escape here would crash the UI
        // thread on every frame.
        try {
            if (!isAttachedToWindow() || getWidth() <= 0 || getHeight() <= 0 || isCapturing()) return true;
            GlassSpec material = spec.get();
            if (!backdrop.available() || material == null) return true;
            long now = SystemClock.uptimeMillis();
            getLocationOnScreen(hostLocation);
            if (hostLocation[0] != lastScreenX || hostLocation[1] != lastScreenY) {
                lastScreenX = hostLocation[0];
                lastScreenY = hostLocation[1];
                scheduler.activity(now);
            }
            GlassOptics optics = GlassOptics.current();
            boolean damageDriven = optics.corrected && optics.temporal;
            // A dropped recording (an Activity stop, a screenshot overlay) is captured again at
            // once rather than showing the fallback until the next heartbeat.
            int decision = scheduler.decide(now, damageDriven, backdrop.wasDropped());
            if ((decision & CaptureScheduler.CAPTURE) == 0) {
                if ((decision & CaptureScheduler.INVALIDATE_LATER) != 0) postInvalidateDelayed(scheduler.delayMs());
                return true;
            }
            View src = safeSource();
            if (src == null && underlay.isEmpty()) return true;
            float density = getResources().getDisplayMetrics().density;
            if (damageDriven) {
                // One budget per window (LG-12): a pane is admitted like any other live surface.
                LiveBudget budget = LiveBudget.forWindow(getRootView());
                if (!budget.admitPane(this, LiveBudget.cost(getWidth(), getHeight(), material, density, optics))) {
                    if (backdrop.hasRecording()) {
                        backdrop.release();
                        invalidate();
                    }
                    return true;
                }
            }
            boolean exact = optics.corrected && optics.geometry;
            boolean captured = backdrop.capture(getWidth(), getHeight(), radius(), density, material,
                    (canvas, padding) -> exact ? paintExact(canvas, src) : paint(canvas, src));
            if (captured) {
                if ((decision & CaptureScheduler.INVALIDATE_NOW) != 0) invalidate();
                else if ((decision & CaptureScheduler.INVALIDATE_LATER) != 0) postInvalidateDelayed(scheduler.delayMs());
            }
        } catch (Throwable error) {
            if (!loggedFailure) {
                loggedFailure = true;
                Log.w(TAG, "pre-draw capture failed; frame skipped", error);
            }
        }
        return true; // never cancel the host's frame
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

    /** The underlay, then the source, each at its own screen position relative to this pane. */
    private boolean paint(RecordingCanvas canvas, View src) {
        // A scaled ancestor shrinks the pane on screen; the span behind it shrinks with it.
        float sx = 1f, sy = 1f;
        for (View v = this; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            sx *= v.getScaleX();
            sy *= v.getScaleY();
        }
        if (sx < 0.01f || sy < 0.01f) return false;
        canvas.scale(1f / sx, 1f / sy);
        boolean drew = false;
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
            drew = true;
        }
        if (src != null) {
            src.getLocationOnScreen(otherLocation);
            canvas.translate(otherLocation[0] - hostLocation[0], otherLocation[1] - hostLocation[1]);
            src.draw(canvas);
            drew = true;
        }
        return drew;
    }

    /**
     * The underlay, then the source, each placed by its exact transform into this pane:
     * {@code inverse(G_pane) · G_view}, with {@code G} from {@link View#transformMatrixToGlobal}.
     * Rotation, scale and translation of either side, and the source's own scroll, are all
     * honoured; the legacy path handles translation and ancestor scale only (LG-08).
     */
    private boolean paintExact(RecordingCanvas canvas, View src) {
        paneMatrix.reset();
        transformMatrixToGlobal(paneMatrix);
        if (!paneMatrix.invert(inverse)) return false;
        boolean drew = false;
        for (View u : underlay) {
            if (u == null || u.getWidth() <= 0 || u.getHeight() <= 0
                    || u.getVisibility() != View.VISIBLE || !u.isAttachedToWindow()) continue;
            if (src != null && GlassPaneGraph.isAncestor(TREE, src, u)) continue; // drawn by the source
            drew |= drawPlaced(canvas, u);
        }
        if (src != null) drew |= drawPlaced(canvas, src);
        return drew;
    }

    private boolean drawPlaced(RecordingCanvas canvas, View view) {
        other.reset();
        view.transformMatrixToGlobal(other);
        other.postConcat(inverse);
        float alpha = Math.max(0f, Math.min(1f, view.getAlpha()));
        int save = alpha < 1f
                ? canvas.saveLayerAlpha(null, Math.round(alpha * 255))
                : canvas.save();
        canvas.concat(other);
        canvas.translate(-view.getScrollX(), -view.getScrollY());
        view.draw(canvas);
        canvas.restoreToCount(save);
        return true;
    }

    @Override protected void onDraw(Canvas canvas) {
        GlassSpec material = spec.get();
        if (material == null) return;
        // Drawn inside another surface's recording too: what this pane records is behind it, so
        // nothing that records this pane can be inside its own recording.
        if (LiveBackdrop.wantsLive(material) && backdrop.draw(canvas, getWidth(), getHeight())) return;
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
