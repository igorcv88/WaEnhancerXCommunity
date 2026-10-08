package com.waenhancer.theme;

import java.util.Locale;

/**
 * Which of the optical corrections are switched on, after their dependencies are enforced.
 *
 * <p>{@link #LEGACY} is the renderer that preceded the corrections: its shader, its capture scale
 * and its scheduling logic (now in shared classes; the logic is the same, the code paths are not
 * literally identical). Every other value runs the corrected renderer ({@link LensEffect}) with
 * stable geometry always on, plus four independently switchable groups (filtering, adaptive
 * contrast, colour, temporal); a group that is off reproduces the legacy behaviour for that group
 * only. The folding legacy warp is not a group: it is only a diagnostic view.</p>
 *
 * <p>Nothing here can select unsafe capture. Both renderers record only acyclic sources
 * ({@link GlassPaneGraph}, {@link BehindRecorder}); the groups change optics and scheduling, never
 * what may be recorded.</p>
 *
 * <p>No preference is read in this package. The hook resolves the user's switches with
 * {@code config.LiquidGlassOptics} and publishes them through {@link #publish}; surfaces read
 * {@link #current()} each time they build or refresh an effect, so a change applies on the next
 * capture without restarting WhatsApp.</p>
 */
public final class GlassOptics {

    /** Shader diagnostics. Developer only; {@link #NONE} is the material itself. */
    public enum Debug {
        NONE(0),
        /** The backdrop as recorded, without refraction, filtering or material. */
        RAW_INPUT(1),
        /** Displacement field: red/green are x/y offset, blue is the depth into the bevel. */
        DISPLACEMENT(2),
        /** Smallest singular value of the sampling Jacobian: green >= 0.30, yellow lower, red folded. */
        JACOBIAN(3),
        /** A procedural grid replaces the backdrop, so the warp can be judged on known lines. */
        GRID(4),
        /** Red is contrast protection, green is the soft (filtered) share of the backdrop. */
        PROTECTION(5),
        /**
         * Diagnostic comparison only: the material with the legacy warp, which folds at the rounded
         * ends, corners and selected tab. Never a production setting.
         */
        LEGACY_WARP(6);

        public final int code;

        Debug(int code) {
            this.code = code;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Debug from(String value) {
            if (value == null) return NONE;
            for (Debug debug : values()) {
                if (debug.key().equalsIgnoreCase(value.trim())) return debug;
            }
            return NONE;
        }
    }

    /** The renderer as it was before the corrections: legacy shader, capture and scheduling. */
    public static final GlassOptics LEGACY =
            new GlassOptics(false, false, false, false, false, false, false, Debug.NONE,
                    LensModel.DEFAULT_DISPLACEMENT);

    /** Every correction on. What the "all improvements" preset selects. */
    public static final GlassOptics ALL =
            resolve(true, true, true, true, true, false, Debug.NONE, LensModel.DEFAULT_DISPLACEMENT);

    private static volatile GlassOptics current = LEGACY;
    /** Bumped on every change of {@link #current}; providers compare it to rebuild their effect. */
    private static volatile int revision;

    /** Whether the corrected renderer runs at all. False is {@link #LEGACY}. */
    public final boolean corrected;
    /**
     * Bounded 2D warp, smooth corners, 1:1 capture and exact transforms (LG-01/06/08). Not a
     * switch: it is an invariant of the corrected renderer, so a correction can never be combined
     * with the folding warp. The legacy warp is reachable only as {@link Debug#LEGACY_WARP}.
     */
    public final boolean geometry;
    /** The diagnostic legacy-warp view is selected. */
    public final boolean legacyWarp;
    /** Gaussian low-pass input mixed with the sharp one by depth (LG-02/07). */
    public final boolean filtering;
    /** In-shader contrast protection and backdrop-adaptive tint (LG-03). Needs filtering. */
    public final boolean adaptive;
    /** Linear-light colour, calibrated saturation, dispersion and highlights (LG-05/09/13). */
    public final boolean color;
    /** Damage-driven capture, value-keyed effects, kept interaction state, shared budget (LG-10/11/12). */
    public final boolean temporal;
    /** Experimental iOS-like Clear profile on the theme's surfaces. Needs adaptive contrast. */
    public final boolean clearProfile;
    public final Debug debug;
    /** Base displacement as a fraction of the bevel, before the hard cap. Developer tuning. */
    public final float displacement;

    private GlassOptics(boolean corrected, boolean geometry, boolean filtering, boolean adaptive,
                        boolean color, boolean temporal, boolean clearProfile, Debug debug,
                        float displacement) {
        this.corrected = corrected;
        this.geometry = geometry;
        this.legacyWarp = corrected && debug == Debug.LEGACY_WARP;
        this.filtering = filtering;
        this.adaptive = adaptive;
        this.color = color;
        this.temporal = temporal;
        this.clearProfile = clearProfile;
        this.debug = debug == null ? Debug.NONE : debug;
        this.displacement = displacement;
    }

    /**
     * The effective switches for what the user asked for. Dependencies are enforced here, once,
     * so no surface can run a combination that has no meaning: adaptive contrast measures the
     * filtered backdrop, so it needs filtering; the Clear profile relies on contrast protection
     * for legibility, so it needs adaptive contrast. With the master switch off every group is off
     * and the result is {@link #LEGACY}.
     */
    public static GlassOptics resolve(boolean master, boolean filtering,
                                      boolean adaptive, boolean color, boolean temporal,
                                      boolean clearProfile, Debug debug, float displacement) {
        if (!master) return LEGACY;
        boolean effectiveAdaptive = adaptive && filtering;
        boolean effectiveClear = clearProfile && effectiveAdaptive;
        float a = Float.isNaN(displacement) ? LensModel.DEFAULT_DISPLACEMENT
                : Math.max(LensModel.MIN_DISPLACEMENT, Math.min(LensModel.MAX_EFFECTIVE, displacement));
        return new GlassOptics(true, true, filtering, effectiveAdaptive, color, temporal,
                effectiveClear, debug, a);
    }

    /** The switches surfaces should render with right now. */
    public static GlassOptics current() {
        return current;
    }

    /**
     * Publishes new switches. Returns true when they differ from the previous ones, so the caller
     * can ask live surfaces to rebuild their effects.
     */
    public static boolean publish(GlassOptics optics) {
        GlassOptics next = optics == null ? LEGACY : optics;
        GlassOptics previous = current;
        current = next;
        if (next.equals(previous)) return false;
        revision++;
        return true;
    }

    /** Changes every time {@link #publish} changes the switches. */
    public static int revision() {
        return revision;
    }

    /** A short stable key for effect caches and logs. */
    public String key() {
        if (!corrected) return "legacy";
        return "v2:" + (filtering ? "F" : "f") + (adaptive ? "A" : "a")
                + (color ? "C" : "c") + (temporal ? "T" : "t") + (clearProfile ? "K" : "k")
                + ":" + debug.code + ":" + displacement;
    }

    @Override public boolean equals(Object other) {
        return other instanceof GlassOptics && key().equals(((GlassOptics) other).key());
    }

    @Override public int hashCode() {
        return key().hashCode();
    }

    @Override public String toString() {
        return key();
    }
}
