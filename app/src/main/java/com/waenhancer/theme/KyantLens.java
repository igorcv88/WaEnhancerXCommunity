package com.waenhancer.theme;

import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.util.Log;

/**
 * The OneMate glass material: the optics of Kyant0's AndroidLiquidGlass as mirrored by the
 * compose-miuix-ui example that OneMate ships, rebuilt on plain {@link RenderEffect}s.
 *
 * <p>The whole material is four steps, in this order, and that order is most of why it looks
 * right where the classic renderer does not:</p>
 * <ol>
 *   <li><b>Vibrancy.</b> Unmodified saturation on the backdrop, before anything else touches it.</li>
 *   <li><b>Backdrop blur.</b> 10dp (miuix maps a radius to sigma by 0.45). Enough to take the
 *       detail out of text behind the glass, little enough that shapes and colour survive.</li>
 *   <li><b>An edge lens.</b> Only a band {@code refractionHeight} deep inside the outline bends
 *       the backdrop, by a circle profile ({@code 1 - sqrt(1 - x²)}): zero in the body, steep at
 *       the rim. Inward only, so it never samples outside the recording.</li>
 *   <li><b>Surface and highlight.</b> A neutral 18% wash over the result, then a soft
 *       persistent grazing-edge reflection joined to a soft directional bevel.</li>
 * </ol>
 *
 * <p>Steps 1-2 are platform effects; 3-4 are one AGSL pass. The press-state bubble of the bottom
 * bar uses the same pass with depth and dispersion switched on and an inner shadow.</p>
 *
 * <p>Ported from Apache-2.0 sources, never from OneMate's own files (which carry no licence):
 * the lens shader from Kyant0/AndroidLiquidGlass
 * ({@code 65ab177e90e5c1d8c62e70cf7755841982da65f6}) and its compose-miuix-ui mirror, and the
 * bloom stroke from compose-miuix-ui/miuix {@code miuix-blur}
 * ({@code 6ef604ae6d53e4b241cdb89ccbf2caf86a7a33d0}). See NOTICE.</p>
 */
public final class KyantLens {

    private static final String TAG = "WaEnhancerX/KyantLens";

    /** miuix-blur's radius-to-sigma factor ({@code BLUR_RADIUS_TO_SIGMA}). */
    static final float RADIUS_TO_SIGMA = 0.45f;
    /** Android's {@code createBlurEffect} radius maps to sigma by Skia's 0.57735·r + 0.5. */
    private static final float SKIA_RADIUS_SCALE = 0.57735f;

    /** Set once a device refuses the program; surfaces then keep the classic renderer. */
    private static volatile boolean broken;

