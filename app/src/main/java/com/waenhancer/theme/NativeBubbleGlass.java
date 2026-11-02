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
    /** Body wash floor: the native colour must stay dense enough for text on any wallpaper. */
    static final float VEIL_MIN = 0.50f;
    /** Fallback corner when the silhouette cannot be measured. */
    static final float FALLBACK_RADIUS_DP = 12f;
    private static final int MAX_SLOTS_PER_ROW = 4;
    private static final Set<Class<?>> UNHOSTED = Collections.synchronizedSet(new java.util.HashSet<>());

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

    /** One probe per drawable class drawn outside a verified row canvas. */
    public static void unhosted(Drawable drawable) {
        if (GlassProbe.enabled() && UNHOSTED.add(drawable.getClass()))
            ConversationBackdropSource.probe("BUBBLE_UNHOSTED", "class=" + drawable.getClass().getName());
    }

    private final WeakReference<View> root;
    private final ConversationBackdropSource source;
    private final WeakHashMap<View, Slot[]> slots = new WeakHashMap<>();
    private final Set<View> waiting = Collections.newSetFromMap(new WeakHashMap<>());
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
        if (broken || released || spec == null || !supported() || !canvas.isHardwareAccelerated()
                || LiveBackdrop.isCapturing()) return false;
        GlassMaterialDrawable.DrawHost frame = GlassMaterialDrawable.currentRowDraw();
        if (frame == null || frame.canvas != canvas) { unhosted(bubble); return false; }
        View host = frame.view;
        Bitmap texture = source.texture();
        if (texture == null) { waiting.add(host); return false; }
        Rect bounds = bubble.getBounds();
        int w = bounds.width(), h = bounds.height();
        if (w < 2 || h < 2) return false;
        // Outermost hooked draw of this row on this canvas: at its entry the canvas is at the
        // row's node origin translated by -scroll (View.updateDisplayListIfDirty).
        GlassMaterialDrawable.DrawHost entry = frame;
        while (entry.previous != null && entry.previous.view == host && entry.previous.canvas == canvas)
            entry = entry.previous;
        canvas.getMatrix(current);
        if (!entry.matrix.invert(entryInverse)) return false;
        Matrix local = new Matrix(current);
        local.preTranslate(bounds.left, bounds.top);
        local.postConcat(entryInverse);
        local.postTranslate(-host.getScrollX(), -host.getScrollY());
        if (!local.rectStaysRect()) return false;
        hostRect.set(0, 0, w, h);
        local.mapRect(hostRect);
        if (entry == lastEntry) {
            if (bubble == lastDrawable && hostRect.equals(lastRect)) return true; // painted twice
            if (lastRect.contains(hostRect) && hostRect.width() * hostRect.height()
                    < .9f * lastRect.width() * lastRect.height()) return false; // nested quote
        }
        float density = host.getResources().getDisplayMetrics().density;
        Slot slot = slotFor(host, bubble);
        if (slot == null) return false;
        int save = canvas.save();
        boolean drawn = false;
        try {
            slot.prepare(bubble, bounds, texture, spec, density);
            slot.local.set(local);
            if (!rootTransform()) return false;
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
            ConversationBackdropSource.probe("BUBBLE_GLASS_DISABLED", failure.getClass().getSimpleName());
        } finally {
            canvas.restoreToCount(save);
        }
        if (!drawn) return false;
        lastEntry = entry; lastDrawable = bubble; lastRect.set(hostRect);
        if (!presented) {
            presented = true;
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
        if (slots.isEmpty() || released || !rootTransform()) return;
        for (Map.Entry<View, Slot[]> entry : slots.entrySet()) {
            View host = entry.getKey();
            if (host == null || !host.isAttachedToWindow()) continue;
            for (Slot slot : entry.getValue()) if (slot != null) slot.align(host, hostGlobal, rootInverse);
        }
    }

    /** Source texture replaced or withdrawn (main thread). */
    public void sourceChanged() {
        if (released) return;
        Bitmap texture = source.texture();
        if (texture == null) { reset(); return; }
        for (Slot[] row : slots.values())
            for (Slot slot : row) if (slot != null) slot.retexture(texture);
        // Rows that painted natively while the source was preparing re-record once.
        for (View host : new ArrayList<>(waiting)) if (host != null) host.invalidate();
        waiting.clear();
        View window = root.get();
        if (window != null) window.invalidate();
    }

    /** Back to native on every known row, e.g. when the switch is turned off. */
    public void release() {
        released = true;
        reset();
    }

    private void reset() {
        for (Map.Entry<View, Slot[]> entry : new ArrayList<>(slots.entrySet())) {
            for (Slot slot : entry.getValue()) if (slot != null) slot.discard();
            View host = entry.getKey();
            if (host != null) host.invalidate();
        }
        slots.clear();
        waiting.clear();
        lastEntry = null; lastDrawable = null;
    }

    private boolean rootTransform() {
        View window = root.get();
        if (window == null) return false;
        rootGlobal.reset();
        window.transformMatrixToGlobal(rootGlobal);
        return rootGlobal.invert(rootInverse);
    }

    private Slot slotFor(View host, Drawable bubble) {
        Slot[] row = slots.get(host);
        if (row == null) { row = new Slot[MAX_SLOTS_PER_ROW]; slots.put(host, row); }
        int free = -1;
        for (int i = 0; i < row.length; i++) {
            Slot slot = row[i];
            if (slot != null && slot.drawable.get() == bubble) return slot;
            if (free < 0 && (slot == null || slot.drawable.get() == null)) free = i;
        }
        if (free < 0) return null;
        if (row[free] != null) row[free].discard();
        return row[free] = new Slot(bubble);
    }

    /** One native bubble on one row: a lens node whose child carries the wallpaper texture. */
    private static final class Slot {
        final WeakReference<Drawable> drawable;
        final RenderNode lens = new RenderNode("WaEX-bubble-lens");
        final RenderNode wallpaper = new RenderNode("WaEX-bubble-wallpaper");
        /** Lens-local → host-row coordinates, from the last native recording. */
        final Matrix local = new Matrix();
        private final Matrix window = new Matrix(), inverse = new Matrix();
        private final float[] values = new float[9], applied = new float[4];
        private RuntimeShader shader;
        private Bitmap texture;
        private BubbleSilhouette silhouette;
        private String measuredKey, effectKey;
        private int lensW = -1, lensH = -1;
        private boolean aligned;

        Slot(Drawable drawable) {
            this.drawable = new WeakReference<>(drawable);
            // Pivot pinned at the origin: the node matrix is then exactly T(translation)·S(scale).
            wallpaper.setPivotX(0f);
            wallpaper.setPivotY(0f);
        }

        void prepare(Drawable bubble, Rect bounds, Bitmap source, GlassSpec spec, float density) {
            int w = bounds.width(), h = bounds.height();
            if (texture != source) retexture(source);
            if (w != lensW || h != lensH) {
                lens.setPosition(0, 0, w, h);
                RecordingCanvas content = lens.beginRecording(w, h);
                try { content.drawRenderNode(wallpaper); } finally { lens.endRecording(); }
                lensW = w; lensH = h;
            }
            String measure = w + "x" + h + ":" + System.identityHashCode(bubble.getColorFilter())
                    + ":" + bubble.getAlpha();
            if (!measure.equals(measuredKey)) {
                BubbleSilhouette measured = null;
                try { measured = BubbleSilhouette.measure(bubble, bounds, FALLBACK_RADIUS_DP * density); }
                catch (RuntimeException | OutOfMemoryError unsupported) { /* e.g. hardware bitmap */ }
                silhouette = measured != null ? measured
                        : new BubbleSilhouette(0, 0, w, h, Math.min(FALLBACK_RADIUS_DP * density, Math.min(w, h) / 2f), 0);
                measuredKey = measure;
            }
            KyantLens.Params params = KyantLens.appSurface(spec, silhouette.height(), density,
                    KyantLens.surfaceAlpha255());
            params.fullCover = 1f;
            int veil = Math.max(KyantLens.surfaceAlpha255(), Math.round(VEIL_MIN * 255f));
            int rgb = silhouette.color != 0 ? silhouette.color : params.tint;
            params.tint = (veil << 24) | (rgb & 0x00FFFFFF);
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

        void retexture(Bitmap source) {
            texture = source;
            wallpaper.setPosition(0, 0, source.getWidth(), source.getHeight());
            RecordingCanvas content = wallpaper.beginRecording(source.getWidth(), source.getHeight());
            try { content.drawBitmap(source, 0f, 0f, null); } finally { wallpaper.endRecording(); }
        }

        /** Window texture space → lens-local: the inverse of where this bubble now sits. */
        void align(View host, Matrix hostGlobal, Matrix rootInverse) {
            hostGlobal.reset();
            host.transformMatrixToGlobal(hostGlobal);
            window.set(local);
            window.postConcat(hostGlobal);
            window.postConcat(rootInverse);
            if (!window.invert(inverse)) return;
            inverse.getValues(values);
            // Rows only translate (scroll, item animator) or scale; a skewed/rotated row keeps
            // its last alignment rather than sampling a distorted wallpaper.
            if (values[Matrix.MSKEW_X] != 0f || values[Matrix.MSKEW_Y] != 0f
                    || values[Matrix.MPERSP_0] != 0f || values[Matrix.MPERSP_1] != 0f) return;
            float sx = values[Matrix.MSCALE_X], sy = values[Matrix.MSCALE_Y];
            float tx = values[Matrix.MTRANS_X], ty = values[Matrix.MTRANS_Y];
            if (aligned && applied[0] == sx && applied[1] == sy && applied[2] == tx && applied[3] == ty) return;
            wallpaper.setScaleX(sx);
            wallpaper.setScaleY(sy);
            wallpaper.setTranslationX(tx);
            wallpaper.setTranslationY(ty);
            applied[0] = sx; applied[1] = sy; applied[2] = tx; applied[3] = ty;
            aligned = true;
        }

        void discard() {
            lens.setRenderEffect(null);
            lens.discardDisplayList();
            wallpaper.discardDisplayList();
            texture = null; lensW = lensH = -1; effectKey = null; aligned = false;
        }
    }
}
