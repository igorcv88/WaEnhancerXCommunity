package com.waenhancer.theme;

/**
 * Resolved appearance of one glass surface.
 *
 * <p>This is the whole of the Advanced Glass engine's decision making, and it is deliberately
 * free of any Android view type: it takes the variant, the theme and the device's capabilities
 * and returns colours and sizes. Whoever paints the surface — the hooked bottom bar in the
 * WhatsApp process, a dialog, a card, or the settings preview — resolves a spec here first, so
 * a preview and the real surface cannot disagree about what glass looks like.</p>
 *
 * <p>Contrast, blending and luminance come from {@link SemanticTheme}; this class does not carry
 * a second copy of that arithmetic.</p>
 */
public final class GlassSpec {

    /**
     * Named glass treatments. {@link #STABLE} reproduces the flat overlay the bottom bar shipped
     * with, so enabling the engine does not silently restyle anyone's bar; the rest are the
     * Advanced Glass family and differ in how much of the backdrop survives.
     */
    public enum Variant {
        /** Flat translucent fill and a hairline border. The pre-engine look. */
        STABLE(1.00f, 14f, 0.00f, 0.00f, 0.6f, 1.00f, 35, 0.00f, 0f, 0.00f, 0.00f, 0.00f, false, false),
        /** The default Advanced Glass: real depth, a specular top edge, a soft bottom glow. */
        ADVANCED(0.86f, 18f, 0.55f, 0.35f, 0.8f, 1.15f, 30, 0.18f, 10f, 0.10f, 0.50f, 0.30f, false, false),
        /**
         * Liquid glass. Not a thinner frost: the effect is carried by what the surface does to
         * the light behind it rather than by how much of it the surface hides.
         *
         * <p>A low uniform blur so the backdrop survives, a wide rim that bends and concentrates
         * that backdrop as it approaches the edge, a fill that retints itself from whatever is
         * actually behind the bar, and a body that morphs under the finger. Every one of those is
         * a property the previous definition lacked, which is why "less blur than frost" still
         * read as frost.</p>
         */
        LIQUID(0.18f, 6f, 0.85f, 0.90f, 1.2f, 2.30f, 14, 1.00f, 22f, 0.42f, 0.75f, 0.31f, true, true),
        /** Dense and diffuse: the most legible variant over busy or high-contrast backdrops. */
        FROST(1.18f, 25f, 0.30f, 0.15f, 0.6f, 0.90f, 55, 0.00f, 0f, 0.00f, 0.00f, 0.00f, false, false),
        /** Almost only a border. Highest backdrop fidelity, lowest legibility guarantee. */
        CLEAR(0.30f, 4f, 0.85f, 0.60f, 1.6f, 2.40f, 12, 0.55f, 16f, 0.30f, 0.85f, 0.35f, true, false);

        final float fillScale;
        final float blurRadius;
        final float highlightStrength;
        final float refractionStrength;
        final float strokeWidthDp;
        final float edgeStrength;
        final int recommendedOpacityPercent;
        final float lensStrength;
        final float rimWidthDp;
        final float dispersion;
        final float specular;
        final float innerShadow;
        final boolean adaptive;
        final boolean morphing;

        Variant(float fillScale, float blurRadius, float highlightStrength,
                float refractionStrength, float strokeWidthDp, float edgeStrength,
                int recommendedOpacityPercent, float lensStrength, float rimWidthDp,
                float dispersion, float specular, float innerShadow,
                boolean adaptive, boolean morphing) {
            this.fillScale = fillScale;
            this.blurRadius = blurRadius;
            this.highlightStrength = highlightStrength;
            this.refractionStrength = refractionStrength;
            this.strokeWidthDp = strokeWidthDp;
            this.edgeStrength = edgeStrength;
            this.recommendedOpacityPercent = recommendedOpacityPercent;
            this.lensStrength = lensStrength;
            this.rimWidthDp = rimWidthDp;
            this.dispersion = dispersion;
            this.specular = specular;
            this.innerShadow = innerShadow;
            this.adaptive = adaptive;
            this.morphing = morphing;
        }

