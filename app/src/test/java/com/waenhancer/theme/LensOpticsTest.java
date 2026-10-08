package com.waenhancer.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Capture coordinates, filtering, colour and contrast of the corrected renderer, on the JVM.
 * These check the arithmetic the shader is built from; what the GPU draws is for the device.
 */
public class LensOpticsTest {

    private static GlassSpec liquid(boolean dark) {
        return GlassSpec.resolve(GlassSpec.Variant.LIQUID, dark, 0, 0xFF25D366, 10f, true, false);
    }

    // ---- capture coordinates (LG-06/LG-08) ---------------------------------------------------

    /** 1:1 recording: the surface maps onto its recording with no residual scale, any size. */
    @Test
    public void exactLayoutHasUnitScaleForEveryRemainder() {
        for (int w = 96; w < 104; w++) {
            for (int pad : new int[]{0, 5, 37}) {
                LiveBackdrop.Layout layout = new LiveBackdrop.Layout(w, w + 13, pad, true);
                assertEquals(1f, layout.netScaleX(), 0f);
                assertEquals(1f, layout.netScaleY(), 0f);
                assertEquals(w + 2 * pad, layout.inputWidth);
                assertEquals(layout.inputWidth, layout.nodeWidth);
            }
        }
    }

    /** Negative control: the legacy quarter-scale pair leaves W/(4·ceil(W/4)) when W % 4 != 0. */
    @Test
    public void legacyLayoutKeepsTheRoundingError() {
        LiveBackdrop.Layout layout = new LiveBackdrop.Layout(997, 109, 0, false);
        assertEquals(997f / 1000f, layout.netScaleX(), 1e-6f);
        assertEquals(109f / 112f, layout.netScaleY(), 1e-6f);
        assertEquals(1f, new LiveBackdrop.Layout(1000, 112, 0, false).netScaleX(), 1e-6f);
    }

    /** With no displacement the map is the identity across the covered surface, to the pixel. */
    @Test
    public void zeroAmplitudeMapIsIdentity() {
        GlassSpec spec = liquid(true);
        LensModel.Surface s = LensModel.surface(spec, 300f, 120f, 60f, 2.75f, true, true, 0.28f);
        LensModel.Surface flat = new LensModel.Surface(s.width, s.height, s.radius, s.bevel, 0f,
                s.cap, 0f, s.legacySpread, s.dispersion, s.legacyRefract, s.lo, 0, 0, 0, 0, 0, 0);
        float[] out = new float[6];
        for (float y = 0.5f; y < 120f; y += 1f) {
            for (float x = 0.5f; x < 300f; x += 1f) {
                if (LensModel.coverage(flat, x, y) < 1f) continue;
                LensModel.sample(flat, x, y, true, out);
                for (int ch = 0; ch < 3; ch++) {
                    assertEquals(x, out[2 * ch], 1e-4f);
                    assertEquals(y, out[2 * ch + 1], 1e-4f);
                }
            }
        }
    }

    /** Displacement is inward only: no sample leaves the covered input, so no clamp is needed. */
    @Test
    public void samplesStayInsideTheInput() {
        LensModel.Surface s = LensModel.surface(liquid(true), 640f, 132f, 66f, 2.75f, true, true, 0.35f);
        float[] out = new float[6];
        for (float y = 0.5f; y < s.height; y += 1f) {
            for (float x = 0.5f; x < s.width; x += 1f) {
                if (LensModel.coverage(s, x, y) < 1f) continue;
                LensModel.sample(s, x, y, true, out);
                for (int ch = 0; ch < 3; ch++) {
                    assertTrue(out[2 * ch] > 0.5f - 1e-4f && out[2 * ch] < s.width - 0.5f + 1e-4f);
                    assertTrue(out[2 * ch + 1] > 0.5f - 1e-4f && out[2 * ch + 1] < s.height - 0.5f + 1e-4f);
                }
            }
        }
    }

    // ---- filtering (LG-02/LG-07) -------------------------------------------------------------

