package com.waenhancer.theme;

import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.animation.ValueAnimator;
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
 *   <li><b>Rim.</b> A narrow hairline just inside the outline, sampled once per channel at
 *       slightly different depths so it fringes into colour — each tap
 *       flat-topped across at least that separation, so the core of the line stays white and only
 *       its two flanks carry hue, which is what makes it a fringe rather than a coloured stroke —
 *       and modulated along the perimeter so
 *       the light arrives in short runs rather than as an even outline. Behind it, one soft band
 *       per side: warm and tight where the surface faces the light, and almost nothing where it
 *       faces away — a downward-facing edge cannot catch a specular run from a light above it,
 *       and a bright lower rim under a bright upper one is the cross section of a tube.</li>
 *   <li><b>Ambient.</b> A flat lift, so the body reads as a translucent sheet over a black list
 *       instead of as the same black with a lit outline drawn round it.</li>
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
            + "uniform float uSpread;\n"
            + "uniform float4 uTint;\n"
            + "uniform float uSat;\n"
            + "uniform float uBlur;\n"
            + "uniform float2 uActiveCenter;\n"
            + "uniform float2 uActiveHalf;\n"
            + "uniform float uActiveRadius;\n"
            + "uniform float4 uActiveTint;\n"
            + "uniform float uActive;\n"
            + "uniform float uPress;\n"
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
            + "    float ad = sdRoundRect(coord - uActiveCenter, uActiveHalf, uActiveRadius);\n"
            + "    float activeCov = uActive * clamp(0.5 - ad / 1.5, 0.0, 1.0);\n"
            + "    float activeEdge = activeCov * clamp(1.0 + ad / max(uBevel * 0.45, 1.0), 0.0, 1.0);\n"
            + "\n"
            // Refraction, inward only. See the class note: sampling outward reads transparent
            // black off the edge of the child input and rings the outline.
            //
            // Very slightly weighted toward the rounded ends, where the normal turns horizontal
            // and the displacement compresses the backdrop into the curve rather than sliding it
            // along the edge.
            //
            // The floor was 0.38 for one build and that was a mistake worth recording: the long
            // top and bottom runs are most of the border's length, so cutting their displacement
            // by two thirds took the chromatic fringe off almost the whole outline. The fringe is
            // the point — it is what makes the edge readable as an optical event rather than as a
            // drawn line — and it is proportional to this displacement, because the per-channel
            // taps below are offsets from it. Bias the ends a little; do not starve the sides.
            + "    float curvature = mix(0.80, 1.0, abs(n.x));\n"
            + "    float2 offset = -n * (slope * uRefract * curvature) * (1.0 + activeEdge * 0.18);\n"
            + "    float2 lo = float2(1.0, 1.0);\n"
            + "    float2 hi = uSize - float2(1.0, 1.0);\n"
            // How far apart the three channels are sampled. This was a straight fraction of the
            // displacement, and the displacement is large by design — which made this the biggest
            // uncontrolled term in the pass and, it turns out, the actual source of the coloured
            // rim that three rounds of hairline work could not shift.
            //
            // Measured 2026-08-12, same build, two backdrops: over the bar's uniform dark list the
            // rim's increment over the body is (47,47,47) — exactly neutral — while over the
            // conversation wallpaper the button's rim reaches G-R +31. Displacing a flat field
            // produces nothing, so a term whose output scales with backdrop *structure* is the
            // only kind that can do that, and this is the only structure-dependent, channel-
            // selective term there is.
            //
            // The magnitude says the same thing. On the button, bevel 25.5px gives refract 15.8px
            // and so spread = 15.8 * 0.55 = 8.7px; on the bar it is 17px. Red and blue were being
            // read 17 to 34 pixels apart from each other — different wallpaper entirely — against
            // the hairline's 1.41px. Green won because it alone comes from sample9's nine-tap
            // average while red and blue are single sharp taps thrown that far out onto a mostly
            // dark wallpaper.
            //
            // Capped in dp, for the same reason the hairline is: this is a fringe, and a fringe has
            // to hold a constant apparent width. It stays proportional to the displacement below
            // the cap, so regra 5 still holds — starve the refraction in a region and the colour
            // there still goes with it.
            + "    float2 spread = offset * uDispersion * slope;\n"
            + "    float spreadLen = length(spread);\n"
            + "    spread = spreadLen > uSpread ? spread * (uSpread / max(spreadLen, 0.0001)) : spread;\n"
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
            // Where round the outline this pixel sits, as an angle. Taken from the position
            // normalised by the half-size rather than from the normal, because the normal is
            // constant along a straight run: anything keyed to atan(n.y, n.x) can only vary at
            // the corners, which is why the previous modulation showed up as four dark notches
            // interrupting an otherwise perfectly even bright outline. This sweeps continuously
            // along the long edges as well.
            + "    float2 pn = p / max(hs, float2(1.0, 1.0));\n"
            + "    float phase = atan(pn.y, pn.x);\n"
            + "\n"
            // How much of the light this stretch of rim actually catches. Light on a real
            // transparent edge arrives in short runs — a few segments bright, most of the
            // perimeter barely lit at all — and it is that unevenness, not the brightness, that
            // separates a lit edge from a stroke drawn round a shape. Two incommensurable
            // harmonics, so nothing repeats over one circuit of the pill and the pattern does not
            // depend on which tab is selected.
            + "    float patches = clamp(0.52 + 0.31 * sin(phase * 3.0 + 0.7)\n"
            + "        + 0.17 * sin(phase * 5.0 - 2.1), 0.06, 1.0);\n"
            + "\n"
            // The lower edge is separation, not highlight. A downward-facing surface cannot catch
            // a specular run from a light above it, and a bright bottom rim beneath a bright top
            // rim is the cross section of a tube — bright, dark, bright — which is the single
            // strongest reason the bar read as a moulded relief rather than as a sheet. What
            // closes the bottom edge instead is the inner shadow below and the short drop shadow
            // the host casts, both of which read as the pane sitting above the list.
            + "    float down = clamp(n.y, 0.0, 1.0);\n"
            + "    float floorDamp = 1.0 - 0.82 * down * down;\n"
            + "\n"
            // One band per side, each a fixed fraction of the bevel. Narrow: at 0.6 of the bevel
            // the band was 35px on a phone-sized bar, wide enough to read as a lit lip rather
            // than as an edge, and wide enough that the two of them left only a dark strip
            // between.
            + "    float bandW = max(uBevel * 0.34, 3.0);\n"
            + "    float band = clamp(1.0 - (-d) / bandW, 0.0, 1.0) * cov;\n"
            + "    float lit = pow(max(facing, 0.0), 3.0) * band * patches;\n"
            + "    float away = pow(max(-facing, 0.0), 1.4) * band * patches * floorDamp;\n"
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
            // How far apart the three channels of the hairline sit. At 0.35 this was about
            // 0.6px on a dense screen — sub-pixel, so antialiasing averaged the three back into
            // white and the edge could not carry colour however high dispersion went. Over a dark
            // chat list the backdrop has no detail left for the refraction above to fringe, so
            // this is where the colour in the edge actually comes from, and it has to be at least
            // a pixel or two to survive being resolved.
            + "    float sep = hw * 0.90 * uDispersion;\n"
            // Why this used to draw a coloured line instead of a fringe, and why two rounds of
            // moving the taps about could not fix it.
            //
            // The taps were triangles: each channel peaked at a point and fell away linearly on
            // both sides. Three triangles a distance sep apart means the middle one peaks where
            // the outer two have already dropped to 1 - sep/hw. At the shipped dispersion that is
            // 0.505, so the brightest row of the whole hairline is (0.505, 1.0, 0.505) — a
            // saturated green, and the row a viewer's eye picks out as "the line", since green
            // alone carries 71% of the luma weight. It is green because green is the middle
            // channel, and for no other reason: it does not depend on the backdrop, the surface
            // size or the geometry, which is exactly what the reports said — the same hue on the
            // bar and on the round button, over completely different content.
            //
            // That also explains the two failed rounds. Before the group was biased inward, blue's
            // triangle spilled past the outline onto near-black backdrop and its exposure beat the
            // shape, so the line read cyan; biasing it inward removed the spill and left the
            // shape's own maximum showing, so the line turned green. And a flat per-channel gain
            // could never have touched it: at that same row red and blue are equal by
            // construction, so there is no per-channel deficit there for a gain to correct.
            //
            // A real edge does not fringe this way because a real edge is not a spike. Disperse a
            // band of white wider than the wavelength shift and you get a white core with a warm
            // flank on one side and a cool flank on the other. So give each tap a flat top at
            // least as wide as the separation: over the core all three channels are saturated
            // together and the line is white, and colour survives only on the two flanks, where
            // one channel has rolled off and another has not.
            //
            // The first attempt at this sized the flat top as max(hw * 0.5, sep) and shipped. It
            // measured as almost no change, and the arithmetic says why: the white core is only as
            // wide as the flat top *exceeds* the separation, and hw * 0.5 = 1.425px against a
            // sep of 1.411px is an all-white core 0.028px wide. A core narrower than a pixel is
            // not a core, so green went on winning the middle. The device confirmed the shape was
            // doing something — along the brightest rim, B-G swept +13 -> +1 -> -5 from the outer
            // flank through the centre to the inner one, which is dispersion behaving correctly —
            // while G-R never crossed zero, which is that missing core.
            //
            // So the core is now a width in its own right, and everything else is derived from it
            // rather than from hw. The tap reaches sep (to place the neighbouring channel) plus
            // core (the achromatic centre) plus ramp (the roll-off that gives the flanks their
            // colour), and the three of them cannot silently collapse into each other the way a
            // single max() let them. Simulated at the device's own numbers: a 1.85px white core at
            // 0.67-0.83 amplitude, against flanks that peak at 0.50 — so the brightest part of the
            // line is white and the hue is confined to its edges, which is the whole difference
            // between a fringe and a coloured stroke.
            //
            // "reach" rather than "half": half is a type in SkSL, and this shader cannot be
            // compiled anywhere but on a device, so a name that might be reserved is a whole
            // install cycle to find out about.
            + "    float core = max(0.9, hw * 0.32);\n"
            + "    float ramp = max(hw * 0.5, 1.0);\n"
            + "    float reach = sep + core + ramp;\n"
            + "    float3 hair = float3(\n"
            + "        clamp((reach - abs(d + reach + 2.0 * sep)) / ramp, 0.0, 1.0),\n"
            + "        clamp((reach - abs(d + reach + sep)) / ramp, 0.0, 1.0),\n"
            + "        clamp((reach - abs(d + reach)) / ramp, 0.0, 1.0));\n"
            // One achromatic envelope over the whole group, so the hairline still tapers to
            // nothing at both ends. Without it the flanks would hold a single channel at full
            // amplitude for a pixel and a half either side — a saturated red edge and a saturated
            // blue one, which trades one coloured line for two.
            //
            // Its reach is (reach + sep) rather than reach alone, and that is the cheaper of two
            // measured trade-offs: the tighter envelope leaves the visible band ending 2.1px short
            // of the outline, which is a dark ring between the shape's edge and its own highlight,
            // and a dark ring reads as a drawn stroke. This one closes that to 1.15px for 8.0px of
            // visible band against the previous shape's 7.3px.
            + "    hair *= clamp(1.0 - abs(d + reach + sep) / (reach + sep), 0.0, 1.0);\n"
            // How saturated the two flanks are allowed to get, which is a different question from
            // where they sit — and the one three rounds of this bug never asked.
            //
            // Measured on the device once the white core finally existed: the profile through the
            // top arc runs blue -> cyan -> white -> yellow exactly as designed, but the flanks
            // reach luma 53 against a core of 78. A fringe at 68% of the core's brightness is not
            // a fringe, it is a rainbow with a thin white line through it, and it reads as a
            // coloured rim however correct the hue sequence is. Worse, where the arc is dimmer the
            // core never resolves to white at all and the flank hue wins outright.
            //
            // mix toward the smallest channel rather than toward grey or toward the mean. Over the
            // core all three channels are equal, so cmin equals them and this is exactly a no-op —
            // the peak brightness, the core width and the bottom-rim reading are all untouched,
            // which is what makes this safe to turn without re-measuring everything else. Only
            // where a channel has rolled off does cmin fall away from it, and there the excess is
            // what gets scaled. Mixing toward the mean would have brightened the flanks instead of
            // damping them; mixing toward grey would have dimmed the core along with them.
            + "    float cmin = min(hair.r, min(hair.g, hair.b));\n"
            + "    hair = mix(float3(cmin), hair, 0.40);\n"
            // Holds the peak at the brightness the device was last measured at, so the shape change
            // is not also a brightness change. The bottom rim is the criterion with the least
            // headroom in this pass — already reading above the 5-luma ceiling on the button — and
            // paying for two things at once is how the last two rounds became unreadable.
            + "    hair *= 0.86;\n"
            + "    float2 bgCoord = clamp(coord - n * hw * 2.0, lo, hi);\n"
            + "    float4 bgSample = float4(content.eval(bgCoord));\n"
            + "    float3 bgRgb = bgSample.rgb / max(bgSample.a, 0.001);\n"
            + "    float bgLuma = dot(bgRgb, float3(0.2126, 0.7152, 0.0722));\n"
            // The hairline takes its brightness from what is behind it — glass has no light of
            // its own — and from the same perimeter modulation as the bands, so the two agree
            // about which stretches of the outline are lit. The floor stays low: keeping the
            // shape perceptible over a black list is the ambient's job now, and it does it by
            // lifting the whole body rather than by drawing a bright line round a dark one.
            + "    float hairGain = mix(0.26, 1.0, smoothstep(0.05, 0.50, bgLuma))\n"
            + "        * patches * floorDamp;\n"
            + "\n"
            + "    float3 warm = float3(1.0, 0.995, 0.98);\n"
            + "    float3 cool = float3(0.95, 0.975, 1.0);\n"
            + "\n"
            // The rim brightens where it faces the light and very nearly disappears where it does
            // not. These are additive, so the sum is what matters — and all of it is spent within
            // a bevel of an edge, which is what lets the body stay flat.
            //
            // A bright upper rim is not what made the bar read as a tube; a bright upper rim
            // *mirrored by a bright lower one* was. With floorDamp and patches carrying that,
            // the lit side can be strong again — a glint has to be brighter than its surroundings
            // to be a glint, and the build that halved everything uniformly measured a top edge
            // only 8 luma above the body, which is no edge at all.
            + "    col += hair * (0.22 + 0.78 * max(facing, 0.0)) * hairGain * uSpec;\n"
            + "    col += warm * lit * 0.26 * uSpec * (1.0 + uPress * 0.10);\n"
            + "    col += cool * away * 0.10 * uSpec;\n"
            + "    col = mix(col, uActiveTint.rgb, uActiveTint.a * activeCov);\n"
            + "    col += warm * activeEdge * 0.05 * uSpec;\n"
            + "\n"
            // A flat pass-through gain, and flat is the whole point: glass carries a little more
            // light than the gap beside it, but any variation down the body brings the dome back.
            // This replaces a Blinn-Phong term whose exponent of 48, evaluated against a normal
            // that was nearly flat across the body, put its brightest output in the middle of the
            // surface and none of it at the bottom rim — top-lit body shading exactly.
            + "    col *= 1.0 + 0.10 * uSpec;\n"
            + "\n"
            // There was a flat ambient addition here, meant to give the body a floor of its own
            // over a black list. It was the wrong instrument and it was measured as such: adding
            // a constant to every pixel raises the body and the rim by the same amount, so it
            // cannot change their ratio — it can only compress it. At 0.063 it put 16 luma into a
            // body that was 17, and the whole pill measured as one flat (26,33,39) from top to
            // bottom: a grey slab with no optical structure left in it at all. What separates a
            // dark pane from a dark backdrop is the rim, the fringe and the drop shadow, all of
            // which are differences rather than offsets. Do not reintroduce this.
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
    private static final float MAX_BEVEL_FRACTION = 0.26f;

    /** Vibrancy applied at full lens strength; 1.0 leaves saturation untouched. */
    private static final float MAX_SATURATION = 1.55f;

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
     * Ceiling on the per-channel sampling offset in the refraction, in dp.
     *
     * <p>In dp for the same reason {@link #HAIRLINE_DP} is: this produces a fringe, and a fringe
     * has to hold a constant apparent width rather than growing with the surface.</p>
     *
     * <p>Uncapped it was a flat fraction of the displacement, which put it at 8.7px on a
     * phone-sized round button and 17px on the bar — so red and blue were read from up to 34px
     * apart, which over a patterned wallpaper is not the same picture twice. That is not chromatic
     * aberration, it is three different images composited one per channel, and it was measured as
     * the source of the coloured rim: neutral to within a unit over a uniform backdrop, G-R +31
     * over a patterned one, from the same build.</p>
     *
     * <p>Sized to sit alongside the hairline's own channel separation (about 1.4px at 3.5x) so the
     * two dispersion mechanisms in this pass finally work at the same scale instead of one being
     * an order of magnitude louder than the other.</p>
     */
    private static final float SPREAD_DP = 0.55f;

    /** Bounds on that cap in px, so it neither vanishes nor becomes a smear. */
    private static final float MIN_SPREAD_PX = 1f;
    private static final float MAX_SPREAD_PX = 3f;

    /**
     * What each view's current effect was built from.
     *
     * <p>The shader bakes in the view's size and radius, so it has to be rebuilt when those
     * change — but callers refresh on every layout pass, and allocating a {@code RuntimeShader}
     * and a {@code RenderEffect} per pass would put that cost on every frame of a scroll for no
     * visible difference.</p>
     */
    private static final java.util.WeakHashMap<View, ShaderState> installed = new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<View, ValueAnimator> pressAnimators = new java.util.WeakHashMap<>();

    private static final class ShaderState {
        final String key;
        final RuntimeShader shader;

        ShaderState(String key, RuntimeShader shader) {
            this.key = key;
            this.shader = shader;
        }
    }

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
        float spread = Math.max(MIN_SPREAD_PX,
                Math.min(SPREAD_DP * density, MAX_SPREAD_PX));
        // The softness the surface needs is this pass's job now, not the blur library's. A spec
        // that asked for no blur at all still gets none, and the lens degrades to a sharp
        // backdrop rather than inventing one.
        float blur = Math.max(0f,
                Math.min(MAX_BLUR_PX, spec.blurRadius * BLUR_DP_PER_UNIT * density));

        String key = width + "x" + height + ":" + cornerRadiusPx + ":" + bevel + ":" + refract
                + ":" + spec.dispersion + ":" + spec.specular + ":" + spec.innerShadow
                + ":" + spec.fillColor + ":" + saturation + ":" + hairline + ":" + blur
                + ":" + spread;
        ShaderState current = installed.get(view);
        if (current != null && key.equals(current.key)) {
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
            shader.setFloatUniform("uSpread", spread);
            shader.setFloatUniform("uTint",
                    Color.red(spec.fillColor) / 255f,
                    Color.green(spec.fillColor) / 255f,
                    Color.blue(spec.fillColor) / 255f,
                    Color.alpha(spec.fillColor) / 255f);
            shader.setFloatUniform("uSat", saturation);
            shader.setFloatUniform("uBlur", blur);
            shader.setFloatUniform("uActiveCenter", 0f, 0f);
            shader.setFloatUniform("uActiveHalf", 0f, 0f);
            shader.setFloatUniform("uActiveRadius", 0f);
            shader.setFloatUniform("uActiveTint", 0f, 0f, 0f, 0f);
            shader.setFloatUniform("uActive", 0f);
            shader.setFloatUniform("uPress", 0f);

            view.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"));
            installed.put(view, new ShaderState(key, shader));
            status = "active: " + width + "x" + height + " bevel=" + bevel + "px refract="
                    + refract + "px dispersion=" + spec.dispersion + " hair=" + hairline
                    + "px blur=" + blur + "px spread=" + spread + "px";
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

    /** Updates the selected-tab lens without reallocating the shader or render effect. */
    public static void updateActive(View view, float centerX, float centerY, float width,
                                    float height, float radius, int tintColor, boolean enabled) {
        ShaderState state = installed.get(view);
        if (state == null) return;
        try {
            state.shader.setFloatUniform("uActiveCenter", centerX, centerY);
            state.shader.setFloatUniform("uActiveHalf", Math.max(0f, width / 2f),
                    Math.max(0f, height / 2f));
            state.shader.setFloatUniform("uActiveRadius", Math.max(0f, radius));
            state.shader.setFloatUniform("uActiveTint",
                    Color.red(tintColor) / 255f,
                    Color.green(tintColor) / 255f,
                    Color.blue(tintColor) / 255f,
                    0.14f);
            state.shader.setFloatUniform("uActive", enabled ? 1f : 0f);
            view.invalidate();
        } catch (Throwable t) {
            Log.w(TAG, "Could not update active lens uniforms", t);
        }
    }

    /** Gives the lens a short, restrained response to a tab press. */
    public static void pulse(View view, boolean animate) {
        ShaderState state = installed.get(view);
        if (state == null) return;
        ValueAnimator previous = pressAnimators.remove(view);
        if (previous != null) previous.cancel();
        if (!animate) {
            state.shader.setFloatUniform("uPress", 0f);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f, 0f);
        animator.setDuration(220L);
        animator.addUpdateListener(value -> {
            ShaderState live = installed.get(view);
            if (live == null) return;
            live.shader.setFloatUniform("uPress", (float) value.getAnimatedValue());
            view.invalidate();
        });
        animator.start();
        pressAnimators.put(view, animator);
    }

    /** Removes any lens previously installed on {@code view}. */
    public static void clear(View view) {
        if (view == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        try {
            view.setRenderEffect(null);
            installed.remove(view);
            ValueAnimator animator = pressAnimators.remove(view);
            if (animator != null) animator.cancel();
        } catch (Throwable ignored) {
        }
    }
}
