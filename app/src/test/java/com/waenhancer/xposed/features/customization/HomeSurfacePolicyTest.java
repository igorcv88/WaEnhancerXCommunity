package com.waenhancer.xposed.features.customization;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HomeSurfacePolicyTest {
    @Test public void nativeFabVisibilityIsNeverOverriddenByStyling() {
        assertTrue(HomeSurfacePolicy.mayStyleFab(true));
        assertFalse(HomeSurfacePolicy.mayStyleFab(false));
    }

    @Test public void newlyVisibleSearchRequestsOneFreshFrame() {
        assertTrue(HomeSurfacePolicy.captureNewSearch(true, true, false, true));
        assertFalse(HomeSurfacePolicy.captureNewSearch(true, true, true, true));
        assertFalse(HomeSurfacePolicy.captureNewSearch(true, false, false, true));
        assertFalse(HomeSurfacePolicy.captureNewSearch(true, true, false, false));
        assertFalse(HomeSurfacePolicy.captureNewSearch(false, true, false, true));
    }
}
