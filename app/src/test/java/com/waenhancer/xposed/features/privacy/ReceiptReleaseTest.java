package com.waenhancer.xposed.features.privacy;

import org.junit.Test;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class ReceiptReleaseTest {
    @Test public void fastWorkerSeesAuthorizationAcrossEveryGate() throws Exception {
        Map<String, Boolean> viewed = new HashMap<>();
        List<String> ids = List.of("one", "two");
        ReceiptRelease.enqueue(ids, id -> viewed.put(id, true), id -> viewed.put(id, false), () -> {
            for (String id : ids) {
                assertTrue(viewed.get(id));
                assertFalse(ReceiptPolicy.suppressDispatch(true, viewed.get(id)));
                assertFalse(ReceiptPolicy.suppressRead(true, true, viewed.get(id)));
            }
        });
        assertEquals(2, viewed.size());
        assertTrue(viewed.values().stream().allMatch(Boolean::booleanValue));
    }
    @Test public void queueFailureRestoresPendingState() {
        Map<String, Boolean> viewed = new HashMap<>();
        try {
            ReceiptRelease.enqueue(List.of("one", "two"), id -> viewed.put(id, true),
                    id -> viewed.put(id, false), () -> { throw new Exception("queue unavailable"); });
            fail("Expected enqueue failure");
        } catch (Exception expected) { assertEquals("queue unavailable", expected.getMessage()); }
        assertEquals(Boolean.FALSE, viewed.get("one"));
        assertEquals(Boolean.FALSE, viewed.get("two"));
    }
    @Test public void authorizationFailureDoesNotRunJob() {
        Map<String, Boolean> viewed = new HashMap<>();
        try {
            ReceiptRelease.enqueue(List.of("one", "two"), id -> {
                if (id.equals("two")) throw new IllegalStateException("database unavailable");
                viewed.put(id, true);
            }, id -> viewed.put(id, false), () -> fail("Job must not run"));
            fail("Expected authorization failure");
        } catch (Exception expected) { assertEquals("database unavailable", expected.getMessage()); }
        assertEquals(Boolean.FALSE, viewed.get("one"));
    }
    @Test public void failureLeavesUnrelatedConversationAlone() {
        Map<String, Boolean> viewed = new HashMap<>();
        viewed.put("other-chat", true);
        try {
            ReceiptRelease.enqueue(List.of("reply-chat"), id -> viewed.put(id, true),
                    id -> viewed.put(id, false), () -> { throw new Exception(); });
        } catch (Exception expected) {}
        assertEquals(Boolean.TRUE, viewed.get("other-chat"));
        assertEquals(Boolean.FALSE, viewed.get("reply-chat"));
    }
    @Test public void failedRollbackDoesNotMaskQueueFailure() {
        try {
            ReceiptRelease.enqueue(List.of("one", "two"), id -> {},
                    id -> { throw new IllegalStateException("rollback"); },
                    () -> { throw new Exception("enqueue"); });
            fail("Expected enqueue failure");
        } catch (Exception expected) {
            assertEquals("enqueue", expected.getMessage());
            assertEquals(2, expected.getSuppressed().length);
        }
    }
}