    static final String SHADER = ""
            + "uniform shader content;\n"
            + "uniform float2 uSize;\n"
            + "uniform float2 uOrigin;\n"
            + "uniform float uRadius;\n"
            + "uniform float uRefractH;\n"
            + "uniform float uRefractA;\n"
            + "uniform float uDepth;\n"
            + "uniform float uChroma;\n"
            + "uniform float4 uTint;\n"
            + "uniform float uStrokeW;\n"
            + "uniform float uBloom;\n"
            + "uniform float uHighlight;\n"
            + "uniform float uStrokeA;\n"
            + "uniform float uContourGain;\n"
            + "uniform float3 uLight1;\n"
            + "uniform float uLight1I;\n"
            + "uniform float3 uLight2;\n"
            + "uniform float uLight2I;\n"
            + "uniform float uShadowR;\n"
            + "uniform float uShadowA;\n"
            + "\n"
            + "float sdRR(float2 p, float2 b, float r) {\n"
            + "    float2 q = abs(p) - b + r;\n"
            + "    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;\n"
            + "}\n"
            + "\n"
            // Kyant's gradient of the rounded box: radial in the corners, axis-aligned along the
            // straight runs. Evaluated with a larger radius than the shape so the corners turn
            // smoothly instead of snapping between the two axes.
            + "float2 gradRR(float2 p, float2 b, float r) {\n"
            + "    float2 c = abs(p) - (b - float2(r));\n"
            + "    float2 s = sign(p);\n"
            + "    if (c.x >= 0.0 || c.y >= 0.0) {\n"
            + "        float2 m = max(c, 0.0);\n"
            + "        float l = length(m);\n"
            + "        if (l > 0.0001) return s * (m / l);\n"
            + "    }\n"
            + "    float gx = step(c.y, c.x);\n"
            + "    return s * float2(gx, 1.0 - gx);\n"
            + "}\n"
            + "\n"
            // miuix BloomStroke getNormal(): a hemispheric rim normal inside a band uBloom deep.
            + "float3 bloomNormal(float2 lc, float2 hs, float sdf, float bigR) {\n"
            + "    float2 xy = lc - floor(hs);\n"
            + "    float2 xa = abs(xy);\n"
            + "    float t = smoothstep(-uBloom, 0.0, sdf);\n"
            + "    float z = sqrt(max(uBloom * uBloom - t * t, 0.0));\n"
            + "    float2 corner = hs - float2(bigR);\n"
            + "    corner.x = min(corner.x, xa.x);\n"
            + "    corner.y = min(corner.y, xa.y);\n"
            + "    float2 toward = xa - corner;\n"
            + "    float tl = length(toward);\n"
            + "    float2 dir = tl > 0.0001 ? toward / tl : float2(0.0, 1.0);\n"
            + "    corner += dir * (bigR - uBloom);\n"
            + "    if (xa.x < corner.x || xa.y < corner.y) return float3(0.0, 0.0, -1.0);\n"
            + "    float3 n = normalize(float3(xa, -z) - float3(corner, 0.0));\n"
            + "    return float3(n.xy * sign(xy), n.z);\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 hs = uSize * 0.5;\n"
            + "    float2 lc = coord - uOrigin;\n"
            + "    float2 p = lc - hs;\n"
            + "    float r = min(uRadius, min(hs.x, hs.y));\n"
            + "    float sd = sdRR(p, hs, r);\n"
            + "    float cov = clamp(0.5 - sd, 0.0, 1.0);\n"
            + "    if (cov <= 0.0) return half4(0.0);\n"
            + "\n"
            + "    float4 col;\n"
            + "    float depth = max(-sd, 0.0);\n"
            + "    if (uRefractH > 0.0 && uRefractA > 0.0 && depth < uRefractH) {\n"
            + "        float x = 1.0 - depth / uRefractH;\n"
            + "        float d = (1.0 - sqrt(max(1.0 - x * x, 0.0))) * uRefractA;\n"
            + "        float gr = min(r * 1.5, min(hs.x, hs.y));\n"
            + "        float pl = length(p);\n"
            + "        float2 g = gradRR(p, hs, gr) + uDepth * (pl > 0.0001 ? p / pl : float2(0.0));\n"
            + "        float gl = length(g);\n"
            + "        g = gl > 0.0001 ? g / gl : float2(0.0);\n"
            + "        float2 rc = coord - d * g;\n"
            + "        if (uChroma > 0.0) {\n"
            + "            float2 dv = -d * g * (uChroma * (p.x * p.y) / max(hs.x * hs.y, 1.0));\n"
            + "            float4 c0 = float4(content.eval(rc + dv));\n"
            + "            float4 c1 = float4(content.eval(rc + dv * (2.0 / 3.0)));\n"
            + "            float4 c2 = float4(content.eval(rc + dv * (1.0 / 3.0)));\n"
            + "            float4 c3 = float4(content.eval(rc));\n"
            + "            float4 c4 = float4(content.eval(rc - dv * (1.0 / 3.0)));\n"
            + "            float4 c5 = float4(content.eval(rc - dv * (2.0 / 3.0)));\n"
            + "            float4 c6 = float4(content.eval(rc - dv));\n"
            + "            col.r = c0.r / 3.5 + c1.r / 3.5 + c2.r / 3.5 + c6.r / 7.0;\n"
            + "            col.g = c1.g / 7.0 + c2.g / 3.5 + c3.g / 3.5 + c4.g / 3.5;\n"
            + "            col.b = c4.b / 3.0 + c5.b / 3.0 + c6.b / 3.0;\n"
            + "            col.a = (c0.a + c1.a + c2.a + c3.a + c4.a + c5.a + c6.a) / 7.0;\n"
            + "        } else {\n"
            + "            col = float4(content.eval(rc));\n"
            + "        }\n"
            + "    } else {\n"
            + "        col = float4(content.eval(coord));\n"
            + "    }\n"
            + "\n"
            // Retain the live refracted colour for the bevel reflection, before the wash.
            + "    float3 transmitted = clamp(col.rgb / max(col.a, 0.0001), 0.0, 1.0);\n"
            // Surface: the flat tint drawn over the lensed backdrop (premultiplied source-over).
            + "    col = col * (1.0 - uTint.a) + float4(uTint.rgb * uTint.a, uTint.a);\n"
            + "\n"
            // Inner shadow: the crescent between the shape and itself moved down, softened.
            + "    if (uShadowA > 0.0 && uShadowR > 0.0) {\n"
            + "        float ss = sdRR(p - float2(0.0, uShadowR), hs, r);\n"
            + "        float m = smoothstep(-uShadowR * 0.6, uShadowR * 0.6, ss);\n"
            + "        col.rgb *= 1.0 - uShadowA * m;\n"
            + "    }\n"
            + "\n"
            // One continuous material: a narrow grazing reflection persists around the
            // outline and overlaps the soft bevel. Both use the same live colour.
            // The pressed tab retains its original BloomStroke treatment.
            + "    if (uHighlight > 0.0 && uStrokeA <= 0.0) {\n"
            + "        float band = max(uBloom * 2.0, 1.0);\n"
            + "        float t = clamp(depth / band, 0.0, 1.0);\n"
            + "        float envelope = 16.0 * t * t * (1.0 - t) * (1.0 - t);\n"
            + "        float2 n = gradRR(p, hs, r);\n"
            + "        float l1 = max(dot(n, uLight1.xy), 0.0);\n"
            + "        float l2 = max(dot(n, uLight2.xy), 0.0);\n"
            + "        float light = l1 * l1 * l1 * l1 * uLight1I\n"
            + "                    + l2 * l2 * l2 * l2 * uLight2I;\n"
            // The reflected colour and intensity follow the live backdrop. No timer or
            // extra capture: scrolling changes the same samples used by the glass body.
            + "        float luminance = dot(transmitted, float3(0.2126, 0.7152, 0.0722));\n"
            + "        float response = mix(0.35, 1.0, smoothstep(0.0, 0.75, luminance));\n"
            // A nonzero all-angle floor keeps dark/back-facing edges visible. Gaussian
            // falloff starts at the actual outline: no inset stroke or second contour.
            // Inspired by OneUILiquidGlass's persistent contour plus directional runs;
            // unlike its compositor-only popup overlay we can modulate live pixels here.
            + "        float sigma = max(uStrokeW * 0.65, 0.75);\n"
            + "        float contour = exp(-0.5 * depth * depth / (sigma * sigma));\n"
            + "        float contourGain = (0.10 + 0.16 * smoothstep(0.0, 0.75, luminance))\n"
            + "                          * (0.85 + 0.15 * clamp(light, 0.0, 1.0)) * uContourGain;\n"
            + "        float bevelGain = light * envelope * uHighlight * response;\n"
            + "        float reflection = clamp(contour * contourGain + bevelGain * 0.45, 0.0, 0.45);\n"
            + "        float3 reflected = mix(float3(1.0), transmitted, 0.15) * col.a;\n"
            // Keep #100's dark-background result exactly. Only the narrow contour
            // changes polarity when the composited body is too bright to illuminate.
            + "        float bodyLuma = dot(col.rgb / max(col.a, 0.0001), float3(0.2126, 0.7152, 0.0722));\n"
            + "        float bright = smoothstep(0.65, 0.95, bodyLuma);\n"
            + "        float contourShare = contour * contourGain / max(contour * contourGain + bevelGain * 0.45, 0.0001);\n"
            + "        float3 target = mix(max(col.rgb, reflected), col.rgb * 0.55, bright * contourShare);\n"
            + "        col.rgb = mix(col.rgb, target, reflection);\n"
            + "    } else if (uHighlight > 0.0) {\n"
            + "        float bigR = max(r, uBloom);\n"
            + "        float2 xa = abs(p);\n"
            + "        if (!(xa.x < hs.x - bigR && xa.y < hs.y - bigR)) {\n"
            + "            float outMask = 1.0 - smoothstep(-1.0, 0.0, sd);\n"
            + "            float sa = smoothstep(-uStrokeW, -uStrokeW + 1.0, sd);\n"
            + "            float3 lit = float3(uStrokeA * sa * sa);\n"
            + "            float3 n = bloomNormal(lc, hs, sd, bigR);\n"
            + "            float l1 = dot(n.xy, uLight1.xy);\n"
            + "            float l2 = dot(n.xy, uLight2.xy);\n"
            + "            lit += float3(l1 * l1 * uLight1I + l2 * l2 * uLight2I);\n"
            + "            col.rgb = min(col.rgb + lit * (uHighlight * outMask), float3(1.0));\n"
            + "        }\n"
            + "    }\n"
            + "    return half4(col * cov);\n"
            + "}\n";

