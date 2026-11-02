package com.waenhancer.theme;

import static org.junit.Assert.*;
import org.junit.Test;

/** Text bubbles keep a producer epoch, not individual view captures. */
public class BubbleCaptureRetentionTest {
    @Test public void oneHundredRowMovesDoNotInvalidateSource() {
        BackdropEpoch source=new BackdropEpoch();
        assertTrue(source.update("wallpaper/crop/theme-A"));
        long epoch=source.revision(); assertTrue(source.complete(epoch,true));
        for(int rowOffset=0;rowOffset<100;rowOffset++) {
            assertFalse(source.update("wallpaper/crop/theme-A"));
            assertEquals(epoch,source.revision()); assertTrue(source.ready());
        }
    }
    @Test public void sourceChangeRejectsLateWorkerAndKeepsNativeUntilReady() {
        BackdropEpoch source=new BackdropEpoch(); source.update("A"); long old=source.revision();
        source.update("B"); assertFalse(source.ready()); assertFalse(source.complete(old,true));
        assertTrue(source.complete(source.revision(),true)); assertTrue(source.ready());
        source.release(); assertFalse(source.ready()); assertFalse(source.complete(source.revision(),true));
    }
    @Test public void noStretchedOrUncoveredWallpaper() {
        assertTrue(BackdropGeometry.covers(new float[]{1,0,0,0,1,0,0,0,1},1440,3120,1440,3120));
        assertFalse(BackdropGeometry.covers(new float[]{1,0,0,0,1,128,0,0,1},1440,2992,1440,3120));
        assertFalse(BackdropGeometry.covers(new float[]{1,0,0,0,2,0,0,0,1},1440,3120,1440,3120));
        assertTrue(BackdropGeometry.covers(new float[]{-1,0,1440,0,1,0,0,0,1},1440,3120,1440,3120));
        assertTrue(BackdropGeometry.covers(new float[]{0,-1,3120,1,0,0,0,0,1},1440,3120,3120,1440));
    }
}
