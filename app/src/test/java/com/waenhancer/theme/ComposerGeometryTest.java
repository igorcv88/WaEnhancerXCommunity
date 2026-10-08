package com.waenhancer.theme;

import org.junit.Test;
import static org.junit.Assert.*;

public class ComposerGeometryTest {
    @Test public void ltrCapsuleEndsBeforeActionDisc() {
        assertArrayEquals(new int[]{10, 196}, ComposerGeometry.separate(10, 230, 0, 60, 200, 260, 0, 60, 4));
    }
    @Test public void rtlCapsuleStartsAfterActionDisc() {
        assertArrayEquals(new int[]{74, 260}, ComposerGeometry.separate(40, 260, 0, 60, 10, 70, 0, 60, 4));
    }
    @Test public void alreadySeparateDiscStillGetsMinimumGap() {
        assertArrayEquals(new int[]{10, 196}, ComposerGeometry.separate(10, 199, 0, 60, 200, 260, 0, 60, 4));
    }
    @Test public void actionInAnotherRowDoesNotTrimComposer() {
        assertArrayEquals(new int[]{10, 230}, ComposerGeometry.separate(10, 230, 0, 60, 200, 260, 70, 130, 4));
    }
    @Test public void tooSmallLayoutNeverProducesNegativeWidth() {
        int[] edges = ComposerGeometry.separate(10, 30, 0, 60, 15, 65, 0, 60, 10);
        assertTrue(edges[1] >= edges[0]);
    }
}