    /** Everything the pass reads, in pixels. Value class so an unchanged frame rebuilds nothing. */
    public static final class Params {
        float refractHeight, refractAmount, depth, chroma;
        int tint;
        float strokeWidth, bloom, highlight, strokeAlpha;
        float contourGain = 1f;
        float l1x, l1y, l1z, l1i, l2x, l2y, l2z, l2i;
        float shadowRadius, shadowAlpha;
        float saturation, blurRadius;

        String key() {
            return refractHeight + ":" + refractAmount + ":" + depth + ":" + chroma + ":" + tint
                    + ":" + strokeWidth + ":" + bloom + ":" + highlight + ":" + strokeAlpha
                    + ":" + l1x + ":" + l1y + ":" + l1z + ":" + l1i + ":" + l2x + ":" + l2y
                    + ":" + l2z + ":" + l2i + ":" + shadowRadius + ":" + shadowAlpha
                    + ":" + saturation + ":" + blurRadius + ":" + contourGain;
        }
    }

    // OneMate's bottom-bar recipe, which is what every surface uses here.
    /** Lens band depth and displacement, dp (OneMate: 24dp/24dp). */
    static final float REFRACTION_DP = 24f;
    /**
     * Backdrop blur, dp (miuix/OneMate: 4dp). Raised after device review: at 4dp (sigma 1.8dp,
     * ~5 px) a high-contrast shape behind the bar, such as WhatsApp's green call FAB, kept a
     * hard edge and the rim's refraction smeared it into streaks, which read as low resolution
     * rather than glass. 10dp gives sigma 4.5dp (~12 px at density 2.75).
     */
    static final float BLUR_DP = 10f;
    /** App chrome needs stronger information suppression than the approved floating bar. */
    static final float APP_BLUR_DP = 30f;
    static final float VIEW_CONTOUR_GAIN = 1.6f;
    static final float VIEW_STROKE_DP = 1.15f;
    static final float BUBBLE_HIGHLIGHT_ALPHA = 0.45f;
    static final float APP_CONTOUR_GAIN = 1.8f;
    static final float APP_STROKE_DP = 1.25f;
    static final float SATURATION = 1f;
    /** Neutral wash at the recommended opacity; blur is calibrated independently. */
    static final float SURFACE_ALPHA = 0.18f;
    static final float SURFACE_ALPHA_MIN = 0f;
    static final float SURFACE_ALPHA_MAX = 0.85f;
    /** Highlight(width 1dp, alpha 0.75) with BloomStroke(white 12%, innerBlur 2dp, dual peak). */
    static final float STROKE_DP = 1f;
    static final float BLOOM_DP = 2f;
    static final float HIGHLIGHT_ALPHA = 0.32f;
    static final float STROKE_ALPHA = 0.12f;

