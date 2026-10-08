package com.waenhancer.theme;

/**
 * The corrected lens's numbers and its sampling map, in plain Java.
 *
 * <p>Two jobs. First, it is the single place the corrected renderer's uniforms are derived:
 * bevel, displacement amplitude and its cap, chromatic separation, blur sigma, the sharp/soft mix
 * and the contrast-protection target. {@link LiquidLens} writes exactly these values. Second, it is
 * a line-for-line mirror of the geometry in {@link LiquidLens}'s corrected shader
 * ({@code SHADER_V2}), so the warp can be checked on the JVM: the 2D Jacobian of the sampling map,
 * per colour channel, including corners, the selected-tab field and the sample clamp. A change to
 * either copy must be made to both; {@code LensJacobianTest} is what notices when they disagree
 * with the stability bound.</p>
 *
 * <h3>The geometry, and why it cannot fold</h3>
 *
 * <p>The legacy lens samples {@code p - n * A * (1 - s/b)^2} with {@code A = 0.62 b}: along the
 * normal the map's derivative at the outline is {@code 1 - 2A/b}, which is 0.008 on straight edges
 * and -0.24 at the rounded ends — a fold. Three more terms make it worse in 2D: the curvature bias
 * {@code mix(0.8, 1, |n.x|)} has a kink, the selected-tab gain switches on across a 1.5px feather,
 * and in a corner tighter than the bevel the inward normal flips across the corner's diagonal
 * (the shape's medial axis), so the sampled image tears there.</p>
 *
 * <p>The corrected map is {@code p + ∇s(p) · A(p) · (1 - s/b)^2}, where {@code s} is depth below the
 * outline of the <em>geometry shape</em>: the same rectangle with its corner radius raised to at
 * least the bevel. Its offset is zero before the medial axis is reached, so it is continuous
 * everywhere. Along the normal its derivative is {@code 1 - 2A/b (1 - s/b)}, bounded below by
 * {@code 1 - 2 * CAP} = 0.34 with the amplitude capped at {@link #MAX_EFFECTIVE} of the bevel after every
 * multiplier. Along the tangent, in a corner of radius {@code ρ}, it is {@code 1 - g/ρ}, which the
 * raised radius keeps above {@code 1 - CAP}. The smooth curvature bias and the smooth selected-tab
 * band add a bounded shear. Red and blue are the same profile at amplitudes {@code A ∓ Δ}, capped the
 * same way, so each channel is itself a stable lens rather than a sharp tap thrown past it.</p>
 */
public final class LensModel {

    /** Legacy: displacement as a fraction of the bevel. Folds; kept only for comparison. */
    public static final float LEGACY_DISPLACEMENT = 0.62f;
    /** Corrected default amplitude, as a fraction of the bevel, before multipliers. */
    public static final float DEFAULT_DISPLACEMENT = 0.28f;
    /** Lowest developer tuning value; below this the lens is not visible. */
    public static final float MIN_DISPLACEMENT = 0.10f;
    /**
     * Hard ceiling on the effective amplitude after every multiplier and per channel, as a
     * fraction of the bevel. Gives a normal derivative of at least {@code 1 - 2 * 0.33 = 0.34}:
     * inside the report's 0.35 bound with margin over the 0.30 acceptance target, so the measured
     * worst case is not sitting on the threshold. The default amplitude times the selected-tab
     * gain (0.28 × 1.18) reaches it exactly.
     */
    public static final float MAX_EFFECTIVE = 0.33f;
    /** Widest the bevel may be, as a fraction of the surface's shorter side. */
    public static final float MAX_BEVEL_FRACTION = 0.32f;
    /** Extra amplitude on the selected tab's outline. Legacy and corrected use the same gain. */
    public static final float ACTIVE_GAIN = 0.18f;
    /** Half-width of the selected-tab band in the corrected map, as a fraction of the bevel. */
    public static final float ACTIVE_BAND_FRACTION = 1.6f;
    /** Floor on that half-width, in px, so a tiny bevel cannot make the band a step. */
    public static final float ACTIVE_BAND_MIN_PX = 6f;

    /** Corrected chromatic separation per side, in dp, and its bounds in px. Total R-B is twice. */
    public static final float SPREAD_V2_DP = 0.25f;
    public static final float SPREAD_V2_MIN_PX = 0.25f;
    public static final float SPREAD_V2_MAX_PX = 0.75f;
    /** Legacy per-channel cap, in dp and px. */
    public static final float SPREAD_LEGACY_DP = 1.30f;
    public static final float SPREAD_LEGACY_MIN_PX = 2f;
    public static final float SPREAD_LEGACY_MAX_PX = 6f;

