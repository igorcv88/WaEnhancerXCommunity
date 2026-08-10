package com.waenhancer.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The Advanced Glass engine has to keep three promises no matter which variant is picked: the
 * surface stays legible, a device without blur still gets something usable, and motion can be
 * switched off. These pin all three.
 */
public class GlassSpecTest {

    private static GlassSpec resolve(GlassSpec.Variant variant, boolean dark,
                                     boolean blurSupported) {
        return GlassSpec.resolve(variant, dark, 0, 0x3B82F6, 35f, blurSupported, false);
    }

    private static int alphaOf(int argb) {
        return (argb >>> 24) & 0xFF;
    }

    // ---- adaptive contrast ------------------------------------------------------------------

    /** Every variant, in both themes, must clear the contrast floor the engine advertises. */
    @Test
    public void everyVariantKeepsContentAboveTheContrastFloor() {
        for (GlassSpec.Variant variant : GlassSpec.Variant.values()) {
            for (boolean dark : new boolean[]{false, true}) {
                for (boolean blur : new boolean[]{false, true}) {
                    GlassSpec spec = resolve(variant, dark, blur);
                    int composited = SemanticTheme.blend(
                            dark ? 0xFF000000 : 0xFFFFFFFF,
                            0xFF000000 | (spec.fillColor & 0x00FFFFFF),
                            alphaOf(spec.fillColor) / 255f);
                    double ratio = SemanticTheme.contrastRatio(spec.contentColor, composited);
                    assertTrue(variant + " dark=" + dark + " blur=" + blur
                                    + " fell to " + ratio,
                            ratio >= GlassSpec.MIN_CONTENT_CONTRAST);
                }
            }
        }
    }

    /** A user tint the engine did not choose still has to end up readable. */
    @Test
    public void aUserChosenTintIsStillHeldToTheContrastFloor() {
        int[] awkwardTints = {0xFFFFFF00, 0xFF00FF00, 0xFF808080, 0xFF000000, 0xFFFFFFFF};
        for (int tint : awkwardTints) {
            GlassSpec spec = GlassSpec.resolve(GlassSpec.Variant.ADVANCED, true, tint,
                    0x3B82F6, 60f, true, false);
            int composited = SemanticTheme.blend(0xFF000000, 0xFF000000 | (tint & 0x00FFFFFF),
                    alphaOf(spec.fillColor) / 255f);
            assertTrue("tint " + Integer.toHexString(tint),
                    SemanticTheme.contrastRatio(spec.contentColor, composited)
                            >= GlassSpec.MIN_CONTENT_CONTRAST);
        }
    }

    // ---- the no-blur fallback ---------------------------------------------------------------

    /**
     * Without blur there is no backdrop separation, so the fill has to supply it. A fallback that
     * merely dropped the blur would leave a thin transparent panel over arbitrary content.
     */
    @Test
    public void withoutBlurTheFillBecomesOpaqueEnoughToStandAlone() {
        GlassSpec blurred = resolve(GlassSpec.Variant.ADVANCED, true, true);
        GlassSpec fallback = resolve(GlassSpec.Variant.ADVANCED, true, false);

        assertTrue(alphaOf(fallback.fillColor) > alphaOf(blurred.fillColor));
        assertTrue(alphaOf(fallback.fillColor) >= Math.round(0.72f * 255f));
    }

    @Test
    public void theFallbackReportsItselfAndAsksForNoBlur() {
        GlassSpec fallback = resolve(GlassSpec.Variant.LIQUID, false, false);
        assertTrue(fallback.usingFallback);
        assertEquals(0f, fallback.blurRadius, 0.0001f);
    }

    @Test
    public void blurCapableDevicesKeepTheVariantsRadiusAndAreNotFlaggedAsFallback() {
        GlassSpec spec = resolve(GlassSpec.Variant.LIQUID, false, true);
        assertFalse(spec.usingFallback);
        assertTrue(spec.blurRadius > 0f);
    }

