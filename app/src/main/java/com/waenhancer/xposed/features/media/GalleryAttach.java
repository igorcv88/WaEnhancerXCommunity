package com.waenhancer.xposed.features.media;

/** Pure rules of the view-once gallery chip, kept free of Android types for unit tests. */
final class GalleryAttach {
    /** The media hub fills its chip row after the first frame; wait up to about three seconds. */
    static final int MAX_ATTEMPTS = 12;
    static final long RETRY_MS = 250L;

    private GalleryAttach() {}

    /** Whether another attempt follows a look at a screen whose chips were not ready. */
    static boolean retry(int attempt) {
        return attempt + 1 < MAX_ATTEMPTS;
    }

    /**
     * {primary, alternative or null}: the screen's own JID first, with the open conversation's
     * other form only when the open conversation is that same chat; else the open conversation.
     */
    static String[] pair(String fromIntent, String user, String phone) {
        if (fromIntent != null) {
            String other = fromIntent.equals(user) ? phone : fromIntent.equals(phone) ? user : null;
            return new String[] { fromIntent, other != null && !other.equals(fromIntent) ? other : null };
        }
        String primary = user != null ? user : phone;
        if (primary == null) return null;
        return new String[] { primary, phone != null && !phone.equals(primary) ? phone : null };
    }
}