    /** Gaussian sigma per unit of the spec's blur radius, in dp. */
    public static final float SIGMA_DP_PER_UNIT = 0.80f;
    public static final float SIGMA_MIN_PX = 1.5f;
    public static final float SIGMA_MAX_PX = 30f;
    /** Share of the soft (filtered) backdrop at the outline; it rises to 1 a bevel inside. */
    public static final float BETA_RIM = 0.15f;
    /** Depth, as a fraction of the bevel, where the backdrop is fully soft. */
    public static final float BETA_FULL_AT = 0.65f;

    /** Contrast the protection aims for between the content colour and the glass under it. */
    public static final float CONTRAST_TARGET = 4.5f;
    public static final float CONTRAST_TARGET_CLEAR = 3.0f;
    /**
     * Most the protection may cover the backdrop. 0.85 is what white content needs over a white
     * backdrop to reach 4.5:1 against the dark protection colour (0.82), and black content over
     * black needs far less, so both polarities of the theme's content colour can reach
     * {@link #CONTRAST_TARGET} anywhere. Grey hint text cannot within this cap; see
     * {@code LensOpticsTest.protectionCoverageByForeground}.
     */
    public static final float PROTECTION_MAX = 0.85f;
    public static final float PROTECTION_MAX_CLEAR = 0.85f;
    /** Where in the bevel protection starts and is complete: the body, where the controls are. */
    public static final float PROTECTION_FROM = 0.30f;
    public static final float PROTECTION_TO = 0.80f;

    /** Corrected saturation ceiling at full lens strength (legacy 1.55). */
    public static final float SATURATION_V2 = 1.10f;
    public static final float SATURATION_LEGACY = 1.55f;

    private LensModel() { }

    /** Everything one surface's geometry needs, as the shader receives it. */
    public static final class Surface {
        public final float width, height, radius, bevel;
        /** Corner radius of the geometry shape: at least the bevel, at most half the short side. */
        public final float geoRadius;
        /** Base amplitude in px before curvature and selected-tab multipliers. */
        public final float amp;
        /** Hard amplitude cap in px. */
        public final float cap;
        /** Chromatic amplitude offset in px; red is {@code A - delta}, blue {@code A + delta}. */
        public final float delta;
        /** Legacy per-channel spread cap and dispersion, for the uncorrected map. */
        public final float legacySpread, dispersion;
        /** Legacy refraction amount, {@code lens * 0.62 * bevel}. */
        public final float legacyRefract;
        /** Sample clamp: [lo, size - lo]. */
        public final float lo;
        public final float activeX, activeY, activeHalfW, activeHalfH, activeRadius, active;

        public Surface(float width, float height, float radius, float bevel, float amp, float cap,
                       float delta, float legacySpread, float dispersion, float legacyRefract,
                       float lo, float activeX, float activeY, float activeHalfW, float activeHalfH,
                       float activeRadius, float active) {
            this.width = width;
            this.height = height;
            this.radius = Math.min(radius, Math.min(width, height) / 2f);
            this.bevel = bevel;
            this.geoRadius = geometryRadius(this.radius, bevel, width, height);
            this.amp = amp;
            this.cap = cap;
            this.delta = delta;
            this.legacySpread = legacySpread;
            this.dispersion = dispersion;
            this.legacyRefract = legacyRefract;
            this.lo = lo;
            this.activeX = activeX;
            this.activeY = activeY;
            this.activeHalfW = activeHalfW;
            this.activeHalfH = activeHalfH;
            this.activeRadius = activeRadius;
            this.active = active;
        }

        /** The same surface with a selected-tab region. */
        public Surface withActive(float x, float y, float halfW, float halfH, float r, float on) {
            return new Surface(width, height, radius, bevel, amp, cap, delta, legacySpread,
                    dispersion, legacyRefract, lo, x, y, halfW, halfH, r, on);
        }
    }

    /** Bevel width in px. The corrected map bounds it by the shorter side, legacy by height only. */
    public static float bevel(GlassSpec spec, float width, float height, float density, boolean corrected) {
        float side = corrected ? Math.min(width, height) : height;
        return Math.max(1f, Math.min(spec.rimWidthDp * density, side * MAX_BEVEL_FRACTION));
    }

