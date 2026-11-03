package com.waenhancer.theme;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/** Review on #134: wallpaper isolation, content-change revisions, first-failure reason, debounce. */
public class WallpaperSourceReviewTest {
    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    // --- 1. A recycler with a custom or obfuscated name is still a consumer -----------------

    @Test public void customNamedRecyclerSubclassIsAConsumer() {
        assertTrue(WallpaperCapture.consumerSignals(Arrays.asList(
                "com.whatsapp.conversation.MessageList", "androidx.recyclerview.widget.RecyclerView",
                "android.view.ViewGroup"), false, false, null));
    }

    @Test public void obfuscatedRecyclerIsCaughtByScrollBehaviour() {
        // R8 renamed the whole chain: only behaviour identifies it.
        assertTrue(WallpaperCapture.consumerSignals(Arrays.asList("X.AbC", "X.0xY", "android.view.ViewGroup"),
                false, true, null));
        assertTrue(WallpaperCapture.consumerSignals(Arrays.asList("X.AbC", "android.view.ViewGroup"),
                true, false, null));
    }

    @Test public void messageRowsAndComposerAreConsumersEvenWhenNotScrolling() {
        assertTrue(WallpaperCapture.consumerSignals(Collections.singletonList("X.Row"), false, false, "conversation_row_text"));
        assertTrue(WallpaperCapture.consumerSignals(Collections.singletonList("X.F"), false, false, "footer"));
        assertTrue(WallpaperCapture.consumerSignals(Arrays.asList("android.widget.ListView", "android.widget.AbsListView"),
                false, false, null));
        assertTrue(WallpaperCapture.consumerSignals(Collections.singletonList("com.waenhancer.theme.GlassPane"),
                false, false, null));
    }

    @Test public void plainWallpaperViewsAreNotConsumers() {
        assertFalse(WallpaperCapture.consumerSignals(Arrays.asList("android.widget.ImageView"), false, false,
                "conversation_background"));
        assertFalse(WallpaperCapture.consumerSignals(Arrays.asList("android.widget.FrameLayout", "android.view.ViewGroup"),
                false, false, null));
    }

    @Test public void consumerSubtreesAreSkippedAtEveryDepthAndSearchFailsClosed() throws IOException {
        String cap = read("src/main/java/com/waenhancer/theme/WallpaperCapture.java");
        assertTrue(cap.contains("if (hostsConsumers(child, 0)) continue;"));
        assertTrue("too large to inspect counts as consumer", cap.contains("if (++visited[0] > CONSUMER_SEARCH_NODES) return true;"));
        assertTrue("too deep to inspect counts as consumer", cap.contains("if (depth >= CONSUMER_SEARCH_DEPTH) return true;"));
        assertFalse("no name-substring heuristic", cap.contains("getName().contains(\"RecyclerView\")"));
    }

    // --- 2. Same drawable object, different colour -> different revision --------------------

    @Test public void contentChangeWithSameIdentityChangesTheKey() {
        String identity = "1440x3120|[0,0][1440,3120]|android.graphics.drawable.ColorDrawable@1a2b:b[0,0][1440,3120]:a255/255";
        int[] blue = new int[WallpaperCapture.PROBE_W * WallpaperCapture.PROBE_H];
        int[] green = blue.clone();
        Arrays.fill(blue, 0xFF2196F3);
        Arrays.fill(green, 0xFF4CAF50);
        assertNotEquals("setColor() on the same ColorDrawable must re-render the texture",
                WallpaperCapture.withContent(identity, blue), WallpaperCapture.withContent(identity, green));
        assertEquals(WallpaperCapture.withContent(identity, blue), WallpaperCapture.withContent(identity, blue.clone()));
        // Dimming applied to one region only also changes it.
        int[] dimmed = blue.clone(); dimmed[dimmed.length - 1] = 0xFF10476F;
        assertNotEquals(WallpaperCapture.withContent(identity, blue), WallpaperCapture.withContent(identity, dimmed));
        String cap = read0();
        assertTrue(cap.contains("int[] pixels = fingerprint(walk.items, coverage, complete);"));
        assertTrue(cap.contains("String key = withContent(identity, pixels);"));
    }

    private static String read0() {
        try { return read("src/main/java/com/waenhancer/theme/WallpaperCapture.java"); }
        catch (IOException e) { throw new AssertionError(e); }
    }

    // --- 3. First unsupported capture reports its reason -------------------------------------

    @Test public void firstUnsupportedCaptureRecordsItsReason() throws IOException {
        // Executes the telemetry state path (logging is unavailable on the host JVM and is skipped).
        BubbleGlassTelemetry.source("unsupported", "animated-AnimatedImageDrawable");
        assertEquals("unsupported(animated-AnimatedImageDrawable)", BubbleGlassTelemetry.sourceState());
        String src = read("src/main/java/com/waenhancer/theme/ConversationBackdropSource.java");
        int report = src.indexOf("if (!usable) BubbleGlassTelemetry.source(\"unsupported\"");
        int epoch = src.indexOf("if (!epoch.update(usable ?");
        assertTrue("reported before the revision check that returns early for a null first key",
                report > 0 && epoch > report);
        // The early return is real: a null key is not a change for a fresh epoch.
        assertFalse(new BackdropEpoch().update(null));
    }

    // --- 4. One debounce for every render start ----------------------------------------------

    @Test public void firstTextureStartsImmediately() {
        RenderDebounce d = new RenderDebounce(150L);
        d.changed(1000L);
        assertEquals(0L, d.delay(1000L, false));
    }

    @Test public void geometryChangesDuringAnActiveRenderWaitForQuiet() {
        RenderDebounce d = new RenderDebounce(150L);
        d.changed(0L);    // render A starts later on the worker
        d.changed(50L);   // keyboard animation frames while A is running
        d.changed(100L);
        // A finishes stale at t=120: the follow-up must not start before 100 + 150.
        assertEquals(130L, d.delay(120L, true));
        d.changed(200L);  // another frame before that deadline moves it again
        assertEquals(150L, d.delay(200L, true));
        assertEquals(0L, d.delay(350L, true));
    }

    @Test public void everyStartPathGoesThroughTheDebounce() throws IOException {
        String src = read("src/main/java/com/waenhancer/theme/ConversationBackdropSource.java");
        int start = src.indexOf("private void start() {");
        String body = src.substring(start, src.indexOf("WORKER.execute", start));
        assertTrue(body.contains("long settle=debounce.delay(android.os.SystemClock.uptimeMillis(), texture!=null);"));
        assertTrue(body.contains("if (settle>0) { main.removeCallbacks(retryStart); main.postDelayed(retryStart,settle); return; }"));
        assertTrue("stale completion re-enters start(), hence the debounce",
                src.contains("if (token!=epoch.revision()) { start(); return; }"));
        assertFalse("no second, bypassable schedule", src.contains("main.postDelayed(retryStart,SETTLE_MS)"));
    }
}