        /**
         * The opacity this treatment is designed around, as a percentage.
         *
         * <p>Opacity and variant are not independent: a "liquid" pane at 100% opacity is just an
         * opaque bar with a lit edge, and a "frost" pane at 10% is not frost at all. The editor
         * moves the opacity slider to this value when the style changes, so picking a style
         * lands on a look that matches its name — and the slider stays free afterwards.</p>
         */
        public int recommendedOpacityPercent() {
            return recommendedOpacityPercent;
        }

        /** Parses a stored preference value, falling back to {@link #ADVANCED}. */
        public static Variant from(String value) {
            if (value == null) return ADVANCED;
            for (Variant variant : values()) {
                if (variant.name().equalsIgnoreCase(value.trim())) return variant;
            }
            return ADVANCED;
        }

        /** The value persisted in preferences. */
        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * Minimum contrast the engine guarantees between a glass surface and the content drawn on
     * it. Glass is a large surface behind icons and labels rather than body text, so this is the
     * WCAG large-text and non-text-contrast figure rather than the 4.5:1 body-text one.
     */
    public static final double MIN_CONTENT_CONTRAST = 3.0d;

    /** Opacity floor applied when the device cannot blur; see {@link #resolve}. */
    private static final float NO_BLUR_MIN_OPACITY = 0.72f;

    /** How far an adaptive fill is pulled toward its backdrop's colour. See {@link #adaptTo}. */
    private static final float ADAPTIVE_TINT_WEIGHT = 0.55f;

    /** Widest swing the adaptive edge may take from its resolved alpha, either way. */
    private static final float ADAPTIVE_EDGE_RANGE = 0.30f;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLACK = 0xFF000000;
    private static final int DARK_SURFACE = 0xFF1F2C34;

    /** Overlay painted on top of the blurred backdrop. Already includes its alpha. */
    public final int fillColor;
    /**
     * Backdrop blur strength, or {@code 0} when this device gets the no-blur fallback.
     *
     * <p>Expressed in the blur library's own 1-25 scale rather than in dp. The library blurs a
     * downscaled copy of the backdrop, so its radius is already resolution-independent, and
     * anything above 25 is clamped by the RenderScript backend. Treating this as dp and
     * multiplying by density is what previously collapsed four of the five variants onto the
     * same clamped radius, which is why they all looked alike.</p>
     */
    public final float blurRadius;
    /** Luminous edge. */
    public final int strokeColor;
    public final float strokeWidthDp;
    /** Specular highlight along the top edge; transparent when the variant has none. */
    public final int highlightColor;
    /** Simulated refraction glow along the bottom edge; transparent when unused. */
    public final int refractionColor;
    /** Icon and label colour that clears {@link #MIN_CONTENT_CONTRAST} against {@link #fillColor}. */
    public final int contentColor;
    /** Whether the surface may animate. False whenever motion is reduced. */
    public final boolean animate;
    /** True when the surface is standing in for blur it could not get. */
    public final boolean usingFallback;
    /**
     * How strongly the rim bends the backdrop behind it, 0-1.
     *
     * <p>This is the property that separates liquid glass from frost. Frost hides the backdrop
     * uniformly; a lens leaves the middle almost untouched and does its work at the edge, where
     * the backdrop is displaced inward and the light it carries is concentrated. Zero on the
     * variants that are meant to read as a flat pane.</p>
     */
    public final float lensStrength;
    /** Width of the lensed band inside the edge, in dp. Zero when there is no lensing. */
    public final float rimWidthDp;
    /**
     * How far the three colour channels are displaced apart inside the rim, 0-1.
     *
     * <p>Real glass has a different refractive index per wavelength, so the band it compresses at
     * its edge fringes into colour. Displacing all three channels identically is what made the
     * previous lens read as a smear rather than as an edge.</p>
     */
    public final float dispersion;
    /** Strength of the rim highlight and the gloss the light source puts on the bevel, 0-1. */
    public final float specular;
    /** Depth of the shadow inside the backlit edge, 0-1. What makes the surface read as thick. */
    public final float innerShadow;
    /** Whether the fill retints itself from the backdrop actually behind the surface. */
    public final boolean adaptive;
    /** Whether the surface may morph and stretch under interaction. */
    public final boolean morphing;

