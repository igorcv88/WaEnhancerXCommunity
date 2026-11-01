package com.waenhancer.theme;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Device review 2026-11-01: bubbles were applied on open, then destroyed and rebuilt on every
 * scroll or recording start. Content-only signals and same-size moves must request a recapture
 * without releasing the presented recording; identity/size changes still release.
 */
public class BubbleCaptureRetentionTest {
    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    @Test public void softDamageKeepsPresentedPixels() throws IOException {
        String g = read("src/main/java/com/waenhancer/theme/GlassMaterialDrawable.java");
        int start = g.indexOf("public void damageCapture(String reason)");
        assertTrue(start > 0);
        String body = g.substring(start, g.indexOf("private boolean published", start));
        assertTrue(body.contains("captureEpoch.damage()"));
        assertFalse("soft damage must not release the live recording", body.contains("releaseLive"));
    }

    @Test public void onlyResizeReleasesOnBoundsChange() throws IOException {
        String g = read("src/main/java/com/waenhancer/theme/GlassMaterialDrawable.java");
        assertTrue(g.contains("if (sameSize) damageCapture(\"drawable-moved\"); else invalidateCapture(\"drawable-bounds\");"));
        assertTrue(g.contains("damageCapture(\"canvas-offset\")"));
        // A different host row is still a hard reset.
        assertTrue(g.contains("invalidateCapture(\"rowRecycled\")"));
    }

    @Test public void rowContentSignalsDoNotRelease() throws IOException {
        String a = read("src/main/java/com/waenhancer/xposed/features/customization/AppLiquidGlass.java");
        assertTrue(a.contains("binding.glass.damageCapture(\"row-content\")"));
        assertFalse(a.contains("binding.glass.invalidateCapture(\"rowRecycled\")"));
    }

    @Test public void epochSoftDamageStillRequestsCapture() {
        BubbleCaptureEpoch epoch = new BubbleCaptureEpoch();
        epoch.published();
        assertFalse(epoch.pending());
        epoch.damage();
        assertTrue("damage schedules a recapture", epoch.pending());
        epoch.published();
        assertFalse(epoch.pending());
    }
}
