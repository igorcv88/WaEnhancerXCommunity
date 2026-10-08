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
 * <p>{@code node} records the caller's {@link Painter} as a display list; {@code glassNode} replays
 * it and carries the lens as a {@link RenderEffect}. Nothing is read back to a bitmap. A display
 * list is not a raster: scaling the recording down and back up, as the legacy path does, does not
 * lower the resolution HWUI rasterises at, so it saves no work and filters nothing (LG-07). It
 * only adds a rounding error, {@code W / (4·ceil(W/4)) ≠ 1} (LG-06). The corrected path records
 * 1:1, and when the filtering group is on it records a margin of {@code 3σ} around the surface so
 * the Gaussian has real pixels at the rim instead of clamped ones. The caller is responsible for
 * never recording anything that draws this backdrop, or HWUI recurses on the RenderThread and the
 * process dies natively.</p>
 */
final class LiveBackdrop {

    private static final String TAG = "WaEnhancerX/LiveBackdrop";

    /**
     * Legacy recording divisor. It scales a display list, so it does not reduce resolution or
     * cost (see the class note); the corrected path does not use it.
     */
    static final float DOWNSAMPLE = 4f;
    private static final String TAG_V2 = "WaEnhancerX/LiveGlass";

    /** Main thread only. True while any backdrop is drawing the host's views into its recording. */
    private static boolean capturing;
    /** Off when the touch-feedback guards could not be installed: recording would crash on ripples. */
    private static boolean captureAllowed = true;

    /** Draws the backdrop in host-local pixels, origin at the host's top-left. */
    interface Painter {
        /**
         * @param padding the recording's margin around the host, in px: content that far outside
         *                the host is part of the recording (the blur reads it at the rim)
         * @return false when there was nothing to draw
         */
        boolean paint(RecordingCanvas canvas, int padding);
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
    /** Corrected lens, built when {@link GlassOptics#corrected} is on. */
    private LensEffect corrected;
    /** Which path installed {@code glassNode}'s effect, so a switch rebuilds it. */
    private boolean correctedInstalled;
    private String loggedStatus;
    private long captureGeneration, effectRebuilds;

    /**
     * The geometry of one recording: input size (surface plus margin), node size, and the
     * record and replay scales. Pure, for tests.
     */
    static final class Layout {
        final int inputWidth, inputHeight, nodeWidth, nodeHeight, padding;
        final float recordScale, replayScaleX, replayScaleY;

        Layout(int width, int height, int padding, boolean exact) {
            this.padding = padding;
            inputWidth = width + 2 * padding;
            inputHeight = height + 2 * padding;
            if (exact) {
                nodeWidth = inputWidth;
                nodeHeight = inputHeight;
                recordScale = 1f;
            } else {
                nodeWidth = (int) Math.ceil(inputWidth / DOWNSAMPLE);
                nodeHeight = (int) Math.ceil(inputHeight / DOWNSAMPLE);
                recordScale = 1f / DOWNSAMPLE;
            }
            replayScaleX = inputWidth / (float) nodeWidth;
            replayScaleY = inputHeight / (float) nodeHeight;
        }

        /** Net scale from the surface to its replayed recording; exactly 1 when correct. */
        float netScaleX() {
            return recordScale * replayScaleX;
        }

        float netScaleY() {
            return recordScale * replayScaleY;
        }
    }

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

    /**
     * Whether {@code spec} asks for live optics at all. A fallback spec (power saving, no blur)
     * or one without a lens must take the static material and record nothing.
     */
    static boolean wantsLive(GlassSpec spec) {
        return spec != null && !spec.usingFallback && spec.lensStrength > 0f;
    }

    /** Whether a live recording is ready to draw. */
    boolean hasRecording() {
        return hasContent && glassNode.hasDisplayList() && node.hasDisplayList();
    }

    /** True when a recording existed and the system dropped it (an Activity stop drops them all). */
    boolean wasDropped() {
        return hasContent && (!glassNode.hasDisplayList() || !node.hasDisplayList());
    }

    private boolean needsFreshCapture() {
        return !hasContent || !glassNode.hasDisplayList() || !node.hasDisplayList();
    }