    private GlassSpec(int fillColor, float blurRadius, int strokeColor, float strokeWidthDp,
                      int highlightColor, int refractionColor, int contentColor,
                      boolean animate, boolean usingFallback,
                      float lensStrength, float rimWidthDp, float dispersion, float specular,
                      float innerShadow, boolean adaptive, boolean morphing) {
        this.fillColor = fillColor;
        this.blurRadius = blurRadius;
        this.strokeColor = strokeColor;
        this.strokeWidthDp = strokeWidthDp;
        this.highlightColor = highlightColor;
        this.refractionColor = refractionColor;
        this.contentColor = contentColor;
        this.animate = animate;
        this.usingFallback = usingFallback;
        this.lensStrength = lensStrength;
        this.rimWidthDp = rimWidthDp;
        this.dispersion = dispersion;
        this.specular = specular;
        this.innerShadow = innerShadow;
        this.adaptive = adaptive;
        this.morphing = morphing;
    }

    /**
     * Resolves one glass surface.
     *
     * @param variant        which treatment to apply
     * @param dark           whether the host is in night mode
     * @param userFillColor  the user's explicit tint, or {@code 0} to derive one from the theme
     * @param accentColor    the theme accent, used for the refraction glow; {@code 0} to skip it
     * @param opacityPercent the user's opacity setting, 0-100, before the variant scales it
     * @param blurSupported  false when the device cannot blur and must get the fallback
     * @param reduceMotion   true when the system asks for reduced motion
     */
    public static GlassSpec resolve(Variant variant, boolean dark, int userFillColor,
                                    int accentColor, float opacityPercent,
                                    boolean blurSupported, boolean reduceMotion) {
        Variant resolved = variant == null ? Variant.ADVANCED : variant;

        int baseRgb = (userFillColor != 0 ? userFillColor : (dark ? DARK_SURFACE : WHITE))
                & 0x00FFFFFF;

        float requested = clamp(opacityPercent / 100f, 0f, 1f) * resolved.fillScale;
        // Without blur there is nothing behind the surface to separate it from the content, so
        // the fill has to carry that separation alone. Raising the floor here is what keeps the
        // fallback legible instead of merely transparent.
        float alpha = blurSupported ? clamp(requested, 0f, 1f)
                : clamp(Math.max(requested, NO_BLUR_MIN_OPACITY), 0f, 1f);

        int fill = (Math.round(alpha * 255f) << 24) | baseRgb;

        // Contrast is judged against what the surface actually looks like once composited over
        // its own backdrop, not against the translucent fill in isolation.
        int composited = SemanticTheme.blend(dark ? BLACK : WHITE, 0xFF000000 | baseRgb, alpha);
        int content = SemanticTheme.ensureTextContrast(
                SemanticTheme.bestTextColor(composited), composited, MIN_CONTENT_CONTRAST);

        int highlight = resolved.highlightStrength <= 0f ? 0
                : SemanticTheme.withAlpha(WHITE, resolved.highlightStrength * (dark ? 0.22f : 0.55f));

        int refraction;
        if (resolved.refractionStrength <= 0f) {
            refraction = 0;
        } else {
            int glow = accentColor != 0 ? accentColor : (dark ? WHITE : BLACK);
            refraction = SemanticTheme.withAlpha(glow,
                    resolved.refractionStrength * (dark ? 0.18f : 0.12f));
        }

        // The lit edge. On the transparent variants it is most of what the user actually sees,
        // so it scales with the variant instead of being one hairline shared by all five.
        int stroke = SemanticTheme.withAlpha(dark ? WHITE : BLACK,
                clamp((dark ? 0.16f : 0.14f) * resolved.edgeStrength, 0f, 0.85f));

        // Lensing displaces the blurred backdrop. Where there is no blur there is nothing to
        // displace, so the fallback drops the lens rather than warping a flat fill.
        float lens = blurSupported ? resolved.lensStrength : 0f;

        return new GlassSpec(
                fill,
                blurSupported ? resolved.blurRadius : 0f,
                stroke,
                resolved.strokeWidthDp,
                highlight,
                refraction,
                content,
                !reduceMotion,
                !blurSupported,
                lens,
                lens <= 0f ? 0f : resolved.rimWidthDp,
                lens <= 0f ? 0f : resolved.dispersion,
                lens <= 0f ? 0f : resolved.specular,
                lens <= 0f ? 0f : resolved.innerShadow,
                resolved.adaptive && blurSupported,
                resolved.morphing && !reduceMotion);
    }

