package com.waenhancer.theme;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Device report 2026-11-03 (after #137): glass lost on a tap and on some other movements, and a
 * blink when a chat opens. Each case below is a path that left a row native although glass was
 * possible; the device trace confirms which ones occur.
 */
public class BubbleGlassStabilityTest {
    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static final String GLASS = "src/main/java/com/waenhancer/theme/NativeBubbleGlass.java";
    private static final String APP = "src/main/java/com/waenhancer/xposed/features/customization/AppLiquidGlass.java";
    private static final String SOURCE = "src/main/java/com/waenhancer/theme/ConversationBackdropSource.java";

    // --- Slots belong to positions in a row's recording, not to drawable instances --------

    @Test public void slotsRestartWithEachRecording() {
        NativeBubbleGlass.RowSlots row = new NativeBubbleGlass.RowSlots();
        Object first = new Object(), second = new Object();
        assertEquals(0, row.claim(first));
        assertEquals(1, row.claim(first));
        assertEquals("a new recording reuses slot 0", 0, row.claim(second));
        assertEquals(1, row.claim(second));
    }

    @Test public void manyRebindsNeverFillARow() {
        // Previously: one slot per drawable instance, freed only by GC. A row rebound with new or
        // cached factory drawables five times painted natively (SLOT_FULL) until a collection.
        NativeBubbleGlass.RowSlots row = new NativeBubbleGlass.RowSlots();
        for (int recording = 0; recording < 100; recording++) {
            Object scope = new Object();
            assertEquals(0, row.claim(scope));
        }
    }

    @Test public void oneRecordingIsStillBounded() {
        NativeBubbleGlass.RowSlots row = new NativeBubbleGlass.RowSlots();
        Object scope = new Object();
        for (int i = 0; i < NativeBubbleGlass.MAX_SLOTS_PER_ROW; i++) assertEquals(i, row.claim(scope));
        assertEquals(-1, row.claim(scope));
    }

    @Test public void slotsDoNotHoldRowsOrDrawables() throws IOException {
        String glass = read(GLASS);
        assertTrue("the scope holds the row, the map's weak key",
                glass.contains("WeakReference<Object> recording = new WeakReference<>(null);"));
        assertFalse(glass.contains("slot.drawable.get()"));
        assertTrue("another drawable at the same size is measured again",
                glass.contains("String measure = System.identityHashCode(bubble) + \":\""));
        assertTrue(glass.contains("Slot slot = slotFor(host, entry);"));
    }

    // --- A transiently unsupported plan keeps the texture ---------------------------------

    @Test public void transientUnsupportedPlanKeepsGlass() throws IOException {
        String app = read(APP);
        int start = app.indexOf("private void syncSharedBubbles()");
        String sync = app.substring(start, app.indexOf("void wallpaperChanged(View target)", start));
        int keep = sync.indexOf("if (unsupported!=null && bubbleSource.texture()!=null && !bubbleSource.failed()");
        assertTrue(keep > 0);
        assertTrue("only for the wallpaper the texture shows", sync.indexOf("identity.equals(textureIdentity)", keep) > keep);
        assertTrue("before the fallback and the withdrawing update",
                keep < sync.indexOf("wallpaperFallback.request(") && keep < sync.indexOf("bubbleSource.update(plan,"));
        assertTrue(sync.contains("if (plan.unsupportedReason()==null) textureIdentity=identity;"));
    }

    @Test public void withdrawalIsVisibleInTraces() throws IOException {
        String glass = read(GLASS);
        assertTrue(glass.contains("if (texture == null) { reset(\"texture-withdrawn\"); return; }"));
        assertTrue(glass.contains("\"BUBBLE_GLASS_RESET\""));
        assertTrue("a row that had glass and records native is reported",
                glass.contains("if (glassRows.remove(host)) BubbleGlassTelemetry.lost(reason.name(), host);"));
        assertTrue(glass.contains("if (slot == null) return lost(host, BubbleGlassTelemetry.Native.SLOT_FULL);"));
    }

    // --- New rows are hosted from their first draw ----------------------------------------

    @Test public void rowsWithAVerifiedIdAreHostedWithoutWaitingForDiscovery() throws IOException {
        String app = read(APP);
        int start = app.indexOf("private void observeNativeRowDraw(View row)");
        String body = app.substring(start, app.indexOf("afterHookedMethod", start));
        assertTrue(body.contains("rememberRowId(row.getId());"));
        assertTrue(body.contains("if (!knownRowId(host.getId()) || bubbleScopeCache == LiquidGlassSettings.BubbleScope.OFF) return;"));
        assertTrue("captures are still skipped first",
                body.indexOf("GlassPane.isCaptureCanvas") < body.indexOf("knownRowId(host.getId())"));
    }

    // --- Opening a chat with a known wallpaper look shows glass at once -------------------

    @Test public void incompleteFingerprintNeverSharesATexture() {
        // Codex review on #138: a hardware-bitmap wallpaper is skipped by the software probe, so
        // two different wallpapers could fingerprint alike once identities are dropped.
        int[] pixels = new int[WallpaperCapture.PROBE_W * WallpaperCapture.PROBE_H];
        assertTrue(WallpaperCapture.lookKeyAllowed(pixels, true));
        assertFalse(WallpaperCapture.lookKeyAllowed(pixels, false));
        assertFalse(WallpaperCapture.lookKeyAllowed(null, true));
    }

    @Test public void provisionalTextureIsKeyedByLookNotIdentity() throws IOException {
        String source = read(SOURCE);
        assertTrue(source.contains("desiredLook=usable && capture.contentKey!=null ? capture.contentKey+\"|blur=\"+radius : null;"));
        assertTrue(source.contains("if (texture==null && desiredLook!=null && desiredLook.equals(lastLook) && lastTexture!=null) {"));
        assertTrue("the chat's own render still replaces it",
                source.indexOf("texture=lastTexture;") < source.indexOf("start();\n    }\n    public static float blurRadius"));
        String capture = read("src/main/java/com/waenhancer/theme/WallpaperCapture.java");
        assertTrue("no look key when a drawable could not be fingerprinted",
                capture.contains("if (lookKeyAllowed(pixels, complete[0])) plan.contentKey"));
        assertTrue(capture.contains("complete[0] = false;"));
        assertTrue("look = geometry + fingerprint, without drawable identities", capture.contains(
                "plan.contentKey = withContent(window.getWidth() + \"x\" + window.getHeight()"));
    }
}