    /**
     * Records the backdrop for a {@code width}×{@code height} host and applies the lens.
     *
     * @return true when a drawable recording was produced
     */
    boolean capture(int width, int height, float radiusPx, float density, GlassSpec spec, Painter painter) {
        if (!wantsLive(spec)) {
            // Power saving and friends: no recording, no shader, and nothing stale left to draw.
            release();
            return false;
        }
        if (!available() || width <= 0 || height <= 0 || capturing) return false;
        // Publish only after both display lists and the effect are complete.
        hasContent = false;
        GlassOptics optics = GlassOptics.current();
        if (optics.corrected && !LensEffect.isBroken()) {
            return captureCorrected(width, height, radiusPx, density, spec, painter, optics);
        }
        if (correctedInstalled) {
            // Back to the legacy lens: make applyLens install it again.
            correctedInstalled = false;
            effectSpec = null;
            contentPadding = 0;
            glassNode.setRenderEffect(null);
        }
        int w = (int) Math.ceil(width / DOWNSAMPLE);
        int h = (int) Math.ceil(height / DOWNSAMPLE);
        node.setPosition(0, 0, w, h);
        RecordingCanvas canvas = node.beginRecording(w, h);
        boolean painted;
        capturing = true;
        try {
            canvas.scale(1f / DOWNSAMPLE, 1f / DOWNSAMPLE);
            canvas.clipRect(0, 0, width, height);
            painted = painter.paint(canvas, 0);
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
        captureGeneration++;
        return true;
    }

    /** Margin around the surface in the current recording, in px. */
    private int contentPadding;

    private boolean captureCorrected(int width, int height, float radiusPx, float density,
                                     GlassSpec spec, Painter painter, GlassOptics optics) {
        int padding = optics.filtering ? LensModel.padding(LensModel.sigmaPx(spec, density)) : 0;
        Layout layout = new Layout(width, height, padding, optics.geometry);
        node.setPosition(0, 0, layout.nodeWidth, layout.nodeHeight);
        RecordingCanvas canvas = node.beginRecording(layout.nodeWidth, layout.nodeHeight);
        boolean painted;
        capturing = true;
        try {
            canvas.scale(layout.recordScale, layout.recordScale);
            canvas.translate(padding, padding);
            canvas.clipRect(-padding, -padding, width + padding, height + padding);
            painted = painter.paint(canvas, padding);
        } finally {
            capturing = false;
            node.endRecording();
        }
        if (!painted) {
            hasContent = false;
            return false;
        }
        glassNode.setPosition(-padding, -padding, width + padding, height + padding);
        RecordingCanvas glass = glassNode.beginRecording(layout.inputWidth, layout.inputHeight);
        try {
            glass.scale(layout.replayScaleX, layout.replayScaleY);
            glass.drawRenderNode(node);
        } finally {
            glassNode.endRecording();
        }
        if (corrected == null) corrected = new LensEffect();
        boolean changed = corrected.update(spec, width, height, radiusPx, density, optics, padding,
                optics.temporal);
        if (corrected.effect() == null) {
            // The corrected program was refused; the next capture takes the legacy path.
            hasContent = false;
            return false;
        }
        if (changed || !correctedInstalled) {
            glassNode.setRenderEffect(corrected.effect());
            effectRebuilds++;
            correctedInstalled = true;
            effectSpec = null;
            String status = corrected.status();
            if (!status.equals(loggedStatus)) {
                loggedStatus = status;
                Log.i(TAG_V2, status);
            }
        }
        contentWidth = width;
        contentHeight = height;
        contentPadding = padding;
        hasContent = true;
        captureGeneration++;
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
            effectRebuilds++;
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

    String status() {
        return "captureAvailable=" + available() + " hasContent=" + hasContent
                + " hasDisplayList=" + (node.hasDisplayList() && glassNode.hasDisplayList())
                + " shaderStatus=" + (lensFailed ? "disabled" : correctedInstalled ? "v2" : "legacy")
                + " captureGeneration=" + captureGeneration + " rebuilds=" + effectRebuilds
                + " materialRevision=" + GlassOptics.revision()
                + " captureSize=" + contentWidth + "x" + contentHeight;
    }

    void release() {
        node.discardDisplayList();
        glassNode.discardDisplayList();
        hasContent = false;
    }
}
