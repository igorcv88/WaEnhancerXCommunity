package com.waenhancer.theme;

import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.view.View;

/**
 * The optics of a liquid-glass surface, as one AGSL pass.
 *
 * <p>Blur alone cannot make glass look liquid, and neither can a blur with a gradient painted on
 * top of it — that is frost with a lit edge, which is what this class used to produce. Glass is an
 * optical model, and the model is what is implemented here: a signed distance field describes the
 * shape, its gradient gives a surface normal, the normal bends the backdrop by a different amount
 * per colour channel, and the same normal lights the rim against a fixed light source. Every one
 * of those steps feeds the next, which is why they have to live in a single shader rather than in
 * a stack of drawables.</p>
 *
 * <h3>What the pass does, in order</h3>
 *
 * <ol>
 *   <li><b>Coverage.</b> The rounded-rectangle SDF is the shape. Pixels outside it return
 *       transparent, so the surface antialiases its own outline and needs no outline clip.</li>
 *   <li><b>Normal.</b> A central difference on the SDF gives the outward 2D normal; the
 *       thickness profile turns that into a 3D one that tips outward as it approaches the rim.</li>
 *   <li><b>Refraction.</b> The backdrop is sampled from further inside than the pixel being
 *       drawn, by an amount that grows toward the edge. That is what compresses the backdrop
 *       into a band at the rim — the single cue that reads as "lens" rather than "tint".</li>
 *   <li><b>Dispersion.</b> Red, green and blue are displaced by different amounts, so the
 *       compression band fringes into colour the way a real edge does.</li>
 *   <li><b>Specular.</b> Fresnel drives how reflective the surface is at grazing angles; the rim
 *       is bright where it faces the light and carries a softer, cooler glow where it faces
 *       away. The asymmetry between those two is most of the perceived realism.</li>
 *   <li><b>Inner shadow, tint, saturation.</b> Thickness on the backlit side, then the variant's
 *       own colour.</li>
 * </ol>
 *
 * <p>Run through {@link View#setRenderEffect} on the view that already holds the blurred
 * backdrop, so {@code content} is the real backdrop rather than an approximation of it. The
 * displacement is always inward: a {@code RuntimeShader} child input only guarantees samples
 * inside the output clip, so reaching outward would read transparent black and ring the outline
 * in black.</p>
 *
 * <p>{@code RuntimeShader} arrived in Android 13; {@link #isSupported()} reports whether this
 * device can have it, and callers fall back to {@link GlassRenderer}'s layered rim, which reads
 * as a lit bevel rather than as refraction.</p>
 *
 * <p>The optical model is ported from two MIT-licensed projects:
 * <a href="https://github.com/QWEA0/Liquid-Glass-Android">QWEA0/Liquid-Glass-Android</a>
 * (SDF coverage, gradient normal, inward refraction with per-channel dispersion, inner shadow)
 * and <a href="https://github.com/styropyr0/Prismal">styropyr0/Prismal</a> (circular thickness
 * profile, Schlick–Fresnel, and the split between the lit rim and the opposite-side glow).</p>
 */
public final class LiquidLens {

