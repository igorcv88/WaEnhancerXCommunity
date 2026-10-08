package com.waenhancer.theme;

import android.graphics.BlendMode;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.util.Log;

/**
 * The corrected lens: {@code SHADER_V2} and the {@link RenderEffect} graph built around it.
 *
 * <p>Used only when {@link GlassOptics#corrected} is on; the legacy shader in {@link LiquidLens}
 * is untouched so the original look stays available for comparison. One instance per surface. It
 * keeps its compiled programs for the life of the surface and rebuilds only the (cheap)
 * {@code RenderEffect} wrapper when uniforms change.</p>
 *
 * <h3>Effect graph</h3>
 *
 * <p>Without filtering, one pass: the shader samples the recorded backdrop directly and does the
 * legacy nine-tap blur itself. With filtering, two passes over the same content, composited
 * source-over:</p>
 * <pre>
 *   soft  = lens(pass=1) ∘ gaussian(σ)     full material, everywhere
 *   sharp = lens(pass=2)                   material × (1-β), alpha (1-β); empty where β = 1
 *   out   = sharp over soft = (1-β)·M(sharp) + β·M(soft)
 * </pre>
 * <p>β is the soft share: low at the rim, where the refraction needs structure to bend, and 1 in
 * the body, where the controls are and background text must not compete with them. The blur is
 * the platform's Gaussian ({@link RenderEffect#createBlurEffect}), so its response is
 * {@code exp(-2π²σ²f²)} with a known σ, not a sparse kernel with copies (LG-02/LG-07).</p>
 *
 * <h3>Why the effect is rebuilt when uniforms change</h3>
 *
 * <p>A runtime-shader effect is built from the shader's uniform block when it is created. Skia's
 * uniform data is copy-on-write, so a uniform written afterwards most likely reaches a copy and
 * not the effect already installed. The legacy path relies on writes reaching the installed
 * effect, and that is not confirmed on a device. This path rebuilds the wrapper after every
 * uniform write. That allocates a small object, never a shader.</p>
 */
final class LensEffect {

    private static final String TAG = "WaEnhancerX/LensV2";

    /** Set once a device refuses the corrected program; surfaces fall back to the legacy lens. */
    private static volatile boolean broken;

    static boolean isBroken() {
        return broken;
    }

    /**
     * Selected-tab and press state. It survives a material change (a tint, an adapted colour)
     * when the temporal group is on (LG-11). With the group off it is reset, as the legacy lens
     * does, so the comparison shows the difference.
     */
    static final class Interaction {
        float activeX, activeY, activeHalfW, activeHalfH, activeRadius, active, press;
        int activeTint;

        void onMaterialChange(boolean keep) {
            if (keep) return;
            active = 0f;
            press = 0f;
        }
    }

    final Interaction interaction = new Interaction();

    private RuntimeShader sharp, soft;
    private RenderEffect effect;
    private String key;
    private boolean twoPass;
    private float sigma;
    private String status = "not built";

    /**
     * Brings the effect up to date for a {@code width}×{@code height} material inside a
     * {@code width+2·padding} square of recorded input.
     *
     * @return true when {@link #effect()} changed and must be installed again; false when it is
     *         unchanged or could not be built ({@link #effect()} is then null)
     */
    boolean update(GlassSpec spec, int width, int height, float radius, float density,
                   GlassOptics optics, int padding, boolean temporalKeys) {
        boolean filtering = optics.filtering && optics.debug != GlassOptics.Debug.RAW_INPUT
                && optics.debug != GlassOptics.Debug.DISPLACEMENT
                && optics.debug != GlassOptics.Debug.JACOBIAN && optics.debug != GlassOptics.Debug.GRID;
        float sig = filtering ? LensModel.sigmaPx(spec, density) : 0f;
        filtering = filtering && sig > 0f;
        String next = optics.key() + "|" + width + "x" + height + "|" + radius + "|" + density
                + "|" + padding + "|" + (temporalKeys ? Integer.toHexString(spec.hashCode()) + materialKey(spec)
                : Integer.toHexString(System.identityHashCode(spec)));
        if (next.equals(key) && effect != null) return false;
        if (broken) return false;
        try {
            if (sharp == null) sharp = new RuntimeShader(SHADER_V2);
            if (filtering && soft == null) soft = new RuntimeShader(SHADER_V2);
            interaction.onMaterialChange(optics.temporal);
            write(sharp, spec, width, height, radius, density, optics, padding, filtering ? 2f : 0f);
            if (filtering) write(soft, spec, width, height, radius, density, optics, padding, 1f);
            twoPass = filtering;
            sigma = sig;
            key = next;
            effect = build();
            status = "v2 " + optics.key() + " " + width + "x" + height + " pad=" + padding
                    + (filtering ? " sigma=" + sig : " single-pass");
            return true;
        } catch (RuntimeException | LinkageError error) {
            broken = true;
            effect = null;
            status = "v2 rejected: " + error;
            Log.e(TAG, "corrected lens rejected; legacy lens from now on", error);
            return false;
        }
    }