    /** Even the most transparent variant must not vanish when it cannot rely on blur. */
    @Test
    public void theClearestVariantStillSurvivesTheFallback() {
        GlassSpec clear = GlassSpec.resolve(GlassSpec.Variant.CLEAR, true, 0, 0, 5f, false, false);
        assertTrue(alphaOf(clear.fillColor) >= Math.round(0.72f * 255f));
    }

    // ---- accessibility ----------------------------------------------------------------------

    @Test
    public void reducedMotionSwitchesAnimationOff() {
        GlassSpec still = GlassSpec.resolve(GlassSpec.Variant.ADVANCED, true, 0, 0, 35f,
                true, true);
        assertFalse(still.animate);
        GlassSpec moving = GlassSpec.resolve(GlassSpec.Variant.ADVANCED, true, 0, 0, 35f,
                true, false);
        assertTrue(moving.animate);
    }

    // ---- variants ---------------------------------------------------------------------------

    /** STABLE is the pre-engine look: no specular highlight, no refraction glow. */
    @Test
    public void theStableVariantAddsNoHighlightOrRefraction() {
        GlassSpec stable = resolve(GlassSpec.Variant.STABLE, true, true);
        assertEquals(0, stable.highlightColor);
        assertEquals(0, stable.refractionColor);
    }

    @Test
    public void theAdvancedVariantAddsBothHighlightAndRefraction() {
        GlassSpec advanced = resolve(GlassSpec.Variant.ADVANCED, true, true);
        assertNotEquals(0, advanced.highlightColor);
        assertNotEquals(0, advanced.refractionColor);
    }

    /** Refraction picks up the accent; with no accent it still renders from the theme. */
    @Test
    public void refractionUsesTheAccentWhenThereIsOneAndTheThemeWhenThereIsNot() {
        GlassSpec accented = GlassSpec.resolve(GlassSpec.Variant.LIQUID, true, 0, 0xFF3B82F6,
                35f, true, false);
        assertEquals(0x3B82F6, accented.refractionColor & 0x00FFFFFF);

        GlassSpec unaccented = GlassSpec.resolve(GlassSpec.Variant.LIQUID, true, 0, 0, 35f,
                true, false);
        assertNotEquals(0, unaccented.refractionColor);
    }

    @Test
    public void variantsRoundTripThroughTheirStoredKey() {
        for (GlassSpec.Variant variant : GlassSpec.Variant.values()) {
            assertEquals(variant, GlassSpec.Variant.from(variant.key()));
        }
    }

    /** An unknown or absent stored value must not crash or blank the bar. */
    @Test
    public void anUnknownStoredVariantFallsBackToAdvanced() {
        assertEquals(GlassSpec.Variant.ADVANCED, GlassSpec.Variant.from(null));
        assertEquals(GlassSpec.Variant.ADVANCED, GlassSpec.Variant.from(""));
        assertEquals(GlassSpec.Variant.ADVANCED, GlassSpec.Variant.from("pill_design_pro"));
    }

    // ---- overdraw ---------------------------------------------------------------------------

    /**
     * Turning the engine on must not cost a layer for users who keep the old look. STABLE has to
     * draw exactly what the flat background it replaced drew: one layer.
     */
    @Test
    public void theStableVariantCostsNoMoreOverdrawThanAFlatBackground() {
        assertEquals(1, resolve(GlassSpec.Variant.STABLE, true, true).layerCount());
        assertEquals(1, resolve(GlassSpec.Variant.STABLE, false, true).layerCount());
    }

    /** Three layers is the ceiling: fill, refraction, highlight. Nothing may exceed it. */
    @Test
    public void noVariantEverExceedsThreeLayers() {
        for (GlassSpec.Variant variant : GlassSpec.Variant.values()) {
            for (boolean dark : new boolean[]{false, true}) {
                for (boolean blur : new boolean[]{false, true}) {
                    int layers = resolve(variant, dark, blur).layerCount();
                    assertTrue(variant + " drew " + layers + " layers",
                            layers >= 1 && layers <= 3);
                }
            }
        }
    }

