package com.waenhancer.theme;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/** Review findings on PR #130 that still apply to the shared wallpaper source. */
public class SharedBubbleReviewTest {
    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
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
