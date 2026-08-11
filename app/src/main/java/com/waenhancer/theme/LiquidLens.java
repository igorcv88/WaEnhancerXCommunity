package com.waenhancer.theme;

import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.util.Log;
import android.view.View;

/**
 * The optics of a liquid-glass surface, as one AGSL pass.
 *
 * <p>Blur alone cannot make glass look liquid, and neither can a blur with a gradient painted on
 * top of it — that is frost with a lit edge, which is what this class used to produce. Glass is an
 * optical model, and the model is what is implemented here: a signed distance field describes the
 * shape, its gradient gives a surface normal, the normal bends the backdrop by a different amount
 * per colour channel, and the same normal decides which way each part of the rim faces the light.
 * Every one of those steps feeds the next, which is why they have to live in a single shader
 * rather than in a stack of drawables.</p>
 *
 * <p>The one rule the whole pass obeys: <b>no term may vary with how far down the surface a pixel
 * sits.</b> All of the light is spent within a bevel of an edge. A gradient running down the body
 * is dome shading, and dome shading is what makes a pane read as a moulded plastic bump however
 * transparent it is — which is the single most common way this effect fails.</p>
 *
 * <h3>What the pass does, in order</h3>
 *
 * <ol>
 *   <li><b>Coverage.</b> The rounded-rectangle SDF is the shape. Pixels outside it return
 *       transparent, so the surface antialiases its own outline and needs no outline clip.</li>
 *   <li><b>Normal.</b> A central difference on the SDF gives the outward 2D normal, which sweeps
 *       continuously round the corners and tells every term below which way the surface faces.</li>
 *   <li><b>Refraction.</b> The backdrop is sampled from further inside than the pixel being
 *       drawn, by an amount that grows toward the edge. That is what compresses the backdrop
 *       into a band at the rim — the single cue that reads as "lens" rather than "tint".</li>
 *   <li><b>Blur, graduated.</b> Nine taps at a radius that is zero on the outline and full a
 *       bevel inside. This belongs here rather than in the blur library beneath, because a
 *       backdrop that arrives pre-blurred has nothing left for the displacement above to bend:
 *       an even haze pushed sideways is the same even haze. Sharp at the rim, soft in the
 *       middle.</li>
 *   <li><b>Dispersion.</b> Red, green and blue are displaced by different amounts, so the
 *       compression band fringes into colour the way a real edge does.</li>
 *   <li><b>Rim.</b> A narrow near-white hairline on the outline, all the way round, sampled once
 *       per channel at slightly different depths so it fringes into colour. Behind it, one soft
 *       band per side: warm and tight where the surface faces the light, cooler and dimmer where
 *       it faces away. Both edges get one — an edge that fades out on the far side is the bump
 *       again.</li>
 *   <li><b>Inner shadow, tint, saturation.</b> Thickness a little way inside the rim rather than
 *       on it, then the variant's own colour.</li>
 * </ol>
 *
 * <p>Run through {@link View#setRenderEffect} on the view that already holds the captured
 * backdrop, so {@code content} is the real backdrop rather than an approximation of it — and as
 * sharp as that view can supply it, since this pass does its own blurring. The
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
 * and <a href="https://github.com/styropyr0/Prismal">styropyr0/Prismal</a> (the split between the
 * lit rim and the opposite-side glow). Prismal's Blinn-Phong gloss and Schlick-Fresnel terms were
 * ported too and have since been removed: with the surface normal tilted as gently as a thin pane
 * needs, Fresnel sat at 0.04 everywhere and never lit the far rim, while the gloss put its
 * brightest output in the middle of the body and none at the bottom edge — between them they
 * produced the dome this pass now has a rule against.</p>
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
            + "uniform float uHair;\n"
            + "uniform float4 uTint;\n"
            + "uniform float uSat;\n"
            + "uniform float uBlur;\n"
            + "\n"
            + "float sdRoundRect(float2 p, float2 b, float r) {\n"
            + "    float2 q = abs(p) - b + r;\n"
            + "    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;\n"
            + "}\n"
            + "\n"
            // Nine taps of the backdrop, in premultiplied form so the weighted sum stays correct
            // where the child input is partly transparent. A centre, a ring of four on the axes
            // and a ring of four on the diagonals: enough structure to read as depth of field
            // without the banding a single wide ring produces. At radius zero all nine collapse
            // onto the same texel and this is an expensive way to write content.eval(c) — which
            // is exactly what happens at the rim, and is the point.
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
            // Thickness. t runs 0 at the outline to 1 a full bevel inside, and slope is its
            // square, so the surface stands up steeply at the edge and is flat again well before
            // the body starts.
            //
            // Every term below is a function of d, t or n, and not one of them is a function of
            // how far down the surface a pixel sits. That is deliberate, and it is most of the
            // difference between glass and plastic: anything that varies smoothly from the top of
            // the body to the bottom is dome shading, and dome shading is what makes a pane read
            // as a moulded bump. The light belongs at the two edges or nowhere.
            + "    float t = clamp(-d / max(uBevel, 1.0), 0.0, 1.0);\n"
            + "    float edge = 1.0 - t;\n"
            + "    float slope = edge * edge;\n"
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
            + "\n"
            // The blur, graduated: none at the outline, full a bevel inside. This is the half of
            // the lens that used to be missing, and it was missing because it was happening in the
            // wrong place. The BlurView blurs the backdrop *before* the shader ever sees it, and
            // displacing the pixels of a uniform blur produces the same uniform blur — the
            // refraction above was already 40px wide on a 1440p device and was invisible for
            // exactly that reason. With the capture arriving sharp, the displacement finally has
            // structure to bend, and the softness the surface still needs for legibility is spent
            // where it costs nothing: the middle, well away from the rim that carries the effect.
            + "    float rad = t * uBlur;\n"
            + "    float4 back = sample9(cG, rad, lo, hi);\n"
            + "    float ca = back.a;\n"
            + "    if (ca < 0.01) {\n"
            // Nothing behind the surface to refract — a backdrop that failed to render. Fall back
            // to the flat tint rather than to black.
            + "        return half4(half3(uTint.rgb * cov), half(cov));\n"
            + "    }\n"
            + "    float3 col = back.rgb / ca;\n"
            // Dispersion stays a single sharp tap per channel, faded in toward the outline. The
            // fringe is a rim effect and the rim is where rad is near zero, so blurring the red
            // and blue taps as well would cost eighteen more samples to reproduce what the green
            // channel already carries everywhere the fringe is not visible.
            + "    float fringe = edge * edge;\n"
            + "    if (fringe > 0.004) {\n"
            + "        float4 sr = float4(content.eval(cR));\n"
            + "        float4 sb = float4(content.eval(cB));\n"
            + "        col.r = mix(col.r, sr.r / max(sr.a, 0.001), fringe);\n"
            + "        col.b = mix(col.b, sb.b / max(sb.a, 0.001), fringe);\n"
            + "    }\n"
            + "\n"
            // Saturation. Glass concentrates the light it passes, and a touch of vibrancy is what
            // keeps the refracted band from reading as grey mush once it has been blurred.
            + "    float lum = dot(col, float3(0.2126, 0.7152, 0.0722));\n"
            + "    col = clamp(mix(float3(lum), col, uSat), float3(0.0), float3(1.0));\n"
            + "\n"
            + "    col = mix(col, uTint.rgb, uTint.a);\n"
            + "\n"
            // Which way each part of the rim faces the light. uLight is the direction the light
            // travels and screen +Y points down, so a surface faces the light where its outward
            // normal opposes it — hence the negation.
            + "    float2 lightDir = normalize(uLight + float2(0.0001, 0.0));\n"
            + "    float facing = dot(n, -lightDir);\n"
            + "\n"
            // One band per side, each a fixed fraction of the bevel. The 12px cap this width
            // used to carry was 19% of a bottom bar's bevel: it squeezed both bands into a sliver
            // beside the outline, and since the away-side band was already being scaled by a
            // Fresnel term pinned at 0.04, the lower half of the pill got no rim at all and its
            // bottom edge simply faded into the list. A pane whose edge fades out instead of
            // closing is a bump, whichever side it fades on.
            + "    float bandW = max(uBevel * 0.6, 3.0);\n"
            + "    float band = clamp(1.0 - (-d) / bandW, 0.0, 1.0) * cov;\n"
            + "    float lit = pow(max(facing, 0.0), 3.0) * band;\n"
            + "    float away = pow(max(-facing, 0.0), 1.2) * band;\n"
            + "\n"
            // The hairline: a narrow, near-white band just inside the outline, present the whole
            // way round. This is what the eye reads as the boundary of a sheet of glass, and it
            // has to stay narrow — a wide band of the same brightness is a lit lip rather than an
            // edge. Its width comes from the caller in dp, because the 1.5px it used to assume
            // was sub-pixel on a dense screen and antialiasing removed most of it.
            //
            // Sampled once per channel at slightly different depths. Blue is displaced furthest
            // outward, as the shortest wavelength is, so the edge fringes cool on its outer flank
            // and warm on its inner one. Dispersion is per-wavelength, and over a dark chat list
            // the backdrop has no detail left for the refraction above to fringe, so this is where
            // the colour in a glass edge actually comes from.
            + "    float hw = max(uHair, 1.0);\n"
            + "    float sep = hw * 0.35 * uDispersion;\n"
            + "    float3 hair = float3(\n"
            + "        clamp(1.0 - abs(d + hw + sep) / hw, 0.0, 1.0),\n"
            + "        clamp(1.0 - abs(d + hw) / hw, 0.0, 1.0),\n"
            + "        clamp(1.0 - abs(d + hw - sep) / hw, 0.0, 1.0));\n"
            + "\n"
            + "    float3 warm = float3(1.0, 0.995, 0.98);\n"
            + "    float3 cool = float3(0.95, 0.975, 1.0);\n"
            + "\n"
            // The rim saturates to white where it faces the light and settles at roughly two
            // thirds of that where it faces away; the two bands behind it fall off inward. These
            // are additive, so the sum is what matters — and all of it is spent within a bevel of
            // an edge, which is what lets the body stay flat.
            + "    col += hair * (0.45 + 0.60 * max(facing, 0.0)) * uSpec;\n"
            + "    col += warm * lit * 0.36 * uSpec;\n"
            + "    col += cool * away * 0.28 * uSpec;\n"
            + "\n"
            // A flat pass-through gain, and flat is the whole point: glass carries a little more
            // light than the gap beside it, but any variation down the body brings the dome back.
            // This replaces a Blinn-Phong term whose exponent of 48, evaluated against a normal
            // that was nearly flat across the body, put its brightest output in the middle of the
            // surface and none of it at the bottom rim — top-lit body shading exactly.
            + "    col += warm * 0.07 * uSpec;\n"
            + "\n"
            // Inner shadow, for the thickness of the pane. Multiplied by t so it vanishes at the
            // outline: it used to peak exactly there, on the same pixels as the rim and on the one
            // side that most needed closing off. It belongs a little way in, where it reads as
            // thickness rather than as a soft outer fade.
            + "    float shadowW = clamp(uBevel * 0.9, 4.0, 40.0);\n"
            + "    float shade = pow(clamp(1.0 + d / shadowW, 0.0, 1.0), 1.5) * t\n"
            + "        * max(-facing, 0.0) * uInnerShadow;\n"
            + "    col = col * (1.0 - 0.26 * shade);\n"
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

    /**
     * Cap on the displacement, as a fraction of the bevel width. Beyond this the rim smears.
     *
     * <p>This is the effect. Cutting it back to a third — while also narrowing the bevel — took
     * the displacement from 68px to 10px on a phone-sized bar and removed the visible bending
     * along with the problem it was blamed for; the lit trough was the inverted light vector, not
     * the magnitude. Kept high enough to compress the backdrop visibly at the edge, short of the
     * point where recognisable content is dragged bodily out of place and reads as a smear.</p>
     */
    private static final float MAX_DISPLACEMENT = 0.62f;

    /**
     * Widest the bevel may be, as a fraction of the surface's height.
     *
     * <p>The meniscus has to end somewhere short of the middle or the surface is all edge. At
     * half the height — the old limit — the two rims met in the centre of a bottom bar and the
     * pill had no flat body left at all: every pixel was being displaced, lit and shadowed, which
     * is what turned it into a lit trough instead of a pane with a bright edge.</p>
     *
     * <p>A third of the height leaves a clear band down the middle while still giving the rim
     * enough room to be seen bending. Below roughly a quarter the rim gets too narrow to read as
     * glass thickness and the surface flattens into a plain tinted panel.</p>
     */
    private static final float MAX_BEVEL_FRACTION = 0.34f;

    /** Vibrancy applied at full lens strength; 1.0 leaves saturation untouched. */
    private static final float MAX_SATURATION = 1.25f;

    /**
     * How much in-shader blur each unit of the spec's own blur radius buys, in dp.
     *
     * <p>The spec's radius is resolution-independent by construction — the blur library works on
     * a downscaled copy of the backdrop, which is why {@code GlassRenderer.blurRadius} explicitly
     * refuses to multiply it by density. Here the sampling happens at full resolution, so the
     * conversion has to be made, and it is made once, here.</p>
     */
    private static final float BLUR_DP_PER_UNIT = 0.70f;

    /**
     * Ceiling on the in-shader blur, in pixels.
     *
     * <p>Nine taps is a depth of field, not a Gaussian. Spread them far enough apart and the rings
     * stop overlapping and start reading as ghosts of the backdrop rather than as a blur of it.
     * Sixteen pixels keeps the outer ring within about a tap-width of the middle one on a dense
     * screen; going wider needs more taps, and more taps is the cost that Phase 2 cannot afford
     * multiplied across many surfaces.</p>
     */
    private static final float MAX_BLUR_PX = 16f;

    /**
     * Radius to hand the blur library when a lens is going to run on top of it.
     *
     * <p>Not zero, and not {@code setBlurEnabled(false)}: the BlurView is what captures the
     * backdrop into the view in the first place, and without it {@code content} arrives empty and
     * the shader falls through to its flat-tint path. What it must not do is arrive already
     * blurred — see the note beside {@code rad} in the shader. This is the smallest radius that
     * still leaves the capture running.</p>
     */
    public static final float CAPTURE_BLUR_RADIUS = 1f;

    /**
     * Half-width of the bright hairline on the outline, in dp.
     *
     * <p>In dp rather than px because that is the one thing about this shader that has to hold a
     * constant apparent size: the hairline is what the eye reads as the boundary of a sheet of
     * glass. The 1.5px it was previously hard-coded to is sub-pixel on a 3.5x screen, so
     * antialiasing spread it out and dimmed it, and the pill lost its outline exactly on the
     * devices with enough resolution to show one.</p>
     *
     * <p>The band spans this either side of its centre, so 0.95dp is a little under 7px of total
     * width at 3.5x — narrow enough to read as an edge rather than as a lit lip.</p>
     */
    private static final float HAIRLINE_DP = 0.95f;

    /** Bounds on the hairline in px, so it survives a low-density screen and never becomes a band. */
    private static final float MIN_HAIRLINE_PX = 1.5f;
    private static final float MAX_HAIRLINE_PX = 5f;

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

    /** Log tag for the one thing about this class that is not visible on screen: why it declined. */
    private static final String TAG = "WaEnhancerX/Lens";

    /**
     * Why the last {@link #apply} call did what it did.
     *
     * <p>A lens that declines is indistinguishable on screen from a lens that was never asked
     * for — both leave the surface exactly as the fallback painted it. That makes "nothing
     * changed" an ambiguous symptom, and this is what disambiguates it.</p>
     */
    private static volatile String status = "not attempted";

    private LiquidLens() { }

    /** Human-readable account of the last {@link #apply} call. Diagnostic only. */
    public static String status() {
        return status;
    }

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
     *                       be drawing the captured backdrop, opaque, across its whole bounds,
     *                       and should hand it over unblurred — see {@link #CAPTURE_BLUR_RADIUS}
     * @param spec           the resolved surface
     * @param cornerRadiusPx the surface's corner radius, already clamped to the view
     * @param density        display density
     * @return true when a lens is now installed
     */
    public static boolean apply(View view, GlassSpec spec, float cornerRadiusPx, float density) {
        if (view == null) {
            status = "no view";
            return false;
        }
        if (!isActiveFor(spec)) {
            status = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                    ? "declined: needs Android 13, device is API " + Build.VERSION.SDK_INT
                    : broken ? "declined: shader previously rejected by this device"
                    : spec == null ? "declined: no spec"
                    : "declined: variant has no lens (lensStrength=" + spec.lensStrength
                            + ") - pick Liquid, Advanced or Clear as the glass style";
            clear(view);
            return false;
        }
        int width = view.getWidth();
        int height = view.getHeight();
        if (width <= 0 || height <= 0) {
            status = "deferred: surface not measured yet (" + width + "x" + height + ")";
            return false;
        }

        // The rim is bounded by the surface's own height, not only by the variant's request: a
        // bar is short, and a rim specified in dp that is fine on a card covers a bottom bar
        // end to end. See MAX_BEVEL_FRACTION.
        float bevel = Math.max(1f,
                Math.min(spec.rimWidthDp * density, height * MAX_BEVEL_FRACTION));
        float refract = spec.lensStrength * MAX_DISPLACEMENT * bevel;
        float saturation = 1f + (MAX_SATURATION - 1f) * spec.lensStrength;
        float hairline = Math.max(MIN_HAIRLINE_PX,
                Math.min(HAIRLINE_DP * density, MAX_HAIRLINE_PX));
        // The softness the surface needs is this pass's job now, not the blur library's. A spec
        // that asked for no blur at all still gets none, and the lens degrades to a sharp
        // backdrop rather than inventing one.
        float blur = Math.max(0f,
                Math.min(MAX_BLUR_PX, spec.blurRadius * BLUR_DP_PER_UNIT * density));

        String key = width + "x" + height + ":" + cornerRadiusPx + ":" + bevel + ":" + refract
                + ":" + spec.dispersion + ":" + spec.specular + ":" + spec.innerShadow
                + ":" + spec.fillColor + ":" + saturation + ":" + hairline + ":" + blur;
        if (key.equals(installed.get(view))) {
            status = "active (unchanged)";
            return true;
        }

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
            shader.setFloatUniform("uHair", hairline);
            shader.setFloatUniform("uTint",
                    Color.red(spec.fillColor) / 255f,
                    Color.green(spec.fillColor) / 255f,
                    Color.blue(spec.fillColor) / 255f,
                    Color.alpha(spec.fillColor) / 255f);
            shader.setFloatUniform("uSat", saturation);
            shader.setFloatUniform("uBlur", blur);

            view.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"));
            installed.put(view, key);
            status = "active: " + width + "x" + height + " bevel=" + bevel + "px refract="
                    + refract + "px dispersion=" + spec.dispersion + " hair=" + hairline
                    + "px blur=" + blur + "px";
            return true;
        } catch (Throwable t) {
            // A device that refuses the shader keeps the layered rim rather than losing the bar,
            // and stops being asked again. Logged rather than swallowed: a rejected shader and a
            // variant with no lens look identical on screen, and only one of them is a bug.
            broken = true;
            status = "shader rejected by this device: " + t;
            Log.e(TAG, "AGSL lens rejected; falling back to the layered rim", t);
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
