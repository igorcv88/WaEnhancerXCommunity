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

    /** A page swipe places the bubble directly, flat: no rise, no lag behind the page. */
    @Test
    public void followTracksThePagerWithoutRising() {
        BubbleMotion m = new BubbleMotion(3, 0, PRESSED);
        m.follow(0.4f);
        assertEquals(0.4f, m.value(), 0f);
        assertEquals(0f, m.pressProgress(), 0f);
        assertTrue(m.isIdle());
        m.follow(1.7f);
        assertEquals(1.7f, m.value(), 0f);
        m.follow(9f);
        assertEquals(2f, m.value(), 0f);
    }

    /** A swipe lifts the bubble into the lens without a finger, and release deflates it. */
    @Test
    public void liftRisesWithoutAFingerAndReleaseDeflates() {
        BubbleMotion m = new BubbleMotion(3, 0, PRESSED);
        m.lift();
        assertFalse(m.isPressed());
        for (int i = 0; i < 30; i++) { m.follow(i / 30f); m.step(FRAME); }
        assertTrue(m.pressProgress() > 0.9f);
        m.follow(1f);
        m.release();
        run(m, 1f);
        assertEquals(1f, m.value(), 0f);
        assertEquals(0f, m.pressProgress(), 0.01f);
        assertTrue(m.isIdle());
    }

    /** The agreed tap timing: rise ~120 ms, travel to ~300 ms, land by ~450 ms, real time. */
    @Test
    public void tapGlideTakesFourHundredFiftyMilliseconds() {
        BubbleMotion m = new BubbleMotion(3, 0, PRESSED);
        m.animateTo(1, true);
        assertTrue(m.isGliding());
        float t = 0f;
        float firstMove = -1f, arrived = -1f, landed = -1f, risen = -1f;
        while (t < 1f) {
            m.step(FRAME);
            t += FRAME;
            if (risen < 0f && m.pressProgress() > 0.99f) risen = t;
            if (firstMove < 0f && m.value() > 0.001f) firstMove = t;
            if (arrived < 0f && m.value() > 0.999f) arrived = t;
            if (landed < 0f && m.pressProgress() < 0.001f && arrived > 0f) landed = t;
        }
        assertEquals(0.12f, risen, 0.02f);
        assertTrue("travel waits for the rise: " + firstMove, firstMove >= 0.12f);
        assertEquals(0.30f, arrived, 0.02f);
        assertEquals(0.45f, landed, 0.02f);
        assertFalse(m.isGliding());
        assertTrue(m.isIdle() || m.scaleX() < 1.05f);
    }

    /** A new tap mid-glide redirects from where the bubble is: no jump in height or position. */
    @Test
    public void redirectedGlideIsContinuous() {
        BubbleMotion m = new BubbleMotion(4, 0, PRESSED);
        m.animateTo(3, true);
        run(m, 0.2f);
        float value = m.value(), press = m.pressProgress();
        m.animateTo(1, true);
        assertEquals(value, m.value(), 1e-4f);
        assertEquals(press, m.pressProgress(), 0.01f);
        run(m, 1f);
        assertEquals(1f, m.value(), 0f);
        assertEquals(0f, m.pressProgress(), 0f);
    }

    /** A page swipe or a finger takes over from a glide. */
    @Test
    public void swipeAndPressInterruptAGlide() {
        BubbleMotion m = new BubbleMotion(3, 0, PRESSED);
        m.animateTo(2, true);
        run(m, 0.15f);
        m.follow(0.5f);
        assertFalse(m.isGliding());
        assertEquals(0.5f, m.value(), 0f);
        m.animateTo(2, true);
        run(m, 0.05f);
        m.press();
        assertFalse(m.isGliding());
        assertTrue(m.isPressed());
    }

    /** The drawn scale reaches exactly 1 as the bubble hands over to the native pill. */
    @Test
    public void settleScaleIsOneAtRest() {
        assertEquals(1f, LiquidBubble.settleScale(1.3f, 0f), 0f);
        assertEquals(1.3f, LiquidBubble.settleScale(1.3f, 0.3f), 1e-6f);
        assertEquals(1.3f, LiquidBubble.settleScale(1.3f, 1f), 1e-6f);
        float mid = LiquidBubble.settleScale(1.3f, 0.15f);
        assertTrue(mid > 1f && mid < 1.3f);
    }

    /** Long frames do not stretch the tap: it still ends 450 ms after it starts. */
    @Test
    public void glideRunsOnWallTime() {
        BubbleMotion m = new BubbleMotion(3, 0, PRESSED);
        m.animateTo(1, true);
        for (int i = 0; i < 7; i++) m.step(0.065f);
        assertFalse(m.isGliding());
        assertEquals(1f, m.value(), 0f);
        assertEquals(0f, m.pressProgress(), 0f);
    }
}
