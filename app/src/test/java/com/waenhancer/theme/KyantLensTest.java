package com.waenhancer.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/**
 * The OneMate material's numbers: the ported profile, the blur calibration, the opacity mapping
 * and the press-state bubble. Shader acceptance is a device question; these pin the inputs.
 */
public class KyantLensTest {

    @After
    public void tearDown() {
        KyantLens.publishOpacity(10f, 10);
    }

    /** Kyant's circle map: full displacement at the outline, none at the band's inner edge. */
    @Test
    public void circleMapIsSteepAtTheRimAndZeroInTheBody() {
        float h = 72f;
        assertEquals(1f, KyantLens.circleMap(0f, h), 1e-6f);
        assertEquals(0f, KyantLens.circleMap(h, h), 1e-6f);
        assertEquals(0f, KyantLens.circleMap(h + 10f, h), 1e-6f);
        assertEquals(0f, KyantLens.circleMap(5f, 0f), 1e-6f);
        float previous = 2f;
        for (float d = 0f; d <= h; d += 1f) {
            float v = KyantLens.circleMap(d, h);
            assertTrue("monotonic at " + d, v <= previous + 1e-6f);
            previous = v;
        }
        // Concentrated at the rim: half way in, the displacement is already under 14%.
        assertTrue(KyantLens.circleMap(h / 2f, h) < 0.14f);
    }

    /** createBlurEffect's radius is chosen so Skia's sigma equals miuix's blur(4dp) sigma. */
    @Test
    public void blurRadiusMatchesMiuixSigma() {
        for (float density : new float[]{1f, 2f, 2.75f, 3.5f}) {
            float radius = KyantLens.blurRadiusPx(density);
            float skiaSigma = 0.57735f * radius + 0.5f;
            assertEquals(4f * density * 0.45f, skiaSigma, 1e-3f);
            assertTrue(KyantLens.padding(density) >= 3f * 4f * density * 0.45f);
        }
    }

    /** The slider's Liquid position gives OneMate's 40%; the ends are clamped. */
    @Test
    public void opacityMapsAroundFortyPercent() {
        assertEquals(0.40f, KyantLens.surfaceAlpha(10f, 10), 1e-6f);
        assertEquals(0.65f, KyantLens.surfaceAlpha(35f, 10), 1e-6f);
        assertEquals(KyantLens.SURFACE_ALPHA_MAX, KyantLens.surfaceAlpha(100f, 10), 1e-6f);
        assertEquals(KyantLens.SURFACE_ALPHA_MIN, KyantLens.surfaceAlpha(-100f, 10), 1e-6f);
        KyantLens.publishOpacity(10f, 10);
        assertEquals(102, KyantLens.surfaceAlpha255());
    }

    @Test
    public void lightDirectionsAreUnitVectors() {
        for (float[] l : new float[][]{KyantLens.LIGHT_PRIMARY_BAR, KyantLens.LIGHT_PRIMARY_BUBBLE,
                KyantLens.LIGHT_SECONDARY}) {
            assertEquals(1f, (float) Math.sqrt(l[0] * l[0] + l[1] * l[1] + l[2] * l[2]), 1e-5f);
        }
        // The bar's light comes from the upper left (OneMate's -45° turn of a top light).
        assertTrue(KyantLens.LIGHT_PRIMARY_BAR[0] < 0f && KyantLens.LIGHT_PRIMARY_BAR[1] < 0f);
    }

    /** App surfaces: the band never reaches past the middle, the tint keeps the surface's hue. */
    @Test
    public void surfaceParamsFollowTheRecipe() {
        GlassSpec spec = GlassSpec.resolve(GlassSpec.Variant.LIQUID, true, 0, 0xFF25D366, 10f,
                true, false);
        float density = 3f;
        KyantLens.Params tall = KyantLens.surface(spec, 400, density, 102);
        assertEquals(24f * density, tall.refractHeight, 1e-4f);
        assertEquals(24f * density, tall.refractAmount, 1e-4f);
        KyantLens.Params small = KyantLens.surface(spec, 100, density, 102);
        assertEquals(50f, small.refractHeight, 1e-4f);
        assertEquals(102, small.tint >>> 24);
        assertEquals(spec.fillColor & 0xFFFFFF, small.tint & 0xFFFFFF);
        assertEquals(1.5f, small.saturation, 0f);
        assertEquals(0f, small.depth, 0f);
        assertEquals(0f, small.chroma, 0f);
        assertEquals(KyantLens.HIGHLIGHT_ALPHA, small.highlight, 0f);
        assertEquals(0f, small.shadowAlpha, 0f);
    }

    /** At rest the bubble is a translucent wash; pressed it is a lens with depth, dispersion and shadow. */
    @Test
    public void bubbleRisesWithPressProgress() {
        float density = 3f;
        KyantLens.Params rest = KyantLens.bubble(true, 0f, density);
        assertEquals(0f, rest.refractHeight, 0f);
        assertEquals(0f, rest.refractAmount, 0f);
        assertEquals(0f, rest.highlight, 0f);
        assertEquals(0f, rest.shadowAlpha, 0f);
        assertEquals(Math.round(0.10f * 255f), rest.tint >>> 24);
        assertEquals(0xFFFFFF, rest.tint & 0xFFFFFF);
        assertEquals(0x000000, KyantLens.bubble(false, 0f, density).tint & 0xFFFFFF);

        KyantLens.Params pressed = KyantLens.bubble(true, 1f, density);
        assertEquals(10f * density, pressed.refractHeight, 1e-4f);
        assertEquals(14f * density, pressed.refractAmount, 1e-4f);
        assertEquals(1f, pressed.depth, 0f);
        assertEquals(0.5f, pressed.chroma, 0f);
        assertEquals(1f, pressed.highlight, 0f);
        assertEquals(0.15f, pressed.shadowAlpha, 1e-6f);
        assertEquals(Math.round(0.03f * 255f), pressed.tint >>> 24);
        assertEquals(0, pressed.tint & 0xFFFFFF);
        assertEquals(0f, pressed.blurRadius, 0f);
        assertEquals(1f, pressed.saturation, 0f);
        assertNotEquals(rest.key(), pressed.key());
        assertEquals(pressed.key(), KyantLens.bubble(true, 1f, density).key());
    }
}