    /**
     * The lens.
     *
     * <p>All geometry is in pixels, centred on the view. {@code d} is the signed distance to the
     * shape: negative inside, zero on the outline. Nearly every term below is a function of it,
     * which is what keeps them consistent with each other and with the actual corner radius.</p>
     */
    private static final String SHADER = ""
            + "uniform shader content;\n"
            + "uniform float2 uSize;\n"
            + "uniform float uRadius;\n"
            + "uniform float uBevel;\n"
            + "uniform float uRefract;\n"
            + "uniform float uDispersion;\n"
            + "uniform float2 uLight;\n"
            + "uniform float uSpec;\n"
            + "uniform float uInnerShadow;\n"
            + "uniform float4 uTint;\n"
            + "uniform float uSat;\n"
            + "\n"
            + "float sdRoundRect(float2 p, float2 b, float r) {\n"
            + "    float2 q = abs(p) - b + r;\n"
            + "    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 hs = uSize * 0.5;\n"
            + "    float2 p = coord - hs;\n"
            + "    float r = min(uRadius, min(hs.x, hs.y));\n"
            + "    float d = sdRoundRect(p, hs, r);\n"
            + "\n"
            // Coverage. A 1.5px feather is the shape's own antialiasing; outside it there is
            // nothing to draw, and returning early keeps the cost of the corners off the body.
            + "    float cov = clamp(0.5 - d / 1.5, 0.0, 1.0);\n"
            + "    if (cov <= 0.004) {\n"
            + "        return half4(0.0);\n"
            + "    }\n"
            + "\n"
            // Outward normal, by central difference on the field itself. Taking it from the field
            // rather than from the rectangle's sides is what makes the corners behave like
            // corners: the normal sweeps continuously round them.
            + "    float2 n = float2(\n"
            + "        sdRoundRect(p + float2(1.0, 0.0), hs, r) - sdRoundRect(p - float2(1.0, 0.0), hs, r),\n"
            + "        sdRoundRect(p + float2(0.0, 1.0), hs, r) - sdRoundRect(p - float2(0.0, 1.0), hs, r));\n"
            + "    float nLen = length(n);\n"
            + "    n = nLen > 0.0001 ? n / nLen : float2(0.0, -1.0);\n"
            + "\n"
            // Thickness. t runs 0 at the outline to 1 a full bevel inside. The height profile is
            // a circular meniscus rather than a linear ramp, so the surface stands up steeply
            // right at the edge and flattens quickly — a bevel reads as a chamfer, a meniscus
            // reads as glass.
            + "    float t = clamp(-d / max(uBevel, 1.0), 0.0, 1.0);\n"
            + "    float edge = 1.0 - t;\n"
            + "    float slope = edge * edge;\n"
            + "    float height = sqrt(max(2.0 * t - t * t, 0.0));\n"
            + "    float tilt = clamp((1.0 - t) / max(height, 0.08), 0.0, 6.0);\n"
            + "    float3 N = normalize(float3(n * tilt * 0.42, 1.0));\n"
            + "\n"
            // Refraction, inward only. See the class note: sampling outward reads transparent
            // black off the edge of the child input and rings the outline.
            + "    float2 offset = -n * (slope * uRefract);\n"
            + "    float2 lo = float2(1.0, 1.0);\n"
            + "    float2 hi = uSize - float2(1.0, 1.0);\n"
            + "    float2 spread = offset * uDispersion * slope;\n"
            + "    float2 cR = clamp(coord + offset - spread, lo, hi);\n"
            + "    float2 cG = clamp(coord + offset, lo, hi);\n"
            + "    float2 cB = clamp(coord + offset + spread, lo, hi);\n"
            + "    half4 centre = content.eval(cG);\n"
            + "    float ca = float(centre.a);\n"
            + "    if (ca < 0.01) {\n"
            // Nothing behind the surface to refract — a backdrop that failed to render. Fall back
            // to the flat tint rather than to black.
            + "        return half4(half3(uTint.rgb * cov), half(cov));\n"
            + "    }\n"
            + "    float3 col = float3(\n"
            + "        float(content.eval(cR).r), float(centre.g), float(content.eval(cB).b)) / ca;\n"
            + "\n"
            // Saturation. Glass concentrates the light it passes, and a touch of vibrancy is what
            // keeps the refracted band from reading as grey mush once it has been blurred.
            + "    float lum = dot(col, float3(0.2126, 0.7152, 0.0722));\n"
            + "    col = clamp(mix(float3(lum), col, uSat), float3(0.0), float3(1.0));\n"
            + "\n"
            + "    col = mix(col, uTint.rgb, uTint.a);\n"
            + "\n"
            // Schlick-Fresnel. Near the rim the surface is seen almost edge-on, F approaches 1,
            // and that is where a real pane turns into a mirror. Everything below is scaled by
            // it, which is why the effect concentrates at the edge without being masked there.
            + "    float cosVN = clamp(N.z, 0.0, 1.0);\n"
            + "    float fres = 0.04 + 0.96 * pow(1.0 - cosVN, 5.0);\n"
            + "\n"
            // The rim, split by which way it faces. The lit side gets a tight white band; the
            // side facing away gets a broader, dimmer Fresnel glow. Screenshot-realistic glass
            // is mostly this asymmetry — a rim of uniform brightness reads as a drawn stroke.
            + "    float2 lightDir = normalize(uLight + float2(0.0001, 0.0));\n"
            + "    float facing = dot(n, lightDir);\n"
            + "    float bandW = clamp(uBevel * 0.45, 2.0, 12.0);\n"
            + "    float band = clamp(1.0 - (-d) / bandW, 0.0, 1.0) * cov;\n"
            + "    float lit = pow(max(facing, 0.0), 3.0) * band;\n"
            + "    float opposite = pow(max(-facing, 0.0), 1.2) * band * fres;\n"
            + "\n"
            // The hairline: a sub-pixel band exactly on the outline, present all the way round.
            // It is what gives the surface a crisp boundary once the body has gone transparent.
            + "    float hair = clamp(1.0 - abs(d + 1.0) / 1.5, 0.0, 1.0);\n"
            + "\n"
            // Blinn-Phong off the same normal field, so the highlight moves with the geometry
            // instead of sitting where a gradient happened to be inset to.
            + "    float3 L = normalize(float3(lightDir, 1.4));\n"
            + "    float3 H = normalize(L + float3(0.0, 0.0, 1.0));\n"
            + "    float gloss = pow(max(dot(N, H), 0.0), 48.0) * (0.3 + 0.7 * height);\n"
            + "\n"
            + "    float3 warm = float3(1.0, 0.995, 0.98);\n"
            + "    float3 cool = float3(0.95, 0.975, 1.0);\n"
            + "    col += warm * (lit * 0.85 + gloss + hair * (0.16 + 0.30 * max(facing, 0.0))) * uSpec;\n"
            + "    col += cool * opposite * uSpec * 0.55;\n"
            + "\n"
            // Inner shadow on the backlit side. Thickness is only legible if the surface is
            // darker where the light did not get through it.
            + "    float shadowW = clamp(uBevel * 1.6, 4.0, 30.0);\n"
            + "    float shade = pow(clamp(1.0 + d / shadowW, 0.0, 1.0), 1.5)\n"
            + "        * max(-facing, 0.0) * uInnerShadow;\n"
            + "    col = col * (1.0 - 0.45 * shade);\n"
            + "\n"
            + "    col = clamp(col, float3(0.0), float3(1.0));\n"
            + "    return half4(half3(col * cov), half(cov));\n"
            + "}\n";

