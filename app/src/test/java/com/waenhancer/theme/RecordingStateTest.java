package com.waenhancer.theme;

import org.junit.Test;
import static org.junit.Assert.*;

public class RecordingStateTest {
    @Test public void absentPixelsDoNotRequestImmediateRecovery() {
        for (RecordingState state : RecordingState.values()) {
            assertEquals(state == RecordingState.DROPPED, state.requestsImmediateRecovery(true));
            assertFalse(state.requestsImmediateRecovery(false));
        }
    }
    @Test public void activeSurfaceAlongsideRefusedOrHiddenSurfaceSettlesAtRest() {
        for (RecordingState missing : new RecordingState[]{RecordingState.UNINITIALIZED,
                RecordingState.BUDGET_REFUSED, RecordingState.INTENTIONALLY_RELEASED,
                RecordingState.TEMPORARILY_UNAVAILABLE}) {
            CaptureScheduler scheduler = new CaptureScheduler();
            scheduler.decide(1000, true, false);
            boolean dropped = RecordingState.RECORDED.requestsImmediateRecovery(true)
                    || missing.requestsImmediateRecovery(true);
            int next = scheduler.decide(1016, true, dropped);
            assertEquals(CaptureScheduler.INVALIDATE_LATER, next);
            assertEquals(CaptureScheduler.CAPTURE, scheduler.decide(1033, true, dropped));
            // The trailing frame asks for no more frames: the admitted surface cannot drive a loop.
            assertEquals(CaptureScheduler.NONE, scheduler.decide(1034, true, dropped)
                    & CaptureScheduler.INVALIDATE_NOW);
        }
    }
    @Test public void eligibleUnexpectedDropRecoversImmediately() {
        CaptureScheduler scheduler = new CaptureScheduler();
        scheduler.decide(1000, true, false);
        assertEquals(CaptureScheduler.CAPTURE | CaptureScheduler.INVALIDATE_NOW,
                scheduler.decide(1001, true, RecordingState.DROPPED.requestsImmediateRecovery(true)));
        assertFalse(RecordingState.RECORDED.requestsImmediateRecovery(true));
    }
    @Test public void droppedSurfaceWaitingForBackoffCannotForceOtherSurfacesToRedraw() {
        SurfaceRecovery recovery = new SurfaceRecovery();
        recovery.failed(1000, false);
        assertFalse(RecordingState.DROPPED.requestsImmediateRecovery(recovery.mayCapture(1001)));
        assertTrue(RecordingState.DROPPED.requestsImmediateRecovery(recovery.mayCapture(1016)));
    }
}