    /**
     * Light positions (miuix {@code LightPosition}, reference origin 0.5/0.7). The primary is the
     * bar's top light turned -45° (OneMate's {@code extraDegrees = -45f} with no tilt sensor);
     * the bubble uses the same light turned +90°.
     */
    static final float[] LIGHT_PRIMARY_BAR = lightDir(0.5f - 0.70710677f, 0.7f - 0.70710677f, -0.05f);
    static final float[] LIGHT_PRIMARY_BUBBLE = lightDir(0.5f + 1f, 0.7f, -0.05f);
    static final float[] LIGHT_SECONDARY = lightDir(0.5f, 0.8f, -0.5f);
    static final float LIGHT_PRIMARY_INTENSITY = 1f;
    static final float LIGHT_SECONDARY_INTENSITY = 0.4f;

    /** Bubble lens (pressed): height 10dp, amount 14dp, depth effect, chromatic aberration 0.5. */
    static final float BUBBLE_REFRACT_HEIGHT_DP = 10f;
    static final float BUBBLE_REFRACT_AMOUNT_DP = 14f;
    static final float BUBBLE_CHROMA = 0.5f;
    static final float BUBBLE_SHADOW_DP = 8f;
    static final float BUBBLE_SHADOW_ALPHA = 0.15f;

