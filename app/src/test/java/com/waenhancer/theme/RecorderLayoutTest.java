package com.waenhancer.theme;

import org.junit.Test;
import static org.junit.Assert.*;

public class RecorderLayoutTest {
    private static final int[] BOUNDS = {0, 0, 1440, 444};
    private static final int[] WAVE = {300, 100, 1200, 244};

    private static int[] moved(int[] r, int[] offset) {
        return new int[]{r[0] + offset[0], r[1] + offset[1], r[2] + offset[0], r[3] + offset[1]};
    }

    @Test public void micMovesToTheStartOfTheWaveformAndRowCentre() {
        int[] mic = {1300, 20, 1330, 50};
        int[] placed = moved(mic, RecorderLayout.micOffset(mic, WAVE, new int[0][], BOUNDS, 4, 6, false));
        assertTrue(placed[2] + 4 <= WAVE[0]);
        assertTrue(Math.abs((placed[1] + placed[3]) / 2 - 172) <= 6);
    }

    @Test public void micPacksBeforeTimerAndClearsTrash() {
        int[] timer = {200, 150, 290, 190}, trash = {20, 140, 80, 200};
        int[] mic = {1230, 160, 1260, 190};
        int[] placed = moved(mic, RecorderLayout.micOffset(mic, WAVE, new int[][]{timer, trash}, BOUNDS, 4, 6, false));
        assertFalse(RecorderLayout.intersects(RecorderLayout.expand(placed, 3), timer));
        assertFalse(RecorderLayout.intersects(placed, trash));
        assertTrue(placed[2] + 4 <= WAVE[0]);
    }

    @Test public void rtlMirrorsToTheWaveformEnd() {
        int[] mic = {100, 160, 130, 190};
        int[] placed = moved(mic, RecorderLayout.micOffset(mic, WAVE, new int[0][], BOUNDS, 4, 6, true));
        assertTrue(placed[0] >= WAVE[2] + 4);
    }

    @Test public void nativeLayoutThatAlreadyFitsIsUnchanged() {
        int[] mic = {250, 157, 280, 187};
        assertArrayEquals(new int[]{0, 0}, RecorderLayout.micOffset(mic, WAVE, new int[0][], BOUNDS, 4, 6, false));
    }

    @Test public void noValidSlotLeavesNativeLayout() {
        int[] mic = {1300, 20, 1330, 50};
        assertNull(RecorderLayout.micOffset(mic, WAVE, new int[][]{{0, 0, 299, 444}}, BOUNDS, 4, 6, false));
        assertNull(RecorderLayout.micOffset(mic, new int[]{2, 100, 900, 244}, new int[0][], BOUNDS, 4, 6, false));
    }

    @Test public void quoteLaneMustEndAboveTheRecordingRow() {
        assertTrue(RecorderLayout.quoteAbove(new int[]{10, 10, 1200, 90}, WAVE, null, 4));
        assertFalse(RecorderLayout.quoteAbove(new int[]{10, 10, 1200, 98}, WAVE, null, 4));
        assertTrue(RecorderLayout.quoteAbove(null, WAVE, null, 4));
    }

    @Test public void envelopeIsUnionAndClampIsBounded() {
        assertArrayEquals(new int[]{0, -40, 1440, 444},
                RecorderLayout.union(new int[]{0, 0, 1440, 444}, new int[]{100, -40, 1300, 100}));
        assertNull(RecorderLayout.clamp(new int[]{0, 500, 10, 600}, BOUNDS));
    }
}
