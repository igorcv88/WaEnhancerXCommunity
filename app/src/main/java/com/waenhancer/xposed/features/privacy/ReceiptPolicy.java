package com.waenhancer.xposed.features.privacy;

/** Receipt decisions must depend on receipt kind, not on an old hidden-message record. */
public final class ReceiptPolicy {
    private ReceiptPolicy() {}

    public static boolean suppressDispatch(boolean hideDelivered, boolean explicitlyViewed) {
        return hideDelivered && !explicitlyViewed;
    }

    public static boolean suppressRead(boolean hideRead, boolean hideDelivered, boolean explicitlyViewed) {
        return !explicitlyViewed && (hideRead || hideDelivered);
    }

    public static boolean recordHidden(boolean hideRead, boolean hideDelivered, String receiptType) {
        return hideDelivered || (hideRead && "read".equals(receiptType));
    }
}
