package com.waenhancer.theme;

import org.junit.Test;
import static org.junit.Assert.*;

public class SharedGlassBackdropBudgetTest {
    @Test public void pixelBudgetCannotBeBypassedBySmallSurfaceCount() {
        SharedGlassBackdrop provider = new SharedGlassBackdrop(null);
        Object first = new Object();
        assertTrue(provider.allowShader(first, 1000, 2000));
        assertTrue(provider.allowShader(first, 1000, 2000));
        assertFalse(provider.allowShader(new Object(), 1000, 1001));
        assertTrue(provider.allowShader(new Object(), 1000, 1000));
    }

    @Test public void tinySurfacesStillHaveACountLimit() {
        SharedGlassBackdrop provider = new SharedGlassBackdrop(null);
        Object[] holders = new Object[24];
        for (int i = 0; i < holders.length; i++) {
            holders[i] = new Object();
            assertTrue(provider.allowShader(holders[i], 1, 1));
        }
        assertFalse(provider.allowShader(new Object(), 1, 1));
        provider.beginFrame();
        assertTrue(provider.allowShader(new Object(), 1, 1));
    }

    @Test public void oversizedAndInvalidBoundsFailWithoutIntegerOverflow() {
        SharedGlassBackdrop provider = new SharedGlassBackdrop(null);
        assertFalse(provider.allowShader(new Object(), 0, 100));
        assertFalse(provider.allowShader(new Object(), -1, 100));
        assertFalse(provider.allowShader(new Object(), Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertTrue(provider.allowShader(new Object(), 100, 100));
    }
}
