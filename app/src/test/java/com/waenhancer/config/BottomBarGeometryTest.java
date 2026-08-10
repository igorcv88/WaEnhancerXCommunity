package com.waenhancer.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Covers the height arithmetic the preview and the hooked bar now share. */
public class BottomBarGeometryTest {

    private static final float DENSITY = 3f;

    private static BottomBarGeometry automatic(int iconDp, int spacingDp, float textSp,
                                               int paddingDp) {
        return BottomBarGeometry.resolve(false, 0, iconDp, spacingDp, textSp, paddingDp,
                DENSITY, 1f);
    }

    private static BottomBarGeometry manual(int heightDp, int iconDp, int spacingDp, float textSp,
                                            int paddingDp) {
        return BottomBarGeometry.resolve(true, heightDp, iconDp, spacingDp, textSp, paddingDp,
                DENSITY, 1f);
    }

    @Test
    public void automaticHeightIsTheContentPlusItsPadding() {
        BottomBarGeometry geometry = automatic(24, 5, 12f, 3);

        assertEquals(geometry.naturalContentHeightPx() + 2 * geometry.verticalPaddingPx,
                geometry.pillHeightPx);
        assertFalse(geometry.compressed);
        assertTrue(geometry.labelVisible);
    }

    @Test
    public void automaticHeightNeverReturnsAWrapContentSentinel() {
        // The bug this class exists for: WhatsApp's tab frame answers a wrap_content measure
        // with the entire available height, so the bar must always be given an exact figure.
        BottomBarGeometry geometry = automatic(24, 5, 12f, 3);

        assertTrue(geometry.pillHeightPx > 0);
    }

    @Test
    public void automaticHeightGrowsWithEveryMetricItDependsOn() {
        int base = automatic(24, 5, 12f, 3).pillHeightPx;

        assertTrue(automatic(32, 5, 12f, 3).pillHeightPx > base);
        assertTrue(automatic(24, 12, 12f, 3).pillHeightPx > base);
        assertTrue(automatic(24, 5, 18f, 3).pillHeightPx > base);
        assertTrue(automatic(24, 5, 12f, 10).pillHeightPx > base);
    }

    @Test
    public void manualHeightIsHonouredExactly() {
        assertEquals(Math.round(64 * DENSITY), manual(64, 24, 5, 12f, 3).pillHeightPx);
    }

    @Test
    public void tallManualHeightLeavesEveryMetricAlone() {
        BottomBarGeometry geometry = manual(96, 24, 5, 12f, 3);

        assertFalse(geometry.compressed);
        assertEquals(Math.round(24 * DENSITY), geometry.iconSizePx);
        assertEquals(Math.round(5 * DENSITY), geometry.spacingPx);
        assertTrue(geometry.contentTopOffsetPx() > 0);
    }

    @Test
    public void theReportedFiftyOneDpBarHasRoomForItsContent() {
        // 24dp icon + 5dp gap + a 12sp label + 3dp padding either side needs a shade under 51dp,
        // so the label drawn on top of the icon in the report was never a shortage of space: the
        // item was placing its own icon and label. Nothing here needs to give way.
        BottomBarGeometry geometry = manual(51, 24, 5, 12f, 3);

        assertFalse(geometry.compressed);
        assertTrue(geometry.naturalContentHeightPx() <= geometry.contentHeightPx());
    }

    @Test
    public void theGapIsGivenUpBeforeTheIcon() {
        BottomBarGeometry geometry = manual(45, 24, 5, 12f, 3);

        assertTrue(geometry.compressed);
        assertTrue(geometry.spacingPx < Math.round(5 * DENSITY));
        assertEquals(Math.round(24 * DENSITY), geometry.iconSizePx);
    }

    @Test
    public void aVeryShortBarKeepsAReadableIconAndDropsTheLabel() {
        BottomBarGeometry geometry = manual(20, 24, 5, 12f, 3);

        assertFalse(geometry.labelVisible);
        assertEquals(0, geometry.labelHeightPx);
        assertTrue(geometry.iconSizePx >= Math.round(BottomBarGeometry.MIN_ICON_DP * DENSITY));
    }

    @Test
    public void contentAlwaysFitsWhateverHeightIsAsked() {
        for (int heightDp = 8; heightDp <= 120; heightDp++) {
            BottomBarGeometry geometry = manual(heightDp, 28, 8, 14f, 6);
            assertTrue("overflowed at " + heightDp + "dp",
                    geometry.naturalContentHeightPx() <= geometry.contentHeightPx());
        }
    }

    @Test
    public void aLargeFontScaleIsAccountedForRatherThanClipped() {
        int normal = automatic(24, 5, 12f, 3).pillHeightPx;
        int large = BottomBarGeometry.resolve(false, 0, 24, 5, 12f, 3, DENSITY, 1.5f)
                .pillHeightPx;

        assertTrue(large > normal);
    }
}
