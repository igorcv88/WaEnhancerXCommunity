package com.waenhancer.theme;

import static org.junit.Assert.assertEquals;

import android.view.View.MeasureSpec;

import org.junit.Test;

public class WallpaperUnderlayMeasureTest {

    @Test
    public void claimsNoSpaceUnlessGivenExactly() {
        // A wrap-content holder offers AT_MOST the window; the underlay must not take it.
        assertEquals(0, WallpaperUnderlay.measuredSize(MeasureSpec.AT_MOST, 2400));
        assertEquals(0, WallpaperUnderlay.measuredSize(MeasureSpec.UNSPECIFIED, 2400));
        // The holder's second pass gives match-parent children its final size exactly.
        assertEquals(168, WallpaperUnderlay.measuredSize(MeasureSpec.EXACTLY, 168));
    }
}
