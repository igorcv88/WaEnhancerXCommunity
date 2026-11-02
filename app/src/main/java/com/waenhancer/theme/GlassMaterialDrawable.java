package com.waenhancer.theme;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Matrix;
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
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Glass in an existing background slot: no reparenting, layout changes or filtering of text.
 *
 * <p>Live when {@link #captureBehind} has run: a {@link LiveBackdrop} records only what the window
 * draws <em>beneath</em> the host view ({@link BehindRecorder}: ancestors' backgrounds and the
 * siblings drawn before it), never the host or anything after it, so the recording cannot reach
 * this drawable again. An earlier design recorded the whole window, including the host's own
 * cached background node, and that display-list cycle crashed the RenderThread natively.</p>
 *
 * <p>When no recording is safe to present, the glass layer remains transparent. In particular,
 * a transient capture failure must not flash a flat tinted replacement over the conversation.
 * We never reuse backdrop pixels after an activity/source epoch changes.</p>
 */
public final class GlassMaterialDrawable extends Drawable implements Drawable.Callback {
    // A factory drawable may be Canvas-owned, with no Drawable.Callback at all. Resolve it
    // only inside a verified native row draw, never during backdrop or alpha-mask recording.
    private static final ThreadLocal<DrawHost> DRAW_HOST = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> MASK_DRAW = new ThreadLocal<>();
    public static final class DrawHost {
        final View view;
        final Canvas canvas;
        final DrawHost previous;
        private final NativeBubbleDrawLedger<GlassMaterialDrawable> factoryDraws =
                new NativeBubbleDrawLedger<>();
        final Matrix matrix = new Matrix();
        DrawHost(View view, Canvas canvas, DrawHost previous) {
            this.view = view;
            this.canvas = canvas;
            this.previous = previous;
            canvas.getMatrix(matrix);
        }
        /** Identifies the factory drawable actually rendered in this native row draw. */
        void mark(GlassMaterialDrawable drawable) { factoryDraws.mark(drawable); }
        public View row() { return view; }
        public boolean saw(GlassMaterialDrawable drawable) { return factoryDraws.saw(drawable); }
        public boolean isNestedFor(View target) { return previous != null && previous.view == target; }
    }
    public static DrawHost enterRowDraw(View view, Canvas canvas) {
        DrawHost previous = DRAW_HOST.get();
        DRAW_HOST.set(new DrawHost(view, canvas, previous));
        return previous;
    }
    public static DrawHost currentRowDraw() { return DRAW_HOST.get(); }
    public static void leaveRowDraw(DrawHost previous) {
        if (previous == null) DRAW_HOST.remove(); else DRAW_HOST.set(previous);
    }
    private boolean canvasOwned;
    private boolean pixelSuppressed;
    /** True only when the old recording was invalidated; soft damage retains its presentation. */
    private Consumer<Boolean> captureDemand;
    private final BubbleCaptureEpoch captureEpoch = new BubbleCaptureEpoch();
    private long capturedAt, publishedSerial;
    private int canvasOffsetX, canvasOffsetY;
    private String guardReason;
    private boolean drawReported;
    private Drawable original;
    private WeakReference<View> owner;
    private Supplier<GlassSpec> spec;
    private final float radiusDp;
    private final boolean nativeMask;
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix hostInverse = new Matrix(), hostCurrent = new Matrix();
    private final float[] hostValues = new float[9];
    private boolean captureDisabled;
    private int alpha = 255;
    private LiveBackdrop live;
    private RecordingState recordingState = RecordingState.UNINITIALIZED;
    private Boolean presentedLive;
    private String budgetStatus = "unassigned";
    private final int[] location = new int[2];
    /** Optional local subregion for full-width WhatsApp rows with narrow bubble silhouettes. */
    private Rect materialRegion;
    private Consumer<View> onHostResolved;
    private Runnable onHostMissing;
    private boolean notifiedHost;
    private boolean reportedMissingHost;

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
        if (Boolean.TRUE.equals(MASK_DRAW.get())) { drawOriginal(canvas); return; }
        View view = owner.get();
        if (view == null) view = callbackOwner();
        DrawHost context = DRAW_HOST.get();
        if (context != null && context.canvas == canvas && !LiveBackdrop.isCapturing()) {
            // draw()/onDraw()/dispatchDraw() may nest for the same row. Record the
            // identity on the enclosing draw() frame, not only the innermost hook.
            for (DrawHost frame = context; frame != null && frame.view == context.view;
                    frame = frame.previous) frame.mark(this);
        }
        if (onHostResolved != null && context != null && context.canvas == canvas
                && !LiveBackdrop.isCapturing() && callbackOwner() == null) {
            // Reused: this runs for every bubble on every scrolled frame (main thread only).
            Matrix inverse = hostInverse, current = hostCurrent;
            float[] values = hostValues;
            canvas.getMatrix(current);
            if (context.matrix.invert(inverse)) {
                current.postConcat(inverse);
                current.getValues(values);
                // Translations are exact; reject scaled/rotated native sub-canvases rather
                // than capture a different region and distort message content.
                if (Math.abs(values[0] - 1f) < .001f && Math.abs(values[4] - 1f) < .001f
                        && Math.abs(values[1]) < .001f && Math.abs(values[3]) < .001f) {
                    if (view != context.view) { notifiedHost = false; invalidateCapture("rowRecycled"); }
                    view = context.view;
                    owner = new WeakReference<>(view);
                    canvasOwned = true;
                    int x = Math.round(values[2]), y = Math.round(values[5]);
                    // Same row, moved inside it: keep presenting until the recapture is ready.
                    if (x != canvasOffsetX || y != canvasOffsetY) damageCapture("canvas-offset");
                    canvasOffsetX = x; canvasOffsetY = y;
                }
            }
        }
        if (view != null && onHostResolved != null && !notifiedHost
                && (!canvasOwned || (context != null && context.canvas == canvas
                    && context.view == view && !LiveBackdrop.isCapturing()))) {
            notifiedHost = true;
            onHostResolved.accept(view);
        } else if (view == null && onHostMissing != null && !reportedMissingHost) {
            reportedMissingHost = true;
            onHostMissing.run();
        }
        if (pixelSuppressed) return;
        GlassSpec material = spec.get();
        Rect b = getBounds();
        if (view == null || material == null || b.isEmpty()) {
            traceGuard(view, view == null ? "owner-missing" : material == null ? "material-disabled" : "bounds-empty");
            drawOriginal(canvas);
            return;
        }
        Rect area = materialArea(b);
        if (area.isEmpty()) { traceGuard(view, "material-area-empty"); return; }
        guardReason = null;
        if (!LiveBackdrop.isCapturing() && !drawReported) {
            drawReported = true;
            GlassTrace.event(view.getRootView(), this, view, "DRAW", "native-drawable",
                    "canvasOwned=" + canvasOwned + " area=" + area.toShortString());
        }
        int save = canvas.save();
        canvas.translate(b.left + area.left, b.top + area.top);
        int layer = -1;
        if (nativeMask && original != null) {
            layer = canvas.saveLayer(0, 0, area.width(), area.height(), null);
        }
        try {
            int fade = alpha < 255 && live != null
                    ? canvas.saveLayerAlpha(0, 0, area.width(), area.height(), alpha) : -1;
            boolean drewLive = live != null && LiveBackdrop.wantsLive(material)
                    && live.draw(canvas, area.width(), area.height());
            if (!LiveBackdrop.isCapturing() && drewLive && !Boolean.TRUE.equals(presentedLive)) {
                BubbleLifecycleProbe.event("first-presented", view, this,
                        "revision=" + captureEpoch.captured() + " serial=" + publishedSerial
                                + " latencyMs=" + (android.os.SystemClock.uptimeMillis() - capturedAt)
                                + " canvasOwned=" + canvasOwned + " budget=" + budgetStatus);
            }
            if (!LiveBackdrop.isCapturing()) GlassTrace.presented(view.getRootView(), this, view.getRootView(), drewLive);
            if (!LiveBackdrop.isCapturing() && (presentedLive == null || presentedLive != drewLive)) {
                presentedLive = drewLive;
                if (GlassProbe.enabled()) GlassProbe.event("DRAWABLE_DRAW_STATE", view,
                        "drawable=" + GlassTrace.id(this) + " canvasOwned=" + canvasOwned
                                + " live=" + drewLive + " area=" + area.toShortString()
                                + " budget=" + budgetStatus + " state=" + recordingState());
                GlassTrace.event(view.getRootView(), this, view.getRootView(),
                        drewLive ? "PRESENTING_LIVE" : "PRESENTING_TRANSPARENT", "draw-result",
                        "specKey=" + Integer.toHexString(material.hashCode()) + " " + captureStatus());
            }
            if (fade >= 0) canvas.restoreToCount(fade);
            if (drewLive && layer >= 0) {
                // Isolate the native silhouette before DST_IN; drawing a sparse mask directly
                // would leave the parts it never touched intact, including square corners.
                int mask = canvas.saveLayer(0, 0, area.width(), area.height(), maskPaint);
                canvas.translate(-b.left - area.left, -b.top - area.top);
                drawOriginal(canvas);
                canvas.restoreToCount(mask);
            }
        } finally {
            if (layer >= 0) canvas.restoreToCount(layer);
            canvas.restoreToCount(save);
        }
    }

    private void drawOriginal(Canvas canvas) {
        if (original == null) return;
        Boolean previous = MASK_DRAW.get();
        MASK_DRAW.set(true);
        try { original.setBounds(getBounds()); original.draw(canvas); }
        finally { if (previous == null) MASK_DRAW.remove(); else MASK_DRAW.set(previous); }
    }

    private void traceGuard(View view, String reason) {
        if (LiveBackdrop.isCapturing() || reason.equals(guardReason)) return;
        guardReason = reason;
        if (view != null) GlassTrace.event(view.getRootView(), this, view, "DRAW_SKIPPED", reason,
                "bounds=" + getBounds().toShortString());
    }

    public boolean isCanvasOwnedBy(View view) { return canvasOwned && owner.get() == view; }

    /** A native row may draw this same factory instance again after dropping its binding.
     * Rearm the draw-host callback on release; otherwise canvas-owned factories stay inert. */
    public void rearmCanvasHost() {
        if (!canvasOwned) return;
        notifiedHost = false;
        reportedMissingHost = false;
        drawReported = false;
    }

    public void attachMaterial(Supplier<GlassSpec> material) { spec = material; }
    public void setPixelSuppressed(boolean suppressed) {
        if (pixelSuppressed == suppressed) return;
        pixelSuppressed = suppressed;
        if (suppressed) releaseLive(); else invalidateCapture("factory-promoted");
        invalidateSelf();
    }
    public void observeCaptureDemand(Consumer<Boolean> demand) { captureDemand = demand; }
    public long damageRevision() { return captureEpoch.revision(); }
    public boolean capturePending() { return !pixelSuppressed && captureEpoch.pending(); }
    public boolean presentationPending() { return !pixelSuppressed && captureEpoch.captured() >= 0 && !hasPresentedLive(); }
    public void captureAttempted() { captureEpoch.attempted(); }
    public void invalidateCapture(String reason) {
        captureEpoch.damage();
        BubbleLifecycleProbe.event(reason, owner.get(), this,
                "mask=" + GlassTrace.id(original) + " revision=" + captureEpoch.revision()
                        + " canvasOwned=" + canvasOwned + " " + captureStatus());
        releaseLive();
        if (captureDemand != null) captureDemand.accept(true);
        invalidateSelf();
    }
    /**
     * Requests a recapture while keeping the current recording on screen until the new one is
     * published. For changes that do not alter what lies behind this drawable or its size (row
     * text/ticks/thumbnails, a move inside the same row): releasing here presented transparent
     * frames on every such signal, which read as the bubble being destroyed and rebuilt. Identity,
     * host, size, mask and region changes still use {@link #invalidateCapture(String)}.
     */
    public void damageCapture(String reason) {
        captureEpoch.damage();
        if (GlassProbe.enabled()) BubbleLifecycleProbe.event("soft-" + reason, owner.get(), this,
                "revision=" + captureEpoch.revision() + " canvasOwned=" + canvasOwned
                        + " " + captureStatus());
        if (captureDemand != null) captureDemand.accept(false);
        invalidateSelf();
    }

    private boolean published(boolean success) {
        if (success) {
            captureEpoch.published();
            capturedAt = android.os.SystemClock.uptimeMillis();
            publishedSerial++;
        }
        return success;
    }

    /** Constrain a message-row recording to its bubble bounds, not the entire 1440px row. */
    public void setMaterialRegion(Rect region) {
        if (region == null ? materialRegion == null : region.equals(materialRegion)) return;
        if (GlassProbe.enabled()) BubbleLifecycleProbe.event("region-changed", owner.get(), this,
                "old=" + materialRegion + " new=" + region + " mask=" + GlassTrace.id(original));
        materialRegion = region == null ? null : new Rect(region);
        invalidateCapture("material-region");
    }

    private Rect materialArea(Rect bounds) {
        if (materialRegion == null) return new Rect(0, 0, bounds.width(), bounds.height());
        Rect area = new Rect(materialRegion);
        if (!area.intersect(0, 0, bounds.width(), bounds.height())) area.setEmpty();
        return area;
    }

    public int captureRegionWidth() { return materialRegion == null ? -1 : materialRegion.width(); }
    public int captureRegionHeight() { return materialRegion == null ? -1 : materialRegion.height(); }

    /** A successful background capture alone does not prove the row display list repainted. */
    public boolean hasPresentedLive() { return Boolean.TRUE.equals(presentedLive); }

    /**
     * A factory result can become nested under a selector after creation, or be drawn before
     * the window's first discovery pass. Follow native Drawable.Callback ownership when the
     * result is actually drawn; notify the app hook once, outside its drawing/capture path.
     */
    public void observeHost(Consumer<View> resolved, Runnable missing) {
        onHostResolved = resolved;
        onHostMissing = missing;
    }

    public View callbackOwner() {
        Drawable.Callback callback = getCallback();
        for (int depth = 0; callback != null && depth < 10; depth++) {
            if (callback instanceof View) return (View) callback;
            if (!(callback instanceof Drawable)) return null;
            callback = ((Drawable) callback).getCallback();
        }
        return null;
    }

    /** A factory drawable may be nested inside a LayerDrawable instead of being the view's
     * direct background. The owning row is discovered after native attachment. */
    public void attachOwner(View host) {
        if (owner.get() != host) invalidateCapture("owner-changed");
        owner = new WeakReference<>(host);
    }

    /**
     * Records what lies beneath {@code host}, whose background this is, for the next draw.
     * Main thread, from a pre-draw pass. False leaves this layer transparent while retrying.
     */
    public boolean captureBehind(View host) {
        captureAttempted();
        if (canvasOwned && owner.get() != host) return false;
        if (host == null || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return false;
        GlassSpec material = spec.get();
        // A background gets its bounds only when the view first draws it, after this pre-draw
        // pass: every new or re-bound direct background failed its first capture and showed
        // the fallback for a frame (24 of 27 binds in the device log). Only a directly mounted
        // background spans the host. Nested Inset/LayerDrawable children and image drawables
        // keep container-assigned bounds, which can deliberately be smaller than the host.
        if (!canvasOwned && host.getBackground() == this
                && (getBounds().width() != host.getWidth() || getBounds().height() != host.getHeight())
                && host.getWidth() > 0 && host.getHeight() > 0) {
            setBounds(0, 0, host.getWidth(), host.getHeight());
        }
        Rect b = getBounds();
        if (material == null || b.isEmpty() || !host.isAttachedToWindow() || !host.isShown()) return false;
        Rect area = materialArea(b);
        if (area.isEmpty()) return false;
        if (live == null) live = new LiveBackdrop();
        if (!live.available()) return false;
        float density = host.getResources().getDisplayMetrics().density;
        float radius = Math.min(radiusDp * density, Math.min(area.width(), area.height()) / 2f);
        int w = area.width(), h = area.height();
        if (GlassProbe.enabled()) GlassProbe.event("DRAWABLE_CAPTURE_ATTEMPT", host,
                "drawable=" + GlassTrace.id(this) + " canvasOwned=" + canvasOwned
                        + " size=" + w + "x" + h + " budget=" + budgetStatus);
        GlassOptics optics = GlassOptics.current();
        if (optics.corrected && optics.geometry) {
            int left = canvasOffsetX + b.left + area.left, top = canvasOffsetY + b.top + area.top;
            boolean success = live.capture(w, h, radius, density, material,
                    (canvas, padding) -> BehindRecorder.paintExact(canvas, host, left, top, w, h, padding));
            if (GlassProbe.enabled()) GlassProbe.event("DRAWABLE_CAPTURE_RESULT", host,
                    "drawable=" + GlassTrace.id(this) + " success=" + success
                            + " state=" + recordingState());
            return published(success);
        }
        host.getLocationOnScreen(location);
        int x = location[0] + canvasOffsetX + b.left + area.left, y = location[1] + canvasOffsetY + b.top + area.top;
        boolean success = live.capture(w, h, radius, density, material,
                (canvas, padding) -> BehindRecorder.paint(canvas, host, x, y, w, h));
        if (GlassProbe.enabled()) GlassProbe.event("DRAWABLE_CAPTURE_RESULT", host,
                "drawable=" + GlassTrace.id(this) + " success=" + success
                        + " state=" + recordingState());
        return published(success);
    }

    public void setBudgetStatus(String status) { budgetStatus = status; }
    public String captureStatus() {
        return "budgetStatus=" + budgetStatus + " "
                + (live == null ? "recordingState=" + recordingState
                    + " captureAvailable=false hasContent=false hasDisplayList=false shaderStatus=waiting" : live.status());
    }
    public void setCaptureDisabled(boolean disabled) { captureDisabled = disabled; }
    public boolean capturePermanentlyUnavailable() {
        return android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU
                || captureDisabled || live != null && !live.available();
    }
    public boolean wantsLiveCapture() {
        return !pixelSuppressed && !captureDisabled && LiveBackdrop.wantsLive(spec.get());
    }

    /** Only an eligible surface that unexpectedly lost valid pixels asks for immediate recovery. */
    public boolean needsFreshCapture(boolean eligible) {
        return recordingState().requestsImmediateRecovery(eligible);
    }

    public RecordingState recordingState() {
        return live == null ? recordingState : live.recordingState();
    }

    /** Drops live pixels; the surface is transparent until the next safe capture. */
    public void releaseLive() {
        releaseLive(RecordingState.INTENTIONALLY_RELEASED);
    }
    public void releaseLive(RecordingState reason) {
        if (GlassProbe.enabled() && (recordingState() != reason || Boolean.TRUE.equals(presentedLive)))
            BubbleLifecycleProbe.event("release-" + reason, owner.get(), this,
                    "revision=" + captureEpoch.revision() + " mask=" + GlassTrace.id(original)
                            + " canvasOwned=" + canvasOwned + " " + captureStatus());
        recordingState = reason;
        captureEpoch.released();
        // A new capture after a scroll/recycled row must repaint its background even if an
        // older capture had already been presented in the same drawable instance.
        presentedLive = null;
        drawReported = false;
        if (live != null) live.release(reason);
    }
    public Drawable original() { return original; }

    /**
     * WhatsApp replaced the view's background while this glass was installed (a toolbar does so
     * when its lift state changes). Adopt the new native drawable as the one to mask with and
     * restore, and keep the live recording: a fresh drawable would start without one and show
     * the fallback for a frame or more on every replacement.
     */
    public void replaceOriginal(Drawable replacement) {
        if (replacement == original || replacement == this) return;
        original = replacement;
        if (nativeMask) invalidateCapture("nativeMaskRebound");
        if (original != null) {
            original.setCallback(this);
            original.setState(getState()); original.setLevel(getLevel());
            original.setBounds(getBounds());
        }
        invalidateSelf();
    }
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
    private int lastBoundsWidth = -1, lastBoundsHeight = -1;

    @Override protected void onBoundsChange(Rect bounds) {
        if (original != null) original.setBounds(bounds);
        boolean sameSize = bounds.width() == lastBoundsWidth && bounds.height() == lastBoundsHeight;
        lastBoundsWidth = bounds.width();
        lastBoundsHeight = bounds.height();
        // A pure move keeps the recording's size valid; a resize cannot reuse it.
        if (sameSize) damageCapture("drawable-moved"); else invalidateCapture("drawable-bounds");
    }
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
