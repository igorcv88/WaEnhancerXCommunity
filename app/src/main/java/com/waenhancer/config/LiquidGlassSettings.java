package com.waenhancer.config;

import android.content.SharedPreferences;

import com.waenhancer.theme.GlassSpec;

/**
 * Which surfaces the Liquid Glass theme is switched on for, and what material they resolve.
 *
 * <p>Phase 2 turned glass from one bar's styling into a theme applied across the app, and a theme
 * needs one place that answers "is it on here" and "what does it look like". That is this class.
 * It is plain Java over a {@link SharedPreferences}, so the settings page in the module process
 * and the hooks inside WhatsApp reach the same answer, and so the answer can be asserted in a
 * unit test rather than discovered on a device.</p>
 *
 * <h3>The bar is not a toggle of its own</h3>
 *
 * <p>Every surface here owns a boolean except the floating bottom bar, which owns none. The bar
 * already had a glass style picker before the theme existed, and two independent switches over
 * one piece of state is how a settings screen starts lying: the page would say Liquid while the
 * bar editor said Frost. So the bar's row on the Liquid Glass page <em>is</em> its style picker,
 * read and written through {@link #isBarLiquid} and {@link #setBarLiquid} — turning it on selects
 * the Liquid style, and turning it off puts back whichever style was chosen before.</p>
 */
public final class LiquidGlassSettings {

    /** The bar's own glass switch, and the style picked for it. Both predate the theme. */
    public static final String BAR_GLASS = "floating_bottom_bar_glass";
    public static final String BAR_VARIANT = "floating_bottom_bar_glass_variant";
    /** Opacity of the glass material, app-wide. Still stored under the bar's key; see {@link #opacityPercent}. */
    public static final String BAR_OPACITY = "floating_bottom_bar_glass_opacity";
    /**
     * The style the bar had before Liquid was switched on, so switching it off is reversible.
     *
     * <p>Without this, "off" would have to mean some fixed default, and a user who had chosen
     * Frost would silently lose it the first time they tried the theme and changed their mind.</p>
     */
    public static final String BAR_PREVIOUS_VARIANT = "liquid_glass_bar_previous_variant";

    /** The round scroll-to-bottom button that floats over the message list. */
    public static final String SCROLL_BUTTON = "liquid_glass_scroll_button";

    /** What the theme means by "on": the variant every themed surface resolves. */
    public static final GlassSpec.Variant MATERIAL = GlassSpec.Variant.LIQUID;

    /** Where the bar falls back to when Liquid is switched off and nothing was remembered. */
    private static final GlassSpec.Variant DEFAULT_BAR_VARIANT = GlassSpec.Variant.ADVANCED;

    private LiquidGlassSettings() { }

    /** Whether the floating bar is currently wearing the Liquid style. */
    public static boolean isBarLiquid(SharedPreferences prefs) {
        if (prefs == null) return false;
        if (!prefs.getBoolean(BAR_GLASS, true)) return false;
        return MATERIAL == GlassSpec.Variant.from(readString(prefs, BAR_VARIANT, null));
    }

    /**
     * Puts the bar into the Liquid style, or back into whatever it wore before.
     *
     * <p>Switching on also switches the bar's glass on — a style is not visible while the effect
     * that draws it is off — and adopts the style's designed opacity, which is exactly what the
     * dropdown in the bar editor does. Opacity and variant are not independent: Liquid at the
     * opacity Frost wants is an opaque bar with a lit edge.</p>
     */
    public static void setBarLiquid(SharedPreferences prefs, boolean liquid) {
        if (prefs == null) return;
        GlassSpec.Variant target;
        SharedPreferences.Editor editor = prefs.edit();
        if (liquid) {
            GlassSpec.Variant current = GlassSpec.Variant.from(readString(prefs, BAR_VARIANT, null));
            if (current != MATERIAL) editor.putString(BAR_PREVIOUS_VARIANT, current.key());
            editor.putBoolean(BAR_GLASS, true);
            target = MATERIAL;
        } else {
            GlassSpec.Variant remembered = GlassSpec.Variant.from(
                    readString(prefs, BAR_PREVIOUS_VARIANT, null));
            target = remembered == MATERIAL ? DEFAULT_BAR_VARIANT : remembered;
        }
        editor.putString(BAR_VARIANT, target.key());
        editor.putFloat(BAR_OPACITY, BottomBarPreferenceSchema.normalize(
                BAR_OPACITY, target.recommendedOpacityPercent()));
        editor.apply();
    }

    /**
     * Records a style the user picked somewhere else, so the theme's off switch can restore it.
     *
     * <p>Called from the bar editor's own dropdown. A style chosen there and a style chosen on the
     * theme page are the same setting, and the one that knows what "before" was has to be told.</p>
     */
    public static void rememberBarVariant(SharedPreferences prefs, String variantKey) {
        if (prefs == null) return;
        GlassSpec.Variant variant = GlassSpec.Variant.from(variantKey);
        if (variant == MATERIAL) return;
        prefs.edit().putString(BAR_PREVIOUS_VARIANT, variant.key()).apply();
    }

    /** Whether the conversation's scroll-to-bottom button is themed. Off until asked for. */
    public static boolean isScrollButtonEnabled(SharedPreferences prefs) {
        return prefs != null && prefs.getBoolean(SCROLL_BUTTON, false);
    }

    /**
     * Opacity of the glass material, 0-100.
     *
     * <p>One figure for every themed surface. It is still stored under the bar's key because that
     * is where the slider the user already knows lives, and a second slider that had to agree with
     * the first would be a second definition of the same material.</p>
     */
    public static float opacityPercent(SharedPreferences prefs) {
        if (prefs == null) return MATERIAL.recommendedOpacityPercent();
        try {
            return BottomBarPreferenceSchema.read(prefs, BAR_OPACITY);
        } catch (Throwable ignored) {
            return MATERIAL.recommendedOpacityPercent();
        }
    }

    /**
     * Reads a string preference whatever type it was stored as.
     *
     * <p>The same defensive read the bar hook uses: these keys are written by the module process
     * and read across a preferences bridge, and a value that arrived as something other than a
     * String must not take a feature down with a ClassCastException.</p>
     */
    private static String readString(SharedPreferences prefs, String key, String defaultValue) {
        try {
            Object raw = prefs.getAll().get(key);
            return raw == null ? defaultValue : String.valueOf(raw);
        } catch (Throwable ignored) {
            return defaultValue;
        }
    }
}
