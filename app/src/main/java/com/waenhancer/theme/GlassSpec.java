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
        STABLE(1.00f, 18f, 0.00f, 0.00f),
        /** The default Advanced Glass: real depth, a specular top edge, a soft bottom glow. */
        ADVANCED(0.86f, 24f, 0.55f, 0.35f),
        /** Thinner fill and a heavier blur, so the backdrop reads through as motion. */
        LIQUID(0.62f, 34f, 0.75f, 0.55f),
        /** Dense and diffuse: the most legible variant over busy or high-contrast backdrops. */
        FROST(1.18f, 40f, 0.30f, 0.15f),
        /** Almost only a border. Highest backdrop fidelity, lowest legibility guarantee. */
        CLEAR(0.38f, 16f, 0.85f, 0.60f);

        final float fillScale;
        final float blurRadiusDp;
        final float highlightStrength;
        final float refractionStrength;

        Variant(float fillScale, float blurRadiusDp, float highlightStrength,
                float refractionStrength) {
            this.fillScale = fillScale;
            this.blurRadiusDp = blurRadiusDp;
            this.highlightStrength = highlightStrength;
            this.refractionStrength = refractionStrength;
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
    /** Blur radius in dp, or {@code 0} when this device gets the no-blur fallback. */
    public final float blurRadiusDp;
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

    private GlassSpec(int fillColor, float blurRadiusDp, int strokeColor, float strokeWidthDp,
                      int highlightColor, int refractionColor, int contentColor,
                      boolean animate, boolean usingFallback) {
        this.fillColor = fillColor;
        this.blurRadiusDp = blurRadiusDp;
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

        int stroke = SemanticTheme.withAlpha(dark ? WHITE : BLACK, dark ? 0.16f : 0.14f);

        return new GlassSpec(
                fill,
                blurSupported ? resolved.blurRadiusDp : 0f,
                stroke,
                0.6f,
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
