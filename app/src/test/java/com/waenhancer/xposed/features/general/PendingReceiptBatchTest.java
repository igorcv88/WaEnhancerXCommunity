package com.waenhancer.xposed.features.general;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class PendingReceiptBatchTest {
    @Test public void sendsNewestResolvedAndMarksTheRest() {
        List<String> ids = new ArrayList<>();
        List<String> resolved = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            ids.add("m" + i);
            resolved.add(i % 10 == 0 ? null : "msg" + i); // 6 unresolvable
        }
        var batch = PendingReceiptBatch.split(ids, resolved, PendingReceiptBatch.MAX_SEND);
        assertEquals(50, batch.send.size());
        assertEquals("msg59", batch.send.get(batch.send.size() - 1));
        assertEquals("msg5", batch.send.get(0)); // m1..m4 are the oldest resolvable, skipped
        assertEquals(10, batch.markOnly.size());
        assertTrue(batch.markOnly.containsAll(Arrays.asList("m0", "m1", "m2", "m3", "m4", "m10", "m50")));
        assertFalse(batch.markOnly.contains("m59"));
    }

    @Test public void smallBatchSendsEverythingResolvable() {
        var batch = PendingReceiptBatch.split(Arrays.asList("a", "b", "c"), Arrays.asList("A", null, "C"), 50);
        assertEquals(Arrays.asList("A", "C"), batch.send);
        assertEquals(List.of("b"), batch.markOnly);
    }

    @Test public void emptyInput() {
        var batch = PendingReceiptBatch.split(List.of(), List.<String>of(), 50);
        assertTrue(batch.send.isEmpty());
        assertTrue(batch.markOnly.isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void mismatchedSizesRejected() {
        PendingReceiptBatch.split(List.of("a"), List.<String>of(), 50);
    }
}
