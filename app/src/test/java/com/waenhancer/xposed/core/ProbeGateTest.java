package com.waenhancer.xposed.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class ProbeGateTest {
    @Test
    public void finishedLoaderProbesWithoutWaiting() throws Exception {
        AtomicInteger waits = new AtomicInteger();
        assertTrue(ProbeGate.mayProbe(true, () -> { waits.incrementAndGet(); return false; }));
        assertEquals(0, waits.get());
    }

    /** After a timeout the probe waits for the workers instead of racing their resolvers. */
    @Test
    public void timedOutLoaderProbesOnlyAfterWorkersFinish() throws Exception {
        assertTrue(ProbeGate.mayProbe(false, () -> true));
        assertFalse(ProbeGate.mayProbe(false, () -> false));
    }
}