    /**
     * Background text behind the body must stop competing with the controls. Body text has a
     * stroke period of roughly 7 dp; the soft backdrop must attenuate it to 10% or less, at any
     * density, while a 60 dp colour mass keeps most of its contrast.
     */
    @Test
    public void softBackdropRemovesTextButKeepsColourMasses() {
        GlassSpec spec = liquid(true);
        for (float density : new float[]{1f, 2f, 2.75f, 3.5f, 4f}) {
            double sigma = LensModel.sigmaPx(spec, density);
            double text = 1d / (7d * density);
            double mass = 1d / (120d * density);
            assertTrue("text residual at " + density + " = " + LensModel.gaussianResponse(sigma, text),
                    LensModel.gaussianResponse(sigma, text) <= 0.10d);
            assertTrue("mass residual at " + density, LensModel.gaussianResponse(sigma, mass) >= 0.75d);
        }
    }

    /** The report's example: 1/6 cycles/px to 10% needs sigma >= 2.05 px. */
    @Test
    public void sigmaForMatchesTheReportExample() {
        assertEquals(2.05d, LensModel.sigmaFor(1d / 6d, 0.1d), 0.01d);
        assertEquals(0.1d, LensModel.gaussianResponse(LensModel.sigmaFor(0.2d, 0.1d), 0.2d), 1e-9);
    }

    /** The margin covers the truncated Gaussian support, so the rim never samples a clamp. */
    @Test
    public void paddingCoversThreeSigma() {
        for (float sigma : new float[]{1.5f, 4.4f, 11f, 30f}) {
            assertTrue(LensModel.padding(sigma) >= 3f * sigma);
        }
        assertEquals(0, LensModel.padding(0f));
    }

    /** β: sharp at the rim for the refraction, fully soft in the body, monotone between. */
    @Test
    public void betaRisesFromRimToBody() {
        assertEquals(LensModel.BETA_RIM, LensModel.beta(0f), 1e-6f);
        assertEquals(1f, LensModel.beta(LensModel.BETA_FULL_AT), 1e-6f);
        assertEquals(1f, LensModel.beta(1f), 1e-6f);
        float previous = -1f;
        for (float t = 0f; t <= 1f; t += 0.01f) {
            float b = LensModel.beta(t);
            assertTrue(b >= previous - 1e-6f);
            previous = b;
        }
    }

    /** A constant backdrop stays constant: the sharp/soft composition adds no texture. */
    @Test
    public void compositionPreservesConstantColour() {
        for (float t = 0f; t <= 1f; t += 0.05f) {
            float beta = LensModel.beta(t);
            for (float v : new float[]{0f, 0.18f, 0.5f, 1f}) {
                assertEquals(v, LensModel.mix(v, v, beta), 1e-6f);
            }
        }
    }

    // ---- colour (LG-05/LG-09) ----------------------------------------------------------------

    @Test
    public void srgbTransferRoundTrips() {
        for (int i = 0; i <= 255; i++) {
            double c = i / 255d;
            assertEquals(c, LensModel.fromLinear(LensModel.toLinear(c)), 1e-9);
        }
        // The report's example: the linear mean of black and white encodes to ~0.735.
        assertEquals(0.735d, LensModel.fromLinear(0.5d), 0.001d);
    }

    /** Saturation in the report's 1.0-1.15 range with colour on; legacy kept for comparison. */
    @Test
    public void saturationIsCalibrated() {
        GlassSpec spec = liquid(true);
        float s = LensModel.saturation(spec, true);
        assertTrue(s >= 1f && s <= 1.15f);
        assertEquals(1.55f, LensModel.saturation(spec, false), 1e-6f);
    }

    /** Chromatic separation (red to blue) is at most 1.5 px with the colour group on. */
    @Test
    public void chromaticSeparationIsBounded() {
        for (GlassSpec.Variant variant : new GlassSpec.Variant[]{GlassSpec.Variant.LIQUID,
                GlassSpec.Variant.CLEAR, GlassSpec.Variant.ADVANCED}) {
            GlassSpec spec = GlassSpec.resolve(variant, true, 0, 0, 10f, true, false);
            for (float density : new float[]{1f, 2f, 2.75f, 3.5f, 4f}) {
                LensModel.Surface s = LensModel.surface(spec, 640f, 132f, 66f, density, true, true, 0.28f);
                assertTrue(2f * s.delta <= 1.5f + 1e-6f);
            }
        }
    }

    // ---- contrast protection (LG-03) ---------------------------------------------------------

