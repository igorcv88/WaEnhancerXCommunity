package com.waenhancer.theme;

import org.junit.Test;
import static org.junit.Assert.*;

public class ResumeTimingTest {
    @Test public void measuresFirstDrawCompleteCaptureAndLiveSeparately() {
        ResumeTiming timing = new ResumeTiming(1000);
        assertEquals(ResumeTiming.FIRST_PRESENTATION, timing.presented(1016, 1, false));
        assertEquals(0, timing.presented(1017, 1, false));
        timing.presented(1032, 2, false);
        assertTrue(timing.captured(1040));
        assertFalse(timing.captured(1041));
        assertEquals(ResumeTiming.FIRST_LIVE, timing.presented(1048, 3, true));
        assertEquals(16, timing.presentationDelay());
        assertEquals(40, timing.captureDelay());
        assertEquals(48, timing.liveDelay());
        assertEquals(2, timing.fallbackFrames());
        assertEquals(0, timing.presented(1064, 4, true));
    }
    @Test public void liveOnFirstFrameHasNoFallbackFrames() {
        ResumeTiming timing = new ResumeTiming(0);
        timing.captured(8);
        assertEquals(ResumeTiming.FIRST_PRESENTATION | ResumeTiming.FIRST_LIVE, timing.presented(16, 1, true));
        assertEquals(0, timing.fallbackFrames());
    }
    @Test public void anotherResumeDoesNotBorrowPreviousCaptureOrTiming() {
        ResumeTiming old = new ResumeTiming(0);
        old.captured(8); old.presented(16, 1, true);
        ResumeTiming next = new ResumeTiming(1000);
        next.presented(1016, 2, false);
        assertEquals(-1, next.captureDelay());
        assertEquals(-1, next.liveDelay());
        assertEquals(1, next.fallbackFrames());
    }
}