    /**
     * This surface as it looks over a particular backdrop.
     *
     * <p>An adaptive surface is not a fixed colour. Real glass takes on the light around it, so
     * the fill is pulled a short way toward the backdrop's own hue, the edge brightens over a
     * dark backdrop and darkens over a light one, and the content colour is re-derived so it
     * still clears {@link #MIN_CONTENT_CONTRAST} against whatever the fill just became — the
     * point of adapting is lost if the labels stop being readable in the process.</p>
     *
     * <p>Returns {@code this} unchanged for the variants that are not adaptive, so a caller may
     * hand every surface its backdrop without asking which ones care.</p>
     *
     * @param backdropColor the average colour actually behind the surface
     * @param dark          whether the host is in night mode
     */
    public GlassSpec adaptTo(int backdropColor, boolean dark) {
        if (!adaptive || (backdropColor >>> 24) == 0) return this;

        int fillAlpha = (fillColor >>> 24) & 0xFF;
        // A short pull only. Taking the backdrop's colour outright would make the bar disappear
        // into it, which is camouflage rather than glass.
        int tintedRgb = SemanticTheme.blend(fillColor, backdropColor, ADAPTIVE_TINT_WEIGHT)
                & 0x00FFFFFF;
        int adaptedFill = (fillAlpha << 24) | tintedRgb;

        double backdropLuminance = SemanticTheme.relativeLuminance(backdropColor);
        // Bright backdrop: the edge has to darken to stay visible. Dark backdrop: it lights up.
        float edgeShift = (float) (0.5d - backdropLuminance) * ADAPTIVE_EDGE_RANGE;
        int adaptedStroke = SemanticTheme.withAlpha(
                backdropLuminance > 0.5d ? BLACK : WHITE,
                clamp(baseEdgeAlpha() + edgeShift, 0.05f, 0.85f));

        int composited = SemanticTheme.blend(backdropColor, 0xFF000000 | tintedRgb,
                fillAlpha / 255f);
        int adaptedContent = SemanticTheme.ensureTextContrast(
                SemanticTheme.bestTextColor(composited), composited, MIN_CONTENT_CONTRAST);

        return new GlassSpec(adaptedFill, blurRadius, adaptedStroke, strokeWidthDp,
                highlightColor, refractionColor, adaptedContent, animate, usingFallback,
                lensStrength, rimWidthDp, dispersion, specular, innerShadow, adaptive, morphing);
    }

    private float baseEdgeAlpha() {
        return ((strokeColor >>> 24) & 0xFF) / 255f;
    }

    /**
     * How many layers a renderer has to draw for this surface, and therefore how much it
     * overdraws.
     *
     * <p>Overdraw is the cost that decides whether glass is affordable, so it is decided here,
     * as a property of the description, rather than emerging from whatever the renderer happens
     * to build. It also makes the guarantee testable without a device: {@code STABLE} is one
     * layer — the same as the flat background it replaces — and no variant exceeds three.</p>
     */
    public int layerCount() {
        int layers = 1;
        if ((highlightColor >>> 24) != 0) layers++;
        if ((refractionColor >>> 24) != 0) layers++;
        return layers;
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }
}
