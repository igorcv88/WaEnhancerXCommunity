package com.waenhancer.config;

import com.waenhancer.theme.GlassSpec;
import com.waenhancer.theme.SemanticTheme;

import java.util.Map;

/**
 * Every floating-bottom-bar value the editor's preview needs, resolved from a preference snapshot.
 *
 * <p>Kept free of Android types so the preview can be covered by JVM tests: the editor previously
 * read a handful of keys inline and silently ignored the rest, so most controls moved a slider
 * without changing anything on screen.
 */
public final class BottomBarPreviewModel {

    /** Radius used to mean "fully rounded"; larger than any pill can be. */
    public static final int FULLY_ROUNDED_RADIUS_DP = 1000;

    public final boolean barEnabled;
    public final boolean glassEnabled;
    public final int glassOpacity;
    public final String glassVariant;
    public final int fillColor;
    public final int radiusDp;
    public final int sideMarginDp;
    public final int bottomMarginDp;
    public final int paddingVerticalDp;
    public final int iconSizeDp;
    public final int textSizeSp;
    public final int iconLabelSpacingDp;
    public final boolean manualHeight;
    public final int manualHeightDp;

    public final String fabMode;
    public final int fabOffsetDp;
    public final int minimalFabSizeDp;
    public final int minimalFabRadiusDp;
    public final int minimalFabOpacity;
    public final int minimalFabMarginDp;
    public final int minimalFabColor;
    public final int minimalFabIconColor;

    private BottomBarPreviewModel(Map<String, ?> snapshot) {
        barEnabled = bool(snapshot, "floating_bottom_bar", false);
        glassEnabled = bool(snapshot, "floating_bottom_bar_glass", true);
        glassOpacity = intOf(snapshot, "floating_bottom_bar_glass_opacity");
        glassVariant = str(snapshot, "floating_bottom_bar_glass_variant",
                GlassSpec.Variant.ADVANCED.key());
        fillColor = color(snapshot, "floating_bottom_bar_fill_color", 0);
        radiusDp = bool(snapshot, "floating_bottom_bar_fully_rounded", false)
                ? FULLY_ROUNDED_RADIUS_DP
                : intOf(snapshot, "floating_bottom_bar_radius");
        sideMarginDp = intOf(snapshot, "floating_bottom_bar_horizontal_margin");
        bottomMarginDp = intOf(snapshot, "floating_bottom_bar_bottom_margin");
        paddingVerticalDp = intOf(snapshot, "floating_bottom_bar_padding_vertical");
        iconSizeDp = intOf(snapshot, "floating_bottom_bar_icon_size");
        textSizeSp = intOf(snapshot, "floating_bottom_bar_text_size");
        iconLabelSpacingDp = intOf(snapshot, "floating_bottom_bar_icon_label_spacing");
        manualHeight = "manual".equals(str(snapshot, "floating_bottom_bar_height_mode",
                "automatic"));
        manualHeightDp = intOf(snapshot, "floating_bottom_bar_manual_height");

        fabMode = str(snapshot, "floating_bottom_bar_fab_mode", "default");
        fabOffsetDp = intOf(snapshot, "floating_bottom_bar_fab_offset");
        minimalFabSizeDp = intOf(snapshot, "floating_bottom_bar_minimal_fab_size");
        minimalFabRadiusDp = intOf(snapshot, "floating_bottom_bar_minimal_fab_radius");
        minimalFabOpacity = intOf(snapshot, "floating_bottom_bar_minimal_fab_opacity");
        minimalFabMarginDp = intOf(snapshot, "floating_bottom_bar_minimal_fab_margin");
        minimalFabColor = color(snapshot, "floating_bottom_bar_minimal_fab_color", 0);
        minimalFabIconColor = color(snapshot, "floating_bottom_bar_minimal_fab_icon_color",
                0xFFFFFFFF);
    }

    public static BottomBarPreviewModel from(Map<String, ?> snapshot) {
        return new BottomBarPreviewModel(snapshot);
    }

    public boolean isFullyRounded() {
        return radiusDp == FULLY_ROUNDED_RADIUS_DP;
    }

    public boolean isFabHidden() {
        return "hidden".equals(fabMode);
    }

    public boolean isFabMinimal() {
        return "minimal".equals(fabMode);
    }

    /**
     * Pill fill as the Advanced Glass engine resolves it.
     *
     * <p>The preview does not fold the opacity in by itself. It asks {@link GlassSpec} for the
     * same fill the hooked bar will ask for, so the variant's scaling — and any floor the engine
     * applies — show up here too. A local copy of that arithmetic would drift the moment a
     * variant changed.</p>
     *
     * <p>Resolved as though blur were available and motion unrestricted: the preview shows the
     * intended look, not this particular device's fallback.</p>
     *
     * @param themeSurface colour to substitute when the user left the fill on "automatic" (0)
     */
    public int resolvedFillColor(int themeSurface) {
        int base = fillColor == 0 ? themeSurface : fillColor;
        if (!glassEnabled) return opaque(base);
        return glassSpec(themeSurface).fillColor;
    }

    /** The glass description this preview is drawing; also the source of its content colour. */
    public GlassSpec glassSpec(int themeSurface) {
        int base = fillColor == 0 ? themeSurface : fillColor;
        boolean dark = SemanticTheme.bestTextColor(opaque(base)) == 0xFFFFFFFF;
        return GlassSpec.resolve(GlassSpec.Variant.from(glassVariant), dark, base, 0,
                glassOpacity, true, false);
    }

    /** @param themePrimary colour to substitute when the FAB colour is left on "automatic" (0) */
    public int resolvedMinimalFabColor(int themePrimary) {
        return applyOpacity(minimalFabColor == 0 ? themePrimary : minimalFabColor,
                minimalFabOpacity);
    }

    /**
     * Matches {@code FloatingBottomBar}: percentage scaled to 0-255 and packed as the alpha byte,
     * so the preview and the real bar agree.
     */
    public static int applyOpacity(int color, int percent) {
        int alpha = Math.max(0, Math.min(255, Math.round(percent * 2.55f)));
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    private static int opaque(int color) {
        return 0xFF000000 | (color & 0x00FFFFFF);
    }

    private static int intOf(Map<String, ?> snapshot, String key) {
        return BottomBarPreferenceSchema.readInt(snapshot, key);
    }

    private static boolean bool(Map<String, ?> snapshot, String key, boolean fallback) {
        Object raw = snapshot == null ? null : snapshot.get(key);
        if (raw instanceof Boolean) return (Boolean) raw;
        if (raw instanceof String) return Boolean.parseBoolean((String) raw);
        return fallback;
    }

    private static String str(Map<String, ?> snapshot, String key, String fallback) {
        Object raw = snapshot == null ? null : snapshot.get(key);
        return raw instanceof String && !((String) raw).isEmpty() ? (String) raw : fallback;
    }

    private static int color(Map<String, ?> snapshot, String key, int fallback) {
        Object raw = snapshot == null ? null : snapshot.get(key);
        if (raw instanceof Number) return ((Number) raw).intValue();
        if (raw instanceof String) {
            Integer parsed = parseColor((String) raw);
            return parsed == null ? fallback : parsed;
        }
        return fallback;
    }

    /** Accepts {@code #RRGGBB}, {@code #AARRGGBB} and {@code 0} ("automatic"). */
    public static Integer parseColor(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || "0".equals(value)) return 0;
        try {
            if (!value.startsWith("#")) value = "#" + value;
            if (value.length() == 7) value = "#FF" + value.substring(1);
            if (value.length() != 9) return null;
            return (int) Long.parseLong(value.substring(1), 16);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
