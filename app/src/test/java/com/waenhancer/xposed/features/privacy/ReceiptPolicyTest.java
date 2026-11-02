package com.waenhancer.xposed.features.privacy;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReceiptPolicyTest {
    @Test public void hideDeliveredBlocksBeforeReply() {
        assertTrue(ReceiptPolicy.suppressDispatch(true, false));
        assertTrue(ReceiptPolicy.suppressIncomingReceipt(true, false));
    }
    @Test public void replyingReleasesBothEvenInGhostMode() {
        // Ghost mode has already been combined into the effective hide flags.
        assertFalse(ReceiptPolicy.suppressDispatch(true, true));
        assertFalse(ReceiptPolicy.suppressIncomingReceipt(true, true));
    }
    @Test public void hiddenReadDoesNotHideDelivery() {
        // Hide Read alone (forced on by Send Blue Ticks upon Reply) must keep two gray ticks:
        // the incoming-message receipt is the delivery receipt.
        assertFalse(ReceiptPolicy.suppressDispatch(false, false));
        assertFalse(ReceiptPolicy.suppressIncomingReceipt(false, false));
        assertFalse(ReceiptPolicy.recordHidden(true, false, null));
        assertFalse(ReceiptPolicy.recordHidden(true, false, "delivery"));
    }
    @Test public void disablingPrivacyAllowsPendingMessages() {
        assertFalse(ReceiptPolicy.suppressDispatch(false, false));
        assertFalse(ReceiptPolicy.suppressIncomingReceipt(false, false));
    }
    @Test public void recordOnlyActuallyHiddenReceipts() {
        assertTrue(ReceiptPolicy.recordHidden(true, false, "read"));
        assertTrue(ReceiptPolicy.recordHidden(false, true, null));
        assertFalse(ReceiptPolicy.recordHidden(false, false, "read"));
    }
    /** Dev4Mod 015d446: the read job blocks on its ack; its dispatch must never be rewritten. */
    @Test public void onlyDeliveryDispatchIsRewritable() {
        assertTrue(ReceiptPolicy.rewritableDispatch(ReceiptPolicy.DISPATCH_DELIVERY));
        assertFalse(ReceiptPolicy.rewritableDispatch(ReceiptPolicy.DISPATCH_READ_JOB));
        assertFalse(ReceiptPolicy.rewritableDispatch(-1));
    }
    @Test public void readJobIsCancelledWhenAnyApplicableHideIsActive() {
        assertTrue(ReceiptPolicy.suppressReadJob(true, false, false));
        // Hide Delivered alone: a read receipt would imply delivery, cancel the job itself.
        assertTrue(ReceiptPolicy.suppressReadJob(false, true, false));
        assertTrue(ReceiptPolicy.suppressReadJob(false, false, true));
        assertFalse(ReceiptPolicy.suppressReadJob(false, false, false));
    }
}
