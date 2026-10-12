package com.waenhancer.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The bubble's choreography, stepped at 60 fps: rise, follow, land, deflate. */
public class BubbleMotionTest {

    private static final float FRAME = 1f / 60f;
    private static final float PRESSED = LiquidBubble.PRESSED_SCALE;

    private static void run(BubbleMotion m, float seconds) {
        for (float t = 0f; t < seconds; t += FRAME) m.step(FRAME);
    }

    @Test
    public void startsAtRestOnTheSelectedTab() {
        BubbleMotion m = new BubbleMotion(4, 2, PRESSED);
        assertEquals(2f, m.value(), 0f);
        assertEquals(0f, m.pressProgress(), 0f);
        assertEquals(1f, m.scaleX(), 0f);
        assertTrue(m.isIdle());
        assertFalse(m.step(FRAME));
    }

    @Test
    public void pressRisesAndReleaseDeflates() {
        BubbleMotion m = new BubbleMotion(4, 0, PRESSED);
        m.press();
        run(m, 0.6f);
        assertEquals(1f, m.pressProgress(), 0.01f);
        assertEquals(PRESSED, m.scaleX(), 0.01f);
        assertEquals(PRESSED, m.scaleY(), 0.01f);
        m.release();
        run(m, 1f);
        assertEquals(0f, m.pressProgress(), 0.01f);
        assertEquals(1f, m.scaleX(), 0.01f);
        assertTrue(m.isIdle());
    }

    /** The scale springs are under-damped: the bubble overshoots as it inflates (the wobble). */
    @Test
    public void inflationOvershoots() {
        BubbleMotion m = new BubbleMotion(3, 0, PRESSED);
        m.press();
        float peak = 0f;
        for (int i = 0; i < 60; i++) {
            m.step(FRAME);
            peak = Math.max(peak, m.scaleX());
        }
        assertTrue("peak " + peak, peak > PRESSED + 0.01f);
    }

    /** A tap on another tab: rise, travel, then deflate only once it has arrived. */
    @Test
    public void animateToTravelsRaisedAndLands() {
        BubbleMotion m = new BubbleMotion(4, 0, PRESSED);
        m.animateTo(3, true);
        m.step(FRAME * 3);
        assertTrue(m.pressProgress() > 0f);
        float maxPress = 0f;
        for (int i = 0; i < 120; i++) {
            m.step(FRAME);
            maxPress = Math.max(maxPress, m.pressProgress());
        }
        assertTrue(maxPress > 0.5f);
        assertEquals(3f, m.value(), 0.001f);
        assertEquals(0f, m.pressProgress(), 0.01f);
        assertTrue(m.isIdle());
    }

    @Test
    public void animateWithoutMotionSnaps() {
        BubbleMotion m = new BubbleMotion(4, 0, PRESSED);
        m.animateTo(2, false);
        assertEquals(2f, m.value(), 0f);
        assertTrue(m.isIdle());
    }

    @Test
    public void dragFollowsClampsAndSettlesOnTheNearestTab() {
        BubbleMotion m = new BubbleMotion(4, 1, PRESSED);
        m.press();
        m.dragBy(0.4f, 40f);
        assertEquals(1, m.settleIndex());
        m.dragBy(0.3f, 30f);
        assertEquals(2, m.settleIndex());
        m.dragBy(10f, 1000f);
        assertEquals(3f, m.targetValue(), 0f);
        m.dragBy(-20f, -2000f);
        assertEquals(0f, m.targetValue(), 0f);
        assertEquals(0, m.settleIndex());
        // A drag that ended is not undone by the selection callback naming the same tab.
        m.release();
        m.animateTo(0, true);
        run(m, 1.5f);
        assertEquals(0f, m.value(), 0.001f);
        assertEquals(0f, m.offset(), 0.5f);
        assertTrue(m.isIdle());
    }

    /** Moving fast stretches the bubble along the motion and flattens it across. */
    @Test
    public void velocitySquashesTheBubble() {
        BubbleMotion m = new BubbleMotion(5, 0, PRESSED);
        m.animateTo(4, true);
        float widest = 0f, flattest = Float.MAX_VALUE;
        for (int i = 0; i < 30; i++) {
            m.step(FRAME);
            widest = Math.max(widest, m.drawScaleX() / m.scaleX());
            flattest = Math.min(flattest, m.drawScaleY() / m.scaleY());
        }
        assertTrue(widest > 1.01f);
        assertTrue(flattest < 0.995f);
        // Bounded by Kyant's ±0.2 clamps.
        assertTrue(widest <= 1f / 0.8f + 1e-4f);
        assertTrue(flattest >= 0.8f - 1e-4f);
    }

    @Test
    public void rubberBandIsBoundedAndSigned() {
        assertEquals(0f, BubbleMotion.rubberBand(0f, 1000f, 12f), 0f);
        assertEquals(12f, BubbleMotion.rubberBand(5000f, 1000f, 12f), 1e-5f);
        assertEquals(-12f, BubbleMotion.rubberBand(-5000f, 1000f, 12f), 1e-5f);
        float half = BubbleMotion.rubberBand(500f, 1000f, 12f);
        assertTrue(half > 6f && half < 12f);
        assertEquals(0f, BubbleMotion.rubberBand(100f, 0f, 12f), 0f);
    }

    @Test
    public void interpolateMapsFractionalIndicesBetweenCentres() {
        float[] centres = {50f, 150f, 300f};
        assertEquals(50f, BubbleMotion.interpolate(centres, 0f), 0f);
        assertEquals(100f, BubbleMotion.interpolate(centres, 0.5f), 1e-5f);
        assertEquals(225f, BubbleMotion.interpolate(centres, 1.5f), 1e-5f);
        assertEquals(300f, BubbleMotion.interpolate(centres, 7f), 0f);
        assertEquals(50f, BubbleMotion.interpolate(centres, -1f), 0f);
        assertEquals(42f, BubbleMotion.interpolate(new float[]{42f}, 0.7f), 0f);
        assertEquals(0f, BubbleMotion.interpolate(new float[0], 1f), 0f);
    }

    /** A stalled frame cannot fling the springs past their target. */
    @Test
    public void longFramesAreClamped() {
        BubbleMotion m = new BubbleMotion(4, 0, PRESSED);
        m.animateTo(3, true);
        m.step(5f);
        assertTrue(m.value() <= 3.5f);
        assertTrue(m.scaleX() < 2f);
    }
}