    /**
     * Direction the surface is lit from, in screen space: down and to the right, i.e. the light
     * sits above and to the left of the bar.
     *
     * <p>Fixed rather than sensor-driven. A bar pinned to the bottom of a scrolling list is not
     * an object the user turns over in their hand, and a highlight that slides around as the
     * phone tilts draws attention to the chrome instead of to the content.</p>
     */
    private static final float LIGHT_X = 0.45f;
    private static final float LIGHT_Y = 0.89f;

    /** Cap on the displacement, as a fraction of the bevel width. Beyond this the rim smears. */
    private static final float MAX_DISPLACEMENT = 0.9f;

    /** Vibrancy applied at full lens strength; 1.0 leaves saturation untouched. */
    private static final float MAX_SATURATION = 1.25f;

    /**
     * What each view's current effect was built from.
     *
     * <p>The shader bakes in the view's size and radius, so it has to be rebuilt when those
     * change — but callers refresh on every layout pass, and allocating a {@code RuntimeShader}
     * and a {@code RenderEffect} per pass would put that cost on every frame of a scroll for no
     * visible difference.</p>
     */
    private static final java.util.WeakHashMap<View, String> installed = new java.util.WeakHashMap<>();

    /**
     * Set once a device has refused to compile the shader.
     *
     * <p>A driver that rejects the AGSL will reject it again on the next layout pass. Without
     * this the bar would try to compile it on every frame of every scroll, which costs far more
     * than the effect it is failing to produce.</p>
     */
    private static volatile boolean broken;

