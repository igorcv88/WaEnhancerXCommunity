package com.waenhancer.theme;

import android.graphics.Canvas;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.util.Log;

/**
 * One live glass recording: what lies behind a rect, captured on the GPU and bent by
 * {@link LiquidLens}. Shared by {@link GlassPane} and {@link GlassMaterialDrawable}.
 *
 * <p>The content is recorded at a quarter of the size into {@code node}; {@code glassNode} redraws
 * it at full size and carries the lens as a {@link RenderEffect}. Nothing is read back to a
 * bitmap, so a capture costs a display list, not a frame of software drawing. What gets recorded
 * is the caller's {@link Painter}; the caller is responsible for never recording anything that
 * draws this backdrop, or HWUI recurses on the RenderThread and the process dies natively.</p>
 */
final class LiveBackdrop {

    private static final String TAG = "WaEnhancerX/LiveBackdrop";

    /** Recording resolution divisor. The lens blurs on its own, so a quarter keeps enough to bend. */
    static final float DOWNSAMPLE = 4f;

    /** Main thread only. True while any backdrop is drawing the host's views into its recording. */
    private static boolean capturing;
    /** Off when the touch-feedback guards could not be installed: recording would crash on ripples. */
    private static boolean captureAllowed = true;

    /** Draws the backdrop in host-local pixels, origin at the host's top-left. */
    interface Painter {
        /** @return false when there was nothing to draw */
        boolean paint(RecordingCanvas canvas);
    }

    private final RenderNode node = new RenderNode("WAEX live backdrop");
    private final RenderNode glassNode = new RenderNode("WAEX live glass");
    private RuntimeShader lens;
    private boolean lensFailed;
    private GlassSpec effectSpec;
    private int effectWidth = -1, effectHeight = -1;
    private float effectRadius = -1f;
    private boolean hasContent;
    private int contentWidth, contentHeight;

    static boolean isCapturing() {
        return capturing;
    }

    static void disableCapture() {
        captureAllowed = false;
    }

    /** Whether this device and process can draw live glass at all right now. */
    boolean available() {
        return captureAllowed && !lensFailed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && LiquidLens.isSupported();
    }

    /** True when the system dropped the recording (an Activity stop drops every display list). */
    boolean needsFreshCapture() {
        return !hasContent || !glassNode.hasDisplayList() || !node.hasDisplayList();
    }

    /**
     * Records the backdrop for a {@code width}×{@code height} host and applies the lens.
     *
     * @return true when a drawable recording was produced
     */
    boolean capture(int width, int height, float radiusPx, float density, GlassSpec spec, Painter painter) {
        if (!available() || spec == null || width <= 0 || height <= 0 || capturing) return false;
        int w = (int) Math.ceil(width / DOWNSAMPLE);
        int h = (int) Math.ceil(height / DOWNSAMPLE);
        node.setPosition(0, 0, w, h);
        RecordingCanvas canvas = node.beginRecording(w, h);
        boolean painted;
        capturing = true;
        try {
            canvas.scale(1f / DOWNSAMPLE, 1f / DOWNSAMPLE);
            canvas.clipRect(0, 0, width, height);
            painted = painter.paint(canvas);
        } finally {
            capturing = false;
            // endRecording must run even if draw() throws, or every later beginRecording throws.
            node.endRecording();
        }
        if (!painted) {
            hasContent = false;
            return false;
        }
        glassNode.setPosition(0, 0, width, height);
        RecordingCanvas glass = glassNode.beginRecording(width, height);
        try {
            glass.scale(width / (float) w, height / (float) h);
            glass.drawRenderNode(node);
        } finally {
            glassNode.endRecording();
        }
        if (!applyLens(width, height, radiusPx, density, spec)) {
            hasContent = false;
            return false;
        }
        contentWidth = width;
        contentHeight = height;
        hasContent = true;
        return true;
    }

    /** Rebuilds the lens only when the material or geometry changed; per-frame effects allocate. */
    private boolean applyLens(int width, int height, float radius, float density, GlassSpec spec) {
        if (lens != null && spec == effectSpec && width == effectWidth && height == effectHeight
                && radius == effectRadius) {
            return true;
        }
        try {
            if (lens == null) lens = LiquidLens.newMaterialShader();
            LiquidLens.updateMaterialUniforms(lens, spec, width, height, radius, density);
            glassNode.setRenderEffect(RenderEffect.createRuntimeShaderEffect(lens, "content"));
        } catch (RuntimeException | LinkageError error) {
            lensFailed = true;
            lens = null;
            glassNode.setRenderEffect(null);
            Log.w(TAG, "lens unavailable; static material from now on", error);
            return false;
        }
        effectSpec = spec;
        effectWidth = width;
        effectHeight = height;
        effectRadius = radius;
        return true;
    }

    /** Draws the live glass if a recording of this size exists; false means paint the fallback. */
    boolean draw(Canvas canvas, int width, int height) {
        if (!(canvas instanceof RecordingCanvas) || !canvas.isHardwareAccelerated()) return false;
        if (needsFreshCapture() || contentWidth != width || contentHeight != height || !available()) {
            return false;
        }
        ((RecordingCanvas) canvas).drawRenderNode(glassNode);
        return true;
    }

    void release() {
        node.discardDisplayList();
        glassNode.discardDisplayList();
        hasContent = false;
    }
}
