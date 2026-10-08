package com.waenhancer.theme;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** A real capability fallback must not attempt optics. Battery Saver does not change capability. */
public class LiveBackdropPolicyTest {

    @Test
    public void fallbackSpecsNeverRecord() {
        GlassSpec unsupported = GlassSpec.resolve(GlassSpec.Variant.LIQUID, true, 0, 0, 40f, false, false);
        assertFalse(LiveBackdrop.wantsLive(unsupported));
        GlassSpec optical = GlassSpec.resolve(GlassSpec.Variant.LIQUID, true, 0, 0, 40f, true, false);
        assertFalse(LiveBackdrop.wantsLive(optical.withoutOptics()));
        assertFalse(LiveBackdrop.wantsLive(null));
    }

    @Test
    public void lensedSpecsRecord() {
        GlassSpec optical = GlassSpec.resolve(GlassSpec.Variant.LIQUID, true, 0, 0, 40f, true, false);
        assertTrue(LiveBackdrop.wantsLive(optical));
    }
}
