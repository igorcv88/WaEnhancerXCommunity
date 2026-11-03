package com.waenhancer.theme;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Message-bubble glass painted <em>inside</em> WhatsApp's own bubble draw call.
 *
 * <p>WhatsApp keeps sizing, recycling, tails, replies and media exactly as it always does: this
 * class never replaces a row background or a factory drawable. When the native bubble drawable
 * is drawn on a conversation row's canvas, the call is answered with
 * <i>shared blurred wallpaper → OneMate lens → native silhouette as alpha mask</i> instead of the
 * opaque native fill. Any missing prerequisite returns {@code false} and the caller lets the
 * native drawable paint; there is no transparent state.</p>
 *
 * <p>Scrolling: a row's display list is replayed, not re-recorded, when the list moves it. The
 * row therefore holds a {@link RenderNode} per bubble whose child carries the wallpaper texture;
 * {@link #realign()} (pre-draw, same frame) only updates that child's translation and scale. No row is
 * invalidated, nothing is re-blurred and nothing is allocated while scrolling.</p>
 */
public final class NativeBubbleGlass {
    private static final ThreadLocal<Boolean> NATIVE_PASS = new ThreadLocal<>();
    /**
     * Bubbles use the composer/header material (same wash, rim and blur) with a lens scaled to
     * their size: the 24dp chrome band covers almost all of a one-line bubble and reads as heavy
     * distortion. Device review 2026-11-03: "like the input and the header, a little refraction".
     */
    static final float BUBBLE_REFRACT_HEIGHT_DP = 12f, BUBBLE_REFRACT_AMOUNT_DP = 8f;
    /**
     * Bubble rim relative to the composer/header rim. Owner review 2026-11-03: the border "draws
     * too much attention"; the reference is a thin, quiet hairline. Gain and highlight scale the
     * user's rim setting, so the rim slider still works on bubbles.
     */
    // Second owner review 2026-11-03: 0.4/0.4/0.8dp went "from one extreme to the other" (rim
    // barely visible). The reference keeps a continuous, clearly visible thin hairline, so the
    // all-round contour is raised more than the directional highlight.
    static final float BUBBLE_RIM_GAIN = 0.65f, BUBBLE_HIGHLIGHT_GAIN = 0.5f, BUBBLE_STROKE_DP = 1.0f;
    /** Native mask alpha × this (clamped): a 15%-alpha palette still masks the glass fully. */
    static final float MASK_ALPHA_GAIN = 8f;
    /** Fallback corner when the silhouette cannot be measured. */
    static final float FALLBACK_RADIUS_DP = 12f;
    static final int MAX_SLOTS_PER_ROW = 4;
    /** CPU silhouette strips are w×1 and 1×h; larger drawables keep the fallback contour. */
    static final int MAX_MEASURE_PX = 4096;

    /** True while the native drawable paints itself (mask, measurement or fallback). */
    public static boolean nativePass() {
        return Boolean.TRUE.equals(NATIVE_PASS.get());
    }

    /** Draws the untouched native drawable; the bubble draw hook passes it straight through. */
    public static void drawNative(Drawable drawable, Canvas canvas) {
        Boolean previous = NATIVE_PASS.get();
        NATIVE_PASS.set(Boolean.TRUE);
        try { drawable.draw(canvas); }
        finally { if (previous == null) NATIVE_PASS.remove(); else NATIVE_PASS.set(previous); }
    }

    /** The conversation row whose own RenderNode is being recorded on {@code canvas}, if any. */
    public static View hostFor(Canvas canvas) {
        GlassMaterialDrawable.DrawHost frame = GlassMaterialDrawable.currentRowDraw();
        return frame != null && frame.canvas == canvas ? frame.view : null;
    }

    /** A registered bubble drawn outside a verified row canvas: native, with host evidence. */
    public static void unhosted(Drawable drawable, Canvas canvas) {
        BubbleGlassTelemetry.stayedNative(BubbleGlassTelemetry.Native.UNHOSTED);
        BubbleGlassTelemetry.unhosted(drawable, canvas, GlassMaterialDrawable.currentRowDraw());
    }

    private final WeakReference<View> root;
    private final ConversationBackdropSource source;
    /**
     * Glass nodes of each row's latest recording, by draw order. A slot belongs to a position in
     * the row's display list, never to a drawable instance: WhatsApp rebinds rows with new or
     * cached factory drawables (taps, recycling), and per-drawable slots filled up until the old
     * drawables were garbage-collected, leaving the row native (SLOT_FULL) meanwhile.
     */
    private final WeakHashMap<View, RowSlots> slots = new WeakHashMap<>();
    private final Set<View> waiting = Collections.newSetFromMap(new WeakHashMap<>());
    /** Rows whose latest recording has glass; a native re-record of one is reported as LOST. */
    private final Set<View> glassRows = Collections.newSetFromMap(new WeakHashMap<>());
    private final Paint dstIn = new Paint();
    private final Matrix current = new Matrix(), entryInverse = new Matrix(), rootGlobal = new Matrix(),
            rootInverse = new Matrix(), hostGlobal = new Matrix();
    private final RectF hostRect = new RectF();
    private boolean broken, released, presented;
    // Same native row draw: a smaller factory drawable inside the bubble is a quote, not a bubble.
    private Object lastEntry;
    private Drawable lastDrawable;
    private final RectF lastRect = new RectF();

    public NativeBubbleGlass(View root, ConversationBackdropSource source) {
        this.root = new WeakReference<>(root);
        this.source = source;
        dstIn.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        // The native drawable is only the shape. A translucent Bubble Colors palette (device:
        // SILHOUETTE_FALLBACK no-opaque-body) would otherwise make the glass equally faint, so
        // the mask's alpha is boosted: any covered pixel keeps the glass fully, edges stay smooth.
        android.graphics.ColorMatrix shapeOnly = new android.graphics.ColorMatrix(new float[]{
                0, 0, 0, 0, 0,
                0, 0, 0, 0, 0,
                0, 0, 0, 0, 0,
                0, 0, 0, MASK_ALPHA_GAIN, 0});
        dstIn.setColorFilter(new android.graphics.ColorMatrixColorFilter(shapeOnly));
    }

    /**
     * Everything a recorded bubble bakes in besides the wallpaper: opacity, rim, optics and the
     * material. Recorded rows are not re-drawn by a settings change alone, so the session
     * invalidates them when this key changes.
     */
    public static String settingsKey(GlassSpec spec) {
        return KyantLens.surfaceAlpha255() + ":" + KyantLens.rimIntensity() + ":" + GlassOptics.revision()
                + ":" + (spec == null ? 0 : spec.hashCode());
    }

    public static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !KyantLens.isBroken();
    }

    /**
     * Called from the bubble drawable's draw hook on the UI thread.
     *
     * @return true when glass was recorded and the native draw must be skipped
     */
    public boolean draw(Drawable bubble, Canvas canvas, GlassSpec spec) {
        if (broken || released || spec == null || !supported()) return stay(BubbleGlassTelemetry.Native.UNSUPPORTED);
        if (!canvas.isHardwareAccelerated()) return stay(BubbleGlassTelemetry.Native.NOT_HARDWARE);
        if (LiveBackdrop.isCaptureCanvas(canvas)) return stay(BubbleGlassTelemetry.Native.CAPTURE);
        GlassMaterialDrawable.DrawHost frame = GlassMaterialDrawable.currentRowDraw();
        if (frame == null || frame.canvas != canvas) { unhosted(bubble, canvas); return false; }
        View host = frame.view;
        Bitmap texture = source.texture();
        if (texture == null) { waiting.add(host); return lost(host, BubbleGlassTelemetry.Native.NO_TEXTURE); }
        Rect bounds = bubble.getBounds();
        int w = bounds.width(), h = bounds.height();
        if (w < 2 || h < 2) return lost(host, BubbleGlassTelemetry.Native.TOO_SMALL);
        // Outermost hooked draw of this row on this canvas: at its entry the canvas is at the
        // row's node origin translated by -scroll (View.updateDisplayListIfDirty).
        GlassMaterialDrawable.DrawHost entry = frame;
        while (entry.previous != null && entry.previous.view == host && entry.previous.canvas == canvas)
            entry = entry.previous;
        canvas.getMatrix(current);
        if (!entry.matrix.invert(entryInverse)) return lost(host, BubbleGlassTelemetry.Native.MATRIX);
        Matrix local = new Matrix(current);
        local.preTranslate(bounds.left, bounds.top);
        local.postConcat(entryInverse);
        local.postTranslate(-host.getScrollX(), -host.getScrollY());
        if (!local.rectStaysRect()) return lost(host, BubbleGlassTelemetry.Native.MATRIX);
        hostRect.set(0, 0, w, h);
        local.mapRect(hostRect);
        if (entry == lastEntry) {
            if (bubble == lastDrawable && hostRect.equals(lastRect)) {
                BubbleGlassTelemetry.rejected("painted-twice", host, bubble, hostRect, lastDrawable, lastRect);
                return true; // The glass already covers it; a second native fill would hide it.
            }
            // Heuristic, not identification: a smaller registered drawable inside the bubble drawn
            // by the same row draw is assumed to be a quote and stays native. Logged so device
            // traces can replace it with a structural signal (factory arguments, resource).
            if (lastRect.contains(hostRect) && hostRect.width() * hostRect.height()
                    < .9f * lastRect.width() * lastRect.height()) {
                BubbleGlassTelemetry.rejected("nested-quote", host, bubble, hostRect, lastDrawable, lastRect);
                return stay(BubbleGlassTelemetry.Native.QUOTE);
            }
        }
        float density = host.getResources().getDisplayMetrics().density;
        Slot slot = slotFor(host, entry);
        if (slot == null) return lost(host, BubbleGlassTelemetry.Native.SLOT_FULL);
        int save = canvas.save();
        boolean drawn = false;
        try {
            slot.prepare(bubble, bounds, texture, source.textureWidth(), source.textureHeight(), spec, density);
            slot.local.set(local);
            if (!rootTransform()) return lost(host, BubbleGlassTelemetry.Native.ROOT);
            slot.align(host, hostGlobal, rootInverse);
            canvas.saveLayer(bounds.left, bounds.top, bounds.right, bounds.bottom, null);
            canvas.translate(bounds.left, bounds.top);
            canvas.drawRenderNode(slot.lens);
            canvas.translate(-bounds.left, -bounds.top);
            canvas.saveLayer(bounds.left, bounds.top, bounds.right, bounds.bottom, dstIn);
            drawNative(bubble, canvas);
            drawn = true;
        } catch (RuntimeException | LinkageError failure) {
            broken = true; // Native for the rest of this conversation; never a transparent row.
            lost(host, BubbleGlassTelemetry.Native.EXCEPTION);
            ConversationBackdropSource.probe("BUBBLE_GLASS_DISABLED", failure.getClass().getSimpleName());
        } finally {
            canvas.restoreToCount(save);
        }
        if (!drawn) return false;
        lastEntry = entry; lastDrawable = bubble; lastRect.set(hostRect);
        if (glassRows.add(host)) notifyChanged(host);
        BubbleGlassTelemetry.glass();
        BubbleGlassTelemetry.hosted(bubble, canvas, host);
        if (!presented) {
            presented = true;
            BubbleGlassTelemetry.alignment("first", host, entry.matrix, local, hostGlobal, slot.applied);
            ConversationBackdropSource.probe("BUBBLE_FIRST_PIXEL_PRESENTED", "revision=" + source.revision());
        }
        return true;
    }

    /**
     * Pre-draw (main thread, before this frame's draw): moves each bubble's wallpaper child to
     * where the row now is. Property updates only; nothing is re-recorded or invalidated.
     */
    public void realign() {
        lastEntry = null; lastDrawable = null;
        BubbleGlassTelemetry.report();
        if (slots.isEmpty() || released || !rootTransform()) return;
        boolean sampled = false;
        for (Map.Entry<View, RowSlots> entry : slots.entrySet()) {
            View host = entry.getKey();
            if (host == null || !host.isAttachedToWindow()) continue;
            RowSlots row = entry.getValue();
            for (int i = 0; i < row.used; i++) {
                Slot slot = row.slots[i];
                if (slot == null || !slot.align(host, hostGlobal, rootInverse) || sampled) continue;
                sampled = true; // One sample per moving frame; the telemetry caps the rate.
                BubbleGlassTelemetry.alignment("moved", host, null, slot.local, hostGlobal, slot.applied);
            }
        }
    }

    /** Whether this row's latest recording painted glass (reply-text contrast follows it). */
    public boolean hasGlass(View row) { return glassRows.contains(row); }

    /** Called (UI thread, inside a row draw) when a row gains or loses glass. */
    private java.util.function.Consumer<View> glassChanged;
    public void onGlassChanged(java.util.function.Consumer<View> listener) { glassChanged = listener; }

    private void notifyChanged(View host) {
        if (glassChanged != null) glassChanged.accept(host);
    }

    /** The side switch left this row native (e.g. a recycled row now holds a sent message). */
    public void nativeRow(View host) {
        if (glassRows.remove(host)) notifyChanged(host);
    }

    /** Native for a known row; reported when that row's previous recording had glass. */
    private boolean lost(View host, BubbleGlassTelemetry.Native reason) {
        if (glassRows.remove(host)) { BubbleGlassTelemetry.lost(reason.name(), host); notifyChanged(host); }
        return stay(reason);
    }

    private static boolean stay(BubbleGlassTelemetry.Native reason) {
        BubbleGlassTelemetry.stayedNative(reason);
        return false;
    }

    /** Source texture replaced or withdrawn (main thread). */
    public void sourceChanged() {
        if (released) return;
        Bitmap texture = source.texture();
        if (texture == null) { reset("texture-withdrawn"); return; }
        for (RowSlots row : slots.values())
            for (Slot slot : row.slots) if (slot != null) slot.retexture(texture, source.textureWidth(), source.textureHeight());
        // Rows that painted natively while the source was preparing re-record once.
        for (View host : new ArrayList<>(waiting)) if (host != null) host.invalidate();
        waiting.clear();
        View window = root.get();
        if (window != null) window.invalidate();
    }

    /** Back to native on every known row, e.g. when the switch is turned off. */
    public void release() {
        released = true;
        reset("release");
    }

    private void reset(String reason) {
        // Every glass bubble of this chat goes native: never silent in a device trace.
        if (!slots.isEmpty()) ConversationBackdropSource.probe("BUBBLE_GLASS_RESET", "reason=" + reason + " rows=" + slots.size());
        for (Map.Entry<View, RowSlots> entry : new ArrayList<>(slots.entrySet())) {
            for (Slot slot : entry.getValue().slots) if (slot != null) slot.discard();
            View host = entry.getKey();
            if (host != null) host.invalidate();
        }
        slots.clear();
        waiting.clear();
        ArrayList<View> wasGlass = new ArrayList<>(glassRows);
        glassRows.clear();
        for (View row : wasGlass) if (row != null) notifyChanged(row);
        lastEntry = null; lastDrawable = null;
    }

    private boolean rootTransform() {
        View window = root.get();
        if (window == null) return false;
        rootGlobal.reset();
        window.transformMatrixToGlobal(rootGlobal);
        return rootGlobal.invert(rootInverse);
    }

    /**
     * The next slot of this row's current recording. A new outermost row draw is a new recording
     * that replaces the row's display list, so numbering restarts and earlier slots are reused.
     */
    private Slot slotFor(View host, Object recording) {
        RowSlots row = slots.get(host);
        if (row == null) { row = new RowSlots(); slots.put(host, row); }
        int index = row.claim(recording);
        if (index < 0) return null;
        Slot slot = row.slots[index];
        return slot != null ? slot : (row.slots[index] = new Slot());
    }

    /** Slots of one row, in the draw order of its latest recording. */
    static final class RowSlots {
        final Slot[] slots = new Slot[MAX_SLOTS_PER_ROW];
        /**
         * Outermost draw scope of the latest recording, compared by identity. Weak: the scope
         * holds the row, which is this map's weak key. It is alive for the whole recording.
         */
        WeakReference<Object> recording = new WeakReference<>(null);
        int used;

        /** Index of the next bubble drawn in {@code recording}, or -1 when the row is full. */
        int claim(Object recording) {
            if (this.recording.get() != recording) { this.recording = new WeakReference<>(recording); used = 0; }
            return used < slots.length ? used++ : -1;
        }
    }

    /** One native bubble on one row: a lens node whose child carries the wallpaper texture. */
    private static final Paint SCALED = new Paint(Paint.FILTER_BITMAP_FLAG);

    private static final class Slot {
        final RenderNode lens = new RenderNode("WaEX-bubble-lens");
        final RenderNode wallpaper = new RenderNode("WaEX-bubble-wallpaper");
        /** Lens-local → host-row coordinates, from the last native recording. */
        final Matrix local = new Matrix();
        private final Matrix window = new Matrix(), inverse = new Matrix();
        private final float[] values = new float[9];
        final float[] applied = new float[4];
        private RuntimeShader shader;
        private Bitmap texture;
        private BubbleSilhouette silhouette;
        private String measuredKey, effectKey;
        private int lensW = -1, lensH = -1;
        private boolean aligned;

        Slot() {
            // Pivot pinned at the origin: the node matrix is then exactly T(translation)·S(scale).
            wallpaper.setPivotX(0f);
            wallpaper.setPivotY(0f);
        }

        void prepare(Drawable bubble, Rect bounds, Bitmap source, int sourceW, int sourceH, GlassSpec spec, float density) {
            int w = bounds.width(), h = bounds.height();
            // HWUI deletes the display lists of a subtree that leaves the frame (RenderNode
            // onRemovedFromTree -> destroyHardwareResources, recursively to child nodes). A row
            // that scrolls away and back re-records itself, but these nodes kept their old
            // size/texture keys: an empty lens left the bubble fully transparent, an empty
            // wallpaper child left only the wash (no blur, no rim). Rebuild whatever was dropped.
            boolean wallpaperDropped = texture == source && !wallpaper.hasDisplayList();
            if (texture != source || wallpaperDropped) retexture(source, sourceW, sourceH);
            boolean lensDropped = lensW >= 0 && !lens.hasDisplayList();
            if (wallpaperDropped || lensDropped) BubbleGlassTelemetry.revived(lensDropped, wallpaperDropped);
            if (w != lensW || h != lensH || lensDropped) {
                lens.setPosition(0, 0, w, h);
                RecordingCanvas content = lens.beginRecording(w, h);
                try { content.drawRenderNode(wallpaper); } finally { lens.endRecording(); }
                lensW = w; lensH = h;
            }
            // Positional slot: another drawable (tail/no tail) can arrive at the same size.
            String measure = System.identityHashCode(bubble) + ":" + w + "x" + h + ":" + System.identityHashCode(bubble.getColorFilter())
                    + ":" + bubble.getAlpha();
            if (!measure.equals(measuredKey)) {
                // The native mask stays the authority for shape and tail; this only places the
                // rim/refraction body. Bounded: strips are at most MAX_MEASURE_PX long.
                BubbleSilhouette measured = null;
                if (w > MAX_MEASURE_PX || h > MAX_MEASURE_PX) {
                    BubbleGlassTelemetry.silhouetteFallback("too-large", bubble, w, h);
                } else {
                    long started = System.nanoTime();
                    try {
                        measured = BubbleSilhouette.measure(bubble, bounds, FALLBACK_RADIUS_DP * density);
                        if (measured == null) BubbleGlassTelemetry.silhouetteFallback("no-opaque-body", bubble, w, h);
                        else BubbleGlassTelemetry.silhouetteMeasured(bubble, w, h, measured, System.nanoTime() - started);
                    } catch (RuntimeException | OutOfMemoryError unsupported) {
                        // e.g. a hardware bitmap cannot be rasterized on a software canvas.
                        BubbleGlassTelemetry.silhouetteFallback("raster-" + unsupported.getClass().getSimpleName(), bubble, w, h);
                    }
                }
                silhouette = measured != null ? measured
                        : new BubbleSilhouette(0, 0, w, h, Math.min(FALLBACK_RADIUS_DP * density, Math.min(w, h) / 2f), 0);
                measuredKey = measure;
            }
            // Same material as the approved composer/header: neutral wash at the opacity slider,
            // or the user's Bubble Colors choice at that same opacity (GlassSpec bubble tint).
            KyantLens.Params params = KyantLens.appSurface(spec, silhouette.height(), density,
                    KyantLens.surfaceAlpha255());
            // No tail: owner review 2026-11-03 ("the little stroke outside the first message").
            // The glass is the rounded body only; the native silhouette still masks it.
            params.fullCover = 0f;
            params.refractHeight = Math.min(BUBBLE_REFRACT_HEIGHT_DP * density, silhouette.height() / 2f);
            params.refractAmount = BUBBLE_REFRACT_AMOUNT_DP * density;
            params.contourGain *= BUBBLE_RIM_GAIN;
            params.highlight *= BUBBLE_HIGHLIGHT_GAIN;
            params.strokeWidth = BUBBLE_STROKE_DP * density;
            String key = w + "x" + h + ":" + silhouette.left + "," + silhouette.top + ","
                    + silhouette.right + "," + silhouette.bottom + ":" + silhouette.radius
                    + ":" + params.key() + ":" + GlassOptics.revision();
            if (!key.equals(effectKey)) {
                if (shader == null) shader = new RuntimeShader(KyantLens.SHADER);
                KyantLens.configure(shader, params, silhouette.width(), silhouette.height(),
                        silhouette.radius, silhouette.left, silhouette.top);
                lens.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"));
                effectKey = key;
            }
        }

        /** Draws the texture over the window area it covers ({@code w}×{@code h}, window px). */
        void retexture(Bitmap source, int w, int h) {
            texture = source;
            if (w <= 0 || h <= 0) { w = source.getWidth(); h = source.getHeight(); }
            wallpaper.setPosition(0, 0, w, h);
            RecordingCanvas content = wallpaper.beginRecording(w, h);
            try {
                // A provisional disk texture is a quarter-resolution copy: scale it up, filtered.
                if (source.getWidth() == w && source.getHeight() == h) content.drawBitmap(source, 0f, 0f, null);
                else content.drawBitmap(source, null, new Rect(0, 0, w, h), SCALED);
            } finally { wallpaper.endRecording(); }
        }

        /** Window texture space → lens-local: the inverse of where this bubble now sits. */
        /** @return true when the wallpaper child moved */
        boolean align(View host, Matrix hostGlobal, Matrix rootInverse) {
            hostGlobal.reset();
            host.transformMatrixToGlobal(hostGlobal);
            window.set(local);
            window.postConcat(hostGlobal);
            window.postConcat(rootInverse);
            if (!window.invert(inverse)) return false;
            inverse.getValues(values);
            // Rows only translate (scroll, item animator) or scale; a skewed/rotated row keeps
            // its last alignment rather than sampling a distorted wallpaper.
            if (values[Matrix.MSKEW_X] != 0f || values[Matrix.MSKEW_Y] != 0f
                    || values[Matrix.MPERSP_0] != 0f || values[Matrix.MPERSP_1] != 0f) return false;
            float sx = values[Matrix.MSCALE_X], sy = values[Matrix.MSCALE_Y];
            float tx = values[Matrix.MTRANS_X], ty = values[Matrix.MTRANS_Y];
            if (aligned && applied[0] == sx && applied[1] == sy && applied[2] == tx && applied[3] == ty) return false;
            wallpaper.setScaleX(sx);
            wallpaper.setScaleY(sy);
            wallpaper.setTranslationX(tx);
            wallpaper.setTranslationY(ty);
            applied[0] = sx; applied[1] = sy; applied[2] = tx; applied[3] = ty;
            aligned = true;
            return true;
        }

        void discard() {
            lens.setRenderEffect(null);
            lens.discardDisplayList();
            wallpaper.discardDisplayList();
            texture = null; lensW = lensH = -1; effectKey = null; aligned = false;
        }
    }
}
