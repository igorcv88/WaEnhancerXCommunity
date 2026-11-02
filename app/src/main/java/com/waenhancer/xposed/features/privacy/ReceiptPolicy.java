package com.waenhancer.xposed.features.privacy;

/** Receipt decisions must depend on receipt kind, not on an old hidden-message record. */
public final class ReceiptPolicy {
    private ReceiptPolicy() {}

    public static boolean suppressDispatch(boolean hideDelivered, boolean explicitlyViewed) {
        return hideDelivered && !explicitlyViewed;
    }

    /**
     * Gate for {@code ReadReceipts/sendReceiptForIncomingMessage}. That host method sends the
     * delivery receipt for every incoming message, so cancelling it under Hide Read alone leaves
     * the sender on one tick. Hide Read is enforced where a read receipt is actually produced:
     * the SendReadReceiptJob hook and the protocol node rewrite ("read" becomes a delivery).
     * Upstream gates this method on Hide Delivered only.
     */
    public static boolean suppressIncomingReceipt(boolean hideDelivered, boolean explicitlyViewed) {
        return hideDelivered && !explicitlyViewed;
    }

    /** Delivery-receipt dispatch code in WhatsApp's modern receipt Message dispatch. */
    public static final int DISPATCH_DELIVERY = 419;
    /** Read-receipt dispatch code; only SendReadReceiptJob creates it. */
    public static final int DISPATCH_READ_JOB = 89;

    /**
     * Dev4Mod 015d446: SendReadReceiptJob waits on its ack future with {@code get()} and no
     * timeout, so rewriting a type-89 dispatch to -1 leaves the job thread blocked and can jam
     * WhatsApp's JobManager. Only the delivery dispatch may be rewritten; read receipts are
     * cancelled in the job itself (see {@link #suppressReadJob}).
     */
    public static boolean rewritableDispatch(int code) {
        return code == DISPATCH_DELIVERY;
    }

    /**
     * Cancels the read job when Hide Read applies, in Ghost Mode, or when Hide Delivered applies:
     * a read receipt implies delivery, and the job is no longer suppressed downstream.
     */
    public static boolean suppressReadJob(boolean hideRead, boolean hideDelivered, boolean ghostMode) {
        return hideRead || hideDelivered || ghostMode;
    }

    public static boolean recordHidden(boolean hideRead, boolean hideDelivered, String receiptType) {
        return hideDelivered || (hideRead && "read".equals(receiptType));
    }
}
