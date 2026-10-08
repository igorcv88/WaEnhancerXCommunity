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
 * <p>Below Android 13 or when the lens fails, the pane paints the static
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
    private final List<GlassPaneGraph.Pane<View>> graph = new ArrayList<>();

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
    private final SurfaceRecovery recovery = new SurfaceRecovery();
    private boolean suspended;
    private boolean observing;
    private int captureWidth, captureHeight;
    private boolean lastSourceReady, lastUnderlayReady;
    private long opticsRevision = -1;
    private GlassSpec captureMaterial;
    private String budgetStatus = "unassigned";
    private final Runnable retryFrame = () -> { if (!suspended && isAttachedToWindow()) invalidate(); };

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
        revalidate("source-changed");
        refusedFor = null;
        scheduler.activity(SystemClock.uptimeMillis());
        invalidate();
    }

    public View source() {
        return source;
    }

    /** Views drawn beneath the source at their own screen positions: the wallpaper. */
    public void setUnderlay(List<View> views) {
        List<View> next = views == null ? Collections.emptyList() : views;
        if (underlay.equals(next)) return;
        underlay = new ArrayList<>(next);
        revalidate("underlay-changed");
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
        revalidate("radius-changed");
        invalidateOutline();
        invalidate();
    }

    private float radius() {
        return Math.min(cornerRadiusPx, Math.min(getWidth(), getHeight()) / 2f);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!LIVE.contains(this)) LIVE.add(this);
        observe(!suspended);
        scheduler.activity(SystemClock.uptimeMillis());
    }

    @Override protected void onDetachedFromWindow() {
        LiveBudget.forWindow(getRootView()).releasePane(this);
        observe(false);
        removeCallbacks(retryFrame);
        LIVE.remove(this);
        backdrop.release();
        recovery.revalidate();
        super.onDetachedFromWindow();
    }

    private void observe(boolean enable) {
        if (observing == enable) return;
        ViewTreeObserver observer = getViewTreeObserver();
        if (!observer.isAlive()) { if (!enable) observing = false; return; }
        if (enable) {
            observer.addOnPreDrawListener(preDraw);
            observer.addOnScrollChangedListener(scrolled);
        } else {
            observer.removeOnPreDrawListener(preDraw);
            observer.removeOnScrollChangedListener(scrolled);
        }
        observing = enable;
    }

    public void setSuspended(boolean value) {
        if (suspended == value) return;
        SurfaceRecovery.State before = recovery.state();
        suspended = value;
        if (value) {
            recovery.suspend();
            observe(false);
            removeCallbacks(retryFrame);
            backdrop.release();
        } else {
            revalidate("resume");
            if (isAttachedToWindow()) observe(true);
            scheduler.activity(SystemClock.uptimeMillis());
            invalidate();
        }
        trace(before, value ? "stop" : "resume");
    }

    private void revalidate(String reason) {
        SurfaceRecovery.State before = recovery.state();
        backdrop.release();
        if (!suspended) recovery.revalidate();
        trace(before, reason);
    }

    private void trace(SurfaceRecovery.State before, String reason) {
        if (before == recovery.state()) return;
        GlassTrace.transition(getRootView(), this, source, before, recovery, reason,
                "paneId=" + GlassTrace.id(this) + " specKey=" + GlassOptics.current().key() + ":"
                + (captureMaterial == null ? "none" : Integer.toHexString(captureMaterial.hashCode()))
                + " budgetStatus=" + budgetStatus + " " + backdrop.status());
    }

    private void scheduleFrame(long delay) {
        removeCallbacks(retryFrame);
        if (!suspended) postDelayed(retryFrame, Math.max(1L, delay));
    }

    private void failed(long now, boolean fatal, String reason) {
        SurfaceRecovery.State before = recovery.state();
        recovery.failed(now, fatal);
        backdrop.release();
        trace(before, reason);
        long delay = recovery.retryDelay(now);
        if (delay > 0) scheduleFrame(delay);
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
            if (suspended || !isShown() || !isAttachedToWindow() || getWidth() <= 0 || getHeight() <= 0 || isCapturing()) return true;
            GlassSpec material = spec.get();
            if (material == null || !LiveBackdrop.wantsLive(material)) return true;
            long now = SystemClock.uptimeMillis();
            boolean sourceReady = source != null && source.isAttachedToWindow() && source.isLaidOut();
            boolean underlayReady = false;
            for (View view : underlay) if (view != null && view.isAttachedToWindow() && view.isLaidOut()) {
                underlayReady = true; break;
            }
            if (sourceReady != lastSourceReady || underlayReady != lastUnderlayReady) {
                lastSourceReady = sourceReady; lastUnderlayReady = underlayReady;
                revalidate("source-readiness-changed");
            }
            if (opticsRevision != GlassOptics.revision() || !material.equals(captureMaterial)) {
                opticsRevision = GlassOptics.revision(); captureMaterial = material;
                revalidate("material-changed");
            }
            if (captureWidth != getWidth() || captureHeight != getHeight()) {
                captureWidth = getWidth(); captureHeight = getHeight(); revalidate("size-changed");
            }
            if (!recovery.mayCapture(now)) return true;
            if (!backdrop.available()) { failed(now, true, "capability-unavailable"); return true; }
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
            View src = safeSource();
            boolean sourceDamage = src != null && src.isDirty();
            for (View view : underlay) if (view != null && view.isDirty()) sourceDamage = true;
            if (sourceDamage) scheduler.activity(now);
            int decision = scheduler.decide(now, damageDriven, !backdrop.hasRecording());
            if ((decision & CaptureScheduler.CAPTURE) == 0) {
                if ((decision & CaptureScheduler.INVALIDATE_LATER) != 0) scheduleFrame(scheduler.delayMs());
                return true;
            }
            if (src == null && underlay.isEmpty()) { failed(now, false, "source-not-ready"); return true; }
            float density = getResources().getDisplayMetrics().density;
            if (damageDriven) {
                // One budget per window (LG-12): a pane is admitted like any other live surface.
                LiveBudget budget = LiveBudget.forWindow(getRootView());
                boolean admitted = budget.admitPane(this, LiveBudget.cost(getWidth(), getHeight(), material, density, optics));
                String status = admitted ? "admitted" : "refused";
                if (!status.equals(budgetStatus)) {
                    budgetStatus = status;
                    GlassTrace.event(getRootView(), this, source, "BUDGET", status,
                            "budgetStatus=" + status + " capacity=" + budget.capacity());
                }
                if (!admitted) {
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
                SurfaceRecovery.State before = recovery.state();
                recovery.ready();
                trace(before, "capture-published");
                if ((decision & CaptureScheduler.INVALIDATE_NOW) != 0) invalidate();
                else if ((decision & CaptureScheduler.INVALIDATE_LATER) != 0) scheduleFrame(scheduler.delayMs());
            } else {
                failed(now, !backdrop.available(), "capture-not-ready");
            }
        } catch (Throwable error) {
            failed(SystemClock.uptimeMillis(), error instanceof LinkageError, "capture-exception");
            if (recovery.failures() == 1) Log.w(TAG, "pre-draw capture failed; bounded recovery", error);
        }
        return true; // never cancel the host's frame
    }

    private View safeSource() {
        graph.clear();
        for (GlassPane pane : LIVE) graph.add(new GlassPaneGraph.Pane<>(pane, pane.source));
        View src = source;
        if (src == null || !src.isAttachedToWindow() || !src.isLaidOut()) return null;
        String reason = GlassPaneGraph.refusal(TREE, this, src, graph);
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

    private boolean safeUnderlay(View view) {
        return GlassPaneGraph.refusal(TREE, this, view, graph) == null;
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
            if (!safeUnderlay(u)) continue;
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
            if (!safeUnderlay(u)) continue;
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
        if (!suspended && recovery.state() == SurfaceRecovery.State.LIVE && backdrop.wasDropped()) {
            failed(SystemClock.uptimeMillis(), false, "display-list-lost");
        }
        drawFallback(canvas, material);
    }

    private void drawFallback(Canvas canvas, GlassSpec material) {
        float radius = radius();
        if (fallback == null || fallbackSpec != material || fallbackWidth != getWidth()
                || fallbackHeight != getHeight() || fallbackRadius != radius) {
            fallback = GlassRenderer.background(material.neutralFallback(), radius,
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
