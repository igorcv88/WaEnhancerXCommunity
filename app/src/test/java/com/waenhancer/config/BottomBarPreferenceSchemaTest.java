package com.waenhancer.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BottomBarPreferenceSchemaTest {

    @Test
    public void clampsLegacyStringAboveMaximum() {
        assertEquals(64f,
                BottomBarPreferenceSchema.normalize("floating_bottom_bar_radius", "96"),
                0.001f);
    }

    @Test
    public void acceptsIntegerAndAlignsStep() {
        assertEquals(35f,
                BottomBarPreferenceSchema.normalize(
                        "floating_bottom_bar_glass_opacity", 34),
                0.001f);
    }

    @Test
    public void usesDefaultForInvalidNumber() {
        assertEquals(24f,
                BottomBarPreferenceSchema.normalize(
                        "floating_bottom_bar_icon_size", "not-a-number"),
                0.001f);
    }

    @Test
    public void preservesNegativeOffsetWithinRange() {
        // fab_offset is now the only control whose range reaches below zero; the indicator offset
        // this used to cover was removed together with the selected-tab indicator.
        assertEquals(-12f,
                BottomBarPreferenceSchema.normalize(
                        "floating_bottom_bar_fab_offset", -12f),
                0.001f);
    }

    /**
     * The pill is a floating element, so the side margin has to be able to make it genuinely
     * narrow. Capped at 48dp it could never take more than about a quarter of a phone screen,
     * which made the slider feel broken rather than restrained.
     */
    @Test
    public void theSideMarginCanNarrowThePillWellPastAQuarterOfTheScreen() {
        BottomBarPreferenceSchema.Spec spec =
                BottomBarPreferenceSchema.spec("floating_bottom_bar_horizontal_margin");
        assertTrue("a 96dp margin a side is ~47% of a 411dp phone", spec.max >= 96f);
        assertEquals(0f, spec.min, 0.001f);
    }
}
