package com.waenhancer.theme;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Bubble glass painted inside WhatsApp's own bubble draw. Contour measurement is pure and runs
 * on synthetic silhouettes; the drawing contracts are checked in source because they need HWUI.
 */
public class NativeBubbleGlassTest {
    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    /** A rounded body with radius r inside [bl,br)×[bt,bb), plus a triangular top-left tail. */
    private static final class Shape implements BubbleSilhouette.Sampler {
        final int w, h, bl, bt, br, bb, fill; final float r; final boolean tail;
        Shape(int w, int h, int bl, int bt, int br, int bb, float r, boolean tail, int fill) {
            this.w = w; this.h = h; this.bl = bl; this.bt = bt; this.br = br; this.bb = bb;
            this.r = r; this.tail = tail; this.fill = fill;
        }
        boolean inside(int x, int y) {
            float px = x + .5f, py = y + .5f;
            if (tail && px < bl + r && py < bt + r) {
                // Tail: the triangle (0,bt)-(bl+r,bt)-(bl+r,bt+r) fills the top-left corner.
                if (px >= 0 && py >= bt && py - bt <= (px) * (r / (bl + r))) return true;
            }
            if (px < bl || px > br || py < bt || py > bb) return false;
            float cx = Math.max(bl + r, Math.min(px, br - r)), cy = Math.max(bt + r, Math.min(py, bb - r));
            float dx = px - cx, dy = py - cy;
            return dx * dx + dy * dy <= r * r;
        }
        int at(int x, int y) { return inside(x, y) ? fill : 0; }
        @Override public int[] row(int y) { int[] o = new int[w]; for (int x = 0; x < w; x++) o[x] = at(x, y); return o; }
        @Override public int[] column(int x) { int[] o = new int[h]; for (int y = 0; y < h; y++) o[y] = at(x, y); return o; }
    }

    @Test public void bodyExcludesTheTailAndRadiusFollowsTheNativeCorner() {
        int fill = 0xFF1F2C34;
        Shape incoming = new Shape(420, 120, 24, 0, 420, 120, 30f, true, fill);
        BubbleSilhouette s = BubbleSilhouette.measure(incoming.w, incoming.h, incoming, 36f);
        assertNotNull(s);
        assertEquals("tail is not part of the optical body", 24, s.left);
        assertEquals(420, s.right);
        assertEquals(0, s.top);
        assertEquals(120, s.bottom);
        assertEquals("measured from the bottom corners", 30f, s.radius, 2.5f);
        assertEquals("native fill colour, carrying Bubble Colors", fill, s.color);
    }

    @Test public void outgoingAndTaillessBubblesMeasureTheirOwnBody() {
        Shape plain = new Shape(300, 90, 0, 0, 300, 90, 18f, false, 0xFF005C4B);
        BubbleSilhouette s = BubbleSilhouette.measure(plain.w, plain.h, plain, 36f);
        assertNotNull(s);
        assertEquals(0, s.left); assertEquals(300, s.right);
        assertEquals(18f, s.radius, 2.5f);
        assertTrue("radius never exceeds half the body", s.radius <= 45f);
    }

    @Test public void transparentDrawableIsNotABubble() {
        Shape empty = new Shape(200, 80, 0, 0, 0, 0, 0f, false, 0);
        assertNull(BubbleSilhouette.measure(empty.w, empty.h, empty, 36f));
        assertNull(BubbleSilhouette.measure(1, 80, empty, 36f));
    }

    @Test public void translucentBubbleColorsStillHaveABody() {
        // Device 2026-11-03: a translucent palette gave SILHOUETTE_FALLBACK no-opaque-body.
        Shape faint = new Shape(200, 80, 0, 0, 200, 80, 10f, false, 0x40FFFFFF);
        BubbleSilhouette s = BubbleSilhouette.measure(faint.w, faint.h, faint, 12f);
        assertNotNull(s);
        assertEquals(0, s.left); assertEquals(200, s.right);
        Shape invisible = new Shape(200, 80, 0, 0, 200, 80, 10f, false, 0x08FFFFFF);
        assertNull("near-zero alpha is still not a body", BubbleSilhouette.measure(invisible.w, invisible.h, invisible, 12f));
    }