    /** Normalised (x − 0.5, y − 0.7, z), as miuix {@code applyLightUniforms}. */
    static float[] lightDir(float x, float y, float z) {
        float dx = x - 0.5f, dy = y - 0.7f;
        float len = (float) Math.max(1e-6, Math.sqrt(dx * dx + dy * dy + z * z));
        return new float[]{dx / len, dy / len, z / len};
    }

    /** Kyant's circle profile: displacement fraction at {@code depth} px into a band {@code height} deep. */
    static float circleMap(float depth, float height) {
        if (height <= 0f || depth >= height) return 0f;
        float x = 1f - Math.max(0f, depth) / height;
        return 1f - (float) Math.sqrt(Math.max(0f, 1f - x * x));
    }

    /** Surface tint alpha for the user's opacity slider, centred on the neutral 18% wash. */
    static float surfaceAlpha(float opacityPercent, int recommendedPercent) {
        float alpha = SURFACE_ALPHA + (opacityPercent - recommendedPercent) / 100f;
        return Math.max(SURFACE_ALPHA_MIN, Math.min(SURFACE_ALPHA_MAX, alpha));
    }

    /** Surface tint alpha, 0-255, published from the opacity slider; 18% until then. */
    private static volatile int surfaceAlpha255 = Math.round(SURFACE_ALPHA * 255f);

    /**
     * Publishes the user's opacity slider. Called by the hooks that own the preference (no
     * preference is read in this package); surfaces pick it up on their next effect build, since
     * the tint is part of every effect's key.
     */
    public static void publishOpacity(float opacityPercent, int recommendedPercent) {
        surfaceAlpha255 = Math.round(surfaceAlpha(opacityPercent, recommendedPercent) * 255f);
    }

    static int surfaceAlpha255() {
        return surfaceAlpha255;
    }

    /** Blur radius to hand {@code createBlurEffect} so its sigma matches miuix's blur(BLUR_DP). */
    static float blurRadiusPx(float density) {
        return blurRadiusPx(density, BLUR_DP);
    }

    private static float blurRadiusPx(float density, float blurDp) {
        float sigma = blurDp * density * RADIUS_TO_SIGMA;
        return Math.max(0f, (sigma - 0.5f) / SKIA_RADIUS_SCALE);
    }

    /** Recording margin the blur needs at the rim, in px. */
    static int padding(float density) {
        return padding(density, BLUR_DP);
    }

    /** Matches appSurface blur; used by both capture and admission accounting. */
    static int appPadding(float density) {
        return padding(density, APP_BLUR_DP);
    }

    private static int padding(float density, float blurDp) {
        float sigma = blurDp * density * RADIUS_TO_SIGMA;
        return (int) Math.ceil(3f * sigma) + 1;
    }

