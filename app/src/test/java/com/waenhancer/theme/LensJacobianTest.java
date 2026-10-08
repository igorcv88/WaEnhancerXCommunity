package com.waenhancer.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The corrected warp is a stable map: across sizes, corner radii, densities, variants and
 * selected-tab states, for each colour channel, its Jacobian keeps a positive determinant and a
 * smallest singular value of at least {@link #SIGMA_MIN} everywhere the surface is fully covered.
 *
 * <p>Measured on {@link LensModel}'s mirror of the shader by central differences, so this checks
 * the real map — corners, the curvature bias, the selected-tab band, chromatic channels and the
 * sample clamp — and not just the 1D profile along a straight edge.</p>
 */
public class LensJacobianTest {

    static final double SIGMA_MIN = 0.30d;
    private static final float H = 0.05f;

    static final class Worst {
        double sigma = Double.MAX_VALUE;
        double det = Double.MAX_VALUE;
        String where = "";
        int samples;
    }

    /** Smallest singular value and determinant of a 2x2 matrix. */
    static double[] svdMinDet(double a, double b, double c, double d) {
        double det = a * d - b * c;
        double t = a * a + b * b + c * c + d * d;
        double disc = Math.sqrt(Math.max(0d, t * t - 4d * det * det));
        double lmin = Math.max(0d, (t - disc) / 2d);
        return new double[]{Math.sqrt(lmin), det};
    }

    static Worst measure(LensModel.Surface s, boolean corrected, int stride) {
        Worst worst = new Worst();
        float[] p0 = new float[6], p1 = new float[6], q0 = new float[6], q1 = new float[6];
        for (int y = 0; y < (int) s.height; y += 1) {
            for (int x = 0; x < (int) s.width; x += 1) {
                float cx = x + 0.5f, cy = y + 0.5f;
                float d = LensModel.sdRoundRect(cx - s.width / 2f, cy - s.height / 2f,
                        s.width / 2f, s.height / 2f, s.radius);
                // Fully covered only: the antialias band and the exterior are not material.
                if (d > -0.75f - H) continue;
                // Only the band where anything is displaced needs the full check; sample the rest.
                if (-d > s.bevel + 2f && ((x % stride) != 0 || (y % stride) != 0)) continue;
                LensModel.sample(s, cx - H, cy, corrected, p0);
                LensModel.sample(s, cx + H, cy, corrected, p1);
                LensModel.sample(s, cx, cy - H, corrected, q0);
                LensModel.sample(s, cx, cy + H, corrected, q1);
                for (int ch = 0; ch < 3; ch++) {
                    double a = (p1[2 * ch] - p0[2 * ch]) / (2d * H);
                    double c = (p1[2 * ch + 1] - p0[2 * ch + 1]) / (2d * H);
                    double b = (q1[2 * ch] - q0[2 * ch]) / (2d * H);
                    double dd = (q1[2 * ch + 1] - q0[2 * ch + 1]) / (2d * H);
                    double[] sd = svdMinDet(a, b, c, dd);
                    worst.samples++;
                    if (sd[0] < worst.sigma) {
                        worst.sigma = sd[0];
                        worst.where = "ch" + ch + " at " + cx + "," + cy + " depth=" + (-d);
                    }
                    if (sd[1] < worst.det) worst.det = sd[1];
                }
            }
        }
        return worst;
    }

    private static GlassSpec spec(GlassSpec.Variant variant) {
        return GlassSpec.resolve(variant, true, 0, 0xFF25D366, variant.recommendedOpacityPercent(), true, false);
    }

    private static final float[][] SIZES = {
            {640f, 132f}, {330f, 120f}, {150f, 150f}, {56f, 56f},
            {720f, 64f}, {200f, 40f}, {64f, 260f}, {24f, 24f}};
    private static final float[] DENSITIES = {1f, 2.75f, 4f};

    private static List<LensModel.Surface> surfaces(GlassSpec spec, boolean geometry) {
        List<LensModel.Surface> out = new ArrayList<>();
        for (float[] size : SIZES) {
            float w = size[0], h = size[1];
            float[] radii = {0f, 4f, 12f, Math.min(w, h) / 4f, Math.min(w, h) / 2f, 1000f};
            for (float density : DENSITIES) {
                for (float r : radii) {
                    for (boolean color : new boolean[]{true, false}) {
                        out.add(LensModel.surface(spec, w, h, r * (r < 1000f ? density / 2.75f : 1f),
                                density, geometry, color, LensModel.DEFAULT_DISPLACEMENT));
                    }
                }
            }
        }
        return out;
    }

    private static List<LensModel.Surface> withActiveStates(LensModel.Surface s) {
        List<LensModel.Surface> out = new ArrayList<>();
        out.add(s);
        float hh = s.height / 2f;
        float inset = Math.min(9f * 2.75f, s.height * 0.22f);
        float halfH = Math.max(0.5f, hh - inset);
        // A tab in the middle, one against the left end, one hanging off the right end, a tiny one.
        out.add(s.withActive(s.width * 0.5f, hh, s.width * 0.12f, halfH, halfH, 1f));
        out.add(s.withActive(s.width * 0.12f, hh, s.width * 0.12f, halfH, halfH, 1f));
        out.add(s.withActive(s.width * 0.98f, hh, s.width * 0.12f, halfH, halfH, 1f));
        out.add(s.withActive(s.width * 0.5f, hh, 6f, 4f, 4f, 1f));
        out.add(s.withActive(s.width * 0.5f, hh, s.width * 0.12f, halfH, halfH, 0.5f));
        return out;
    }

    @Test
    public void correctedWarpIsStableEverywhere() {
        Worst overall = new Worst();
        int surfaces = 0;
        for (GlassSpec.Variant variant : new GlassSpec.Variant[]{
                GlassSpec.Variant.LIQUID, GlassSpec.Variant.CLEAR, GlassSpec.Variant.ADVANCED}) {
            GlassSpec spec = spec(variant);
            for (LensModel.Surface base : surfaces(spec, true)) {
                for (LensModel.Surface s : withActiveStates(base)) {
                    Worst w = measure(s, true, 7);
                    surfaces++;
                    overall.samples += w.samples;
                    if (w.sigma < overall.sigma) {
                        overall.sigma = w.sigma;
                        overall.where = variant + " " + s.width + "x" + s.height + " r=" + s.radius
                                + " bevel=" + s.bevel + " active=" + s.active + " " + w.where;
                    }
                    overall.det = Math.min(overall.det, w.det);
                }
            }
        }
        System.out.println("corrected warp: " + surfaces + " surfaces, " + overall.samples
                + " channel samples, min sigma=" + overall.sigma + " min det=" + overall.det
                + " worst=" + overall.where);
        assertTrue("det must stay positive; worst " + overall.where, overall.det > 0d);
        assertTrue("sigma_min " + overall.sigma + " < " + SIGMA_MIN + " at " + overall.where,
                overall.sigma >= SIGMA_MIN);
    }

    /** The developer tuning range never leaves the stable region either: the cap holds. */
    @Test
    public void tuningRangeIsBoundedByTheCap() {
        GlassSpec spec = spec(GlassSpec.Variant.LIQUID);
        double worst = Double.MAX_VALUE;
        for (float a : new float[]{LensModel.MIN_DISPLACEMENT, 0.22f, 0.32f, LensModel.MAX_EFFECTIVE}) {
            for (float density : DENSITIES) {
                LensModel.Surface base = LensModel.surface(spec, 640f, 132f, 66f, density, true, true, a);
                for (LensModel.Surface s : withActiveStates(base)) {
                    worst = Math.min(worst, measure(s, true, 5).sigma);
                }
            }
        }
        assertTrue("sigma_min " + worst, worst >= SIGMA_MIN);
    }

    /** Negative control: the legacy map folds, which is what the correction exists for. */
    @Test
    public void legacyWarpFolds() {
        GlassSpec spec = spec(GlassSpec.Variant.LIQUID);
        LensModel.Surface s = LensModel.surface(spec, 640f, 132f, 66f, 2.75f, false, false,
                LensModel.DEFAULT_DISPLACEMENT);
        Worst w = measure(s, false, 3);
        assertTrue("legacy sigma_min " + w.sigma, w.sigma < 0.05d);
    }

    /** The 1D derivation: 1 - 2A/b at the outline. */
    @Test
    public void normalDerivativeMatchesTheDerivation() {
        assertEquals(0.008d, 1d - 2d * LensModel.LEGACY_DISPLACEMENT * 0.8d, 1e-6);
        assertEquals(-0.24d, 1d - 2d * LensModel.LEGACY_DISPLACEMENT, 1e-6);
        // The cap leaves margin over the 0.30 acceptance target.
        assertEquals(0.34d, 1d - 2d * LensModel.MAX_EFFECTIVE, 1e-6);
        assertTrue(LensModel.MAX_EFFECTIVE <= 0.35f);
    }

    /** The cap holds after every multiplier, per channel. */
    @Test
    public void amplitudeNeverExceedsTheCap() {
        GlassSpec spec = spec(GlassSpec.Variant.LIQUID);
        for (float density : DENSITIES) {
            LensModel.Surface s = LensModel.surface(spec, 640f, 132f, 66f, density, true, false,
                    LensModel.MAX_EFFECTIVE);
            float[] out = new float[6];
            // The outline's deepest-displaced point: the left end, mid height, selected.
            LensModel.Surface active = s.withActive(40f, 66f, 60f, 40f, 40f, 1f);
            LensModel.sample(active, 1.5f, 66f, true, out);
            for (int ch = 0; ch < 3; ch++) {
                float moved = out[2 * ch] - 1.5f;
                assertTrue("channel " + ch + " moved " + moved + " > cap " + s.cap, moved <= s.cap + 1e-3f);
            }
        }
    }
}