    @Test public void maskUsesShapeNotPaletteAlpha() throws IOException {
        String glass = read("src/main/java/com/waenhancer/theme/NativeBubbleGlass.java");
        assertTrue(glass.contains("dstIn.setColorFilter(new android.graphics.ColorMatrixColorFilter(shapeOnly));"));
        assertEquals(8f, NativeBubbleGlass.MASK_ALPHA_GAIN, 0f);
    }

    // --- Contracts that need HWUI: checked in source -------------------------------------

    @Test public void factoryResultIsNeverReplaced() throws IOException {
        String app = read("src/main/java/com/waenhancer/xposed/features/customization/AppLiquidGlass.java");
        int start = app.indexOf("private void installSharedBubbleFactoryHook()");
        String body = app.substring(start, app.indexOf("private void hookPendingBubbleClasses()", start));
        assertTrue(body.contains("params[0]!=int.class"));
        assertFalse("WhatsApp keeps its own drawable type", body.contains("setResult("));
        assertTrue(body.contains("bubbleSides.put((Drawable) result"));
        assertFalse(app.contains("BubbleFactoryDrawable"));
        assertFalse(app.contains("BubbleGlassDrawable"));
    }

    @Test public void glassReplacesTheNativeDrawOnlyWhenRecorded() throws IOException {
        String app = read("src/main/java/com/waenhancer/xposed/features/customization/AppLiquidGlass.java");
        int start = app.indexOf("private void drawNativeBubble(");
        String hook = app.substring(start, app.indexOf("\n    }\n", start));
        assertTrue(hook.contains("if (!sharedFactoryWanted(outgoing)) {"));
        assertTrue(hook.contains("if (session.drawBubble(bubble, canvas, outgoing)) param.setResult(null);"));
        assertTrue(hook.indexOf("nativePass()") < hook.indexOf("param.setResult(null)"));
        String glass = read("src/main/java/com/waenhancer/theme/NativeBubbleGlass.java");
        assertTrue("no glass without a texture: native paints", glass.contains(
                "if (texture == null) { waiting.add(host); return lost(host, BubbleGlassTelemetry.Native.NO_TEXTURE); }"));
        assertTrue(glass.contains("if (!drawn) return false;"));
        assertFalse("never touches row backgrounds", glass.contains("setBackground"));
    }

    @Test public void scrollingOnlyMovesTheWallpaperChild() throws IOException {
        String glass = read("src/main/java/com/waenhancer/theme/NativeBubbleGlass.java");
        int start = glass.indexOf("public void realign()");
        String realign = glass.substring(start, glass.indexOf("public void sourceChanged()", start));
        assertFalse("no row is re-recorded while scrolling", realign.contains("invalidate"));
        assertFalse(realign.contains("beginRecording"));
        int align = glass.indexOf("boolean align(View host");
        String body = glass.substring(align, glass.indexOf("void discard()", align));
        assertTrue(body.contains("wallpaper.setTranslationY(ty);"));
        assertFalse(body.contains("invalidate"));
        String app = read("src/main/java/com/waenhancer/xposed/features/customization/AppLiquidGlass.java");
        int pre = app.indexOf("realignSharedBubbles();");
        assertTrue(pre > 0 && app.indexOf("if (bindings.isEmpty()) return true;", pre) > pre);
    }

    @Test public void settingsChangesReRecordObservedRows() throws IOException {
        String app = read("src/main/java/com/waenhancer/xposed/features/customization/AppLiquidGlass.java");
        int start = app.indexOf("private void syncSharedBubbles()");
        String sync = app.substring(start, app.indexOf("void wallpaperChanged(View target)", start));
        assertTrue(sync.contains("String settings=bubbleScopeCache.name() + \":\""));
        assertTrue(sync.contains("NativeBubbleGlass.settingsKey(material)"));
        assertTrue(sync.contains("if (bubbleSettings!=null && !bubbleSettings.equals(settings)) invalidateBubbleRows(window);"));
        String glass = read("src/main/java/com/waenhancer/theme/NativeBubbleGlass.java");
        int key = glass.indexOf("public static String settingsKey(");
        String body = glass.substring(key, glass.indexOf("public static boolean supported()", key));
        assertTrue(body.contains("surfaceAlpha255()") && body.contains("rimIntensity()")
                && body.contains("GlassOptics.revision()") && body.contains("spec.hashCode()"));
    }

    // --- Review guidelines (device evidence instead of guesses) -------------------------