    /** The layer count has to follow the colours the renderer will actually be handed. */
    @Test
    public void theLayerCountMatchesTheDecoratedLayersThatArePresent() {
        GlassSpec advanced = resolve(GlassSpec.Variant.ADVANCED, true, true);
        int expected = 1
                + (alphaOf(advanced.highlightColor) > 0 ? 1 : 0)
                + (alphaOf(advanced.refractionColor) > 0 ? 1 : 0);
        assertEquals(expected, advanced.layerCount());
    }

    /**
     * The variants have to be visibly different from one another, which is only true if their
     * blur radii stay inside the range the blur library honours. When these were treated as dp
     * and scaled by density, every one of them landed on the library's clamp and the whole
     * picker became decorative.
     */
    @Test
    public void everyVariantAsksForABlurTheLibraryCanActuallyDistinguish() {
        java.util.Set<Float> radii = new java.util.HashSet<>();
        for (GlassSpec.Variant variant : GlassSpec.Variant.values()) {
            float radius = resolve(variant, true, true).blurRadius;
            assertTrue(variant + " asked for " + radius + ", outside the library's 1-25 range",
                    radius >= 1f && radius <= 25f);
            radii.add(radius);
        }
        assertEquals("variants must not collapse onto a shared blur radius",
                GlassSpec.Variant.values().length, radii.size());
    }

    /** Liquid glass is thin and lightly blurred; frost is the dense, heavily blurred one. */
    @Test
    public void liquidStaysClearerAndLessBlurredThanFrost() {
        GlassSpec liquid = resolve(GlassSpec.Variant.LIQUID, true, true);
        GlassSpec frost = resolve(GlassSpec.Variant.FROST, true, true);
        assertTrue(liquid.blurRadius < frost.blurRadius);
        assertTrue(alphaOf(liquid.fillColor) < alphaOf(frost.fillColor));
        // Liquid carries its effect on the edge instead, so its border is the stronger one.
        assertTrue(liquid.strokeWidthDp > frost.strokeWidthDp);
        assertTrue(alphaOf(liquid.strokeColor) > alphaOf(frost.strokeColor));
    }

    /** Each style names an opacity it is designed around; the editor snaps the slider to it. */
    @Test
    public void everyVariantRecommendsAnOpacityInsideTheSlidersRange() {
        for (GlassSpec.Variant variant : GlassSpec.Variant.values()) {
            int recommended = variant.recommendedOpacityPercent();
            assertTrue(variant + " recommends " + recommended,
                    recommended >= 10 && recommended <= 100);
        }
        assertTrue(GlassSpec.Variant.CLEAR.recommendedOpacityPercent()
                < GlassSpec.Variant.FROST.recommendedOpacityPercent());
    }

    @Test
    public void opacityIsClampedRatherThanOverflowingTheAlphaByte() {
        GlassSpec overshoot = GlassSpec.resolve(GlassSpec.Variant.FROST, false, 0, 0, 400f,
                true, false);
        assertEquals(255, alphaOf(overshoot.fillColor));

        GlassSpec undershoot = GlassSpec.resolve(GlassSpec.Variant.CLEAR, false, 0, 0, -50f,
                true, false);
        assertEquals(0, alphaOf(undershoot.fillColor));
    }

    // ---- liquid glass -----------------------------------------------------------------------

    /**
     * The complaint this section exists for: Liquid looked like frost. It differs from frost by
     * being a lens rather than a heavier veil, so it has to blur less and bend more.
     */
    @Test
    public void liquidIsALensAndFrostIsNot() {
        GlassSpec liquid = resolve(GlassSpec.Variant.LIQUID, true, true);
        GlassSpec frost = resolve(GlassSpec.Variant.FROST, true, true);

        assertTrue(liquid.lensStrength > 0f);
        assertTrue(liquid.rimWidthDp > 0f);
        assertEquals(0f, frost.lensStrength, 0f);
        assertTrue(liquid.blurRadius < frost.blurRadius);
        assertTrue(alphaOf(liquid.fillColor) < alphaOf(frost.fillColor));
    }

