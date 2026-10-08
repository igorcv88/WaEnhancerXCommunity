package com.waenhancer.theme;

import org.junit.Test;
import static org.junit.Assert.*;

public class SurfaceRecoveryTest {
    @Test public void staggeredSurfacesRetainLaterRetryAfterEarlierOneCompletes() {
        SurfaceRecovery first = new SurfaceRecovery(), second = new SurfaceRecovery();
        first.failed(0, false); second.failed(8, false);
        assertTrue(first.retryDue(16));
        first.ready();
        assertFalse(second.retryDue(16));
        assertEquals(8, second.retryDelay(16));
        assertTrue(second.retryDue(24));
        second.ready();
        assertEquals(SurfaceRecovery.State.LIVE, second.state());
    }
    @Test public void stopRetainsLifetimeButBlocksCaptureUntilResume() {
        SurfaceRecovery recovery = new SurfaceRecovery();
        for (int cycle = 0; cycle < 10; cycle++) {
            recovery.ready();
            recovery.suspend();
            assertEquals(SurfaceRecovery.State.SUSPENDED, recovery.state());
            assertFalse(recovery.mayCapture(1000));
            recovery.revalidate();
            assertEquals(SurfaceRecovery.State.REVALIDATING, recovery.state());
            assertTrue(recovery.mayCapture(1000));
            assertEquals(cycle + 1, recovery.generation());
        }
    }
    @Test public void transientErrorRecoversInSameBindingWithBackoff() {
        SurfaceRecovery recovery = new SurfaceRecovery();
        recovery.ready(); recovery.failed(100, false);
        assertFalse(recovery.mayCapture(115));
        assertEquals(1, recovery.retryDelay(115));
        assertTrue(recovery.mayCapture(116));
        recovery.ready();
        assertEquals(SurfaceRecovery.State.LIVE, recovery.state());
        assertEquals(0, recovery.failures());
    }
    @Test public void threeTransientFailuresRecoverWithoutAnotherEpoch() {
        SurfaceRecovery recovery = new SurfaceRecovery();
        recovery.failed(0, false);
        assertTrue(recovery.mayCapture(16));
        recovery.failed(16, false);
        assertFalse(recovery.mayCapture(79));
        assertTrue(recovery.mayCapture(80));
        recovery.failed(80, false);
        assertEquals(SurfaceRecovery.State.BACKOFF, recovery.state());
        assertFalse(recovery.mayCapture(1079));
        assertEquals(80, recovery.retryDelay(1000));
        assertTrue(recovery.mayCapture(1080));
        recovery.ready();
        assertEquals(SurfaceRecovery.State.LIVE, recovery.state());
        assertEquals(0, recovery.generation());
        assertEquals(0, recovery.failures());
    }
    @Test public void repeatedTransientFailuresKeepABoundedAutonomousRetryRate() {
        SurfaceRecovery recovery = new SurfaceRecovery();
        long now = 0;
        for (int attempt = 0; attempt < 100; attempt++) {
            assertTrue(recovery.mayCapture(now));
            recovery.failed(now, false);
            long delay = recovery.retryDelay(now);
            assertTrue(delay > 0 && delay <= SurfaceRecovery.MAX_RETRY_MS);
            if (attempt >= 5) assertEquals(SurfaceRecovery.MAX_RETRY_MS, delay);
            assertFalse(recovery.mayCapture(now + delay - 1));
            now += delay;
        }
        assertTrue(recovery.mayCapture(now));
        recovery.ready();
        assertEquals(SurfaceRecovery.State.LIVE, recovery.state());
    }
    @Test public void stopCancelsBackoffAndResumeCanCaptureImmediately() {
        SurfaceRecovery recovery = new SurfaceRecovery();
        recovery.failed(0, false); recovery.failed(16, false); recovery.failed(80, false);
        recovery.suspend();
        assertEquals(0, recovery.retryDelay(100));
        assertFalse(recovery.mayCapture(10000));
        recovery.revalidate();
        assertTrue(recovery.mayCapture(100));
    }
    @Test public void sourceEpochDoesNotAuthorizePreviousLiveState() {
        SurfaceRecovery recovery = new SurfaceRecovery();
        recovery.ready();
        recovery.revalidate();
        assertEquals(SurfaceRecovery.State.REVALIDATING, recovery.state());
        assertNotEquals(SurfaceRecovery.State.LIVE, recovery.state());
        recovery.failed(10, false);
        assertEquals(SurfaceRecovery.State.DEGRADED, recovery.state());
        recovery.revalidate(); recovery.ready();
        assertEquals(2, recovery.generation());
    }
    @Test public void permanentCapabilityFailureDoesNotRetryOnResume() {
        SurfaceRecovery recovery = new SurfaceRecovery();
        recovery.failed(0, true); recovery.suspend(); recovery.revalidate(); recovery.ready();
        assertEquals(SurfaceRecovery.State.DISABLED, recovery.state());
        assertFalse(recovery.mayCapture(1000));
        assertEquals(0, recovery.retryDelay(1000));
    }
    @Test public void destroyedLifetimeCannotResumeOrPublishDelayedCapture() {
        SurfaceRecovery recovery = new SurfaceRecovery();
        recovery.ready(); recovery.release(); recovery.revalidate(); recovery.ready(); recovery.suspend();
        assertEquals(SurfaceRecovery.State.RELEASED, recovery.state());
        assertFalse(recovery.mayCapture(1000));
    }
    @Test public void lateSuccessWhileSuspendedCannotMakeSurfaceLive() {
        SurfaceRecovery recovery = new SurfaceRecovery();
        recovery.suspend(); recovery.ready();
        assertEquals(SurfaceRecovery.State.SUSPENDED, recovery.state());
        recovery.failed(10, false);
        assertEquals(0, recovery.failures());
    }
}