    /** The closed form reaches the target exactly when it is not capped. */
    @Test
    public void protectionReachesTheTarget() {
        double white = 1d, dark = LensModel.luminance(0.004d, 0.007d, 0.009d);
        for (double glass = 0d; glass <= 1d; glass += 0.01d) {
            double a = LensModel.protection(glass, white, dark, 4.5d, 1d);
            double after = (1d - a) * glass + a * dark;
            assertTrue("glass " + glass, LensModel.contrast(white, after) >= 4.5d - 1e-6);
            if (LensModel.contrast(white, glass) >= 4.5d) assertEquals(0d, a, 0d);
        }
        // Dark content over glass: protection lightens.
        double black = 0d;
        for (double glass = 0d; glass <= 1d; glass += 0.01d) {
            double a = LensModel.protection(glass, black, 1d, 4.5d, 1d);
            double after = (1d - a) * glass + a;
            assertTrue(LensModel.contrast(black, after) >= 4.5d - 1e-6);
        }
    }

    /** Capped: the protection never covers more than its maximum, however bright the backdrop. */
    @Test
    public void protectionIsCapped() {
        // Grey content (L 0.3) over white needs ~0.97 of a black protection colour: capped.
        assertEquals(LensModel.PROTECTION_MAX,
                LensModel.protection(1d, 0.3d, 0d, 4.5d, LensModel.PROTECTION_MAX), 1e-6);
        assertEquals(0f, LensModel.protectionWeight(0f), 0f);
        assertEquals(1f, LensModel.protectionWeight(1f), 0f);
    }

    // ---- material identity and the Clear profile ---------------------------------------------

    /** Re-resolving an identical material yields an equal value, so no effect is rebuilt. */
    @Test
    public void materialsCompareByValue() {
        assertEquals(liquid(true), liquid(true));
        assertEquals(liquid(true).hashCode(), liquid(true).hashCode());
        assertNotEquals(liquid(true), liquid(false));
    }

    @Test
    public void clearProfileTransmitsMoreAndKeepsTheLens() {
        GlassSpec base = GlassSpec.resolve(GlassSpec.Variant.LIQUID, true, 0, 0, 40f, true, false);
        GlassSpec clear = base.clearProfile();
        assertTrue((clear.fillColor >>> 24) <= Math.round(GlassSpec.CLEAR_PROFILE_TINT * 255f));
        assertEquals(0f, clear.dispersion, 0f);
        assertEquals(base.lensStrength, clear.lensStrength, 0f);
        assertTrue(clear.blurRadius <= base.blurRadius);
        GlassSpec fallback = base.withoutOptics();
        assertTrue(fallback.clearProfile() == fallback);
    }

    /** With the temporal group, a material change keeps the selected tab and press (LG-11). */
    @Test
    public void interactionSurvivesMaterialChangesOnlyWithTemporal() {
        LensEffect.Interaction kept = new LensEffect.Interaction();
        kept.active = 1f;
        kept.press = 0.5f;
        kept.onMaterialChange(true);
        assertEquals(1f, kept.active, 0f);
        assertEquals(0.5f, kept.press, 0f);
        kept.onMaterialChange(false);
        assertEquals(0f, kept.active, 0f);
        assertEquals(0f, kept.press, 0f);
    }

    // ---- the shader's interface --------------------------------------------------------------

    /**
     * Every uniform the Java side writes is declared, with the right kind: colours as
     * {@code layout(color)} (set with setColorUniform), everything else as plain floats. A
     * mismatch throws on the device and takes the corrected renderer down to legacy.
     */
    @Test
    public void everyWrittenUniformIsDeclared() throws Exception {
        Path source = Paths.get("src/main/java/com/waenhancer/theme/LensEffect.java");
        String java = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        String shader = LensEffect.SHADER_V2;
        Set<String> floats = names(java, "setFloatUniform\\(\"(\\w+)\"");
        Set<String> colors = names(java, "setColorUniform\\(\"(\\w+)\"");
        assertFalse(floats.isEmpty());
        assertFalse(colors.isEmpty());
        for (String name : floats) {
            assertTrue(name + " not declared as a float uniform",
                    Pattern.compile("\\nuniform float[234]? " + name + ";").matcher(shader).find());
        }
        for (String name : colors) {
            assertTrue(name + " not declared layout(color)",
                    shader.contains("layout(color) uniform half4 " + name + ";"));
        }
        Set<String> declared = names(shader, "uniform (?:float[234]?|half4) (\\w+);");
        declared.removeAll(floats);
        declared.removeAll(colors);
        assertTrue("declared but never written: " + declared, declared.isEmpty());
    }

    private static Set<String> names(String text, String regex) {
        Set<String> out = new HashSet<>();
        Matcher m = Pattern.compile(regex).matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }
}
