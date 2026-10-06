package com.waenhancer.theme;

import org.junit.Test;
import static org.junit.Assert.*;

public class SharedGlassBackdropBudgetTest {
    private static final GlassSpec SIMPLE = GlassSpec.resolve(GlassSpec.Variant.STABLE, false, 0, 0, 35, false, false);
    private static final GlassSpec LIQUID = GlassSpec.resolve(GlassSpec.Variant.LIQUID, false, 0, 0, 10, true, false);
    @Test public void twoSessionsCannotResetBudgetInTheSameFrame() {
        GlassRenderPolicy policy = new GlassRenderPolicy();
        Object first = new Object();
        policy.beginFrame(17, 100, GlassRenderPolicy.Tier.NORMAL);
        assertTrue(policy.allow(first, 10, 10, LIQUID, false));
        policy.beginFrame(17, 100_000, GlassRenderPolicy.Tier.NORMAL);
        assertTrue(policy.allow(first, 10, 10, LIQUID, false));
        assertFalse(policy.allow(new Object(), 10, 10, LIQUID, false));
        policy.beginFrame(18, 100, GlassRenderPolicy.Tier.NORMAL);
        assertTrue(policy.allow(new Object(), 10, 10, LIQUID, false));
    }
    @Test public void countLimitResetsOnlyWithNextFrameToken() {
        GlassRenderPolicy policy = new GlassRenderPolicy();
        policy.beginFrame(1, 1000, GlassRenderPolicy.Tier.NORMAL);
        Object[] holders = new Object[24];
        for (int i = 0; i < holders.length; i++) {
            holders[i] = new Object(); assertTrue(policy.allow(holders[i], 1, 1, SIMPLE, false));
        }
        policy.beginFrame(1, 1000, GlassRenderPolicy.Tier.NORMAL);
        assertFalse(policy.allow(new Object(), 1, 1, SIMPLE, false));
        policy.beginFrame(2, 1000, GlassRenderPolicy.Tier.NORMAL);
        assertTrue(policy.allow(new Object(), 1, 1, SIMPLE, false));
    }
    @Test public void invalidBoundsCannotOverflowOrBypassInitialization() {
        GlassRenderPolicy policy = new GlassRenderPolicy();
        assertFalse(policy.allow(new Object(), 1, 1, SIMPLE, false));
        policy.beginFrame(1, 100, GlassRenderPolicy.Tier.NORMAL);
        assertFalse(policy.allow(new Object(), 0, 100, SIMPLE, false));
        assertFalse(policy.allow(new Object(), -1, 100, SIMPLE, false));
        assertFalse(policy.allow(new Object(), Integer.MAX_VALUE, Integer.MAX_VALUE, SIMPLE, false));
        assertTrue(policy.allow(new Object(), 10, 10, SIMPLE, false));
    }
    @Test public void dispersionBlurAndMaskConsumeMoreThanSimpleFill() {
        GlassRenderPolicy policy = new GlassRenderPolicy();
        policy.beginFrame(1, 100, GlassRenderPolicy.Tier.NORMAL);
        assertTrue(policy.allow(new Object(), 10, 10, SIMPLE, false));
        assertFalse(policy.allow(new Object(), 10, 10, LIQUID, true));
        assertTrue(policy.allow(new Object(), 10, 10, LIQUID, false));
    }
    @Test public void conservingTierLimitsOutputWork() {
        GlassRenderPolicy policy = new GlassRenderPolicy();
        policy.beginFrame(1, 100, GlassRenderPolicy.Tier.CONSERVING);
        assertFalse(policy.allow(new Object(), 10, 10, LIQUID, false));
        policy.beginFrame(2, 100, GlassRenderPolicy.Tier.NORMAL);
        assertTrue(policy.allow(new Object(), 10, 10, LIQUID, false));
    }
    @Test public void sustainedMissesReduceBudgetAndRecoveryHasHysteresis() {
        GlassRenderPolicy policy = new GlassRenderPolicy();
        for (int i = 0; i < 3; i++) policy.observeFrame(9_000_000, 8_333_333);
        assertTrue(policy.overloaded());
        policy.beginFrame(1, 100, GlassRenderPolicy.Tier.NORMAL);
        assertFalse(policy.allow(new Object(), 10, 10, LIQUID, false));
        for (int i = 0; i < 59; i++) policy.observeFrame(5_000_000, 8_333_333);
        assertTrue(policy.overloaded());
        policy.observeFrame(5_000_000, 8_333_333);
        assertFalse(policy.overloaded());
    }
    @Test public void transientAndRepeatedFailuresAlwaysAllowLaterRecovery() {
        GlassRenderPolicy.Retry retry = new GlassRenderPolicy.Retry();
        assertTrue(retry.ready(1000));
        retry.failure(1000);
        assertFalse(retry.ready(1249)); assertTrue(retry.ready(1250));
        retry.failure(1250);
        assertFalse(retry.ready(1749)); assertTrue(retry.ready(1750));
        for (int i = 0; i < 20; i++) retry.failure(2000);
        assertFalse(retry.ready(31_999)); assertTrue(retry.ready(32_000));
        retry.success(); assertEquals(0, retry.failures()); assertTrue(retry.ready(32_001));
    }
}
