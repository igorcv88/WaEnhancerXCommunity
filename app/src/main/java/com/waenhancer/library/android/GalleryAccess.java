package com.waenhancer.library.android;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single-use, short-lived capabilities that let the hooked WhatsApp process open the module's
 * gallery for exactly one account and conversation.
 *
 * <p>The gallery activity has to be exported so WhatsApp can start it, and an activity cannot
 * reliably learn who started it. Instead of trusting intent extras, WhatsApp first asks the
 * UID-checked provider for a token; the provider records the account and chat, and the activity
 * accepts only a token issued in this process within {@link #TTL_MS}. Package and account come
 * from the provider's view of the caller, not from the intent.</p>
 */
public final class GalleryAccess {
    public static final long TTL_MS = 60_000L;
    private static final int MAX_PENDING = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Grant> PENDING = new LinkedHashMap<>();

    private GalleryAccess() { }

    /** What a token entitles the gallery to show. */
    public static final class Grant {
        public final String packageName;
        public final String ownJid;
        public final String chatJid;
        public final String chatAltJid;
        public final String title;
        /** Optional item to open directly (tap on an inline copy). */
        public final String openUuid;
        final long issuedAt;

        public Grant(String packageName, String ownJid, String chatJid, String chatAltJid, String title,
                     String openUuid, long issuedAt) {
            this.packageName = packageName;
            this.ownJid = ownJid;
            this.chatJid = chatJid;
            this.chatAltJid = chatAltJid;
            this.title = title;
            this.openUuid = openUuid;
            this.issuedAt = issuedAt;
        }
    }

    public static synchronized String issue(Grant grant) {
        prune(grant.issuedAt);
        while (PENDING.size() >= MAX_PENDING) {
            Iterator<String> oldest = PENDING.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        StringBuilder token = new StringBuilder();
        for (byte value : bytes) token.append(String.format(java.util.Locale.ROOT, "%02x", value));
        PENDING.put(token.toString(), grant);
        return token.toString();
    }

    /** Returns and invalidates the grant, or {@code null} for an unknown, used or expired token. */
    public static synchronized Grant redeem(String token, long now) {
        prune(now);
        return token == null ? null : PENDING.remove(token);
    }

    private static void prune(long now) {
        PENDING.values().removeIf(grant -> now - grant.issuedAt > TTL_MS || now < grant.issuedAt);
    }
}