    /** The material for an app surface or the bar body. */
    static Params surface(GlassSpec spec, int height, float density, int surfaceAlpha255) {
        Params p = new Params();
        float half = height / 2f;
        p.refractHeight = Math.min(REFRACTION_DP * density, half);
        p.refractAmount = REFRACTION_DP * density;
        // The theme surface is blue-grey, and adaptTo can tint it again. Neither is
        // a pigment in clear glass. Keep only a neutral readability wash; the
        // recorded backdrop supplies all hue. Content colour selects dark/light.
        int neutral = SemanticTheme.relativeLuminance(spec.contentColor) > 0.5d
                ? 0x000000 : 0xFFFFFF;
        p.tint = (surfaceAlpha255 << 24) | neutral;
        p.strokeWidth = VIEW_STROKE_DP * density;
        p.contourGain = VIEW_CONTOUR_GAIN;
        p.bloom = BLOOM_DP * density;
        p.highlight = HIGHLIGHT_ALPHA;
        p.strokeAlpha = 0f;
        setLights(p, LIGHT_PRIMARY_BAR);
        p.saturation = SATURATION;
        p.blurRadius = blurRadiusPx(density);
        return p;
    }

    /** Non-navbar app chrome: stronger blur and contour, with the same neutral body. */
    static Params appSurface(GlassSpec spec, int height, float density, int surfaceAlpha255) {
        Params p = surface(spec, height, density, surfaceAlpha255);
        p.blurRadius = blurRadiusPx(density, APP_BLUR_DP);
        p.strokeWidth = APP_STROKE_DP * density;
        p.contourGain = APP_CONTOUR_GAIN;
        return p;
    }

    /**
     * Rest wash of the bubble: OneMate's 10% white (dark theme) or black (light). It is
     * translucent on purpose; WhatsApp's own selected-tab pill is hidden while the bubble is
     * installed, so the bubble is the only indicator and must not read as a solid pill.
     */
    static final float BUBBLE_REST_ALPHA = 0.10f;

    /** The bottom-bar bubble at press progress {@code progress} (0 rest, 1 pressed). */
    static Params bubble(boolean dark, float progress, float density) {
        return bubble(dark, progress, progress, density);
    }

    /**
     * The bottom-bar bubble with its glass decoupled from the press: {@code press} drives the
     * surface wash and the inner shadow (a finger, a swipe), {@code glass} the lens depth and the
     * lit rim. At rest the bubble keeps some glass, so it reads as a lens rather than a flat
     * translucent pill.
     */
    static Params bubble(boolean dark, float press, float glass, float density) {
        Params p = new Params();
        float t = Math.max(0f, Math.min(1f, press));
        float g = Math.max(0f, Math.min(1f, glass));
        p.refractHeight = BUBBLE_REFRACT_HEIGHT_DP * density * g;
        p.refractAmount = BUBBLE_REFRACT_AMOUNT_DP * density * g;
        p.depth = 1f;
        p.chroma = BUBBLE_CHROMA;
        // Rest: the content-colour wash; pressed: 3% black (OneMate onDrawSurface).
        float restAlpha = BUBBLE_REST_ALPHA * (1f - t);
        float pressAlpha = 0.03f * t;
        float a = restAlpha + pressAlpha * (1f - restAlpha);
        float mix = a <= 0f ? 0f : restAlpha / a;
        int rgb = lerpRgb(0x000000, dark ? 0xFFFFFF : 0x000000, mix);
        p.tint = (Math.round(a * 255f) << 24) | rgb;
        p.strokeWidth = STROKE_DP * density;
        p.bloom = BLOOM_DP * density;
        p.highlight = BUBBLE_HIGHLIGHT_ALPHA * g;
        p.strokeAlpha = STROKE_ALPHA;
        setLights(p, LIGHT_PRIMARY_BUBBLE);
        p.shadowRadius = BUBBLE_SHADOW_DP * density * t;
        p.shadowAlpha = BUBBLE_SHADOW_ALPHA * t;
        p.saturation = 1f;
        p.blurRadius = 0f;
        return p;
    }