    private static String materialKey(GlassSpec spec) {
        return ":" + spec.fillColor + ":" + spec.blurRadius + ":" + spec.lensStrength + ":"
                + spec.dispersion + ":" + spec.specular + ":" + spec.contentColor;
    }

    RenderEffect effect() {
        return effect;
    }

    String status() {
        return status;
    }

    /** Selected tab. Returns the rebuilt effect, or null when there is none to install. */
    RenderEffect setActive(float x, float y, float halfW, float halfH, float r, int tint, boolean on) {
        Interaction i = interaction;
        i.activeX = x;
        i.activeY = y;
        i.activeHalfW = Math.max(0f, halfW);
        i.activeHalfH = Math.max(0f, halfH);
        i.activeRadius = Math.max(0f, r);
        i.activeTint = tint;
        i.active = on ? 1f : 0f;
        return rewriteInteraction();
    }

    RenderEffect setPress(float value) {
        interaction.press = value;
        return rewriteInteraction();
    }

    private RenderEffect rewriteInteraction() {
        if (sharp == null || effect == null) return null;
        try {
            writeInteraction(sharp);
            if (twoPass && soft != null) writeInteraction(soft);
            effect = build();
            return effect;
        } catch (RuntimeException | LinkageError error) {
            Log.w(TAG, "interaction uniforms not written", error);
            return null;
        }
    }

    private RenderEffect build() {
        RenderEffect sharpEffect = RenderEffect.createRuntimeShaderEffect(sharp, "content");
        if (!twoPass) return sharpEffect;
        RenderEffect blurred = RenderEffect.createBlurEffect(sigma, sigma, Shader.TileMode.CLAMP);
        RenderEffect softEffect = RenderEffect.createChainEffect(
                RenderEffect.createRuntimeShaderEffect(soft, "content"), blurred);
        return RenderEffect.createBlendModeEffect(softEffect, sharpEffect, BlendMode.SRC_OVER);
    }

