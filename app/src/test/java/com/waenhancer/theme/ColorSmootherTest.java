package com.waenhancer.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The bar's adaptation colour follows its backdrop with a 120 ms time constant, in linear light. */
public class ColorSmootherTest {

    @Test
    public void firstSampleIsTakenAsIs() {
        ColorSmoother s = new ColorSmoother();
        assertEquals(0xFF336699, s.step(0xFF336699, 0));
        assertTrue(s.converged(0xFF336699));
    }

    /** After one time constant, 1 - 1/e of the way, measured in linear light. */
    @Test
    public void oneTimeConstantCoversSixtyThreePercent() {
        ColorSmoother s = new ColorSmoother();
        s.step(0xFF000000, 0);
        int mid = s.step(0xFFFFFFFF, (long) ColorSmoother.TAU_MS);
        double linear = LensModel.toLinear(((mid >> 16) & 0xFF) / 255d);
        assertEquals(1d - Math.exp(-1d), linear, 0.01d);
        assertFalse(s.converged(0xFFFFFFFF));
    }

    @Test
    public void convergesAndStops() {
        ColorSmoother s = new ColorSmoother();
        s.step(0xFF102030, 0);
        int last = 0;
        for (long t = 16; t < 2000; t += 16) last = s.step(0xFFE0D0C0, t);
        assertEquals(0xFFE0D0C0, last);
        assertTrue(s.converged(0xFFE0D0C0));
    }
}