    private LiquidLens() { }

    /** Whether this device can run the lens at all. */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !broken;
    }

    /**
     * Whether {@code spec} wants a lens, and this device can give it one.
     *
     * <p>Callers need this before they paint, not after: the lens produces its own fill, edge and
     * coverage, so the layered background and the blur's overlay tint have to be left off when it
     * is active or the surface is painted twice and reads as opaque.</p>
     */
    public static boolean isActiveFor(GlassSpec spec) {
        return isSupported() && spec != null && spec.lensStrength > 0f;
    }

    /**
     * Puts the lens on {@code view}, or takes it off when the spec does not call for one.
     *
     * <p>Safe to call on every layout pass: the effect is rebuilt only when something baked into
     * it actually changed.</p>
     *
     * @param view           the view whose rendered output is the backdrop to bend; must already
     *                       be drawing the blurred backdrop, opaque, across its whole bounds
     * @param spec           the resolved surface
     * @param cornerRadiusPx the surface's corner radius, already clamped to the view
     * @param density        display density
     * @return true when a lens is now installed
     */
    public static boolean apply(View view, GlassSpec spec, float cornerRadiusPx, float density) {
        if (view == null) return false;
        if (!isActiveFor(spec)) {
            clear(view);
            return false;
        }
        int width = view.getWidth();
        int height = view.getHeight();
        if (width <= 0 || height <= 0) return false;

        // A bevel wider than half the bar would have the two rims meeting in the middle, which
        // turns the whole surface into edge and loses the clear centre the lens exists to keep.
        float bevel = Math.max(1f, Math.min(spec.rimWidthDp * density, height * 0.5f));
        float refract = spec.lensStrength * MAX_DISPLACEMENT * bevel;
        float saturation = 1f + (MAX_SATURATION - 1f) * spec.lensStrength;

        String key = width + "x" + height + ":" + cornerRadiusPx + ":" + bevel + ":" + refract
                + ":" + spec.dispersion + ":" + spec.specular + ":" + spec.innerShadow
                + ":" + spec.fillColor + ":" + saturation;
        if (key.equals(installed.get(view))) return true;

        try {
            RuntimeShader shader = new RuntimeShader(SHADER);
            shader.setFloatUniform("uSize", width, height);
            shader.setFloatUniform("uRadius", cornerRadiusPx);
            shader.setFloatUniform("uBevel", bevel);
            shader.setFloatUniform("uRefract", refract);
            shader.setFloatUniform("uDispersion", spec.dispersion);
            shader.setFloatUniform("uLight", LIGHT_X, LIGHT_Y);
            shader.setFloatUniform("uSpec", spec.specular);
            shader.setFloatUniform("uInnerShadow", spec.innerShadow);
            shader.setFloatUniform("uTint",
                    Color.red(spec.fillColor) / 255f,
                    Color.green(spec.fillColor) / 255f,
                    Color.blue(spec.fillColor) / 255f,
                    Color.alpha(spec.fillColor) / 255f);
            shader.setFloatUniform("uSat", saturation);

            view.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"));
            installed.put(view, key);
            return true;
        } catch (Throwable ignored) {
            // A device that refuses the shader keeps the layered rim rather than losing the bar,
            // and stops being asked again.
            broken = true;
            clear(view);
            return false;
        }
    }

    /** Removes any lens previously installed on {@code view}. */
    public static void clear(View view) {
        if (view == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        try {
            view.setRenderEffect(null);
            installed.remove(view);
        } catch (Throwable ignored) {
        }
    }
}
