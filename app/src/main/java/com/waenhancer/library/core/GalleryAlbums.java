package com.waenhancer.library.core;

/**
 * Where an explicit "Save to gallery" puts a kept item (MediaStore relative path).
 *
 * <p>Mirrors the owner's current layout: WhatsApp's own "Save" puts normal photos and videos in
 * {@code DCIM/WhatsApp}; view-once copies get their own album, {@code DCIM/WhatsApp View Once}, so they
 * never mix with normal media. Nothing is ever published without that explicit action.</p>
 */
public final class GalleryAlbums {
    public static final String VIEW_ONCE = "DCIM/WhatsApp View Once";
    public static final String NORMAL = "DCIM/WhatsApp";

    private GalleryAlbums() { }

    public static String relativePathFor(boolean viewOnce) {
        return viewOnce ? VIEW_ONCE : NORMAL;
    }

    /** A stable, non-identifying display name: WA-yyyyMMdd-&lt;first 8 hex of SHA-256&gt;.ext */
    public static String displayName(String sha256, long messageTimestampMillis, String mimeType) {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT);
        format.setTimeZone(java.util.TimeZone.getDefault());
        String date = format.format(new java.util.Date(Math.max(0, messageTimestampMillis)));
        return "WA-" + date + "-" + sha256.substring(0, 8) + extension(mimeType);
    }

    static String extension(String mimeType) {
        if (mimeType == null) return "";
        switch (mimeType) {
            case "image/jpeg": return ".jpg";
            case "image/png": return ".png";
            case "image/gif": return ".gif";
            case "image/webp": return ".webp";
            case "image/heic": return ".heic";
            case "image/avif": return ".avif";
            case "video/mp4": return ".mp4";
            case "video/3gpp": return ".3gp";
            case "video/quicktime": return ".mov";
            case "video/webm": return ".webm";
            default: return "";
        }
    }
}