    private void write(RuntimeShader shader, GlassSpec spec, int width, int height, float radius,
                       float density, GlassOptics optics, int padding, float pass) {
        LensModel.Surface geo = LensModel.surface(spec, width, height, radius, density,
                optics.geometry, optics.color, optics.displacement);
        float hairline = Math.max(1.5f, Math.min(0.95f * density, 5f));
        float blur = Math.max(0f, Math.min(16f, spec.blurRadius * 0.50f * density));
        shader.setFloatUniform("uSize", width, height);
        shader.setFloatUniform("uOrigin", padding, padding);
        shader.setFloatUniform("uInput", width + 2f * padding, height + 2f * padding);
        shader.setFloatUniform("uRadius", geo.radius);
        shader.setFloatUniform("uGeoRadius", geo.geoRadius);
        shader.setFloatUniform("uBevel", optics.geometry ? geo.bevel
                : LensModel.bevel(spec, width, height, density, false));
        shader.setFloatUniform("uAmp", geo.amp);
        shader.setFloatUniform("uCap", geo.cap);
        shader.setFloatUniform("uDelta", geo.delta);
        shader.setFloatUniform("uRefract", geo.legacyRefract);
        shader.setFloatUniform("uDispersion", spec.dispersion);
        shader.setFloatUniform("uSpread", geo.legacySpread);
        shader.setFloatUniform("uLo", geo.lo);
        shader.setFloatUniform("uGeo", optics.geometry ? 1f : 0f);
        shader.setFloatUniform("uPass", pass);
        shader.setFloatUniform("uBlur", blur);
        shader.setFloatUniform("uColorV2", optics.color ? 1f : 0f);
        shader.setFloatUniform("uDebug", optics.debug.code);
        shader.setFloatUniform("uLight", 0.45f, 0.89f);
        shader.setFloatUniform("uSpec", spec.specular);
        shader.setFloatUniform("uInnerShadow", spec.innerShadow);
        shader.setFloatUniform("uHair", hairline);
        shader.setFloatUniform("uSat", LensModel.saturation(spec, optics.color));
        // Colour-mode finish: hairline channel separation, and the light gains (LG-05/LG-13).
        shader.setFloatUniform("uSepMax", optics.color ? LensModel.SPREAD_V2_MAX_PX : 1000f);
        shader.setFloatUniform("uLitGain", optics.color ? 0.18f : 0.26f);
        shader.setFloatUniform("uAwayGain", optics.color ? 0.07f : 0.10f);
        shader.setFloatUniform("uHairGain", optics.color ? 0.75f : 0.86f);
        shader.setFloatUniform("uFlatGain", optics.color ? 0f : 0.10f);
        shader.setColorUniform("uTint", spec.fillColor);
        // Adaptive contrast: protection toward the colour opposite the content (LG-03).
        boolean lightContent = SemanticTheme.relativeLuminance(spec.contentColor) > 0.5d;
        shader.setColorUniform("uContent", 0xFF000000 | spec.contentColor);
        shader.setColorUniform("uProtect", lightContent ? 0xFF0B141A : 0xFFFFFFFF);
        shader.setFloatUniform("uAdaptive", optics.adaptive ? 1f : 0f);
        shader.setFloatUniform("uAdaptTint", optics.adaptive && spec.adaptive ? 0.55f : 0f);
        shader.setFloatUniform("uContrast", optics.clearProfile
                ? LensModel.CONTRAST_TARGET_CLEAR : LensModel.CONTRAST_TARGET);
        shader.setFloatUniform("uProtectMax", optics.clearProfile
                ? LensModel.PROTECTION_MAX_CLEAR : LensModel.PROTECTION_MAX);
        shader.setFloatUniform("uBetaRim", LensModel.BETA_RIM);
        shader.setFloatUniform("uBetaFull", LensModel.BETA_FULL_AT);
        shader.setFloatUniform("uProtFrom", LensModel.PROTECTION_FROM);
        shader.setFloatUniform("uProtTo", LensModel.PROTECTION_TO);
        writeInteraction(shader);
    }

    private void writeInteraction(RuntimeShader shader) {
        Interaction i = interaction;
        shader.setFloatUniform("uActiveCenter", i.activeX, i.activeY);
        shader.setFloatUniform("uActiveHalf", i.activeHalfW, i.activeHalfH);
        shader.setFloatUniform("uActiveRadius", i.activeRadius);
        shader.setColorUniform("uActiveTint", Color.argb(Math.round(0.14f * 255f),
                Color.red(i.activeTint), Color.green(i.activeTint), Color.blue(i.activeTint)));
        shader.setFloatUniform("uActive", i.active);
        shader.setFloatUniform("uPress", i.press);
    }

