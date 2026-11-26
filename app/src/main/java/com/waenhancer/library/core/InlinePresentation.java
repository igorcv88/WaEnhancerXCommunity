package com.waenhancer.library.core;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Decisions for showing a kept copy inline in a conversation row.
 *
 * <p>The presentation is purely local and reversible: WhatsApp's message, its type in
 * {@code msgstore.db}, the row's layout, background and children are never changed. The kept
 * copy is drawn as an overlay on top of WhatsApp's own media thumbnail view and removed as soon
 * as the row is rebound to another message, the native file becomes available again, or the
 * feature is switched off.</p>
 */
public final class InlinePresentation {

    /** Resource-entry names WhatsApp has used for the media thumbnail inside a row. */
    static final List<String> KNOWN_THUMBNAIL_IDS = Arrays.asList(
            "image", "thumb", "conversation_row_image", "video_thumb", "media_thumb", "thumbnail");

    /** Views that are never a media thumbnail even when large (avatars, stickers, link previews). */
    private static final Set<String> EXCLUDED_IDS = new HashSet<>(Arrays.asList(
            "avatar", "contact_photo", "profile_picture", "sticker_image", "link_preview_image",
            "thumb_and_ppl_frame", "quoted_thumb", "quoted_image"));

    private InlinePresentation() { }

    /**
     * Present the kept copy only when it adds something: WhatsApp no longer has the file, the
     * copy is intact, and the message is not received ephemeral media (whose native expiry is
     * preserved; the library never holds such media anyway).
     */
    public static boolean shouldPresent(boolean featureEnabled, boolean hasAvailableCopy,
                                        boolean nativeFileAvailable, boolean receivedEphemeral) {
        return featureEnabled && hasAvailableCopy && !nativeFileAvailable && !receivedEphemeral;
    }

    /** A view as seen by the selector; adapted from {@code android.view.View} in the hook. */
    public interface Node {
        String idName();

        boolean isImageView();

        boolean isVisible();

        int width();

        int height();

        List<? extends Node> children();
    }

    /**
     * Chooses the native media thumbnail of a row, or {@code null} when none is identifiable (in
     * which case nothing is presented and the row stays native). Known ids win; otherwise the
     * largest visible image view of at least {@code minSizePx} in both dimensions that is not a
     * quote or avatar. The row root itself is never chosen.
     */
    public static Node selectThumbnail(Node row, int minSizePx) {
        if (row == null) return null;
        Node[] best = new Node[1];
        int[] bestRank = { Integer.MIN_VALUE };
        visit(row, row, minSizePx, best, bestRank, 0);
        return best[0];
    }

    private static void visit(Node node, Node row, int min, Node[] best, int[] bestRank, int depth) {
        if (depth > 24 || !node.isVisible()) return;
        String id = node.idName();
        if (id != null && EXCLUDED_IDS.contains(id)) return; // and everything inside it
        if (node != row && node.isImageView() && node.width() >= min && node.height() >= min) {
            long area = (long) node.width() * node.height();
            int known = id == null ? -1 : KNOWN_THUMBNAIL_IDS.indexOf(id);
            // Known ids rank above any size; among unknown views the larger one wins.
            int rank = known >= 0 ? Integer.MAX_VALUE - known : (int) Math.min(Integer.MAX_VALUE - 100, area);
            if (rank > bestRank[0]) {
                bestRank[0] = rank;
                best[0] = node;
            }
        }
        List<? extends Node> children = node.children();
        if (children == null) return;
        for (Node child : children) visit(child, row, min, best, bestRank, depth + 1);
    }
}