    /** Only the variants meant to react do; the flat ones stay a fixed colour. */
    @Test
    public void onlyTheLensingVariantsAdaptAndMorph() {
        assertTrue(resolve(GlassSpec.Variant.LIQUID, true, true).adaptive);
        assertTrue(resolve(GlassSpec.Variant.LIQUID, true, true).morphing);
        assertFalse(resolve(GlassSpec.Variant.STABLE, true, true).adaptive);
        assertFalse(resolve(GlassSpec.Variant.FROST, true, true).morphing);
    }

    /** There is nothing to bend without a blurred backdrop, so the fallback drops the lens. */
    @Test
    public void aDeviceWithoutBlurGetsNoLensAndNoAdaptation() {
        GlassSpec spec = resolve(GlassSpec.Variant.LIQUID, true, false);

        assertEquals(0f, spec.lensStrength, 0f);
        assertEquals(0f, spec.rimWidthDp, 0f);
        assertFalse(spec.adaptive);
    }

    /** Reduced motion stops the surface moving, whatever the variant would like to do. */
    @Test
    public void reducedMotionStopsTheMorph() {
        GlassSpec spec = GlassSpec.resolve(GlassSpec.Variant.LIQUID, true, 0, 0x3B82F6, 35f,
                true, true);

        assertFalse(spec.morphing);
        assertFalse(spec.animate);
    }

    // ---- adaptive tint ----------------------------------------------------------------------

    @Test
    public void adaptingShiftsTheFillTowardTheBackdrop() {
        GlassSpec spec = resolve(GlassSpec.Variant.LIQUID, true, true);
        GlassSpec adapted = spec.adaptTo(0xFF206020, true);

        assertNotEquals(spec.fillColor & 0x00FFFFFF, adapted.fillColor & 0x00FFFFFF);
        // Adapting is a tint, not a repaint: the configured opacity has to survive it.
        assertEquals(alphaOf(spec.fillColor), alphaOf(adapted.fillColor));
    }

    @Test
    public void adaptingNeverCostsTheSurfaceItsLegibility() {
        GlassSpec spec = resolve(GlassSpec.Variant.LIQUID, true, true);
        int[] backdrops = {0xFF000000, 0xFFFFFFFF, 0xFF206020, 0xFFD94F4F, 0xFF3B82F6};

        for (int backdrop : backdrops) {
            for (boolean dark : new boolean[]{false, true}) {
                GlassSpec adapted = spec.adaptTo(backdrop, dark);
                int composited = SemanticTheme.blend(backdrop,
                        0xFF000000 | (adapted.fillColor & 0x00FFFFFF),
                        alphaOf(adapted.fillColor) / 255f);
                double ratio = SemanticTheme.contrastRatio(adapted.contentColor, composited);
                assertTrue("backdrop " + Integer.toHexString(backdrop) + " dark=" + dark
                                + " gave " + ratio,
                        ratio >= GlassSpec.MIN_CONTENT_CONTRAST);
            }
        }
    }

    /** A bright backdrop needs a dark edge to be seen against, and the reverse. */
    @Test
    public void theEdgeInvertsWithTheBackdropBrightness() {
        GlassSpec spec = resolve(GlassSpec.Variant.LIQUID, true, true);

        int overWhite = spec.adaptTo(0xFFFFFFFF, true).strokeColor;
        int overBlack = spec.adaptTo(0xFF000000, true).strokeColor;

        assertEquals(0x000000, overWhite & 0x00FFFFFF);
        assertEquals(0xFFFFFF, overBlack & 0x00FFFFFF);
    }

    /** A caller may hand any surface a backdrop without first asking whether it cares. */
    @Test
    public void aNonAdaptiveSurfaceIsUnchangedByItsBackdrop() {
        GlassSpec spec = resolve(GlassSpec.Variant.FROST, true, true);

        assertEquals(spec, spec.adaptTo(0xFF206020, true));
    }

    /** No sample yet means no change, rather than a lurch to some default. */
    @Test
    public void anEmptySampleLeavesTheSurfaceAlone() {
        GlassSpec spec = resolve(GlassSpec.Variant.LIQUID, true, true);

        assertEquals(spec, spec.adaptTo(0, true));
    }
}
