package com.waenhancer.xposed.features.general;

import org.junit.Test;
import static org.junit.Assert.*;

/** Uses the same checkpoint/scoped SQL decision invoked by the production callback. */
public class RevokeCallContextTest {
    @Test public void failedSnapshotRetainsDeleteByKeyBarrierUntilCallbackReturns() throws Exception {
        for (Exception failure : new Exception[]{new IllegalStateException("Snapshot text limit"),
                new java.security.GeneralSecurityException("Key unavailable"),
                new java.io.IOException("Storage unavailable")}) {
            RevokeCallContext calls = new RevokeCallContext();
            calls.enter();
            try {
                calls.capture("account-a", 42, "synthetic-key", () -> { throw failure; });
                fail("Failed snapshots must never acknowledge persistence");
            } catch (Exception expected) { assertSame(failure, expected); }
            RevokeSql fallback = calls.fallback("account-a", "DELETE FROM message WHERE key_id=?");
            assertNotNull(fallback);
            assertEquals(42, fallback.row);
            assertTrue(fallback.delete);
            assertTrue(calls.owns("account-a", 42, "synthetic-key"));
            assertFalse(calls.owns("account-a", 43, "synthetic-key"));
            assertFalse(calls.owns("account-a", 42, "different-key"));
            assertFalse(calls.owns("account-b", 42, "synthetic-key"));
            RevokeSql media = calls.fallback("account-a", "DELETE FROM message_media WHERE message_row_id IN (?)");
            assertNotNull(media);
            assertEquals("message_media", media.table);
            assertEquals(42, media.row);
            assertNull(calls.fallback("account-b", "DELETE FROM message WHERE key_id=?"));
            calls.leave();
            assertNull(calls.fallback("account-a", "DELETE FROM message WHERE key_id=?"));
        }
    }
    @Test public void checkpointIsAlreadyArmedInsideSnapshotWork() throws Exception {
        RevokeCallContext calls = new RevokeCallContext();
        calls.enter();
        assertEquals(1234, calls.capture("account-a", 42, "key", () -> {
            assertNotNull(calls.fallback("account-a", "UPDATE message SET message_type=? WHERE key_id=?"));
            return 1234L;
        }));
        calls.leave();
    }
    @Test public void absentAcknowledgementKeepsEmergencyRetentionWithoutSuccess() throws Exception {
        for (Long value : new Long[]{null, 0L, -1L}) {
            RevokeCallContext calls = new RevokeCallContext();
            calls.enter();
            try { calls.capture("a", 42, "key", () -> value); fail(); }
            catch (IllegalStateException expected) { }
            assertNotNull(calls.fallback("a", "DELETE FROM message WHERE key_id=?"));
            calls.leave();
        }
    }
    @Test public void nestedCallbackRestoresOuterIdentityAndExplicitPassIsNative() throws Exception {
        RevokeCallContext calls = new RevokeCallContext();
        calls.enter();
        calls.capture("outer", 42, "outer-key", () -> 1L);
        calls.enter();
        calls.protect("inner", 43, "inner-key", true);
        assertNull(calls.fallback("inner", "DELETE FROM message WHERE key_id=?"));
        calls.leave();
        assertEquals(42, calls.fallback("outer", "DELETE FROM message WHERE key_id=?").row);
        calls.leave();
        assertNull(calls.current());
    }
    @Test public void barrierNeverCrossesThreads() throws Exception {
        RevokeCallContext calls = new RevokeCallContext();
        calls.enter();
        calls.capture("a", 42, "key", () -> 1L);
        java.util.concurrent.atomic.AtomicReference<RevokeSql> other = new java.util.concurrent.atomic.AtomicReference<>();
        Thread thread = new Thread(() -> other.set(calls.fallback("a", "DELETE FROM message WHERE key_id=?")));
        thread.start(); thread.join();
        assertNull(other.get());
        assertNotNull(calls.fallback("a", "DELETE FROM message WHERE key_id=?"));
        calls.leave();
    }
    @Test public void invalidOrMissingCallbackScopeNeverRunsSnapshot() throws Exception {
        RevokeCallContext calls = new RevokeCallContext();
        java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
        try { calls.capture("a", 42, "key", () -> { ran.set(true); return 1L; }); fail(); }
        catch (IllegalStateException expected) { }
        assertFalse(ran.get());
        calls.enter();
        try { calls.capture("a", 0, "key", () -> { ran.set(true); return 1L; }); fail(); }
        catch (IllegalArgumentException expected) { }
        assertFalse(ran.get());
        calls.leave();
    }
}
