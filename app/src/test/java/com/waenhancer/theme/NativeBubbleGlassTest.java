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

    @Test public void translucentFillHasNoTintColour() {
        Shape faint = new Shape(200, 80, 0, 0, 200, 80, 10f, false, 0x40FFFFFF);
        assertNull("alpha below the mask threshold is not a body",
                BubbleSilhouette.measure(faint.w, faint.h, faint, 12f));
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
        assertTrue(hook.contains("if (!sharedFactoryWanted(outgoing)) return;"));
        assertTrue(hook.contains("if (session.drawBubble(bubble, canvas)) param.setResult(null);"));
        assertTrue(hook.indexOf("nativePass()") < hook.indexOf("param.setResult(null)"));
        String glass = read("src/main/java/com/waenhancer/theme/NativeBubbleGlass.java");
        assertTrue("no glass without a texture: native paints", glass.contains(
                "if (texture == null) { waiting.add(host); return false; }"));
        assertTrue(glass.contains("if (!drawn) return false;"));
        assertFalse("never touches row backgrounds", glass.contains("setBackground"));
    }

    @Test public void scrollingOnlyMovesTheWallpaperChild() throws IOException {
        String glass = read("src/main/java/com/waenhancer/theme/NativeBubbleGlass.java");
        int start = glass.indexOf("public void realign()");
        String realign = glass.substring(start, glass.indexOf("public void sourceChanged()", start));
        assertFalse("no row is re-recorded while scrolling", realign.contains("invalidate"));
        assertFalse(realign.contains("beginRecording"));
        int align = glass.indexOf("void align(View host");
        String body = glass.substring(align, glass.indexOf("void discard()", align));
        assertTrue(body.contains("wallpaper.setTranslationY(ty);"));
        assertFalse(body.contains("invalidate"));
        String app = read("src/main/java/com/waenhancer/xposed/features/customization/AppLiquidGlass.java");
        int pre = app.indexOf("realignSharedBubbles();");
        assertTrue(pre > 0 && app.indexOf("if (bindings.isEmpty()) return true;", pre) > pre);
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
        assertTrue(src.contains("if (snapshot==null && texture!=null) { texture=null; main.post(changed); }"));
    }
}
