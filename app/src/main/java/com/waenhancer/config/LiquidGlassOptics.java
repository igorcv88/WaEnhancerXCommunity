package com.waenhancer.config;

import android.content.SharedPreferences;

import com.waenhancer.theme.GlassOptics;
import com.waenhancer.theme.LensModel;

/**
 * The experimental optical-correction switches, as stored preferences.
 *
 * <p>Off by default: installing a build with these corrections changes nothing until the master
 * switch is turned on, and no existing preference changes meaning (the opacity slider, the style
 * picker and the surface switches keep theirs). With the master switch on, each group defaults to
 * on, so turning it on selects every correction; each group can then be switched off on its own
 * for a side-by-side comparison. Dependencies are enforced by {@link GlassOptics#resolve}, not by
 * the stored values, so a combination saved under an older dependency rule still resolves
 * sensibly.</p>
 *
 * <p>All keys are public settings: the WhatsApp process reads them through the preference bridge
 * ({@link PreferenceSchema}), and none is a secret.</p>
 */
public final class LiquidGlassOptics {

    public static final String MASTER = "liquid_glass_optics";
    public static final String FILTERING = "liquid_glass_optics_filtering";
    public static final String ADAPTIVE = "liquid_glass_optics_adaptive";
    public static final String COLOR = "liquid_glass_optics_color";
    public static final String TEMPORAL = "liquid_glass_optics_temporal";
    public static final String CLEAR = "liquid_glass_optics_clear";
    /** Developer: shader diagnostic view, a {@link GlassOptics.Debug} key. */
    public static final String DEBUG = "liquid_glass_optics_debug";
    /** Developer: displacement as a fraction of the bevel, before the hard cap. */
    public static final String DISPLACEMENT = "liquid_glass_optics_displacement";

    /**
     * Group switches, in the order the comparison is meant to be made. Stable geometry is not a
     * group: it is always on in the corrected renderer (the legacy warp is a diagnostic view).
     */
    public static final String[] GROUPS = {FILTERING, ADAPTIVE, COLOR, TEMPORAL};

    private LiquidGlassOptics() { }

    /** The effective switches. Never throws; unreadable values fall back to their defaults. */
    public static GlassOptics read(SharedPreferences prefs) {
        if (prefs == null) return GlassOptics.LEGACY;
        return GlassOptics.resolve(
                bool(prefs, MASTER, false),
                bool(prefs, FILTERING, true),
                bool(prefs, ADAPTIVE, true),
                bool(prefs, COLOR, true),
                bool(prefs, TEMPORAL, true),
                bool(prefs, CLEAR, false),
                GlassOptics.Debug.from(string(prefs, DEBUG)),
                number(prefs, DISPLACEMENT, LensModel.DEFAULT_DISPLACEMENT));
    }

    /** "All improvements": the master switch and every group on, diagnostics off. */
    public static void applyAllImprovements(SharedPreferences prefs) {
        SharedPreferences.Editor editor = prefs.edit().putBoolean(MASTER, true);
        for (String group : GROUPS) editor.putBoolean(group, true);
        editor.putString(DEBUG, GlassOptics.Debug.NONE.key());
        editor.apply();
    }

    /** "Original": the master switch off. Group choices are kept for the next comparison. */
    public static void applyOriginal(SharedPreferences prefs) {
        prefs.edit().putBoolean(MASTER, false).apply();
    }

    private static boolean bool(SharedPreferences prefs, String key, boolean fallback) {
        try {
            Object raw = prefs.getAll().get(key);
            if (raw instanceof Boolean) return (Boolean) raw;
            if (raw instanceof String) return Boolean.parseBoolean((String) raw);
            return fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String string(SharedPreferences prefs, String key) {
        try {
            Object raw = prefs.getAll().get(key);
            return raw == null ? null : String.valueOf(raw);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static float number(SharedPreferences prefs, String key, float fallback) {
        try {
            Object raw = prefs.getAll().get(key);
            if (raw instanceof Number) return ((Number) raw).floatValue();
            if (raw instanceof String) return Float.parseFloat((String) raw);
            return fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
