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
    private Drawable original;
    private WeakReference<View> owner;
    private Supplier<GlassSpec> spec;
    private final float radiusDp;
    private final boolean nativeMask;
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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
        View view = owner.get();
        if (view == null) view = callbackOwner();
        if (view != null && onHostResolved != null && !notifiedHost) {
            notifiedHost = true;
            onHostResolved.accept(view);
        } else if (view == null && onHostMissing != null && !reportedMissingHost) {
            reportedMissingHost = true;
            onHostMissing.run();
        }
        GlassSpec material = spec.get();
        Rect b = getBounds();
        if (view == null || material == null || b.isEmpty()) { drawOriginal(canvas); return; }
        Rect area = materialArea(b);
        if (area.isEmpty()) return;
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
            if (!LiveBackdrop.isCapturing()) GlassTrace.presented(view.getRootView(), this, view.getRootView(), drewLive);
            if (!LiveBackdrop.isCapturing() && (presentedLive == null || presentedLive != drewLive)) {
                presentedLive = drewLive;
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
        if (original != null) { original.setBounds(getBounds()); original.draw(canvas); }
    }

    public void attachMaterial(Supplier<GlassSpec> material) { spec = material; }

    /** Constrain a message-row recording to its bubble bounds, not the entire 1440px row. */
    public void setMaterialRegion(Rect region) {
        if (region == null ? materialRegion == null : region.equals(materialRegion)) return;
        materialRegion = region == null ? null : new Rect(region);
        releaseLive();
        invalidateSelf();
    }

    private Rect materialArea(Rect bounds) {
        if (materialRegion == null) return new Rect(0, 0, bounds.width(), bounds.height());
        Rect area = new Rect(materialRegion);
        if (!area.intersect(0, 0, bounds.width(), bounds.height())) area.setEmpty();
        return area;
    }

    public int captureRegionWidth() { return materialRegion == null ? -1 : materialRegion.width(); }
    public int captureRegionHeight() { return materialRegion == null ? -1 : materialRegion.height(); }

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
    public void attachOwner(View host) { owner = new WeakReference<>(host); }

    /**
     * Records what lies beneath {@code host}, whose background this is, for the next draw.
     * Main thread, from a pre-draw pass. False leaves this layer transparent while retrying.
     */
    public boolean captureBehind(View host) {
        if (host == null || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return false;
        GlassSpec material = spec.get();
        // A background gets its bounds only when the view first draws it, after this pre-draw
        // pass: every new or re-bound drawable failed its first capture and showed the fallback
        // for a frame (24 of 27 binds in the device log). A view's background always spans the
        // view, so take that size now; the view's own draw sets the same bounds.
        if (getBounds().isEmpty() && host.getWidth() > 0 && host.getHeight() > 0) {
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
        GlassOptics optics = GlassOptics.current();
        if (optics.corrected && optics.geometry) {
            int left = b.left + area.left, top = b.top + area.top;
            return live.capture(w, h, radius, density, material,
                    (canvas, padding) -> BehindRecorder.paintExact(canvas, host, left, top, w, h, padding));
        }
        host.getLocationOnScreen(location);
        int x = location[0] + b.left + area.left, y = location[1] + b.top + area.top;
        return live.capture(w, h, radius, density, material,
                (canvas, padding) -> BehindRecorder.paint(canvas, host, x, y, w, h));
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
        return !captureDisabled && LiveBackdrop.wantsLive(spec.get());
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
        recordingState = reason;
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