    /** Corner radius of the geometry shape. */
    public static float geometryRadius(float radius, float bevel, float width, float height) {
        float half = Math.min(width, height) / 2f;
        return Math.min(Math.max(radius, bevel), half);
    }

    /** Builds the surface the corrected shader receives for {@code spec}. */
    public static Surface surface(GlassSpec spec, float width, float height, float radius,
                                  float density, boolean geometry, boolean color, float displacement) {
        float bevel = bevel(spec, width, height, density, geometry);
        float amp = spec.lensStrength * displacement * bevel;
        float cap = MAX_EFFECTIVE * bevel;
        float spreadPx = color ? clamp(SPREAD_V2_DP * density, SPREAD_V2_MIN_PX, SPREAD_V2_MAX_PX)
                : clamp(SPREAD_LEGACY_DP * density, SPREAD_LEGACY_MIN_PX, SPREAD_LEGACY_MAX_PX);
        float delta = Math.min(spec.dispersion * amp, spreadPx);
        float legacyRefract = spec.lensStrength * LEGACY_DISPLACEMENT * bevel(spec, width, height, density, false);
        return new Surface(width, height, radius, bevel, amp, cap, delta, spreadPx,
                spec.dispersion, legacyRefract, geometry ? 0.5f : 1f, 0f, 0f, 0f, 0f, 0f, 0f);
    }

    // ---- signed distance ---------------------------------------------------------------------

    /** Rounded-rectangle SDF centred on the origin: negative inside. Mirrors the shader. */
    public static float sdRoundRect(float px, float py, float hx, float hy, float r) {
        float qx = Math.abs(px) - hx + r;
        float qy = Math.abs(py) - hy + r;
        float ox = Math.max(qx, 0f), oy = Math.max(qy, 0f);
        return (float) Math.sqrt(ox * ox + oy * oy) + Math.min(Math.max(qx, qy), 0f) - r;
    }