    /**
     * The corrected lens. Every group is switchable by uniform; with a group off, that group's
     * code is the legacy shader's. Geometry is mirrored by {@link LensModel} (keep both in step).
     * Coordinates: {@code coord} is in the recorded input, which may be padded; {@code local} is
     * in the material's own pixels.
     */
    static final String SHADER_V2 = ""
            + "uniform shader content;\n"
            + "uniform float2 uSize;\n"
            + "uniform float2 uOrigin;\n"
            + "uniform float2 uInput;\n"
            + "uniform float uRadius;\n"
            + "uniform float uGeoRadius;\n"
            + "uniform float uBevel;\n"
            + "uniform float uAmp;\n"
            + "uniform float uCap;\n"
            + "uniform float uDelta;\n"
            + "uniform float uRefract;\n"
            + "uniform float uDispersion;\n"
            + "uniform float uSpread;\n"
            + "uniform float uLo;\n"
            + "uniform float uGeo;\n"
            + "uniform float uPass;\n"
            + "uniform float uBlur;\n"
            + "uniform float uColorV2;\n"
            + "uniform float uDebug;\n"
            + "uniform float2 uLight;\n"
            + "uniform float uSpec;\n"
            + "uniform float uInnerShadow;\n"
            + "uniform float uHair;\n"
            + "uniform float uSat;\n"
            + "uniform float uSepMax;\n"
            + "uniform float uLitGain;\n"
            + "uniform float uAwayGain;\n"
            + "uniform float uHairGain;\n"
            + "uniform float uFlatGain;\n"
            + "layout(color) uniform half4 uTint;\n"
            + "layout(color) uniform half4 uContent;\n"
            + "layout(color) uniform half4 uProtect;\n"
            + "uniform float uAdaptive;\n"
            + "uniform float uAdaptTint;\n"
            + "uniform float uContrast;\n"
            + "uniform float uProtectMax;\n"
            + "uniform float uBetaRim;\n"
            + "uniform float uBetaFull;\n"
            + "uniform float uProtFrom;\n"
            + "uniform float uProtTo;\n"
            + "uniform float2 uActiveCenter;\n"
            + "uniform float2 uActiveHalf;\n"
            + "uniform float uActiveRadius;\n"
            + "layout(color) uniform half4 uActiveTint;\n"
            + "uniform float uActive;\n"
            + "uniform float uPress;\n"
            + "\n"
            + "float sdRoundRect(float2 p, float2 b, float r) {\n"
            + "    float2 q = abs(p) - b + r;\n"
            + "    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;\n"
            + "}\n"
            + "\n"
            // Analytic outward gradient: continuous outside and round the arcs, switching axes
            // only on the medial axis, which the corrected field never reaches (its offset is zero
            // deeper than the bevel, and the geometry radius is at least the bevel).
            + "float2 sdGrad(float2 p, float2 b, float r) {\n"
            + "    float2 q = abs(p) - b + r;\n"
            + "    float2 sg = float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);\n"
            + "    if (q.x > 0.0 && q.y > 0.0) {\n"
            + "        return sg * q / length(q);\n"
            + "    }\n"
            + "    if (q.x > q.y) {\n"
            + "        return float2(sg.x, 0.0);\n"
            + "    }\n"
            + "    return float2(0.0, sg.y);\n"
            + "}\n"
            + "\n"
            + "float lum(float3 c) {\n"
            + "    return dot(c, float3(0.2126, 0.7152, 0.0722));\n"
            + "}\n"
            + "\n"
            // The green channel's offset (xy) and the red and blue offsets as multiples of it (zw).
            // All three are collinear in both maps, which is what lets one vector carry them.
            + "float4 lensOffsets(float2 local) {\n"
            + "    float2 hs = uSize * 0.5;\n"
            + "    float2 p = local - hs;\n"
            + "    if (uGeo > 0.5) {\n"
            + "        float dg = sdRoundRect(p, hs, uGeoRadius);\n"
            + "        float2 n = sdGrad(p, hs, uGeoRadius);\n"
            + "        float t = clamp(max(-dg, 0.0) / max(uBevel, 1.0), 0.0, 1.0);\n"
            + "        float e = 1.0 - t;\n"
            + "        float curv = 0.80 + 0.20 * n.x * n.x;\n"
            + "        float band = 0.0;\n"
            + "        if (uActive > 0.0) {\n"
            + "            float ar = min(uActiveRadius, min(uActiveHalf.x, uActiveHalf.y));\n"
            + "            float ad = sdRoundRect(local - uActiveCenter, uActiveHalf, ar);\n"
            + "            float w = max(uBevel * 1.6, 6.0);\n"
            + "            band = uActive * (1.0 - smoothstep(0.0, w, abs(ad)));\n"
            + "        }\n"
            + "        float a = min(uAmp * curv * (1.0 + 0.18 * band), uCap);\n"
            + "        if (a <= 0.0001) {\n"
            + "            return float4(0.0, 0.0, 1.0, 1.0);\n"
            + "        }\n"
            + "        float aR = max(a - uDelta, 0.0);\n"
            + "        float aB = min(a + uDelta, uCap);\n"
            + "        return float4(-n * (a * e * e), aR / a, aB / a);\n"
            + "    }\n"
            + "    float r = min(uRadius, min(hs.x, hs.y));\n"
            + "    float2 n = float2(\n"
            + "        sdRoundRect(p + float2(1.0, 0.0), hs, r) - sdRoundRect(p - float2(1.0, 0.0), hs, r),\n"
            + "        sdRoundRect(p + float2(0.0, 1.0), hs, r) - sdRoundRect(p - float2(0.0, 1.0), hs, r));\n"
            + "    float nLen = length(n);\n"
            + "    n = nLen > 0.0001 ? n / nLen : float2(0.0, -1.0);\n"
            + "    float d = sdRoundRect(p, hs, r);\n"
            + "    float t = clamp(-d / max(uBevel, 1.0), 0.0, 1.0);\n"
            + "    float slope = (1.0 - t) * (1.0 - t);\n"
            + "    float ad = sdRoundRect(local - uActiveCenter, uActiveHalf, uActiveRadius);\n"
            + "    float activeCov = uActive * clamp(0.5 - ad / 1.5, 0.0, 1.0);\n"
            + "    float activeEdge = activeCov * clamp(1.0 + ad / max(uBevel * 0.45, 1.0), 0.0, 1.0);\n"
            + "    float curvature = mix(0.80, 1.0, abs(n.x));\n"
            + "    float2 offset = -n * (slope * uRefract * curvature) * (1.0 + activeEdge * 0.18);\n"
            + "    float oLen = length(offset);\n"
            + "    float k = uDispersion * slope;\n"
            + "    float sLen = oLen * k;\n"
            + "    k = sLen > uSpread ? uSpread / max(oLen, 0.0001) : k;\n"
            + "    return float4(offset, 1.0 - k, 1.0 + k);\n"
            + "}\n"
            + "\n"
            // Known lines for the GRID diagnostic: a 24px grid, finer 6px bars in alternate cells.
            + "float3 gridAt(float2 c) {\n"
            + "    float2 g = abs(fract(c / 24.0) - 0.5) * 24.0;\n"
            + "    float ln = step(11.0, max(g.x, g.y));\n"
            + "    float2 cell = floor(c / 24.0);\n"
            + "    float bars = step(0.5, fract((cell.x + cell.y) * 0.5)) * step(0.5, fract(c.x / 6.0));\n"
            + "    float v = 0.92 - 0.80 * max(ln, bars * 0.7);\n"
            + "    return float3(v, v, v);\n"
            + "}\n"
            + "\n"
            // Legacy nine-tap blur, used only without filtering.
            + "float4 sample9(float2 c, float rad, float2 lo, float2 hi) {\n"
            + "    float2 ax = float2(rad * 0.62, 0.0);\n"
            + "    float2 ay = float2(0.0, rad * 0.62);\n"
            + "    float dg = rad * 0.7071;\n"
            + "    float2 d1 = float2(dg, dg);\n"
            + "    float2 d2 = float2(dg, -dg);\n"
            + "    float4 acc = float4(content.eval(clamp(c, lo, hi)));\n"
            + "    acc += float4(content.eval(clamp(c + ax, lo, hi))) * 0.75;\n"
            + "    acc += float4(content.eval(clamp(c - ax, lo, hi))) * 0.75;\n"
            + "    acc += float4(content.eval(clamp(c + ay, lo, hi))) * 0.75;\n"
            + "    acc += float4(content.eval(clamp(c - ay, lo, hi))) * 0.75;\n"
            + "    acc += float4(content.eval(clamp(c + d1, lo, hi))) * 0.5;\n"
            + "    acc += float4(content.eval(clamp(c - d1, lo, hi))) * 0.5;\n"
            + "    acc += float4(content.eval(clamp(c + d2, lo, hi))) * 0.5;\n"
            + "    acc += float4(content.eval(clamp(c - d2, lo, hi))) * 0.5;\n"
            + "    return acc / 6.0;\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 local = coord - uOrigin;\n"
            + "    float4 tint4 = float4(uTint);\n"
            + "    float4 activeTint4 = float4(uActiveTint);\n"
            + "    float2 hs = uSize * 0.5;\n"
            + "    float2 p = local - hs;\n"
            + "    float r = min(uRadius, min(hs.x, hs.y));\n"
            + "    float d = sdRoundRect(p, hs, r);\n"
            + "    float cov = clamp(0.5 - d / 1.5, 0.0, 1.0);\n"
            + "    if (cov <= 0.004) {\n"
            + "        return half4(0.0);\n"
            + "    }\n"
            // Lighting geometry: the real outline, as in the legacy shader.
            + "    float2 n = float2(\n"
            + "        sdRoundRect(p + float2(1.0, 0.0), hs, r) - sdRoundRect(p - float2(1.0, 0.0), hs, r),\n"
            + "        sdRoundRect(p + float2(0.0, 1.0), hs, r) - sdRoundRect(p - float2(0.0, 1.0), hs, r));\n"
            + "    float nLen = length(n);\n"
            + "    n = nLen > 0.0001 ? n / nLen : float2(0.0, -1.0);\n"
            + "    float t = clamp(-d / max(uBevel, 1.0), 0.0, 1.0);\n"
            + "    float edge = 1.0 - t;\n"
            + "    float ad = sdRoundRect(local - uActiveCenter, uActiveHalf, uActiveRadius);\n"
            + "    float activeCov = uActive * clamp(0.5 - ad / 1.5, 0.0, 1.0);\n"
            + "    float activeEdge = activeCov * clamp(1.0 + ad / max(uBevel * 0.45, 1.0), 0.0, 1.0);\n"
            + "\n"
            + "    float beta = uPass > 0.5 ? uBetaRim + (1.0 - uBetaRim) * smoothstep(0.0, uBetaFull, t) : 0.0;\n"
            // The sharp pass draws only where the sharp share is non-zero.
            + "    if (uPass > 1.5 && (beta > 0.995 || uDebug > 4.5)) {\n"
            + "        return half4(0.0);\n"
            + "    }\n"
            + "\n"
            + "    float4 off = lensOffsets(local);\n"
            + "    float2 lo = float2(uLo, uLo);\n"
            + "    float2 hi = uInput - float2(uLo, uLo);\n"
            + "    float2 cG = clamp(coord + off.xy, lo, hi);\n"
            + "    float2 cR = clamp(coord + off.xy * off.z, lo, hi);\n"
            + "    float2 cB = clamp(coord + off.xy * off.w, lo, hi);\n"
            + "\n"
            // Diagnostics that replace the material.
            + "    if (uDebug > 0.5 && uDebug < 1.5) {\n"
            + "        float4 raw = float4(content.eval(clamp(coord, lo, hi)));\n"
            + "        return half4(raw * cov);\n"
            + "    }\n"
            + "    if (uDebug > 1.5 && uDebug < 2.5) {\n"
            + "        float2 v = off.xy / max(uCap, 1.0);\n"
            + "        return half4(half3(float3(0.5 + 0.5 * v.x, 0.5 + 0.5 * v.y, t) * cov), half(cov));\n"
            + "    }\n"
            + "    if (uDebug > 2.5 && uDebug < 3.5) {\n"
            + "        float2 m0 = local + off.xy;\n"
            + "        float2 mx = local + float2(1.0, 0.0) + lensOffsets(local + float2(1.0, 0.0)).xy;\n"
            + "        float2 my = local + float2(0.0, 1.0) + lensOffsets(local + float2(0.0, 1.0)).xy;\n"
            + "        float2 jx = mx - m0;\n"
            + "        float2 jy = my - m0;\n"
            + "        float det = jx.x * jy.y - jy.x * jx.y;\n"
            + "        float tr = dot(jx, jx) + dot(jy, jy);\n"
            + "        float smin = sqrt(max(0.0, (tr - sqrt(max(0.0, tr * tr - 4.0 * det * det))) * 0.5));\n"
            + "        float3 heat = det <= 0.0 ? float3(1.0, 0.0, 0.0)\n"
            + "            : (smin >= 0.30 ? float3(0.1, 0.75, 0.2) : float3(1.0, 0.85, 0.0));\n"
            + "        return half4(half3(heat * cov), half(cov));\n"
            + "    }\n"
            + "    if (uDebug > 3.5 && uDebug < 4.5) {\n"
            + "        float3 gc = float3(gridAt(cR - uOrigin).r, gridAt(cG - uOrigin).g, gridAt(cB - uOrigin).b);\n"
            + "        return half4(half3(gc * cov), half(cov));\n"
            + "    }\n"
            + "\n"
            // The backdrop at the refracted positions. With filtering, one tap per channel from this
            // pass's input; without, the legacy nine-tap blur and sharp red/blue fringe taps.
            + "    float4 back;\n"
            + "    float3 col;\n"
            + "    if (uPass > 0.5) {\n"
            + "        back = float4(content.eval(cG));\n"
            + "        if (back.a < 0.01) {\n"
            + "            return half4(half3(tint4.rgb * cov), half(cov));\n"
            + "        }\n"
            + "        col = back.rgb / back.a;\n"
            + "        float4 sr = float4(content.eval(cR));\n"
            + "        float4 sb = float4(content.eval(cB));\n"
            + "        float fr = uColorV2 > 0.5 ? 1.0 : edge * edge;\n"
            + "        col.r = mix(col.r, sr.r / max(sr.a, 0.001), fr);\n"
            + "        col.b = mix(col.b, sb.b / max(sb.a, 0.001), fr);\n"
            + "    } else {\n"
            + "        back = sample9(cG, t * uBlur, lo, hi);\n"
            + "        if (back.a < 0.01) {\n"
            + "            return half4(half3(tint4.rgb * cov), half(cov));\n"
            + "        }\n"
            + "        col = back.rgb / back.a;\n"
            + "        float fringe = edge * edge;\n"
            + "        if (fringe > 0.004) {\n"
            + "            float4 sr = float4(content.eval(cR));\n"
            + "            float4 sb = float4(content.eval(cB));\n"
            + "            col.r = mix(col.r, sr.r / max(sr.a, 0.001), fringe);\n"
            + "            col.b = mix(col.b, sb.b / max(sb.a, 0.001), fringe);\n"
            + "        }\n"
            + "    }\n"
            + "\n"
            // Transmission: saturation, tint and contrast protection. In linear light with the
            // colour group on; in encoded values (the legacy arithmetic) with it off.
            + "    bool lin = uColorV2 > 0.5;\n"
            + "    float3 tint = tint4.rgb;\n"
            + "    if (lin) {\n"
            + "        col = float3(toLinearSrgb(half3(col)));\n"
            + "        tint = float3(toLinearSrgb(half3(tint)));\n"
            + "    }\n"
            + "    float l0 = lum(col);\n"
            + "    col = max(mix(float3(l0), col, uSat), float3(0.0));\n"
            + "    if (!lin) {\n"
            + "        col = min(col, float3(1.0));\n"
            + "    }\n"
            // Adaptive tint: pulled toward the local (filtered) backdrop, as adaptTo does for the bar.
            + "    if (uPass > 0.5 && uPass < 1.5 && uAdaptTint > 0.0) {\n"
            + "        tint = mix(tint, col, uAdaptTint);\n"
            + "    }\n"
            + "    col = mix(col, tint, tint4.a);\n"
            // Contrast protection, from the filtered backdrop: a local mean, which is what the glyphs
            // of the controls above actually sit on. Linear luminance is linear in the mix, so the
            // share that reaches the target has a closed form (LensModel.protection).
            + "    float prot = 0.0;\n"
            + "    if (uAdaptive > 0.5 && uPass > 0.5 && uPass < 1.5) {\n"
            + "        float3 linCol = lin ? col : float3(toLinearSrgb(half3(col)));\n"
            + "        float3 linP = float3(toLinearSrgb(half3(uProtect.rgb)));\n"
            + "        float lg = lum(linCol);\n"
            + "        float lc = lum(float3(toLinearSrgb(half3(uContent.rgb))));\n"
            + "        float lp = lum(linP);\n"
            + "        float need = 0.0;\n"
            + "        if (lc >= lp) {\n"
            + "            float limit = (lc + 0.05) / uContrast - 0.05;\n"
            + "            need = lg > limit ? (lg - limit) / max(lg - lp, 0.000001) : 0.0;\n"
            + "        } else {\n"
            + "            float limit = uContrast * (lc + 0.05) - 0.05;\n"
            + "            need = lg < limit ? (limit - lg) / max(lp - lg, 0.000001) : 0.0;\n"
            + "        }\n"
            + "        prot = clamp(need, 0.0, uProtectMax) * smoothstep(uProtFrom, uProtTo, t);\n"
            + "        float3 pTarget = lin ? linP : float3(uProtect.rgb);\n"
            + "        col = mix(col, pTarget, prot);\n"
            + "    }\n"
            + "    if (lin) {\n"
            + "        col = float3(fromLinearSrgb(half3(clamp(col, float3(0.0), float3(1.0)))));\n"
            + "    }\n"
            + "    if (uDebug > 4.5) {\n"
            + "        return half4(half3(float3(prot / max(uProtectMax, 0.001), beta, 0.0) * cov), half(cov));\n"
            + "    }\n"
            + "\n"
            // The rim's light. Artistic, in encoded values, as in the legacy shader; the colour
            // group lowers its gains and caps the hairline's channel separation.
            + "    float2 lightDir = normalize(uLight + float2(0.0001, 0.0));\n"
            + "    float facing = dot(n, -lightDir);\n"
            + "    float2 pn = p / max(hs, float2(1.0, 1.0));\n"
            + "    float phase = atan(pn.y, pn.x);\n"
            // Integer harmonics, so the pattern closes on itself round the outline.
            + "    float patches = clamp(0.52 + 0.31 * sin(phase * 3.0 + 0.7)\n"
            + "        + 0.17 * sin(phase * 5.0 - 2.1), 0.06, 1.0);\n"
            + "    float down = clamp(n.y, 0.0, 1.0);\n"
            + "    float floorDamp = 1.0 - 0.82 * down * down;\n"
            + "    float hairDamp = 1.0 - 0.30 * down * down;\n"
            + "    float hairPatches = mix(1.0, patches, 0.35);\n"
            + "    float bandW = max(uBevel * 0.34, 3.0);\n"
            + "    float band = clamp(1.0 - (-d) / bandW, 0.0, 1.0) * cov;\n"
            + "    float lit = pow(max(facing, 0.0), 3.0) * band * patches;\n"
            + "    float away = pow(max(-facing, 0.0), 1.4) * band * patches * floorDamp;\n"
            + "    float hw = max(uHair, 1.0);\n"
            + "    float sep = min(hw * 0.90 * uDispersion, uSepMax);\n"
            + "    float core = max(0.9, hw * 0.32);\n"
            + "    float ramp = max(hw * 0.5, 1.0);\n"
            + "    float reach = sep + core + ramp;\n"
            + "    float3 hair = float3(\n"
            + "        clamp((reach - abs(d + reach + 2.0 * sep)) / ramp, 0.0, 1.0),\n"
            + "        clamp((reach - abs(d + reach + sep)) / ramp, 0.0, 1.0),\n"
            + "        clamp((reach - abs(d + reach)) / ramp, 0.0, 1.0));\n"
            + "    hair *= clamp(1.0 - abs(d + reach + sep) / (reach + sep), 0.0, 1.0);\n"
            + "    float cmin = min(hair.r, min(hair.g, hair.b));\n"
            + "    hair = mix(float3(cmin), hair, 0.55);\n"
            + "    hair *= uHairGain;\n"
            + "    float2 bgCoord = clamp(coord - n * hw * 2.0, lo, hi);\n"
            + "    float4 bgSample = float4(content.eval(bgCoord));\n"
            + "    float bgLuma = lum(bgSample.rgb / max(bgSample.a, 0.001));\n"
            + "    float hairGain = mix(0.26, 1.0, smoothstep(0.05, 0.50, bgLuma)) * hairPatches * hairDamp;\n"
            + "    float3 warm = float3(1.0, 0.995, 0.98);\n"
            + "    float3 cool = float3(0.95, 0.975, 1.0);\n"
            + "    col += hair * (0.70 + 0.22 * max(facing, 0.0)) * hairGain * uSpec;\n"
            + "    col += warm * lit * uLitGain * uSpec * (1.0 + uPress * 0.10);\n"
            + "    col += cool * away * uAwayGain * uSpec;\n"
            + "    col = mix(col, activeTint4.rgb, activeTint4.a * activeCov);\n"
            + "    col += warm * activeEdge * 0.05 * uSpec;\n"
            + "    col *= 1.0 + uFlatGain * uSpec;\n"
            + "    float shadowW = clamp(uBevel * 0.9, 4.0, 40.0);\n"
            + "    float shade = pow(clamp(1.0 + d / shadowW, 0.0, 1.0), 1.5) * t\n"
            + "        * max(-facing, 0.0) * uInnerShadow;\n"
            + "    col = col * (1.0 - 0.26 * shade);\n"
            + "    col = clamp(col, float3(0.0), float3(1.0));\n"
            // The sharp pass carries (1-β) of the result, as premultiplied colour and alpha, so that
            // drawn over the soft pass it leaves exactly β of it (see the class note).
            + "    float w = uPass > 1.5 ? (1.0 - beta) : 1.0;\n"
            + "    return half4(half3(col * cov * w), half(cov * w));\n"
            + "}\n";
}
