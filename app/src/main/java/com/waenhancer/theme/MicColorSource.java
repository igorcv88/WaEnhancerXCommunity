package com.waenhancer.theme;

/**
 * Selects the microphone's effective glyph colour from values read through public Android APIs.
 *
 * <p>Order: the ImageView tint list for its current drawable state, a {@code BlendModeColorFilter}
 * (API 29+), the colour argument of an observed {@code ImageView.setColorFilter(int[, Mode])} on
 * the identified microphone while that filter is still installed, then a {@code ColorDrawable}.
 * {@code PorterDuffColorFilter} exposes no public colour getter, so a filter installed through
 * {@code setColorFilter(ColorFilter)} is only known through these sources. {@code null} means
 * "unknown": {@link ComposerPalette#update(Integer)} then keeps the last reference, which starts at
 * the current-profile white ({@link ComposerPalette#CURRENT_PROFILE_ICON}).</p>
 */
public final class MicColorSource {
    private MicColorSource() {
    }

    public static Integer select(Integer tintColor, Integer blendFilterColor,
                                 Integer observedFilterColor, Integer drawableColor) {
        if (tintColor != null) return tintColor;
        if (blendFilterColor != null) return blendFilterColor;
        if (observedFilterColor != null) return observedFilterColor;
        return drawableColor;
    }
}
