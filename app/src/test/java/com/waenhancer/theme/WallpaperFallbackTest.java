package com.waenhancer.theme;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Automatic bare-wallpaper copy when the wallpaper cannot be replayed. Geometry and eligibility
 * run here; hiding, PixelCopy and restore need a real window and are checked in source.
 */
public class WallpaperFallbackTest {
    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    @Test public void onlyAVisibleWallpaperIsWorthCopying() {
        assertTrue(WallpaperFallback.eligible("animated-AnimatedImageDrawable"));
        assertTrue(WallpaperFallback.eligible("video-surface"));
        assertTrue(WallpaperFallback.eligible("nothing-drawn"));
        assertTrue(WallpaperFallback.eligible("record-IllegalStateException"));
        assertFalse(WallpaperFallback.eligible("wallpaper-hidden"));
        assertFalse(WallpaperFallback.eligible("no-window"));
        assertFalse(WallpaperFallback.eligible("off-window"));
        assertFalse(WallpaperFallback.eligible(null));
    }

    @Test public void keyboardShrinkReusesTheCopyCoverMapped() {
        // Copied from a 1440x2800 wallpaper; the keyboard leaves 1440x1600.
        int[] r = cover(1440, 2800, 0, 200, 1440, 1800);
        assertTrue("covers the new rectangle", r[0] <= 0 && r[1] <= 200 && r[2] >= 1440 && r[3] >= 1800);
        assertEquals("aspect kept", 1440f / 2800f, (float) (r[2] - r[0]) / (r[3] - r[1]), 0.01f);
        assertEquals("centred", 1000, (r[1] + r[3]) / 2, 2);
    }

    @Test public void identicalGeometryIsTheIdentityMapping() {
        int[] r = cover(1440, 3000, 0, 120, 1440, 3120);
        assertArrayEquals(new int[]{0, 120, 1440, 3120}, r);
    }

    @Test public void widerTargetScalesUpWithoutGaps() {
        int[] r = cover(720, 1500, 0, 0, 1500, 1500); // rotation-like change
        assertTrue(r[0] <= 0 && r[1] <= 0 && r[2] >= 1500 && r[3] >= 1500);
    }

    private static int[] cover(int sw, int sh, int l, int t, int rr, int b) {
        // Rect is an Android stub on the host JVM; exercise the same arithmetic as coverRect.
        float scale = Math.max((float) (rr - l) / sw, (float) (b - t) / sh);
        int w = Math.round(sw * scale), h = Math.round(sh * scale);
        int left = l + ((rr - l) - w) / 2, top = t + ((b - t) - h) / 2;
        return new int[]{left, top, left + w, top + h};
    }

    @Test public void coverArithmeticMatchesProduction() throws IOException {
        String cap = read("src/main/java/com/waenhancer/theme/WallpaperCapture.java");
        assertTrue(cap.contains("float scale = Math.max((float) target.width() / Math.max(1, sourceWidth),"));
        assertTrue(cap.contains("int left = target.left + (target.width() - w) / 2, top = target.top + (target.height() - h) / 2;"));
    }

    @Test public void copyHidesOnlyWhatCoversTheWallpaperAndVerifiesBeforeCopying() throws IOException {
        String f = read("src/main/java/com/waenhancer/theme/WallpaperFallback.java");
        int hide = f.indexOf("hideAbove(wallpaper, window);");
        int verify = f.indexOf("if (!consumersHidden(window, wallpaper, 0, new int[1])) { restore();");
        int copy = f.indexOf("PixelCopy.request(activityWindow, rect, destination");
        assertTrue("hide, then verify every list/row/pane is transparent, then copy",
                hide > 0 && verify > hide && copy > verify);
        assertTrue("copies the committed frame without the covering views",
                f.contains("registerFrameCommitCallback("));
        assertTrue("always restored: success, failure and timeout",
                f.split("restore\\(\\);", -1).length - 1 >= 4);
        assertTrue("undo only our own change", f.contains("if (view.getAlpha() == 0f) view.setAlpha(hiddenAlpha.get(i));"));
        assertTrue("half resolution, memory only", f.contains("rect.width() / 2") && !f.contains("FileOutputStream")
                && !f.contains("compress("));
        assertTrue(f.contains("if (image != null) { image.recycle(); image = null; }"));
    }

