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
        STABLE(1.00f, 14f, 0.00f, 0.00f, 0.6f, 1.00f, 35),
        /** The default Advanced Glass: real depth, a specular top edge, a soft bottom glow. */
        ADVANCED(0.86f, 18f, 0.55f, 0.35f, 0.8f, 1.15f, 30),
        /**
         * Liquid glass: very little fill, a light blur, and most of the effect carried by the
         * lit edge. Blurring it heavily is what turns liquid glass into frost, so it does not.
         */
        LIQUID(0.45f, 8f, 0.95f, 0.85f, 1.4f, 2.10f, 18),
        /** Dense and diffuse: the most legible variant over busy or high-contrast backdrops. */
        FROST(1.18f, 25f, 0.30f, 0.15f, 0.6f, 0.90f, 55),
        /** Almost only a border. Highest backdrop fidelity, lowest legibility guarantee. */
        CLEAR(0.30f, 4f, 0.85f, 0.60f, 1.6f, 2.40f, 12);

        final float fillScale;
        final float blurRadius;
        final float highlightStrength;
        final float refractionStrength;
        final float strokeWidthDp;
        final float edgeStrength;
        final int recommendedOpacityPercent;

        Variant(float fillScale, float blurRadius, float highlightStrength,
                float refractionStrength, float strokeWidthDp, float edgeStrength,
                int recommendedOpacityPercent) {
            this.fillScale = fillScale;
            this.blurRadius = blurRadius;
            this.highlightStrength = highlightStrength;
            this.refractionStrength = refractionStrength;
            this.strokeWidthDp = strokeWidthDp;
            this.edgeStrength = edgeStrength;
            this.recommendedOpacityPercent = recommendedOpacityPercent;
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

    private GlassSpec(int fillColor, float blurRadius, int strokeColor, float strokeWidthDp,
                      int highlightColor, int refractionColor, int contentColor,
                      boolean animate, boolean usingFallback) {
        this.fillColor = fillColor;
        this.blurRadius = blurRadius;
        this.strokeColor = strokeColor;
        this.strokeWidthDp = strokeWidthDp;
        this.highlightColor = highlightColor;
        this.refractionColor = refractionColor;
        this.contentColor = contentColor;
        this.animate = animate;
        this.usingFallback = usingFallback;
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

        return new GlassSpec(
                fill,
                blurSupported ? resolved.blurRadius : 0f,
                stroke,
                resolved.strokeWidthDp,
                highlight,
                refraction,
                content,
                !reduceMotion,
                !blurSupported);
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
