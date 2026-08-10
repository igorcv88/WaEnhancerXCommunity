package com.waenhancer.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * The editor's preview used to read only a handful of keys, so most controls changed a stored value
 * without changing anything on screen. These tests pin every control to the model the preview
 * renders from.
 */
public class BottomBarPreviewModelTest {

    private static Map<String, Object> snapshot() {
        return new HashMap<>();
    }

    @Test
    public void defaultsComeFromTheSchema() {
        BottomBarPreviewModel model = BottomBarPreviewModel.from(snapshot());

        assertEquals(28, model.radiusDp);
        assertEquals(22, model.bottomMarginDp);
        assertEquals(16, model.sideMarginDp);
        assertEquals(24, model.iconSizeDp);
        assertEquals(12, model.textSizeSp);
        assertEquals(2, model.iconLabelSpacingDp);
        assertEquals(6, model.paddingVerticalDp);
        assertEquals(64, model.manualHeightDp);
        assertEquals("default", model.fabMode);
        assertFalse(model.manualHeight);
    }

    @Test
    public void everyGeometryControlReachesTheModel() {
        Map<String, Object> prefs = snapshot();
        prefs.put("floating_bottom_bar_radius", 12f);
        prefs.put("floating_bottom_bar_bottom_margin", 4f);
        prefs.put("floating_bottom_bar_horizontal_margin", 6f);
        prefs.put("floating_bottom_bar_padding_vertical", 11f);
        prefs.put("floating_bottom_bar_icon_size", 33f);
        prefs.put("floating_bottom_bar_text_size", 17f);
        prefs.put("floating_bottom_bar_icon_label_spacing", 9f);
        prefs.put("floating_bottom_bar_manual_height", 77f);

        BottomBarPreviewModel model = BottomBarPreviewModel.from(prefs);

        assertEquals(12, model.radiusDp);
        assertEquals(4, model.bottomMarginDp);
        assertEquals(6, model.sideMarginDp);
        assertEquals(11, model.paddingVerticalDp);
        assertEquals(33, model.iconSizeDp);
        assertEquals(17, model.textSizeSp);
        assertEquals(9, model.iconLabelSpacingDp);
        assertEquals(77, model.manualHeightDp);
    }

    /**
     * The selected-tab indicator was removed: it drew a second indicator over WhatsApp's own
     * instead of restyling it. Its keys must not resurface in the model.
     */
    @Test
    public void indicatorKeysAreIgnored() {
        Map<String, Object> prefs = snapshot();
        prefs.put("floating_bottom_bar_indicator_visible", true);
        prefs.put("floating_bottom_bar_indicator_width", 55f);

        BottomBarPreviewModel model = BottomBarPreviewModel.from(prefs);

        for (java.lang.reflect.Field field : BottomBarPreviewModel.class.getFields()) {
            assertFalse("indicator field survived removal: " + field.getName(),
                    field.getName().toLowerCase(java.util.Locale.US).contains("indicator"));
        }
        assertEquals(28, model.radiusDp);
    }

    @Test
    public void everyFabControlReachesTheModel() {
        Map<String, Object> prefs = snapshot();
        prefs.put("floating_bottom_bar_fab_mode", "minimal");
        prefs.put("floating_bottom_bar_fab_offset", 20f);
        prefs.put("floating_bottom_bar_minimal_fab_size", 60f);
        prefs.put("floating_bottom_bar_minimal_fab_radius", 30f);
        prefs.put("floating_bottom_bar_minimal_fab_opacity", 50f);
        prefs.put("floating_bottom_bar_minimal_fab_margin", 40f);

        BottomBarPreviewModel model = BottomBarPreviewModel.from(prefs);

        assertTrue(model.isFabMinimal());
        assertFalse(model.isFabHidden());
        assertEquals(20, model.fabOffsetDp);
        assertEquals(60, model.minimalFabSizeDp);
        assertEquals(30, model.minimalFabRadiusDp);
        assertEquals(50, model.minimalFabOpacity);
        assertEquals(40, model.minimalFabMarginDp);
    }

