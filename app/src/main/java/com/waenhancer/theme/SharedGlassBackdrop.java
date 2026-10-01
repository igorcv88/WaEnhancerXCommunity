package com.waenhancer.theme;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** One bounded, downsampled recording per activity, shared by drawable surfaces and popup windows. */
public final class SharedGlassBackdrop {
    public static final long FRAME_INTERVAL_MS = 100;
    private static final int MAX_PIXELS = 400_000;
    private static final ThreadLocal<Boolean> CAPTURING = ThreadLocal.withInitial(() -> false);
    private static final Set<View> EXCLUDED = Collections.newSetFromMap(new WeakHashMap<>());
    private final WeakReference<View> root;
    private final int[] position = new int[2];
    private Bitmap bitmap;
    private Canvas recordingCanvas;
    private long lastCapture;
    private boolean pending;
    private boolean failed;
    private boolean released;
    private int screenX;
    private int screenY;
    private int width;
    private int height;
    private int shaderPixels;
    private final Set<Object> frameHolders = Collections.newSetFromMap(new WeakHashMap<>());

    public SharedGlassBackdrop(View root) {
        this.root = new WeakReference<>(root);
    }

    public static void exclude(View view, boolean excluded) {
        if (excluded) EXCLUDED.add(view); else EXCLUDED.remove(view);
    }

    /** Called by draw hooks only during our software recording, never during a real frame. */
    public static boolean shouldSkip(View view) {
        return isCapturing() && EXCLUDED.contains(view);
    }

    public static boolean isCapturing() {
        return Boolean.TRUE.equals(CAPTURING.get());
    }

    /** Shared capture does not make shader fill rate free; excess surfaces keep the lit fallback. */
    public void beginFrame() {
        frameHolders.clear();
        shaderPixels = 0;
    }

    public boolean allowShader(Object holder, int width, int height) {
        if (holder == null || width <= 0 || height <= 0) return false;
        if (frameHolders.contains(holder)) return true;
        long area = (long) width * height;
        if (area <= 0 || frameHolders.size() >= 24 || shaderPixels + area > 3_000_000L) return false;
        shaderPixels += (int) area;
        frameHolders.add(holder);
        return true;
    }

    /** Queue outside onDraw: recursively drawing a decor from a drawable would feed back. */
    public void request() {
        View view = root.get();
        if (view == null || pending || failed || released || isCapturing()
                || !view.isAttachedToWindow()) return;
        if (bitmap != null && SystemClock.uptimeMillis() - lastCapture < FRAME_INTERVAL_MS) return;
        pending = true;
        view.post(() -> {
            pending = false;
            if (!released) capture();
        });
    }

    private void capture() {
        View view = root.get();
        if (view == null || !view.isAttachedToWindow() || isCapturing()) return;
        int w = view.getWidth(), h = view.getHeight();
        if (w < 1 || h < 1) return;
        float scale = Math.min(0.35f, (float) Math.sqrt(MAX_PIXELS / ((double) w * h)));
        int bw = Math.max(1, Math.round(w * scale)), bh = Math.max(1, Math.round(h * scale));
        try {
            if (bitmap == null || bitmap.getWidth() != bw || bitmap.getHeight() != bh) {
                // Old copies may still be referenced by a HW display list; let GC reclaim them.
                bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                recordingCanvas = new Canvas(bitmap);
            }
            bitmap.eraseColor(Color.TRANSPARENT);
            int save = recordingCanvas.save();
            try {
                CAPTURING.set(true);
                recordingCanvas.scale(bw / (float) w, bh / (float) h);
                view.draw(recordingCanvas);
            } finally {
                CAPTURING.set(false);
                recordingCanvas.restoreToCount(save);
            }
            view.getLocationOnScreen(position);
            screenX = position[0]; screenY = position[1];
            width = w; height = h;
            lastCapture = SystemClock.uptimeMillis();
            // One redraw publishes the first snapshot. Further recordings happen only while
            // the host draws again; there is no timer keeping a stationary screen awake.
            view.invalidate();
            for (View consumer : new java.util.ArrayList<>(EXCLUDED)) {
                if (consumer != null && consumer.isAttachedToWindow()) consumer.invalidate();
            }
        } catch (Throwable error) {
            failed = true;
            bitmap = null;
            recordingCanvas = null;
            android.util.Log.w("WaEnhancerX/Backdrop", "Recording unavailable; using material fallback", error);
        }
    }

    public Bitmap bitmap() { return bitmap; }
    public int screenX() { return screenX; }
    public int screenY() { return screenY; }
    public float scaleX() { return bitmap == null ? 1f : width / (float) bitmap.getWidth(); }
    public float scaleY() { return bitmap == null ? 1f : height / (float) bitmap.getHeight(); }

    public void release() {
        released = true;
        bitmap = null;
        recordingCanvas = null;
    }
}
