package com.waenhancer.theme;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * What view discovery is allowed to call a floating round button.
 *
 * <p>Every wrong answer here is a real view sitting in the same tree, and a lens on the wrong one
 * is not a subtle visual difference. Discovery adds position to this test; what geometry alone has
 * to rule out is everything of roughly the right size and shape.</p>
 *
 * <p>Measurements are those of a 1440x3120 phone at 3.5x unless a case needs otherwise.</p>
 */
public class GlassTargetProbeTest {

    private static final float DENSITY = 3.5f;

    /** The scroll-to-bottom button: square, about 40dp. */
    @Test
    public void aSmallSquareButtonIsAccepted() {
        assertTrue(GlassTargetProbe.isRoundButton(140, 140, DENSITY));
    }

    /** A couple of pixels off square is rounding in a layout pass, not a different shape. */
    @Test
    public void aButtonAFewPixelsOffSquareIsStillAccepted() {
        assertTrue(GlassTargetProbe.isRoundButton(140, 132, DENSITY));
    }

    /** The input capsule is the same height and nine times the width. */
    @Test
    public void aCapsuleIsNotAButton() {
        assertFalse(GlassTargetProbe.isRoundButton(1180, 140, DENSITY));
    }

    /** An avatar inside a message row is square and too small to be chrome. */
    @Test
    public void aThumbnailIsTooSmall() {
        assertFalse(GlassTargetProbe.isRoundButton(70, 70, DENSITY));
    }

    /** A square container filling half the screen is not a button either. */
    @Test
    public void aLargeSquareIsRejected() {
        assertFalse(GlassTargetProbe.isRoundButton(700, 700, DENSITY));
    }

    /**
     * The bounds are in dp, so the same button is accepted on a low-density screen where its pixel
     * size is less than half what it was above.
     */
    @Test
    public void theBoundsFollowDensityRatherThanPixels() {
        assertTrue(GlassTargetProbe.isRoundButton(60, 60, 1.5f));
    }

    /**
     * A view that has not been laid out is not accepted; the caller retries on the next pass.
     * Answering yes here would put a lens on a view of unknown shape.
     */
    @Test
    public void anUnmeasuredViewIsNotAccepted() {
        assertFalse(GlassTargetProbe.isRoundButton(0, 0, DENSITY));
    }

    /** Nonsense inputs answer no rather than dividing by zero inside a host app's layout pass. */
    @Test
    public void degenerateInputsAreRefusedRatherThanThrowing() {
        assertFalse(GlassTargetProbe.isRoundButton(140, 140, 0f));
        assertFalse(GlassTargetProbe.isRoundButton(-140, -140, DENSITY));
    }
}
