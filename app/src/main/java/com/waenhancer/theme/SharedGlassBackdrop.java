package com.waenhancer.theme;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RenderNode;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.FrameMetrics;
import android.view.View;
import android.view.Window;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/** One GPU recording per window/frame, shared by all its background drawables. */
public final class SharedGlassBackdrop {
    public static final long FRAME_INTERVAL_MS = 100; // Software fallback only.
    private static boolean captureAvailable = true;
    public static void disableCapture() { captureAvailable = false; }
    private static final int MAX_PIXELS = 400_000;
    private static final ThreadLocal<SharedGlassBackdrop> CAPTURING = new ThreadLocal<>();
    // Registry contains providers, never a process-wide exclusion set.
    private static final WeakHashMap<View, WeakReference<SharedGlassBackdrop>> PROVIDERS = new WeakHashMap<>();
    private final Set<View> excluded = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<View> captureExcluded = Collections.newSetFromMap(new WeakHashMap<>());
    private final WeakReference<View> root;
    private View currentLayer;
    private final GlassRenderPolicy policy = new GlassRenderPolicy();
    private final GlassRenderPolicy.Retry gpuRetry = new GlassRenderPolicy.Retry();
    private final GlassRenderPolicy.Retry softwareRetry = new GlassRenderPolicy.Retry();
    private final int[] position = new int[2];
    private Bitmap bitmap;
    private Canvas recordingCanvas;
    private RenderNode gpu;
    private boolean gpuValid;
    private long lastCapture;
    private long gpuFrame = Long.MIN_VALUE;
    private long motionUntil;
    private long powerChecked;
    private boolean conserving;
    private boolean pending;
    private boolean released;
    private int screenX, screenY, width, height;
    private WeakReference<Window> measuredWindow = new WeakReference<>(null);
    private Window.OnFrameMetricsAvailableListener metricsListener;
    private long metricFrames, missedFrames, drawNanos, gpuNanos;

    public SharedGlassBackdrop(View root) {
        this.root = new WeakReference<>(root);
        if (root != null) PROVIDERS.put(root, new WeakReference<>(this));
    }
    public void exclude(View view, boolean value) {
        if (value) excluded.add(view); else excluded.remove(view);
    }
    public static boolean shouldSkip(View view) {
        SharedGlassBackdrop active = CAPTURING.get();
        return active != null && active.captureExcluded.contains(view);
    }
    public static boolean isCapturing() { return CAPTURING.get() != null; }
    /** Lower windows can draw their already-recorded glass; the window being recorded cannot. */
    public static boolean canDrawCapturedMaterial(SharedGlassBackdrop provider) {
        SharedGlassBackdrop active = CAPTURING.get();
        return active == null || (provider != null && provider != active && active.currentLayer == provider.root.get());
    }
    public void markMotion() { motionUntil = SystemClock.uptimeMillis() + 250; }

