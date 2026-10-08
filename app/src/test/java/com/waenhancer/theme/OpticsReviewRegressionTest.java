package com.waenhancer.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * One regression per finding of the independent review of PR #73 (composition, contrast after
 * composition, padded culling and draw order, effective budget, material identity).
 */
public class OpticsReviewRegressionTest {

    // ---- 1. two-pass composition -------------------------------------------------------------

    /**
     * Coverage is applied once: at fractional coverage γ the composite is γ·[(1-β)S + βF] with
     * alpha γ. Source-over with coverage on both passes, the first version, gives 0.7125 at
     * γ = 0.5, β = 0.15 — kept here as the counterexample.
     */
    @Test
    public void compositionAppliesCoverageOnce() {
        for (float coverage = 0f; coverage <= 1f; coverage += 0.05f) {
            for (float t = 0f; t <= 1f; t += 0.05f) {
                float beta = LensModel.beta(t);
                for (float[] cols : new float[][]{{0f, 1f}, {0.4f, 0.9f}, {0.7f, 0.7f}}) {
                    float[] out = LensModel.composite(coverage, beta, cols[0], cols[1]);
                    assertEquals(coverage, out[1], 1e-5f);
                    assertEquals(coverage * LensModel.mix(cols[0], cols[1], beta), out[0], 1e-5f);
                }
            }
        }
        assertEquals(0.7125f, LensModel.compositeSourceOver(0.5f, 0.15f, 0f, 0f)[1], 1e-5f);
    }

    /** The effect graph adds the passes; it must not go back to source-over. */
    @Test
    public void effectGraphAddsThePasses() throws Exception {
        String java = new String(Files.readAllBytes(
                Paths.get("src/main/java/com/waenhancer/theme/LensEffect.java")), StandardCharsets.UTF_8);
        assertTrue(java.contains("createBlendModeEffect(softEffect, sharpEffect, BlendMode.PLUS)"));
        assertFalse(java.contains("BlendMode.SRC_OVER"));
        // Both weights are in the shader, and both fallback returns carry them.
        String shader = LensEffect.SHADER_V2;
        assertTrue(shader.contains("(protView ? 0.0 : 1.0 - beta)"));
        assertTrue(shader.contains("(protView ? 1.0 : beta)"));
        assertFalse(shader.contains("return half4(half3(tint4.rgb * cov), half(cov));"));
    }

    // ---- 3/9. contrast after composition -----------------------------------------------------

    /**
     * Protection runs per pass on that pass's final colour. Linear luminance is linear in the
     * (1-β, β) mix, so if both passes are at or under the limit the composite is too.
     */
    @Test
    public void perPassProtectionHoldsAfterComposition() {
        double content = 1d, protect = LensModel.luminance(LensModel.toLinear(0x0B / 255d),
                LensModel.toLinear(0x14 / 255d), LensModel.toLinear(0x1A / 255d));
        double limit = (content + 0.05d) / LensModel.CONTRAST_TARGET - 0.05d;
        for (double sharp = 0d; sharp <= 1d; sharp += 0.05d) {
            for (double soft = 0d; soft <= 1d; soft += 0.05d) {
                double as = LensModel.protection(sharp, content, protect, LensModel.CONTRAST_TARGET, LensModel.PROTECTION_MAX);
                double af = LensModel.protection(soft, content, protect, LensModel.CONTRAST_TARGET, LensModel.PROTECTION_MAX);
                double s2 = (1 - as) * sharp + as * protect, f2 = (1 - af) * soft + af * protect;
                for (int k = 0; k <= 10; k++) {
                    double beta = k / 10d;
                    double mixed = (1 - beta) * s2 + beta * f2;
                    assertTrue(mixed <= limit + 1e-9);
                }
            }
        }
        // The shader protects last, in linear light, in both passes, whatever the colour group.
        String shader = LensEffect.SHADER_V2;
        int protection = shader.indexOf("if (uAdaptive > 0.5 && uPass > 0.5) {");
        assertTrue(protection > shader.indexOf("col = col * (1.0 - 0.26 * shade);"));
        assertTrue(shader.contains("float3 linCol = float3(toLinearSrgb(half3(col)));"));
    }

    /**
     * What each foreground actually gets. Protection targets the theme's content colour: white
     * (dark mode) and black (light mode) reach 4.5:1 over any backdrop within the cap. WhatsApp's
     * grey hint and secondary icons are lighter than the content colour; protecting for them
     * would need near-opaque glass, so they are recorded here, not guaranteed.
     */
    @Test
    public void protectionCoverageByForeground() {
        double dark = lum(0x0B141A), white = lum(0xFFFFFF);
        double worstWhite = worst(1d, dark, 1d), worstBlack = worst(0d, white, 0d);
        assertTrue("white " + worstWhite, worstWhite >= 4.5d - 1e-6);
        assertTrue("black " + worstBlack, worstBlack >= 4.5d - 1e-6);
        // The hint (#8696A0, dark mode) over a backdrop protected for white content: the glass may
        // be as bright as the 4.5:1 limit for white, which leaves the hint at about 1.5:1 there.
        double limit = (1d + 0.05d) / LensModel.CONTRAST_TARGET - 0.05d;
        double hint = lum(0x8696A0);
        double hintWorst = (Math.max(hint, limit) + 0.05d) / (Math.min(hint, limit) + 0.05d);
        assertTrue("hint " + hintWorst, hintWorst < 2d);
    }

