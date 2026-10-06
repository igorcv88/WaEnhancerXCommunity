package com.waenhancer.xposed.features.privacy;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReceiptPolicyTest {
    @Test public void hideDeliveredBlocksBeforeReply() {
        assertTrue(ReceiptPolicy.suppressDispatch(true, false));
        assertTrue(ReceiptPolicy.suppressRead(true, true, false));
    }
    @Test public void replyingReleasesBothEvenInGhostMode() {
        // Ghost mode has already been combined into the effective hide flags.
        assertFalse(ReceiptPolicy.suppressDispatch(true, true));
        assertFalse(ReceiptPolicy.suppressRead(true, true, true));
    }
    @Test public void hiddenReadDoesNotHideDelivery() {
        assertFalse(ReceiptPolicy.suppressDispatch(false, false));
        assertTrue(ReceiptPolicy.suppressRead(true, false, false));
        assertFalse(ReceiptPolicy.recordHidden(true, false, null));
        assertFalse(ReceiptPolicy.recordHidden(true, false, "delivery"));
    }
    @Test public void disablingPrivacyAllowsPendingMessages() {
        assertFalse(ReceiptPolicy.suppressDispatch(false, false));
        assertFalse(ReceiptPolicy.suppressRead(false, false, false));
    }
    @Test public void recordOnlyActuallyHiddenReceipts() {
        assertTrue(ReceiptPolicy.recordHidden(true, false, "read"));
        assertTrue(ReceiptPolicy.recordHidden(false, true, null));
        assertFalse(ReceiptPolicy.recordHidden(false, false, "read"));
    }
}
