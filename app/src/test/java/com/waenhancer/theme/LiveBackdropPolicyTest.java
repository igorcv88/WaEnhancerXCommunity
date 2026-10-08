package com.waenhancer.theme;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Power saving must turn off the GPU path, not just change how it looks. */
public class LiveBackdropPolicyTest {

    @Test
    public void fallbackSpecsNeverRecord() {
        GlassSpec powerSaving = GlassSpec.resolve(GlassSpec.Variant.LIQUID, true, 0, 0, 40f, false, false);
        assertFalse(LiveBackdrop.wantsLive(powerSaving));
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
