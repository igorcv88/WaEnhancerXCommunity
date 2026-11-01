package com.waenhancer.theme;

import java.util.List;

/**
 * Selects the composer reference colour from the action button's disc.
 *
 * <p>The reference is the disc's fill (white in the current WhatsApp profile), not the microphone
 * glyph painted on it (black): device review on 2026-11-01 showed emoji, attachment, camera,
 * waveform and paused-recorder controls turning black when the glyph was used. Candidates are
 * read through public APIs only (background tint list for the current state, then the
 * background's ColorDrawable/GradientDrawable/ShapeDrawable fill through selectors, layers and
 * insets), for the action button and its first descendants, in that order. The first colour that
 * is at least half opaque wins: a transparent container or ripple layer is not the disc.
 * {@code null} means "unknown", and {@link ComposerPalette#update(Integer)} then keeps the last
 * reference, which starts at {@link ComposerPalette#CURRENT_PROFILE_ICON} (white).</p>
 */
public final class MicColorSource {
    private MicColorSource() {
    }

    public static Integer selectDisc(List<Integer> candidates) {
        if (candidates == null) return null;
        for (Integer color : candidates) {
            if (color != null && (color >>> 24) >= 0x80) return color;
        }
        return null;
    }
}