    @Test
    public void hiddenFabIsReported() {
        Map<String, Object> prefs = snapshot();
        prefs.put("floating_bottom_bar_fab_mode", "hidden");
        assertTrue(BottomBarPreviewModel.from(prefs).isFabHidden());
    }

    @Test
    public void fullyRoundedOverridesTheRadiusSlider() {
        Map<String, Object> prefs = snapshot();
        prefs.put("floating_bottom_bar_radius", 10f);
        prefs.put("floating_bottom_bar_fully_rounded", true);

        BottomBarPreviewModel model = BottomBarPreviewModel.from(prefs);

        assertTrue(model.isFullyRounded());
        assertEquals(BottomBarPreviewModel.FULLY_ROUNDED_RADIUS_DP, model.radiusDp);
    }

    @Test
    public void glassOpacityIsFoldedIntoTheFill() {
        Map<String, Object> prefs = snapshot();
        prefs.put("floating_bottom_bar_glass", true);
        prefs.put("floating_bottom_bar_glass_opacity", 50f);
        prefs.put("floating_bottom_bar_fill_color", 0xFF203040);

        int fill = BottomBarPreviewModel.from(prefs).resolvedFillColor(0xFF000000);

        assertEquals(128, (fill >>> 24));
        assertEquals(0x203040, fill & 0x00FFFFFF);
    }

    @Test
    public void glassOffKeepsTheFillOpaque() {
        Map<String, Object> prefs = snapshot();
        prefs.put("floating_bottom_bar_glass", false);
        prefs.put("floating_bottom_bar_glass_opacity", 20f);
        prefs.put("floating_bottom_bar_fill_color", 0xFF203040);

        assertEquals(0xFF203040, BottomBarPreviewModel.from(prefs).resolvedFillColor(0xFF000000));
    }

    @Test
    public void automaticFillFallsBackToTheThemeSurface() {
        Map<String, Object> prefs = snapshot();
        prefs.put("floating_bottom_bar_glass", false);
        prefs.put("floating_bottom_bar_fill_color", 0);

        assertEquals(0xFF123456, BottomBarPreviewModel.from(prefs).resolvedFillColor(0xFF123456));
    }

    @Test
    public void colourStoredAsStringIsAccepted() {
        Map<String, Object> prefs = snapshot();
        prefs.put("floating_bottom_bar_glass", false);
        prefs.put("floating_bottom_bar_fill_color", "#FF112233");

        assertEquals(0xFF112233, BottomBarPreviewModel.from(prefs).resolvedFillColor(0));
    }

    @Test
    public void outOfRangeValuesAreClampedByTheSchema() {
        Map<String, Object> prefs = snapshot();
        prefs.put("floating_bottom_bar_icon_size", 999f);
        prefs.put("floating_bottom_bar_text_size", -5f);

        BottomBarPreviewModel model = BottomBarPreviewModel.from(prefs);

        assertEquals(40, model.iconSizeDp);
        assertEquals(8, model.textSizeSp);
    }

    @Test
    public void presetsChangeThePreviewedGeometry() {
        Map<String, Object> compact = snapshot();
        compact.putAll(BottomBarPreferenceSchema.preset("compact"));
        Map<String, Object> accessibility = snapshot();
        accessibility.putAll(BottomBarPreferenceSchema.preset("accessibility"));

        BottomBarPreviewModel compactModel = BottomBarPreviewModel.from(compact);
        BottomBarPreviewModel accessibleModel = BottomBarPreviewModel.from(accessibility);

        assertNotEquals(compactModel.iconSizeDp, accessibleModel.iconSizeDp);
        assertTrue(accessibleModel.textSizeSp > compactModel.textSizeSp);
    }

    @Test
    public void parseColorRejectsGarbage() {
        assertEquals(null, BottomBarPreviewModel.parseColor("nope"));
        assertEquals(Integer.valueOf(0), BottomBarPreviewModel.parseColor("0"));
        assertEquals(Integer.valueOf(0xFF112233), BottomBarPreviewModel.parseColor("112233"));
    }
}
