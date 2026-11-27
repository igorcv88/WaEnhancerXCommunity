package com.waenhancer.library.core;

/**
 * Who may read or look up a library item through the provider.
 *
 * <p>Scope = WhatsApp package (derived by the provider from the calling UID) + account (the own
 * JID the WhatsApp process states for its active account). A request without an account is
 * refused; an item is served only when both match. Limit, stated plainly: two accounts inside
 * the same WhatsApp package run under the same UID, so the account cannot be authenticated by
 * Android; this check prevents one account's media from being shown or opened while another
 * account is active (mix-ups after a switch, stale caches), not a deliberately lying WhatsApp
 * process, which already has access to everything of that UID.</p>
 */
public final class AccessPolicy {
    private AccessPolicy() { }

    /**
     * @param callerIsModule the module itself (gallery, viewer) may read every item
     * @param callerPackage  WhatsApp package of the caller UID, or {@code null}
     * @param claimedAccount account the caller states it is serving, or {@code null}
     */
    public static boolean mayRead(LibraryItem item, boolean callerIsModule, String callerPackage, String claimedAccount) {
        if (item == null) return false;
        if (callerIsModule) return true;
        return callerPackage != null && claimedAccount != null && !claimedAccount.isEmpty()
                && callerPackage.equals(item.packageName) && claimedAccount.equals(item.ownJid);
    }
}
