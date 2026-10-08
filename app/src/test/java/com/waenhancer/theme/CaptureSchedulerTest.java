package com.waenhancer.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** When live glass records, and when it asks for frames, in both scheduling modes. */
public class CaptureSchedulerTest {

    private static boolean has(int decision, int flag) {
        return (decision & flag) != 0;
    }

    /** Legacy: every frame while moving, then a 250 ms heartbeat that invalidates itself. */
    @Test
    public void legacyKeepsItsHeartbeat() {
        CaptureScheduler s = new CaptureScheduler();
        s.activity(1000);
        int d = s.decide(1000, false, false);
        assertTrue(has(d, CaptureScheduler.CAPTURE) && has(d, CaptureScheduler.INVALIDATE_NOW));
        d = s.decide(1016, false, false);
        assertTrue(has(d, CaptureScheduler.CAPTURE));
        // At rest: one capture per 250 ms, each asking for the next one.
        d = s.decide(2000, false, false);
        assertTrue(has(d, CaptureScheduler.CAPTURE) && has(d, CaptureScheduler.INVALIDATE_LATER));
        assertEquals(CaptureScheduler.IDLE_INTERVAL_MS, s.delayMs());
        assertEquals(CaptureScheduler.NONE, s.decide(2100, false, false));
    }

    /** Damage-driven: captures frames the window draws anyway and never asks for one itself. */
    @Test
    public void damageDrivenNeverInvalidatesWhileMoving() {
        CaptureScheduler s = new CaptureScheduler();
        for (long t = 1000; t < 1300; t += 8) {
            s.activity(t);
            int d = s.decide(t, true, false);
            assertEquals(CaptureScheduler.CAPTURE, d);
        }
    }

    /**
     * At rest, frames are captured at most every 33 ms; a frame skipped for that leaves exactly
     * one trailing redraw, so the last change is captured and then the screen goes quiet.
     */
    @Test
    public void damageDrivenRestIsBoundedAndQuiet() {
        CaptureScheduler s = new CaptureScheduler();
        assertEquals(CaptureScheduler.CAPTURE, s.decide(5000, true, false));
        int d = s.decide(5010, true, false);
        assertEquals(CaptureScheduler.INVALIDATE_LATER, d);
        assertEquals(23L, s.delayMs());
        // Further frames inside the gap add nothing.
        assertEquals(CaptureScheduler.NONE, s.decide(5020, true, false));
        // The trailing frame captures.
        assertEquals(CaptureScheduler.CAPTURE, s.decide(5033, true, false));
        // No frames, no work: the scheduler is only ever asked from a pre-draw.
    }

    /** A dropped recording is captured at once and redrawn, in both modes. */
    @Test
    public void droppedRecordingRecovers() {
        CaptureScheduler s = new CaptureScheduler();
        s.decide(100, true, false);
        int d = s.decide(105, true, true);
        assertTrue(has(d, CaptureScheduler.CAPTURE) && has(d, CaptureScheduler.INVALIDATE_NOW));
        CaptureScheduler legacy = new CaptureScheduler();
        legacy.decide(100, false, false);
        d = legacy.decide(105, false, true);
        assertTrue(has(d, CaptureScheduler.CAPTURE) && has(d, CaptureScheduler.INVALIDATE_NOW));
    }
}