    @Test public void unhostedDrawsLogTheRealRecordingViewEvidence() throws IOException {
        String t = read("src/main/java/com/waenhancer/theme/BubbleGlassTelemetry.java");
        int start = t.indexOf("static void unhosted(");
        String body = t.substring(start, t.indexOf("static void hosted(", start));
        for (String field : new String[]{"drawable=", "canvas=", "canvasSize=", "frameView=", "frameCanvas=",
                "chain=", "sizeMatches="}) assertTrue(field, body.contains(field));
        String glass = read("src/main/java/com/waenhancer/theme/NativeBubbleGlass.java");
        assertTrue("host rule unchanged: only a verified row canvas",
                glass.contains("return frame != null && frame.canvas == canvas ? frame.view : null;"));
    }

    @Test public void alignmentInputsAreLoggedAtFirstPresentationAndAfterMovement() throws IOException {
        String glass = read("src/main/java/com/waenhancer/theme/NativeBubbleGlass.java");
        assertTrue(glass.contains("BubbleGlassTelemetry.alignment(\"first\", host, entry.matrix, local, hostGlobal, slot.applied);"));
        assertTrue(glass.contains("BubbleGlassTelemetry.alignment(\"moved\", host, null, slot.local, hostGlobal, slot.applied);"));
        String t = read("src/main/java/com/waenhancer/theme/BubbleGlassTelemetry.java");
        assertTrue(t.contains("\" scroll=\"") && t.contains("\" hostGlobal=\"") && t.contains("\" entry=\""));
    }

    @Test public void quoteRuleIsLoggedNotTrusted() throws IOException {
        String glass = read("src/main/java/com/waenhancer/theme/NativeBubbleGlass.java");
        int rule = glass.indexOf("BubbleGlassTelemetry.rejected(\"nested-quote\"");
        assertTrue(rule > 0 && glass.indexOf("Native.QUOTE", rule) > rule);
        assertTrue(glass.contains("BubbleGlassTelemetry.rejected(\"painted-twice\""));
        String t = read("src/main/java/com/waenhancer/theme/BubbleGlassTelemetry.java");
        assertTrue(t.contains("areaRatio=") && t.contains("previousArgs=") && t.contains("sameInstance="));
        String app = read("src/main/java/com/waenhancer/xposed/features/customization/AppLiquidGlass.java");
        assertTrue("factory primitives recorded as a candidate structural signal",
                app.contains("com.waenhancer.theme.BubbleGlassTelemetry.factory((Drawable) result, param.args);"));
    }

    @Test public void silhouetteFallbackIsExplicitAndBounded() throws IOException {
        assertEquals(4096, NativeBubbleGlass.MAX_MEASURE_PX);
        assertEquals(12f, NativeBubbleGlass.FALLBACK_RADIUS_DP, 0f);
        String glass = read("src/main/java/com/waenhancer/theme/NativeBubbleGlass.java");
        assertTrue(glass.contains("silhouetteFallback(\"too-large\""));
        assertTrue(glass.contains("silhouetteFallback(\"no-opaque-body\""));
        assertTrue(glass.contains("silhouetteFallback(\"raster-\""));
        assertTrue("native mask stays the shape authority",
                glass.contains("canvas.saveLayer(bounds.left, bounds.top, bounds.right, bounds.bottom, dstIn);"));
    }

    @Test public void bubblesUseTheComposerMaterialWithAGentleLens() throws IOException {
        String glass = read("src/main/java/com/waenhancer/theme/NativeBubbleGlass.java");
        int prep = glass.indexOf("void prepare(Drawable bubble");
        String body = glass.substring(prep, glass.indexOf("void retexture(", prep));
        assertTrue(body.contains("KyantLens.appSurface(spec, silhouette.height(), density,"));
        assertFalse("no forced 50% native-colour veil", body.contains("VEIL_MIN") || body.contains("params.tint ="));
        assertTrue(body.contains("params.refractHeight = Math.min(BUBBLE_REFRACT_HEIGHT_DP * density, silhouette.height() / 2f);"));
        assertEquals(12f, NativeBubbleGlass.BUBBLE_REFRACT_HEIGHT_DP, 0f);
        assertEquals(8f, NativeBubbleGlass.BUBBLE_REFRACT_AMOUNT_DP, 0f);
        String app = read("src/main/java/com/waenhancer/xposed/features/customization/AppLiquidGlass.java");
        assertTrue("Bubble Colors tints the glass at the slider opacity",
                app.contains("return bubbleGlass.draw(bubble, canvas, bubbleDrawMaterial(outgoing, resolvedMaterial()));"));
    }