    /** Use Choreographer's token, not the number of Session/pre-draw invocations. */
    public void prepareFrame() {
        if (released || isCapturing()) return;
        View view = root.get();
        if (view == null || !view.isAttachedToWindow()) return;
        long token;
        try { token = Choreographer.getInstance().getFrameTimeNanos(); }
        catch (IllegalStateException outsideFrame) { request(); return; }
        long now = SystemClock.uptimeMillis();
        if (now - powerChecked >= 1000) {
            powerChecked = now;
            PowerManager power = (PowerManager) view.getContext().getSystemService(android.content.Context.POWER_SERVICE);
            conserving = power != null && (power.isPowerSaveMode() || (Build.VERSION.SDK_INT >= 29
                    && power.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE));
        }
        GlassRenderPolicy.Tier tier = conserving ? GlassRenderPolicy.Tier.CONSERVING
                : now < motionUntil ? GlassRenderPolicy.Tier.MOTION : GlassRenderPolicy.Tier.NORMAL;
        policy.beginFrame(token, (long) view.getWidth() * view.getHeight(), tier);
        if (gpuFrame == token) return;
        gpuFrame = token;
        capture();
    }
    public boolean allowShader(Object holder, int width, int height, GlassSpec spec, boolean nativeMask) {
        return policy.allow(holder, width, height, spec, nativeMask);
    }
    public void observeWindow(Window window) {
        if (window == null || Build.VERSION.SDK_INT < 24 || metricsListener != null) return;
        measuredWindow = new WeakReference<>(window);
        metricsListener = (w, metrics, drops) -> {
            long deadline = Build.VERSION.SDK_INT >= 31 ? metrics.getMetric(FrameMetrics.DEADLINE) : 0;
            View view = root.get();
            if (deadline <= 0) {
                float refresh = view != null && view.getDisplay() != null ? view.getDisplay().getRefreshRate() : 60;
                deadline = (long) (1_000_000_000d / Math.max(1, refresh));
            }
            long total = metrics.getMetric(FrameMetrics.TOTAL_DURATION);
            policy.observeFrame(total, deadline);
            metricFrames++; if (total > deadline) missedFrames++;
            drawNanos += Math.max(0, metrics.getMetric(FrameMetrics.DRAW_DURATION));
            if (Build.VERSION.SDK_INT >= 31) gpuNanos += Math.max(0, metrics.getMetric(FrameMetrics.GPU_DURATION));
            if (metricFrames % 300 == 0) android.util.Log.d("WaEnhancerX/Backdrop",
                    "frames=" + metricFrames + " missed=" + missedFrames + " drawAvgMs="
                            + drawNanos / (metricFrames * 1_000_000d) + " gpuAvgMs="
                            + gpuNanos / (metricFrames * 1_000_000d) + " constrained=" + policy.overloaded());
        };
        try { window.addOnFrameMetricsAvailableListener(metricsListener, new Handler(Looper.getMainLooper())); }
        catch (RuntimeException unavailable) { metricsListener = null; }
    }
    /** Only schedules bootstrap/fallback; primary GPU capture runs in pre-draw for this frame. */
    public void request() {
        View view = root.get();
        if (!captureAvailable || view == null || pending || released || isCapturing() || !view.isAttachedToWindow()) return;
        if (gpuValid || (bitmap != null && SystemClock.uptimeMillis() - lastCapture < FRAME_INTERVAL_MS)) return;
        if (!gpuRetry.ready(SystemClock.uptimeMillis()) && !softwareRetry.ready(SystemClock.uptimeMillis())) return;
        pending = true;
        view.post(() -> { pending = false; if (!released) { capture(); view.invalidate(); } });
    }
    private void capture() {
        View view = root.get();
        if (!captureAvailable || view == null || !view.isAttachedToWindow() || isCapturing() || released) return;
        int w = view.getWidth(), h = view.getHeight();
        if (w < 1 || h < 1) return;
        view.getLocationOnScreen(position);
        screenX = position[0]; screenY = position[1]; width = w; height = h;
        List<View> layers;
        try { layers = WindowStackBackdrop.layers(view); }
        catch (RuntimeException | LinkageError unavailable) { layers = new ArrayList<>(); layers.add(view); }
        captureExcluded.clear();
        captureExcluded.addAll(excluded);
        // Publish each lower window once for this frame before entering the capture scope.
        // Its lenses can then be rendered into the upper snapshot without referencing that snapshot.
        for (View layer : layers) {
            if (layer == view) break;
            WeakReference<SharedGlassBackdrop> reference = PROVIDERS.get(layer);
            SharedGlassBackdrop provider = reference == null ? null : reference.get();
            if (provider != null && provider != this) provider.prepareFrame();
        }
        long now = SystemClock.uptimeMillis();
        if (Build.VERSION.SDK_INT >= 33 && view.isHardwareAccelerated() && gpuRetry.ready(now)) {
            try {
                if (gpu == null) { gpu = new RenderNode("WA shared glass backdrop"); gpu.setClipToBounds(true); }
                gpu.setPosition(0, 0, w, h);
                Canvas canvas = gpu.beginRecording(w, h);
                CAPTURING.set(this);
                try { drawLayers(canvas, layers); }
                finally { CAPTURING.remove(); gpu.endRecording(); }
                gpuValid = true; gpuRetry.success();
                bitmap = null; recordingCanvas = null; captureExcluded.clear();
                return;
            } catch (RuntimeException | LinkageError | OutOfMemoryError error) {
                gpuValid = false; gpuRetry.failure(now);
                if (gpu != null) gpu.discardDisplayList();
                gpu = null;
                if (gpuRetry.failures() == 1) android.util.Log.w("WaEnhancerX/Backdrop", "GPU capture cooling down", error);
            } finally { CAPTURING.remove(); }
        }
        // Never increase the frequency of whole-tree software draw to match the display.
        if (!softwareRetry.ready(now) || now - lastCapture < FRAME_INTERVAL_MS) return;
        float scale = Math.min(.35f, (float) Math.sqrt(MAX_PIXELS / ((double) w * h)));
        int bw = Math.max(1, Math.round(w * scale)), bh = Math.max(1, Math.round(h * scale));
        try {
            if (bitmap == null || bitmap.getWidth() != bw || bitmap.getHeight() != bh) {
                bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                recordingCanvas = new Canvas(bitmap);
            }
            bitmap.eraseColor(Color.TRANSPARENT);
            int save = recordingCanvas.save();
            CAPTURING.set(this);
            try { recordingCanvas.scale(bw / (float) w, bh / (float) h); drawLayers(recordingCanvas, layers); }
            finally { CAPTURING.remove(); recordingCanvas.restoreToCount(save); }
            lastCapture = now; softwareRetry.success();
        } catch (RuntimeException | LinkageError | OutOfMemoryError error) {
            bitmap = null; recordingCanvas = null; softwareRetry.failure(now);
            if (softwareRetry.failures() == 1) android.util.Log.w("WaEnhancerX/Backdrop", "Software capture cooling down", error);
        } finally { CAPTURING.remove(); captureExcluded.clear(); }
    }
    private void drawLayers(Canvas canvas, List<View> layers) {
        try {
            for (View layer : layers) {
                currentLayer = layer;
                WindowStackBackdrop.drawLayer(canvas, layer, screenX, screenY, width, height);
            }
        } finally { currentLayer = null; }
    }
    public RenderNode gpu() { return gpuValid ? gpu : null; }
    public Bitmap bitmap() { return bitmap; }
    public int screenX() { return screenX; }
    public int screenY() { return screenY; }
    public float scaleX() { return bitmap == null ? 1f : width / (float) bitmap.getWidth(); }
    public float scaleY() { return bitmap == null ? 1f : height / (float) bitmap.getHeight(); }
    public void release() {
        released = true;
        Window window = measuredWindow.get();
        if (window != null && metricsListener != null) window.removeOnFrameMetricsAvailableListener(metricsListener);
        metricsListener = null; excluded.clear(); captureExcluded.clear(); policy.clear();
        View view = root.get(); if (view != null) PROVIDERS.remove(view);
        if (Build.VERSION.SDK_INT >= 29 && gpu != null) gpu.discardDisplayList();
        gpu = null; gpuValid = false; bitmap = null; recordingCanvas = null;
    }
}
