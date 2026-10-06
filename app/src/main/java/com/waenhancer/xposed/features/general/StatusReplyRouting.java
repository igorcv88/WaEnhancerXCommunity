package com.waenhancer.xposed.features.general;

/** A visible status author must match the outgoing reply; missing identities never match. */
public final class StatusReplyRouting {
    private StatusReplyRouting() { }

    public static boolean matches(boolean statusResumed, String recipientPhone, String recipientLid,
                                  String authorPhone, String authorLid) {
        return statusResumed && (same(recipientPhone, authorPhone) || same(recipientLid, authorLid));
    }

    private static boolean same(String left, String right) {
        return left != null && !left.isEmpty() && left.equals(right);
    }
}