    // --- Device review 2026-11-03: transparent rows, tail, reply corners ------------------

    @Test public void drawPathNeverReadsPreferences() throws IOException {
        String app = read("src/main/java/com/waenhancer/xposed/features/customization/AppLiquidGlass.java");
        int start = app.indexOf("private boolean sharedFactoryWanted(boolean outgoing)");
        String gate = app.substring(start, app.indexOf("\n    }\n", start));
        assertFalse("no prefs.getAll() per bubble draw", gate.contains("prefs"));
        assertTrue(gate.contains("LiquidGlassSettings.scopeIncludes(bubbleScopeCache, outgoing)"));
        assertTrue("a failed read keeps the last good scope",
                app.contains("if (read != null) bubbleScopeCache = read;"));
        int hook = app.indexOf("private void drawNativeBubble(");
        String body = app.substring(hook, app.indexOf("\n    }\n", hook));
        assertFalse(body.contains("prefs"));
        assertTrue(app.contains("return bubbleGlass.draw(bubble, canvas, bubbleDrawMaterial(outgoing, resolvedMaterial()));"));
    }

    @Test public void scopeDecisionIsPure() {
        com.waenhancer.config.LiquidGlassSettings.BubbleScope both = com.waenhancer.config.LiquidGlassSettings.BubbleScope.BOTH;
        com.waenhancer.config.LiquidGlassSettings.BubbleScope in = com.waenhancer.config.LiquidGlassSettings.BubbleScope.INCOMING;
        assertTrue(com.waenhancer.config.LiquidGlassSettings.scopeIncludes(both, true));
        assertTrue(com.waenhancer.config.LiquidGlassSettings.scopeIncludes(both, false));
        assertTrue(com.waenhancer.config.LiquidGlassSettings.scopeIncludes(in, false));
        assertFalse(com.waenhancer.config.LiquidGlassSettings.scopeIncludes(in, true));
        assertFalse(com.waenhancer.config.LiquidGlassSettings.scopeIncludes(
                com.waenhancer.config.LiquidGlassSettings.BubbleScope.OFF, false));
        assertNull("unreadable prefs keep the cached value", com.waenhancer.config.LiquidGlassSettings.bubbleScopeOrNull(null));
    }

    @Test public void glassHasNoTail() throws IOException {
        String glass = read("src/main/java/com/waenhancer/theme/NativeBubbleGlass.java");
        assertTrue(glass.contains("params.fullCover = 0f;"));
        assertFalse(glass.contains("params.fullCover = 1f;"));
    }

    @Test public void replyCornersAreClippedReversibly() throws IOException {
        String app = read("src/main/java/com/waenhancer/xposed/features/customization/AppLiquidGlass.java");
        assertTrue(app.contains("\"bubble_quoted_message_frame\", \"id\""));
        assertTrue(app.contains("outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);"));
        assertTrue(app.contains("clippedQuotes.put(frame, new Object[]{frame.getOutlineProvider(), frame.getClipToOutline()});"));
        int release = app.indexOf("private void releaseSharedBubbles()");
        assertTrue(app.indexOf("restoreReplyCorners();", release) > release);
    }

    @Test public void fallbackCopyIsSkippedWhenTheReplayBecameReady() throws IOException {
        String f = read("src/main/java/com/waenhancer/theme/WallpaperFallback.java");
        int check = f.indexOf("if (!stillNeeded.getAsBoolean()) {");
        assertTrue(check > 0 && check < f.indexOf("copy(wallpaperRef.get()"));
        assertTrue(f.contains("BubbleGlassTelemetry.source(\"fallback-skipped\", \"replay-ready\");"));
    }

    @Test public void hookCountsEveryCallAndLeavesUnrecognizedDrawsNative() throws IOException {
        String app = read("src/main/java/com/waenhancer/xposed/features/customization/AppLiquidGlass.java");
        int start = app.indexOf("private void drawNativeBubble(");
        String hook = app.substring(start, app.indexOf("\n    }\n", start));
        assertTrue(hook.indexOf("BubbleGlassTelemetry.call();") < hook.indexOf("bubbleSides.get(bubble)"));
        assertTrue(hook.contains("Native.NOT_REGISTERED); return; }"));
        assertEquals("setResult only after a recorded glass draw", 1, hook.split("setResult", -1).length - 1);
        String t = read("src/main/java/com/waenhancer/theme/BubbleGlassTelemetry.java");
        assertTrue(t.contains("log(\"FREQUENCY\""));
    }

