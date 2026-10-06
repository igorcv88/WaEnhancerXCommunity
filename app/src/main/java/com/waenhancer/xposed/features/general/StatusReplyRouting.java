package com.waenhancer.xposed.features.general;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/** Release only the resolved quoted status and its matching recipient. */
public final class StatusReplyRouting {
    private StatusReplyRouting() { }

    public static boolean matches(boolean resolvedStatus, String recipientPhone, String recipientLid,
                                  String authorPhone, String authorLid) {
        return resolvedStatus && (same(recipientPhone, authorPhone) || same(recipientLid, authorLid));
    }

    public static <T> List<T> selectQuoted(String quotedId, T resolved,
                                          Function<T, String> messageId,
                                          Predicate<T> incomingStatus) {
        if (quotedId == null || quotedId.isEmpty() || resolved == null
                || !quotedId.equals(messageId.apply(resolved)) || !incomingStatus.test(resolved)) {
            return List.of();
        }
        return List.of(resolved);
    }

    private static boolean same(String left, String right) {
        return left != null && !left.isEmpty() && left.equals(right);
    }
}