    @Test public void onePerWallpaperIdentityWithBoundedAttempts() throws IOException {
        String f = read("src/main/java/com/waenhancer/theme/WallpaperFallback.java");
        assertTrue(f.contains("if (released || image == null || !wallpaperIdentity.equals(identity)) return null;"));
        assertTrue(f.contains("if (wallpaperIdentity.equals(failedIdentity) && attempts >= MAX_ATTEMPTS) return;"));
        assertEquals(2, WallpaperFallback.MAX_ATTEMPTS);
        assertTrue("a not-ready moment does not spend an attempt",
                f.contains("BubbleGlassTelemetry.source(\"fallback-waiting\", \"not-ready\");"));
        for (String state : new String[]{"fallback-scheduled", "fallback-capturing", "fallback-ready", "fallback-failed"})
            assertTrue(state, f.contains("\"" + state + "\""));
    }

    // --- Codex review on #135 ---------------------------------------------------------------

    @Test public void replayFailureKeepsTheCopySelectedForThatWallpaper() throws IOException {
        assertTrue(stickyAfterFailure("wp-A"));
        String app = read("src/main/java/com/waenhancer/xposed/features/customization/AppLiquidGlass.java");
        assertTrue(app.contains("wallpaperFallback.replayFailed(identity);"));
        assertTrue(app.contains("|| (wallpaperFallback!=null && wallpaperFallback.sticky(identity))) {"));
    }

    private static boolean stickyAfterFailure(String id) {
        WallpaperFallback fallback = new WallpaperFallback();
        assertFalse(fallback.sticky(id));
        fallback.replayFailed(id);
        return fallback.sticky(id) && !fallback.sticky("wp-B");
    }

    @Test public void recordingExceptionIsAFailureEligibleForTheCopy() throws IOException {
        String src = read("src/main/java/com/waenhancer/theme/ConversationBackdropSource.java");
        int record = src.indexOf("try { desiredContent=capture.record(); }");
        int flag = src.indexOf("failed=true; // Same as a permanent render failure", record);
        assertTrue(record > 0 && flag > record);
    }

    @Test public void aWallpaperHostingConsumersIsNeverCopied() throws IOException {
        String f = read("src/main/java/com/waenhancer/theme/WallpaperFallback.java");
        int guard = f.indexOf("if (WallpaperCapture.hostsConsumers(wallpaper, 0)) { fail(wallpaperIdentity, \"wallpaper-hosts-consumers\"); return; }");
        assertTrue("checked before anything is hidden or copied", guard > 0 && guard < f.indexOf("hideAbove(wallpaper, window);"));
    }

    @Test public void sessionUsesTheCopyOnlyWhenReplayIsImpossible() throws IOException {
        String app = read("src/main/java/com/waenhancer/xposed/features/customization/AppLiquidGlass.java");
        int sync = app.indexOf("private void syncSharedBubbles()");
        String body = app.substring(sync, app.indexOf("void wallpaperChanged(View target)", sync));
        assertTrue(body.contains("if (unsupported!=null || bubbleSource.failed()\n"));
        assertTrue(body.contains("com.waenhancer.theme.WallpaperCapture.identity(wallpaper)"));
        assertTrue(body.contains("if (copied!=null) plan=copied;"));
        assertTrue(app.contains("if (wallpaperFallback != null) wallpaperFallback.release();"));
        assertTrue(app.contains("session.activityWindow = new WeakReference<>(activity.getWindow());"));
        String src = read("src/main/java/com/waenhancer/theme/ConversationBackdropSource.java");
        assertTrue("a render that failed for good also triggers the copy", src.contains("failed=true;"));
    }
}
