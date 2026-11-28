package com.waenhancer.theme;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** A container whose middle row is transparent (a hollow centre) is still measured. */
public class BubbleSilhouetteProbeTest {
    /** Opaque frame of thickness t around a transparent centre band [holeTop, holeBottom). */
    private static BubbleSilhouette.Sampler hollow(int w, int h, int holeTop, int holeBottom) {
        return new BubbleSilhouette.Sampler() {
            int at(int x, int y) { return y >= holeTop && y < holeBottom ? 0 : 0xFF203040; }
            @Override public int[] row(int y) { int[] o = new int[w]; for (int x = 0; x < w; x++) o[x] = at(x, y); return o; }
            @Override public int[] column(int x) { int[] o = new int[h]; for (int y = 0; y < h; y++) o[y] = at(x, y); return o; }
        };
    }

    @Test
    public void hollowMiddleRowFallsBackToAnotherProbeRow() {
        // Transparent band across the middle (rows 40..79 of 120): the middle row 60 is empty.
        BubbleSilhouette s = BubbleSilhouette.measure(200, 120, hollow(200, 120, 40, 80), 20f);
        assertNotNull(s);
        assertEquals(0, s.left);
        assertEquals(200, s.right);
    }

    @Test
    public void fullyTransparentDrawableStillFallsBack() {
        BubbleSilhouette.Sampler empty = hollow(200, 120, 0, 120);
        assertNull(BubbleSilhouette.measure(200, 120, empty, 20f));
    }

    @Test
    public void probeRowsAreBoundedDistinctAndInside() {
        assertArrayEquals(new int[]{60, 40, 80, 30, 90}, BubbleSilhouette.probeRows(120));
        assertArrayEquals(new int[]{1, 0}, BubbleSilhouette.probeRows(2));
    }
}
