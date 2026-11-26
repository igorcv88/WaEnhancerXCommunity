package com.waenhancer.library.core;

/**
 * Identifies photos and videos from their leading bytes.
 *
 * <p>The MIME type reported by the hooked process is a hint only. The library stores the type
 * derived from the content, so a mislabelled or hostile file cannot be catalogued as a photo and
 * later handed to an image decoder or player as something else.</p>
 */
public final class MediaSniffer {
    /** Bytes {@link #sniff} needs to decide. */
    public static final int PREFIX_BYTES = 16;

    public static final String KIND_IMAGE = "image";
    public static final String KIND_VIDEO = "video";

    private MediaSniffer() { }

    /** Returns a canonical MIME type, or {@code null} when the content is not supported. */
    public static String sniff(byte[] prefix, int length) {
        if (prefix == null || length < 4) return null;
        int n = Math.min(length, prefix.length);
        if (n >= 3 && u(prefix[0]) == 0xFF && u(prefix[1]) == 0xD8 && u(prefix[2]) == 0xFF) return "image/jpeg";
        if (n >= 8 && u(prefix[0]) == 0x89 && prefix[1] == 'P' && prefix[2] == 'N' && prefix[3] == 'G'
                && u(prefix[4]) == 0x0D && u(prefix[5]) == 0x0A && u(prefix[6]) == 0x1A && u(prefix[7]) == 0x0A) {
            return "image/png";
        }
        if (n >= 6 && prefix[0] == 'G' && prefix[1] == 'I' && prefix[2] == 'F' && prefix[3] == '8'
                && (prefix[4] == '7' || prefix[4] == '9') && prefix[5] == 'a') {
            return "image/gif";
        }
        if (n >= 12 && prefix[0] == 'R' && prefix[1] == 'I' && prefix[2] == 'F' && prefix[3] == 'F'
                && prefix[8] == 'W' && prefix[9] == 'E' && prefix[10] == 'B' && prefix[11] == 'P') {
            return "image/webp";
        }
        if (n >= 4 && u(prefix[0]) == 0x1A && u(prefix[1]) == 0x45 && u(prefix[2]) == 0xDF && u(prefix[3]) == 0xA3) {
            return "video/webm";
        }
        if (n >= 12 && prefix[4] == 'f' && prefix[5] == 't' && prefix[6] == 'y' && prefix[7] == 'p') {
            String brand = new String(prefix, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (brand.startsWith("3gp") || brand.startsWith("3g2")) return "video/3gpp";
            if (brand.equals("heic") || brand.equals("heix") || brand.equals("mif1") || brand.equals("heim")
                    || brand.equals("heis")) {
                return "image/heic";
            }
            if (brand.equals("avif") || brand.equals("avis")) return "image/avif";
            if (brand.equals("qt  ")) return "video/quicktime";
            // isom, iso2..6, mp41, mp42, avc1, dash, M4V, MSNV: ISO base media video.
            return "video/mp4";
        }
        return null;
    }

    /** {@link #KIND_IMAGE} or {@link #KIND_VIDEO} for a canonical MIME type, else {@code null}. */
    public static String kindOf(String mimeType) {
        if (mimeType == null) return null;
        if (mimeType.startsWith("image/")) return KIND_IMAGE;
        if (mimeType.startsWith("video/")) return KIND_VIDEO;
        return null;
    }

    private static int u(byte value) {
        return value & 0xFF;
    }
}
