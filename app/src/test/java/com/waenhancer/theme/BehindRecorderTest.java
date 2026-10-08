package com.waenhancer.theme;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The ordering that keeps two live surfaces from recording each other. */
public class BehindRecorderTest {

    @Test
    public void lowerIndexAtSameZIsBehind() {
        assertTrue(BehindRecorder.drawnBefore(0f, 0, 0f, 2));
        assertFalse(BehindRecorder.drawnBefore(0f, 3, 0f, 2));
    }

    @Test
    public void zDecidesBeforeIndex() {
        // A floated header (Z=1) is drawn after the list even when it comes first in the group.
        assertTrue(BehindRecorder.drawnBefore(0f, 5, 1f, 0));
        assertFalse(BehindRecorder.drawnBefore(1f, 0, 0f, 5));
    }

    @Test
    public void theBranchItselfIsNeverBehind() {
        assertFalse(BehindRecorder.drawnBefore(0f, 2, 0f, 2));
    }

    /** Whatever one surface records, the other cannot record it back: the relation is antisymmetric. */
    @Test
    public void noTwoSiblingsAreBehindEachOther() {
        float[] zs = {0f, 0f, 1f, 1f, 2f};
        for (int a = 0; a < zs.length; a++) {
            for (int b = 0; b < zs.length; b++) {
                assertFalse(BehindRecorder.drawnBefore(zs[a], a, zs[b], b)
                        && BehindRecorder.drawnBefore(zs[b], b, zs[a], a));
            }
        }
    }

    @Test
    public void onlyOverlappingSiblingsAreRecorded() {
        assertTrue(BehindRecorder.intersects(0, 0, 100, 100, 50, 50, 150, 150));
        assertFalse(BehindRecorder.intersects(0, 0, 100, 100, 0, 100, 100, 200)); // rows touch, no overlap
        assertFalse(BehindRecorder.intersects(0, 0, 100, 100, 200, 0, 300, 100));
    }
}
