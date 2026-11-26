package com.waenhancer.library.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Path rules for the two places the library touches WhatsApp's own files.
 *
 * <ul>
 *   <li>Reading: WhatsApp's {@code message_media.file_path} may be absolute or relative to one of
 *   its media roots; {@link #candidates} lists where to look, refusing traversal.</li>
 *   <li>Restoring: a kept conventional copy may be written back to the exact path WhatsApp still
 *   records for that message, so WhatsApp's own bubble, viewer and "Media" list use it again
 *   without any change to {@code msgstore.db}. {@link #isSafeRestoreTarget} allows this only
 *   inside WhatsApp's own media roots, never over an existing file, and never for view-once.</li>
 * </ul>
 */
public final class WhatsAppMediaPaths {
    private WhatsAppMediaPaths() { }

    /** Absolute paths to try for a stored path, in order; empty when the path is unusable. */
    public static List<String> candidates(String storedPath, List<String> mediaRoots) {
        List<String> result = new ArrayList<>();
        if (storedPath == null || storedPath.isEmpty() || hasTraversal(storedPath)) return result;
        if (storedPath.startsWith("/")) {
            result.add(storedPath);
            return result;
        }
        for (String root : mediaRoots) {
            if (root == null || root.isEmpty()) continue;
            result.add(root.endsWith("/") ? root + storedPath : root + "/" + storedPath);
        }
        return result;
    }

    /**
     * @param canonicalTarget canonical absolute path WhatsApp records for the message
     * @param canonicalRoots  canonical WhatsApp media roots (its Android/media and data dirs)
     * @param exists          whether something already exists at the target
     * @param viewOnce        view-once media is never written back into WhatsApp's storage
     */
    public static boolean isSafeRestoreTarget(String canonicalTarget, List<String> canonicalRoots,
                                              boolean exists, boolean viewOnce) {
        if (viewOnce || exists || canonicalTarget == null || hasTraversal(canonicalTarget)
                || !canonicalTarget.startsWith("/")) {
            return false;
        }
        for (String root : canonicalRoots) {
            if (root == null || root.isEmpty()) continue;
            String prefix = root.endsWith("/") ? root : root + "/";
            if (canonicalTarget.startsWith(prefix) && canonicalTarget.length() > prefix.length()) return true;
        }
        return false;
    }

    private static boolean hasTraversal(String path) {
        for (String segment : path.split("/")) {
            if (segment.equals("..")) return true;
        }
        return path.indexOf('\0') >= 0;
    }
}
