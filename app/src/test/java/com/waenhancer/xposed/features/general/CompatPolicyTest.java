package com.waenhancer.xposed.features.general;

import static org.junit.Assert.*;

import org.junit.Test;

public class CompatPolicyTest {

    enum StoreResult { STORED, STORED_ORPHAN, FAILED_TO_STORE, DIDNT_STORE }
    enum Renamed { A, B, C, D, E }
    enum Short { ONLY }

    static final class Ack {
        static final Ack NONE = new Ack();
        private Ack() {}
    }

    @Test public void statusRevokeResultFollowsReturnType() {
        assertEquals(StoreResult.DIDNT_STORE, RevokeResultPolicy.blockedStatusRevokeResult(StoreResult.class));
        assertEquals(Renamed.D, RevokeResultPolicy.blockedStatusRevokeResult(Renamed.class));
        assertEquals(Short.ONLY, RevokeResultPolicy.blockedStatusRevokeResult(Short.class));
        assertEquals(0, RevokeResultPolicy.blockedStatusRevokeResult(int.class));
        assertEquals(0L, RevokeResultPolicy.blockedStatusRevokeResult(long.class));
        assertEquals(Boolean.FALSE, RevokeResultPolicy.blockedStatusRevokeResult(boolean.class));
        assertNull(RevokeResultPolicy.blockedStatusRevokeResult(void.class));
        assertSame(RevokeResultPolicy.UNSUPPORTED, RevokeResultPolicy.blockedStatusRevokeResult(String.class));
    }

    @Test public void staticSingletonIsFound() {
        assertSame(Ack.NONE, RevokeResultPolicy.staticSingleton(Ack.class));
        assertNull(RevokeResultPolicy.staticSingleton(String.class));
    }

    /** WhatsApp 2.26.42 shape: (int stringRes, int limit, boolean hfm, boolean groups). */
    @Test public void multiSelectionLimitSkipsResourceIdAndClearsBlocks() {
        Class<?>[] types = { int.class, int.class, boolean.class, boolean.class };
        Object[] args = { 0x7f120abc, 5, true, true };
        assertEquals(1, MultiSelectionLimitArgs.apply(types, args, 9999));
        assertArrayEquals(new Object[]{ 0x7f120abc, 9999, false, false }, args);
    }

    /** Older shape: limit first. */
    @Test public void multiSelectionLimitLegacyShape() {
        Class<?>[] types = { int.class, String.class };
        Object[] args = { 5, "x" };
        assertEquals(0, MultiSelectionLimitArgs.apply(types, args, 250));
        assertEquals(250, args[0]);
        assertEquals("x", args[1]);
    }

    @Test public void multiSelectionLimitWithoutPlausibleIntIsUntouched() {
        Class<?>[] types = { int.class, boolean.class };
        Object[] args = { 0x7f010001, true };
        assertEquals(-1, MultiSelectionLimitArgs.apply(types, args, 10));
        assertEquals(true, args[1]);
        assertFalse(MultiSelectionLimitArgs.hasIntParameter(new Class<?>[]{ String.class, boolean.class }));
    }
}
