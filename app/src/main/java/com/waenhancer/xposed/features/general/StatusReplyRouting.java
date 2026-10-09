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

    public enum Route { CHAT, STATUS, NONE }

    /**
     * Decide what an outgoing message releases. Only a resolved outgoing message that quotes a
     * status can take the status route, and only for the matching quoted item. Anything else sent
     * to a contact or group releases that chat: the chat release must not depend on the
     * status-identity lookup, which can fail on host builds (PN/LID key form, lookup timing).
     */
    public static Route route(boolean destinationIsStatus, boolean outgoingResolved,
                              boolean quotesStatus, boolean quotedStatusMatches) {
        if (destinationIsStatus) return Route.NONE;
        if (outgoingResolved && quotesStatus) return quotedStatusMatches ? Route.STATUS : Route.NONE;
        return Route.CHAT;
    }

    private static boolean same(String left, String right) {
        return left != null && !left.isEmpty() && left.equals(right);
    }
}
