package com.waenhancer.theme;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Device probe (2026-11-03, WhatsApp 2.26.38.74): source ready, yet calls=1327 = capture 1212 +
 * not_registered 70 + no_texture 35 + switch_off 10, glass=0. Header/composer panes capture in
 * pre-draw via src.draw(captureCanvas); dirty rows then re-record their OWN RenderNode inside that
 * call, and the real frame reuses it. Skipping on "a capture is running" therefore recorded every
 * bubble natively. Only a draw straight into the capture canvas may be skipped.
 */
public class CaptureCanvasScopeTest {
    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    @Test public void noCaptureMeansNoCaptureCanvas() {
        assertFalse(LiveBackdrop.isCaptureCanvas(null));
    }

    @Test public void everyCaptureRecordsAndRestoresItsCanvas() throws IOException {
        String src = read("src/main/java/com/waenhancer/theme/LiveBackdrop.java");
        int set = src.split("captureCanvas = canvas;", -1).length - 1;
        int restored = src.split("captureCanvas = previousCapture;", -1).length - 1;
        int starts = src.split("capturing = true;", -1).length - 1;
        assertEquals("each capture publishes its canvas", starts, set);
        assertEquals("and restores it in finally", starts, restored);
        assertTrue(src.contains("return capturing && canvas != null && canvas == captureCanvas;"));
    }

    @Test public void bubblePathSkipsOnlyTheCaptureCanvas() throws IOException {
        String app = read("src/main/java/com/waenhancer/xposed/features/customization/AppLiquidGlass.java");
        int start = app.indexOf("private void drawNativeBubble(");
        String hook = app.substring(start, app.indexOf("\n    }\n", start));
        assertFalse("a running capture alone must not leave rows native", hook.contains("GlassPane.isCapturing()"));
        assertTrue(hook.contains("if (GlassPane.isCaptureCanvas(canvas)) {"));
        int rows = app.indexOf("private void observeNativeRowDraw(View row)");
        String rowHook = app.substring(rows, app.indexOf("private boolean sharedFactoryAvailable", rows) > 0
                ? app.indexOf("private boolean sharedFactoryAvailable", rows) : rows + 3000);
        assertTrue("row frames exist for own-node recordings during a capture",
                rowHook.contains("if (GlassPane.isCaptureCanvas((Canvas) param.args[0])"));
        assertFalse(rowHook.contains("if (GlassPane.isCapturing() || !nativeDrawRows.containsKey(host)) return;"));
        String glass = read("src/main/java/com/waenhancer/theme/NativeBubbleGlass.java");
        assertTrue(glass.contains("if (LiveBackdrop.isCaptureCanvas(canvas)) return stay(BubbleGlassTelemetry.Native.CAPTURE);"));
        assertFalse(glass.contains("LiveBackdrop.isCapturing()"));
    }
}