    private static double worst(double content, double protect, double contentLum) {
        double w = Double.MAX_VALUE;
        for (int i = 0; i <= 100; i++) {
            double g = i / 100d;
            double a = LensModel.protection(g, contentLum, protect, LensModel.CONTRAST_TARGET, LensModel.PROTECTION_MAX);
            w = Math.min(w, LensModel.contrast(content, (1 - a) * g + a * protect));
        }
        return w;
    }

    private static double lum(int rgb) {
        return LensModel.luminance(LensModel.toLinear(((rgb >> 16) & 0xFF) / 255d),
                LensModel.toLinear(((rgb >> 8) & 0xFF) / 255d), LensModel.toLinear((rgb & 0xFF) / 255d));
    }

    // ---- 4. padded culling and draw order ----------------------------------------------------

    /** A sibling only in the blur margin feeds the rim; without the margin it would be dropped. */
    @Test
    public void marginOnlySiblingsAreRecorded() {
        // Surface at 0..200 in drawable pixels; a sibling at -20..-5, like the review's 80..95 vs 100.
        assertTrue(BehindRecorder.reachesRecording(-20f, 10f, -5f, 40f, 200, 60, 20));
        assertFalse(BehindRecorder.reachesRecording(-20f, 10f, -5f, 40f, 200, 60, 0));
        assertFalse(BehindRecorder.reachesRecording(-60f, 10f, -25f, 40f, 200, 60, 20));
    }

    /** Red (index 0, Z 2) and blue (index 1, Z 1): blue draws first, as the window draws them. */
    @Test
    public void siblingsAreOrderedByZThenIndex() {
        assertTrue(BehindRecorder.compareDrawOrder(1f, 1, 2f, 0) < 0);
        assertTrue(BehindRecorder.compareDrawOrder(2f, 0, 1f, 1) > 0);
        assertTrue(BehindRecorder.compareDrawOrder(0f, 0, 0f, 1) < 0);
    }

    // ---- 5. effective budget -----------------------------------------------------------------

    @Test
    public void costCountsMarginAndPasses() {
        assertEquals(576L, LiveBudget.cost(24, 24, 0, 1));
        assertEquals(30_000L, LiveBudget.cost(24, 24, 38, 3));
        GlassSpec spec = GlassSpec.resolve(GlassSpec.Variant.LIQUID, true, 0, 0, 10f, true, false);
        assertEquals(3, LiveBudget.passes(GlassOptics.ALL, spec, 2.75f));
        assertEquals(1, LiveBudget.passes(GlassOptics.LEGACY, spec, 2.75f));
        assertEquals(LensModel.padding(LensModel.sigmaPx(spec, 2.75f)),
                LiveBudget.padding(GlassOptics.ALL, spec, 2.75f));
    }

    /** Panes go through the same admission: past capacity a pane is refused and withdrawn. */
    @Test
    public void panesAreAdmittedAgainstCapacity() {
        LiveBudget budget = new LiveBudget(1000);
        Object header = new Object(), composer = new Object(), band = new Object();
        assertTrue(budget.admitPane(header, 400));
        assertTrue(budget.admitPane(composer, 500));
        assertFalse(budget.admitPane(band, 200));
        assertEquals(900L, budget.paneSpend());
        assertEquals(2, budget.paneCount());
        // Re-admitting updates the cost rather than double-counting it.
        assertTrue(budget.admitPane(header, 300));
        assertEquals(800L, budget.paneSpend());
        budget.releasePane(composer);
        assertTrue(budget.admitPane(band, 200));
        assertEquals(500L, budget.paneSpend());
    }

    // ---- 7. material identity ----------------------------------------------------------------

    /**
     * Every field the shader reads takes part in equality, so a changed uniform always rebuilds
     * the effect. Each field is perturbed on its own through the private constructor.
     */
    @Test
    public void everyMaterialFieldTakesPartInEquality() throws Exception {
        GlassSpec base = GlassSpec.resolve(GlassSpec.Variant.LIQUID, true, 0, 0xFF25D366, 10f, true, false);
        List<Field> fields = new ArrayList<>();
        for (Field f : GlassSpec.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) fields.add(f);
        }
        Constructor<?> ctor = null;
        for (Constructor<?> c : GlassSpec.class.getDeclaredConstructors()) {
            if (c.getParameterCount() == fields.size()) ctor = c;
        }
        assertTrue("constructor over all fields", ctor != null);
        ctor.setAccessible(true);
        Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < fields.size(); i++) assertEquals(fields.get(i).getType(), types[i]);
        for (int changed = 0; changed < fields.size(); changed++) {
            Object[] args = new Object[fields.size()];
            for (int i = 0; i < fields.size(); i++) {
                Field f = fields.get(i);
                f.setAccessible(true);
                Object v = f.get(base);
                if (i == changed) v = perturb(v);
                args[i] = v;
            }
            GlassSpec other = (GlassSpec) ctor.newInstance(args);
            assertFalse(fields.get(changed).getName() + " ignored by equals", base.equals(other));
            assertFalse(LensEffect.sameMaterial(base, other, true));
        }
        GlassSpec same = GlassSpec.resolve(GlassSpec.Variant.LIQUID, true, 0, 0xFF25D366, 10f, true, false);
        assertTrue(LensEffect.sameMaterial(base, same, true));
        // Without the temporal group: identity, as the legacy lens keys.
        assertFalse(LensEffect.sameMaterial(base, same, false));
        assertTrue(LensEffect.sameMaterial(base, base, false));
    }

    private static Object perturb(Object v) {
        if (v instanceof Integer) return ((Integer) v) ^ 0x01010101;
        if (v instanceof Float) return ((Float) v) + 0.125f;
        if (v instanceof Boolean) return !((Boolean) v);
        throw new AssertionError("unhandled field type " + v);
    }
}
