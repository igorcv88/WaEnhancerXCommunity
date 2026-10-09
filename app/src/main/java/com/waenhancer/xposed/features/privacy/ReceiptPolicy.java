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

    public static boolean recordHidden(boolean hideRead, boolean hideDelivered, String receiptType) {
        return hideDelivered || (hideRead && "read".equals(receiptType));
    }
}
