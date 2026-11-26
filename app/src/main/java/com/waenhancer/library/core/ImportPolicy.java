package com.waenhancer.library.core;

/**
 * Decides whether a message's media may be kept in the private library, and on which basis.
 *
 * <p>The library is for the user's own media and for copies the user chooses to keep. Received
 * view-once media is kept only when the owner turned on the separate "Keep received view-once"
 * switch (owner decision 2026-11-26: WhatsApp expires unopened view-once media, which the
 * Unlimited View Once feature leaves unopened); then it is kept automatically, because the media
 * must be copied before WhatsApp drops it. That switch is read by the module itself, never taken
 * from the caller. Other people's status posts are always refused. Any other received media is
 * kept only after an explicit user action.</p>
 */
public final class ImportPolicy {

    /** The user sent this media (their own photo or video). */
    public static final String BASIS_OWN_MEDIA = "own_media";
    /** A conventional (non-ephemeral) received media the user explicitly chose to keep. */
    public static final String BASIS_USER_EXPLICIT = "user_explicit";
    /** Received view-once kept automatically because the owner enabled that switch. */
    public static final String BASIS_AUTO_VIEW_ONCE = "auto_view_once";
    /** Restored from a library backup created by this user. */
    public static final String BASIS_BACKUP_RESTORE = "backup_restore";

    private ImportPolicy() { }

    /** Facts about the source message, all reported by the hooked process. */
    public static final class Facts {
        public final boolean fromMe;
        public final boolean viewOnce;
        public final boolean statusBroadcast;
        public final boolean userInitiated;
        /** The module-side "Keep received view-once" switch; never supplied by the caller. */
        public final boolean keepReceivedViewOnce;

        public Facts(boolean fromMe, boolean viewOnce, boolean statusBroadcast, boolean userInitiated) {
            this(fromMe, viewOnce, statusBroadcast, userInitiated, false);
        }

        public Facts(boolean fromMe, boolean viewOnce, boolean statusBroadcast, boolean userInitiated,
                     boolean keepReceivedViewOnce) {
            this.fromMe = fromMe;
            this.viewOnce = viewOnce;
            this.statusBroadcast = statusBroadcast;
            this.userInitiated = userInitiated;
            this.keepReceivedViewOnce = keepReceivedViewOnce;
        }
    }

    /** Returns the authorization basis, or throws {@link LibraryException.Code#NOT_AUTHORIZED}. */
    public static String authorize(Facts facts) throws LibraryException {
        if (facts == null) {
            throw new LibraryException(LibraryException.Code.NOT_AUTHORIZED, "No request facts");
        }
        if (facts.statusBroadcast && !facts.fromMe) {
            throw new LibraryException(LibraryException.Code.NOT_AUTHORIZED,
                    "Other people's status posts keep WhatsApp's native expiry");
        }
        if (facts.viewOnce && !facts.fromMe) {
            if (facts.keepReceivedViewOnce) return BASIS_AUTO_VIEW_ONCE;
            throw new LibraryException(LibraryException.Code.NOT_AUTHORIZED,
                    "Keeping received view-once media is switched off");
        }
        if (!facts.userInitiated) {
            throw new LibraryException(LibraryException.Code.NOT_AUTHORIZED,
                    "Media is only kept after an explicit user action");
        }
        return facts.fromMe ? BASIS_OWN_MEDIA : BASIS_USER_EXPLICIT;
    }

    /** WhatsApp media-type codes for view-once photo, video and voice (see FMessageWpp). */
    public static boolean isViewOnceMediaType(int mediaType) {
        return mediaType == 42 || mediaType == 43 || mediaType == 82;
    }
}
