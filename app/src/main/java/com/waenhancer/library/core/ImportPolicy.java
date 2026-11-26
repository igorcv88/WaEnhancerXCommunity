package com.waenhancer.library.core;

/**
 * Decides whether a message's media may be kept in the private library, and on which basis.
 *
 * <p>The library is for the user's own media and for copies the user explicitly chooses to keep.
 * It is deliberately not a way around ephemeral delivery: media that a contact sent as view-once,
 * and other people's status posts, keep WhatsApp's native behaviour and are refused here. Nothing
 * is ever imported without a user action; this policy is evaluated for each explicit request.</p>
 */
public final class ImportPolicy {

    /** The user sent this media (their own photo or video). */
    public static final String BASIS_OWN_MEDIA = "own_media";
    /** A conventional (non-ephemeral) received media the user explicitly chose to keep. */
    public static final String BASIS_USER_EXPLICIT = "user_explicit";
    /** Restored from a library backup created by this user. */
    public static final String BASIS_BACKUP_RESTORE = "backup_restore";

    private ImportPolicy() { }

    /** Facts about the source message, all reported by the hooked process. */
    public static final class Facts {
        public final boolean fromMe;
        public final boolean viewOnce;
        public final boolean statusBroadcast;
        public final boolean userInitiated;

        public Facts(boolean fromMe, boolean viewOnce, boolean statusBroadcast, boolean userInitiated) {
            this.fromMe = fromMe;
            this.viewOnce = viewOnce;
            this.statusBroadcast = statusBroadcast;
            this.userInitiated = userInitiated;
        }
    }

    /** Returns the authorization basis, or throws {@link LibraryException.Code#NOT_AUTHORIZED}. */
    public static String authorize(Facts facts) throws LibraryException {
        if (facts == null || !facts.userInitiated) {
            throw new LibraryException(LibraryException.Code.NOT_AUTHORIZED,
                    "Media is only kept after an explicit user action");
        }
        if (facts.fromMe) return BASIS_OWN_MEDIA;
        if (facts.viewOnce) {
            throw new LibraryException(LibraryException.Code.NOT_AUTHORIZED,
                    "Received view-once media keeps WhatsApp's native expiry");
        }
        if (facts.statusBroadcast) {
            throw new LibraryException(LibraryException.Code.NOT_AUTHORIZED,
                    "Other people's status posts keep WhatsApp's native expiry");
        }
        return BASIS_USER_EXPLICIT;
    }

    /** WhatsApp media-type codes for view-once photo, video and voice (see FMessageWpp). */
    public static boolean isViewOnceMediaType(int mediaType) {
        return mediaType == 42 || mediaType == 43 || mediaType == 82;
    }
}
