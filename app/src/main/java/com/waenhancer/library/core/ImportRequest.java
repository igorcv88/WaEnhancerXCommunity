package com.waenhancer.library.core;

import java.util.regex.Pattern;

/**
 * Identity and context of a media item to keep.
 *
 * <p>The deterministic identity of an item is {@code (account, chat, message key, from-me)}:
 * importing the same message again is idempotent, and two messages that carry identical bytes
 * become two items sharing one blob. {@link #packageName} must come from the provider's view of
 * the calling UID, never from caller-supplied data, so one WhatsApp flavour can never write into
 * or read from another's library.</p>
 */
public final class ImportRequest {
    private static final Pattern MESSAGE_KEY = Pattern.compile("[A-Za-z0-9._:\\-]{1,128}");
    private static final int MAX_JID = 256;
    private static final int MAX_CAPTION = 4096;

    public final String packageName;
    public final String ownJid;
    public final String chatJid;
    public final String chatAltJid;
    public final String messageKey;
    public final boolean fromMe;
    public final String senderJid;
    public final boolean viewOnce;
    public final boolean statusBroadcast;
    public final boolean userInitiated;
    /** Set by the module from its own preference, never from caller data. */
    public final boolean keepReceivedViewOnce;
    public final long messageTimestamp;
    public final String caption;

    private ImportRequest(Builder builder) {
        packageName = builder.packageName;
        ownJid = builder.ownJid;
        chatJid = builder.chatJid;
        chatAltJid = emptyToNull(builder.chatAltJid);
        messageKey = builder.messageKey;
        fromMe = builder.fromMe;
        senderJid = emptyToNull(builder.senderJid);
        viewOnce = builder.viewOnce;
        statusBroadcast = builder.statusBroadcast;
        userInitiated = builder.userInitiated;
        keepReceivedViewOnce = builder.keepReceivedViewOnce;
        messageTimestamp = Math.max(0, builder.messageTimestamp);
        caption = emptyToNull(builder.caption);
    }

    public ImportPolicy.Facts facts() {
        return new ImportPolicy.Facts(fromMe, viewOnce, statusBroadcast, userInitiated, keepReceivedViewOnce);
    }

    /** Rejects incomplete or oversized metadata before any bytes are read. */
    public void validate() throws LibraryException {
        if (isBlank(packageName) || packageName.length() > 128) invalid("package");
        if (isBlank(ownJid) || ownJid.length() > MAX_JID) invalid("account");
        if (isBlank(chatJid) || chatJid.length() > MAX_JID) invalid("chat");
        if (chatAltJid != null && chatAltJid.length() > MAX_JID) invalid("chat alias");
        if (messageKey == null || !MESSAGE_KEY.matcher(messageKey).matches()) invalid("message key");
        if (senderJid != null && senderJid.length() > MAX_JID) invalid("sender");
        if (caption != null && caption.length() > MAX_CAPTION) invalid("caption");
    }

    private static void invalid(String field) throws LibraryException {
        throw new LibraryException(LibraryException.Code.INVALID_REQUEST, "Invalid " + field);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String packageName;
        private String ownJid;
        private String chatJid;
        private String chatAltJid;
        private String messageKey;
        private boolean fromMe;
        private String senderJid;
        private boolean viewOnce;
        private boolean statusBroadcast;
        private boolean userInitiated;
        private boolean keepReceivedViewOnce;
        private long messageTimestamp;
        private String caption;

        public Builder packageName(String value) { packageName = value; return this; }
        public Builder ownJid(String value) { ownJid = value; return this; }
        public Builder chatJid(String value) { chatJid = value; return this; }
        public Builder chatAltJid(String value) { chatAltJid = value; return this; }
        public Builder messageKey(String value) { messageKey = value; return this; }
        public Builder fromMe(boolean value) { fromMe = value; return this; }
        public Builder senderJid(String value) { senderJid = value; return this; }
        public Builder viewOnce(boolean value) { viewOnce = value; return this; }
        public Builder statusBroadcast(boolean value) { statusBroadcast = value; return this; }
        public Builder userInitiated(boolean value) { userInitiated = value; return this; }
        public Builder keepReceivedViewOnce(boolean value) { keepReceivedViewOnce = value; return this; }
        public Builder messageTimestamp(long value) { messageTimestamp = value; return this; }
        public Builder caption(String value) { caption = value; return this; }

        public ImportRequest build() {
            return new ImportRequest(this);
        }
    }
}
