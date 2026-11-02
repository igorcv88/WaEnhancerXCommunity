package com.waenhancer.theme;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/** Review findings on PR #130 (shared wallpaper bubbles). */
public class SharedBubbleReviewTest {
    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static float[] translate(float x, float y) {
        return new float[]{1, 0, x, 0, 1, y, 0, 0, 1};
    }

    // --- P1: scroll alignment -------------------------------------------------------------

    @Test public void scrollOffsetIsDetectedWithoutADraw() {
        TextureAlignment a = new TextureAlignment();
        assertFalse("nothing presented yet", a.moved(translate(0, -300)));
        a.recordDrawn(translate(-40, -300));
        assertFalse(a.moved(translate(-40, -300)));
        assertFalse("sub-pixel jitter", a.moved(translate(-40.2f, -300.3f)));
        // RecyclerView offsets the row by 24 px without re-running draw().
        assertTrue(a.moved(translate(-40, -276)));
        a.recordDrawn(translate(-40, -276));
        assertFalse(a.moved(translate(-40, -276)));
        a.reset();
        assertFalse("new texture: the next draw records again", a.moved(translate(0, 0)));
    }

    @Test public void scaleChangeIsAMove() {
        TextureAlignment a = new TextureAlignment();
        a.recordDrawn(translate(0, 0));
        assertTrue(a.moved(new float[]{1.01f, 0, 0, 0, 1, 0, 0, 0, 1}));
    }

    @Test public void preDrawRealignsBeforeTheBindingsEarlyReturn() throws IOException {
        String app = read("src/main/java/com/waenhancer/xposed/features/customization/AppLiquidGlass.java");
        int realign = app.indexOf("realignSharedBubbles();");
        int early = app.indexOf("if (bindings.isEmpty()) return true;", realign - 200);
        assertTrue(realign > 0 && early > realign);
        String drawable = read("src/main/java/com/waenhancer/theme/BubbleGlassDrawable.java");
        assertTrue(drawable.contains("alignment.recordDrawn(values);"));
        int start = drawable.indexOf("public boolean realignIfMoved()");
        String body = drawable.substring(start, drawable.indexOf("private void nativeDraw", start));
        assertTrue(body.contains("invalidateSelf()"));
        assertFalse("realignment must never touch the shared source", body.contains("source.update")
                || body.contains("prepare("));
    }

    // --- P1: factory hook gated by preferences -------------------------------------------

    @Test public void factoryWrapperOnlyWhenGlassWantedForThatSide() throws IOException {
        String app = read("src/main/java/com/waenhancer/xposed/features/customization/AppLiquidGlass.java");
        int start = app.indexOf("private void installSharedBubbleFactoryHook()");
        String body = app.substring(start, app.indexOf("private boolean sharedFactoryWanted", start));
        assertTrue(body.contains("params[0]!=int.class"));
        assertTrue(body.contains("if (!sharedFactoryWanted(outgoing)) return;"));
        assertTrue(body.indexOf("sharedFactoryWanted(outgoing)") < body.indexOf("new com.waenhancer.theme.BubbleFactoryDrawable"));
        assertTrue(app.contains("LiquidGlassSettings.bubbleEnabled(prefs, outgoing)\n                && GlassOptics.current().oneMate"));
    }

    // --- P1: bounded recovery from transient source failures ------------------------------

    @Test public void transientFailuresRetryBoundedThenStop() {
        SourceRetryPolicy p = new SourceRetryPolicy();
        assertTrue(p.mayAttempt(7));
        assertEquals(100L, p.onResult(7, SourceRetryPolicy.Outcome.TRANSIENT));
        assertTrue(p.mayAttempt(7));
        assertEquals(400L, p.onResult(7, SourceRetryPolicy.Outcome.TRANSIENT));
        assertTrue(p.mayAttempt(7));
        assertEquals(-1L, p.onResult(7, SourceRetryPolicy.Outcome.TRANSIENT));
        assertFalse("bounded: no constant retries", p.mayAttempt(7));
        assertTrue("a new revision gets a fresh budget", p.mayAttempt(8));
    }

    @Test public void permanentFailureNeverRetriesSameRevision() {
        SourceRetryPolicy p = new SourceRetryPolicy();
        assertEquals(-1L, p.onResult(3, SourceRetryPolicy.Outcome.PERMANENT));
        assertFalse(p.mayAttempt(3));
        assertTrue(p.exhausted(3));
    }

    @Test public void successResetsAndStaleSchedulesNothing() {
        SourceRetryPolicy p = new SourceRetryPolicy();
        p.onResult(5, SourceRetryPolicy.Outcome.TRANSIENT);
        assertEquals(-1L, p.onResult(5, SourceRetryPolicy.Outcome.READY));
        assertTrue(p.mayAttempt(5));
        assertEquals(-1L, p.onResult(5, SourceRetryPolicy.Outcome.STALE));
        assertTrue(p.mayAttempt(5));
    }

    @Test public void sourceDistinguishesTransientDelivery() throws IOException {
        String src = read("src/main/java/com/waenhancer/theme/ConversationBackdropSource.java");
        assertTrue(src.contains("int sync=renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();"));
        assertTrue(src.contains("if (transientSync(sync)) return Render.transientFailure();"));
        assertTrue(src.contains("if (image==null) return Render.transientFailure();"));
        assertFalse("no permanent one-shot failure marker", src.contains("failedRevision"));
    }

    // --- P2: stale work does not delay the next conversation ------------------------------

    @Test public void staleJobsSkipRenderingAndQueueIsBounded() throws IOException {
        String src = read("src/main/java/com/waenhancer/theme/ConversationBackdropSource.java");
        assertTrue(src.contains("token!=liveToken.get() ? Render.stale() : render(snapshot,radius)"));
        assertTrue(src.contains("liveToken.set(-1)"));
        assertTrue(src.contains("if (PENDING.get()>=MAX_PENDING)"));
        assertTrue(src.contains("PENDING.decrementAndGet();"));
    }
}