    // --- Wallpaper source: what WhatsApp paints, not one bitmap shape ------------------------

    @Test public void sourceRecordsTheWallpaperDrawablesWithoutTheNarrowBitmapGate() throws IOException {
        assertFalse("old bitmap-only gate removed", new java.io.File(
                "src/main/java/com/waenhancer/theme/WallpaperSnapshot.java").exists());
        String app = read("src/main/java/com/waenhancer/xposed/features/customization/AppLiquidGlass.java");
        assertTrue(app.contains("com.waenhancer.theme.WallpaperCapture.plan(wallpaper,window)"));
        String cap = read("src/main/java/com/waenhancer/theme/WallpaperCapture.java");
        // Dimming and overlays are reproduced, not rejected.
        assertTrue(cap.contains("view.getBackground()") && cap.contains("image.getImageMatrix()")
                && cap.contains("view.getForeground()") && cap.contains("canvas.saveLayerAlpha(null, item.alpha)"));
        assertFalse("no isMutable/colour-filter rejection", cap.contains("isMutable()"));
    }

    @Test public void captureNeverRecordsViewsOrConsumers() throws IOException {
        String cap = read("src/main/java/com/waenhancer/theme/WallpaperCapture.java");
        String code = cap.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
        assertFalse("drawables only: no View.draw", code.contains("view.draw(") || code.contains("wallpaper.draw("));
        assertFalse(code.contains("drawRenderNode"));
        assertTrue(code.contains("name.equals(\"android.widget.AbsListView\")"));
        assertTrue(code.contains("name.equals(\"com.waenhancer.theme.GlassPane\")"));
        assertTrue(code.contains("walk.leafOnly = hostsConsumers(wallpaper, 0);"));
        assertTrue(code.contains("if (consumer(view)) return;"));
        assertTrue("animated wallpaper stays native", code.contains("drawable instanceof Animatable"));
        assertTrue("video wallpaper stays native", code.contains("view instanceof SurfaceView || view instanceof TextureView"));
    }

    @Test public void sourceExtendsCoverageAndReportsItsState() throws IOException {
        String src = read("src/main/java/com/waenhancer/theme/ConversationBackdropSource.java");
        assertTrue("wallpaper stopping short of the window still covers every bubble",
                src.contains("new BitmapShader(region.bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP)"));
        assertTrue(src.contains("at.setTranslate(area.left,area.top);"));
        assertTrue("recorded once per revision, not per scan",
                src.indexOf("desiredContent=capture.record();") > src.indexOf("if (!epoch.update("));
        for (String state : new String[]{"\"unsupported\"", "\"preparing\"", "\"ready\"", "\"retry\"", "\"unavailable\""})
            assertTrue(state, src.contains("BubbleGlassTelemetry.source(" + state));
        String t = read("src/main/java/com/waenhancer/theme/BubbleGlassTelemetry.java");
        assertTrue(t.contains(".append(\" source=\").append(sourceState)"));
        assertTrue("replacements are debounced; the first texture is immediate",
                src.contains("private final RenderDebounce debounce=new RenderDebounce(SETTLE_MS);"));
    }

    @Test public void otherSurfacesKeepTheApprovedShader() throws IOException {
        String lens = read("src/main/java/com/waenhancer/theme/KyantLens.java");
        assertTrue(lens.contains("if (cov <= 0.0 && uFullCover < 0.5) return half4(0.0);"));
        assertTrue(lens.contains("shader.setFloatUniform(\"uFullCover\", params.fullCover);"));
        assertTrue("default 0: only bubbles opt in", lens.contains("float fullCover;"));
    }

    @Test public void sourceKeepsTheLastTextureWhileRevalidating() throws IOException {
        String src = read("src/main/java/com/waenhancer/theme/ConversationBackdropSource.java");
        assertTrue(src.contains("public boolean ready() { return texture!=null && !closed; }"));
        assertFalse(src.contains("desired=snapshot; blur=radius; texture=null;"));
        assertTrue(src.contains("if (desired==null && texture!=null) { texture=null; main.post(changed); }"));
    }
}
