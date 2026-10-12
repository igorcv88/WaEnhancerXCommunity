package com.waenhancer.theme;

import static org.junit.Assert.*;

import com.waenhancer.config.LiquidGlassOptics;
import com.waenhancer.testing.FakeSharedPreferences;
import org.junit.Test;

/** Numeric and policy checks, never a GPU/PSF claim. Device PNG acceptance lives in the lab. */
public class OpticalReconstructionTest {
    private static GlassOptics optics(GlassOptics.Debug debug, boolean filtering) {
        return GlassOptics.resolve(true,filtering,true,true,true,false,debug,0.28f)
                .withProfile(GlassOptics.Profile.RECONSTRUCT);
    }

    @Test public void defaultRemainsBaselineAndExplicitCandidateIsAtomic() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        prefs.edit().putBoolean(LiquidGlassOptics.MASTER,true).apply();
        assertEquals(GlassOptics.Profile.BASELINE,LiquidGlassOptics.read(prefs).profile);
        prefs.edit().putBoolean("liquid_glass_search",false).putBoolean(LiquidGlassOptics.CLEAR,true)
                .putString(LiquidGlassOptics.DEBUG,"raw_input").apply();
        LiquidGlassOptics.applyReconstruction(prefs);
        GlassOptics o = LiquidGlassOptics.read(prefs);
        assertTrue(o.reconstruct());
        assertTrue(o.filtering && o.adaptive && o.color && o.temporal);
        assertFalse(o.clearProfile);
        assertEquals(GlassOptics.Debug.NONE,o.debug);
        assertFalse(prefs.getBoolean("liquid_glass_search",false));
        LiquidGlassOptics.applyOriginal(prefs);
        assertSame(GlassOptics.LEGACY,LiquidGlassOptics.read(prefs));
    }

    @Test public void allProfilesRebuildAndRoundTripWithoutLosingSwitches() {
        for (GlassOptics.Profile p : GlassOptics.Profile.values()) {
            assertEquals(p,GlassOptics.Profile.from(p.key()));
            GlassOptics o = GlassOptics.ALL.withProfile(p);
            assertTrue(o.corrected && o.temporal && o.geometry);
            if (p != GlassOptics.Profile.BASELINE) assertNotEquals(GlassOptics.ALL.key(),o.key());
            assertSame(GlassOptics.LEGACY,GlassOptics.LEGACY.withProfile(p));
        }
        assertEquals(GlassOptics.Profile.BASELINE,GlassOptics.Profile.from("unknown"));
    }

    @Test public void isolationModesCannotAccidentallyRunMaterialOrLoseSoftInput() {
        for (GlassOptics.Debug d : new GlassOptics.Debug[]{GlassOptics.Debug.RAW_INPUT,
                GlassOptics.Debug.SHARP_ONLY,GlassOptics.Debug.SOFT_ONLY,GlassOptics.Debug.BETA_HEATMAP,
                GlassOptics.Debug.JACOBIAN,GlassOptics.Debug.FOOTPRINT,GlassOptics.Debug.COVERAGE}) {
            assertFalse(optics(d,true).reconstruct());
        }
        assertTrue(optics(GlassOptics.Debug.SOFT_ONLY,false).needsBlur());
        assertFalse(optics(GlassOptics.Debug.SHARP_ONLY,true).needsBlur());
        assertTrue(optics(GlassOptics.Debug.PROTECTION,true).reconstruct());
        assertTrue(optics(GlassOptics.Debug.FINAL_NO_LIGHT,true).reconstruct());
        assertFalse(optics(GlassOptics.Debug.NONE,false).reconstruct());
    }

    @Test public void transferCandidatesHaveBoundedContinuousResiduals() {
        for (GlassOptics.Profile p : GlassOptics.Profile.values()) {
            for (float offset : new float[]{0f,0.25f,0.5f,1f}) {
                float previous = 0f;
                for (int i=0;i<=1000;i++) {
                    float b = LensModel.beta(i/1000f,p,offset);
                    assertTrue(b>=previous && b>=p.rim && b<=1f);
                    previous=b;
                }
                assertEquals(1f,LensModel.beta(p.fullAt,p,offset),1e-6f);
                float h=0.0001f;
                assertTrue((1-LensModel.beta(p.fullAt-h,p,offset))/h<0.02f);
            }
        }
        assertEquals(0.85f,1-LensModel.beta(0f,GlassOptics.Profile.BASELINE,0f),1e-6f);
        assertTrue(1-LensModel.beta(0f,GlassOptics.Profile.RECONSTRUCT,1f)<0.30f);
    }

    @Test public void composedLinearNumericContributionsEncodeOnceAndKeepAlpha() {
        // Black/white at equal weight must encode 0.735, not the encoded-space 0.5 baseline.
        for (float coverage : new float[]{0.05f,0.25f,0.5f,1f}) {
            float[] mixture = LensModel.composite(coverage,0.5f,0f,1f);
            assertEquals(coverage,mixture[1],1e-6f);
            assertEquals(0.73535698,LensModel.fromLinear(mixture[0]/mixture[1]),1e-6);
            for (float constant : new float[]{0.05f,0.2f,0.5f,0.95f}) {
                float linear=(float)LensModel.toLinear(constant);
                float[] same=LensModel.composite(coverage,0.7f,linear,linear);
                assertEquals(constant,LensModel.fromLinear(same[0]/same[1]),1e-6);
            }
        }
    }

    @Test public void reconstructionBudgetIncludesNewPassesAndDetailRadiusIsBounded() {
        GlassSpec s=GlassSpec.resolve(GlassSpec.Variant.LIQUID,true,0,0,10f,true,false);
        assertEquals(6,LiveBudget.passes(optics(GlassOptics.Debug.NONE,true),s,3.75f));
        assertEquals(3,LiveBudget.passes(GlassOptics.ALL,s,3.75f));
        assertEquals(1,LiveBudget.passes(optics(GlassOptics.Debug.RAW_INPUT,true),s,3.75f));
        for (float d : new float[]{0.5f,1f,2.75f,3.75f,5f}) {
            float detail=LensModel.detailRadiusPx(d);
            assertTrue(detail>=0.75f && detail<=3f);
            assertTrue(detail<LensModel.sigmaPx(s,d));
        }
    }
}