    private static void setLights(Params p, float[] primary) {
        p.l1x = primary[0]; p.l1y = primary[1]; p.l1z = primary[2];
        p.l1i = LIGHT_PRIMARY_INTENSITY;
        p.l2x = LIGHT_SECONDARY[0]; p.l2y = LIGHT_SECONDARY[1]; p.l2z = LIGHT_SECONDARY[2];
        p.l2i = LIGHT_SECONDARY_INTENSITY;
    }

    private static int lerpRgb(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1f - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1f - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1f - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }

    static boolean isAvailable() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !broken;
    }

    static boolean isBroken() {
        return broken;
    }

    // ---------------------------------------------------------------- instance: one per surface

    private RuntimeShader shader;
    private RenderEffect effect;
    private String key;

    /**
     * Brings the effect up to date for a {@code width}×{@code height} surface whose top-left sits
     * at ({@code originX}, {@code originY}) inside the recorded input.
     *
     * @return true when {@link #effect()} changed and must be installed again
     */
    boolean update(Params params, int width, int height, float radius, float originX, float originY) {
        if (broken) return false;
        String next = width + "x" + height + ":" + radius + ":" + originX + ":" + originY
                + ":" + params.key();
        if (next.equals(key) && effect != null) return false;
        try {
            if (shader == null) {
                try {
                    shader = new RuntimeShader(SHADER);
                } catch (RuntimeException | LinkageError compile) {
                    broken = true;
                    throw compile;
                }
            }
            shader.setFloatUniform("uSize", width, height);
            shader.setFloatUniform("uOrigin", originX, originY);
            shader.setFloatUniform("uRadius", radius);
            shader.setFloatUniform("uRefractH", params.refractHeight);
            shader.setFloatUniform("uRefractA", params.refractAmount);
            shader.setFloatUniform("uDepth", params.depth);
            shader.setFloatUniform("uChroma", params.chroma);
            int t = params.tint;
            shader.setFloatUniform("uTint", ((t >> 16) & 0xFF) / 255f, ((t >> 8) & 0xFF) / 255f,
                    (t & 0xFF) / 255f, ((t >>> 24) & 0xFF) / 255f);
            shader.setFloatUniform("uStrokeW", params.strokeWidth);
            shader.setFloatUniform("uBloom", params.bloom);
            shader.setFloatUniform("uHighlight", params.highlight);
            shader.setFloatUniform("uStrokeA", params.strokeAlpha);
            shader.setFloatUniform("uContourGain", params.contourGain);
            shader.setFloatUniform("uLight1", params.l1x, params.l1y, params.l1z);
            shader.setFloatUniform("uLight1I", params.l1i);
            shader.setFloatUniform("uLight2", params.l2x, params.l2y, params.l2z);
            shader.setFloatUniform("uLight2I", params.l2i);
            shader.setFloatUniform("uShadowR", params.shadowRadius);
            shader.setFloatUniform("uShadowA", params.shadowAlpha);

            // A runtime-shader effect snapshots its uniforms when it is created (see LensEffect),
            // so the wrapper is rebuilt after every write; the compiled program is kept.
            RenderEffect lens = RenderEffect.createRuntimeShaderEffect(shader, "content");
            RenderEffect input = null;
            if (params.saturation != 1f) {
                ColorMatrix saturation = new ColorMatrix();
                saturation.setSaturation(params.saturation);
                input = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(saturation));
            }
            if (params.blurRadius > 0f) {
                input = input == null
                        ? RenderEffect.createBlurEffect(params.blurRadius, params.blurRadius,
                                Shader.TileMode.CLAMP)
                        : RenderEffect.createBlurEffect(params.blurRadius, params.blurRadius,
                                input, Shader.TileMode.CLAMP);
            }
            effect = input == null ? lens : RenderEffect.createChainEffect(lens, input);
            key = next;
            return true;
        } catch (RuntimeException | LinkageError error) {
            effect = null;
            key = null;
            Log.e(TAG, "OneMate lens unavailable", error);
            return false;
        }
    }

    RenderEffect effect() {
        return effect;
    }

    void reset() {
        effect = null;
        key = null;
    }
}