    /**
     * Analytic outward gradient of {@link #sdRoundRect}. Continuous outside and in the corner
     * arcs; it switches between the axes only on the medial axis, deeper than the radius.
     */
    public static void sdGradient(float px, float py, float hx, float hy, float r, float[] out) {
        float qx = Math.abs(px) - hx + r;
        float qy = Math.abs(py) - hy + r;
        float sx = px < 0f ? -1f : 1f, sy = py < 0f ? -1f : 1f;
        if (qx > 0f && qy > 0f) {
            float len = (float) Math.sqrt(qx * qx + qy * qy);
            out[0] = sx * qx / len;
            out[1] = sy * qy / len;
        } else if (qx > qy) {
            out[0] = sx;
            out[1] = 0f;
        } else {
            out[0] = 0f;
            out[1] = sy;
        }
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    // ---- the sampling map --------------------------------------------------------------------

    /**
     * Where the shader samples the backdrop for the output pixel at {@code (x, y)}, in surface
     * pixels, for red, green and blue: {@code out = {rx, ry, gx, gy, bx, by}}.
     *
     * @param corrected the corrected map; false reproduces the legacy one
     */
    public static void sample(Surface s, float x, float y, boolean corrected, float[] out) {
        if (corrected) sampleCorrected(s, x, y, out);
        else sampleLegacy(s, x, y, out);
    }

    private static void sampleCorrected(Surface s, float x, float y, float[] out) {
        float hx = s.width / 2f, hy = s.height / 2f;
        float px = x - hx, py = y - hy;
        float[] n = new float[2];
        float dg = sdRoundRect(px, py, hx, hy, s.geoRadius);
        sdGradient(px, py, hx, hy, s.geoRadius, n);
        float depth = Math.max(-dg, 0f);
        float t = clamp(depth / Math.max(s.bevel, 1f), 0f, 1f);
        float e = 1f - t;
        float slope = e * e;
        // Smooth curvature bias: n.x^2 rather than |n.x|, so it has no kink.
        float curvature = 0.80f + 0.20f * n[0] * n[0];
        // Smooth band on the selected tab's outline, symmetric about it and C1 at the outline.
        float band = 0f;
        if (s.active > 0f) {
            float ad = sdRoundRect(x - s.activeX, y - s.activeY, s.activeHalfW, s.activeHalfH,
                    Math.min(s.activeRadius, Math.min(s.activeHalfW, s.activeHalfH)));
            float w = Math.max(s.bevel * ACTIVE_BAND_FRACTION, ACTIVE_BAND_MIN_PX);
            band = s.active * (1f - smoothstep(0f, w, Math.abs(ad)));
        }
        float a = Math.min(s.amp * curvature * (1f + ACTIVE_GAIN * band), s.cap);
        float aR = Math.max(a - s.delta, 0f);
        float aB = Math.min(a + s.delta, s.cap);
        // Inward is -n.
        float lo = s.lo;
        out[0] = clamp(x - n[0] * aR * slope, lo, s.width - lo);
        out[1] = clamp(y - n[1] * aR * slope, lo, s.height - lo);
        out[2] = clamp(x - n[0] * a * slope, lo, s.width - lo);
        out[3] = clamp(y - n[1] * a * slope, lo, s.height - lo);
        out[4] = clamp(x - n[0] * aB * slope, lo, s.width - lo);
        out[5] = clamp(y - n[1] * aB * slope, lo, s.height - lo);
    }

    /** The legacy map, as {@link LiquidLens}'s first shader computes it. */
    private static void sampleLegacy(Surface s, float x, float y, float[] out) {
        float hx = s.width / 2f, hy = s.height / 2f;
        float px = x - hx, py = y - hy;
        float r = s.radius;
        // Central-difference normal, as the legacy shader does.
        float nx = sdRoundRect(px + 1f, py, hx, hy, r) - sdRoundRect(px - 1f, py, hx, hy, r);
        float ny = sdRoundRect(px, py + 1f, hx, hy, r) - sdRoundRect(px, py - 1f, hx, hy, r);
        float len = (float) Math.sqrt(nx * nx + ny * ny);
        if (len > 0.0001f) { nx /= len; ny /= len; } else { nx = 0f; ny = -1f; }
        float d = sdRoundRect(px, py, hx, hy, r);
        float t = clamp(-d / Math.max(s.bevel, 1f), 0f, 1f);
        float e = 1f - t;
        float slope = e * e;
        float activeEdge = 0f;
        if (s.active > 0f) {
            float ad = sdRoundRect(x - s.activeX, y - s.activeY, s.activeHalfW, s.activeHalfH, s.activeRadius);
            float activeCov = s.active * clamp(0.5f - ad / 1.5f, 0f, 1f);
            activeEdge = activeCov * clamp(1f + ad / Math.max(s.bevel * 0.45f, 1f), 0f, 1f);
        }
        float curvature = 0.80f + 0.20f * Math.abs(nx);
        float k = slope * s.legacyRefract * curvature * (1f + activeEdge * 0.18f);
        float ox = -nx * k, oy = -ny * k;
        float spx = ox * s.dispersion * slope, spy = oy * s.dispersion * slope;
        float sl = (float) Math.sqrt(spx * spx + spy * spy);
        if (sl > s.legacySpread) {
            spx *= s.legacySpread / Math.max(sl, 0.0001f);
            spy *= s.legacySpread / Math.max(sl, 0.0001f);
        }
        float lo = 1f;
        out[0] = clamp(x + ox - spx, lo, s.width - lo);
        out[1] = clamp(y + oy - spy, lo, s.height - lo);
        out[2] = clamp(x + ox, lo, s.width - lo);
        out[3] = clamp(y + oy, lo, s.height - lo);
        out[4] = clamp(x + ox + spx, lo, s.width - lo);
        out[5] = clamp(y + oy + spy, lo, s.height - lo);
    }

    /** Coverage of the real outline at a pixel, as the shader computes it. */
    public static float coverage(Surface s, float x, float y) {
        float d = sdRoundRect(x - s.width / 2f, y - s.height / 2f, s.width / 2f, s.height / 2f, s.radius);
        return clamp(0.5f - d / 1.5f, 0f, 1f);
    }

    // ---- filtering ---------------------------------------------------------------------------

    /** Gaussian sigma of the soft backdrop, in px. */
    public static float sigmaPx(GlassSpec spec, float density) {
        if (spec.blurRadius <= 0f) return 0f;
        return clamp(spec.blurRadius * SIGMA_DP_PER_UNIT * density, SIGMA_MIN_PX, SIGMA_MAX_PX);
    }

    /** Amplitude response of a Gaussian of {@code sigma} px at {@code cyclesPerPx}. */
    public static double gaussianResponse(double sigma, double cyclesPerPx) {
        return Math.exp(-2d * Math.PI * Math.PI * sigma * sigma * cyclesPerPx * cyclesPerPx);
    }

    /** Smallest sigma that attenuates {@code cyclesPerPx} to {@code residual}. */
    public static double sigmaFor(double cyclesPerPx, double residual) {
        return Math.sqrt(-Math.log(residual) / (2d * Math.PI * Math.PI)) / cyclesPerPx;
    }

    /** Padding the recording needs around the surface so the blur has real pixels at the rim. */
    public static int padding(float sigmaPx) {
        return sigmaPx <= 0f ? 0 : (int) Math.ceil(3f * sigmaPx) + 2;
    }

    /** Share of the soft backdrop at depth fraction {@code t} into the bevel. */
    public static float beta(float t) {
        return BETA_RIM + (1f - BETA_RIM) * smoothstep(0f, BETA_FULL_AT, t);
    }

    /** sharp/soft mix: {@code (1-β)·sharp + β·soft}, which is how the two passes composite. */
    public static float mix(float sharp, float soft, float beta) {
        return sharp * (1f - beta) + soft * beta;
    }

    /**
     * The two-pass output exactly as the effect graph forms it, premultiplied: each pass returns
     * {@code (colour·γ·w, γ·w)} with its own weight w (soft β, sharp 1-β) and the passes are added
     * ({@code BlendMode.PLUS}). Returns {@code {r, a}} for one channel.
     */
    public static float[] composite(float coverage, float beta, float sharp, float soft) {
        float wSharp = 1f - beta, wSoft = beta;
        if (wSharp < 0.0001f) wSharp = 0f;
        float a = coverage * wSharp + coverage * wSoft;
        float c = sharp * coverage * wSharp + soft * coverage * wSoft;
        return new float[]{c, a};
    }

    /** The same passes composited source-over, as the first version of this graph did. */
    public static float[] compositeSourceOver(float coverage, float beta, float sharp, float soft) {
        float as = coverage * (1f - beta), cs = sharp * as;
        float af = coverage, cf = soft * af;
        return new float[]{cs + cf * (1f - as), as + af * (1f - as)};
    }

    // ---- colour and contrast -----------------------------------------------------------------

    /** sRGB transfer, encoded to linear. */
    public static double toLinear(double c) {
        return c <= 0.04045d ? c / 12.92d : Math.pow((c + 0.055d) / 1.055d, 2.4d);
    }

    /** sRGB transfer, linear to encoded. */
    public static double fromLinear(double c) {
        return c <= 0.0031308d ? c * 12.92d : 1.055d * Math.pow(c, 1d / 2.4d) - 0.055d;
    }

    /** Relative luminance of a linear RGB triple. */
    public static double luminance(double r, double g, double b) {
        return 0.2126d * r + 0.7152d * g + 0.0722d * b;
    }

    /** WCAG contrast ratio of two relative luminances. */
    public static double contrast(double l1, double l2) {
        double hi = Math.max(l1, l2), lo = Math.min(l1, l2);
        return (hi + 0.05d) / (lo + 0.05d);
    }

    /**
     * How far to mix the glass toward the protection colour so the content colour clears
     * {@code target}, capped at {@code max}. Mixing in linear light is linear in luminance, so the
     * required share has a closed form; the shader computes the same expression.
     *
     * @param glass   luminance of the glass under the content before protection
     * @param content luminance of the content colour
     * @param protect luminance of the protection colour
     */
    public static double protection(double glass, double content, double protect, double target, double max) {
        double needed;
        if (content >= protect) {
            // Light content: the glass must be at most this bright.
            double limit = (content + 0.05d) / target - 0.05d;
            if (glass <= limit) return 0d;
            needed = (glass - limit) / Math.max(glass - protect, 1e-6);
        } else {
            double limit = target * (content + 0.05d) - 0.05d;
            if (glass >= limit) return 0d;
            needed = (limit - glass) / Math.max(protect - glass, 1e-6);
        }
        return Math.max(0d, Math.min(max, needed));
    }

    /** Protection weight across the bevel: none at the rim, full in the body. */
    public static float protectionWeight(float t) {
        return smoothstep(PROTECTION_FROM, PROTECTION_TO, t);
    }

    /** Saturation at full lens strength for the current colour mode. */
    public static float saturation(GlassSpec spec, boolean color) {
        float max = color ? SATURATION_V2 : SATURATION_LEGACY;
        return 1f + (max - 1f) * spec.lensStrength;
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
