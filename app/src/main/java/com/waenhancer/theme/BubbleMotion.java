package com.waenhancer.theme;

/**
 * Motion of the bottom bar's liquid bubble, free of any Android type so it can be tested.
 *
 * <p>A port of {@code DampedDragAnimation} from Kyant0/AndroidLiquidGlass as mirrored by the
 * compose-miuix-ui example (Apache-2.0): the same springs, the same press/release choreography,
 * and the same velocity squash, stepped by hand instead of by Compose's {@code Animatable}.</p>
 *
 * <ul>
 *   <li>{@code value} is the bubble's position as a fractional tab index;</li>
 *   <li>{@code press} rises to 1 while a finger is down and falls back once the bubble has
 *       arrived — this is the bubble "rising" out of the bar;</li>
 *   <li>{@code scaleX}/{@code scaleY} grow to {@link #pressedScale} on press with slightly
 *       different damping, so the bubble wobbles as it inflates;</li>
 *   <li>{@code offset} is the drag's rubber band, applied to the whole bar.</li>
 * </ul>
 */
public final class BubbleMotion {

    /** A Compose {@code spring(dampingRatio, stiffness)}, integrated with small fixed steps. */
    static final class Spring {
        final float damping, stiffness, threshold;
        float value, velocity, target;

        Spring(float damping, float stiffness, float threshold, float initial) {
            this.damping = damping;
            this.stiffness = stiffness;
            this.threshold = threshold;
            value = target = initial;
        }

        void step(float seconds) {
            float remaining = seconds;
            float c = 2f * damping * (float) Math.sqrt(stiffness);
            while (remaining > 0f) {
                float h = Math.min(remaining, MAX_SUBSTEP);
                float acceleration = -stiffness * (value - target) - c * velocity;
                velocity += acceleration * h;
                value += velocity * h;
                remaining -= h;
            }
            if (atRest()) {
                value = target;
                velocity = 0f;
            }
        }

        boolean atRest() {
            return Math.abs(value - target) < threshold && Math.abs(velocity) < threshold * 10f;
        }

        void snap(float to) {
            value = target = to;
            velocity = 0f;
        }
    }

    /** Integration step ceiling: well inside the stability limit of the stiffest spring. */
    static final float MAX_SUBSTEP = 1f / 480f;
    /** Longest frame accepted, so a stalled process cannot fling the springs. */
    static final float MAX_FRAME = 1f / 20f;

    /** Rubber band reach of the bar while dragging, before density (OneMate: 4dp). */
    public static final float RUBBER_BAND_DP = 4f;

    final Spring value;
    final Spring press = new Spring(1f, 1000f, 0.001f, 0f);
    final Spring scaleX = new Spring(0.6f, 250f, 0.001f, 1f);
    final Spring scaleY = new Spring(0.7f, 250f, 0.001f, 1f);
    final Spring offset = new Spring(0.5f, 300f, 0.5f, 0f);

    private final float maxValue;
    private final float pressedScale;
    /** True while a release is waiting for the bubble to arrive before it deflates. */
    private boolean releasePending;
    private boolean pressed;

    /**
     * @param tabs         number of tabs, at least 1
     * @param initial      initially selected tab
     * @param pressedScale bubble scale while pressed (OneMate: 78/56)
     */
    public BubbleMotion(int tabs, int initial, float pressedScale) {
        maxValue = Math.max(0, tabs - 1);
        this.pressedScale = pressedScale;
        value = new Spring(1f, 1000f, 0.001f, clamp(initial, 0f, maxValue));
    }

    public float value() { return value.value; }
    public float targetValue() { return value.target; }
    public float pressProgress() { return clamp(press.value, 0f, 1f); }
    public float scaleX() { return scaleX.value; }
    public float scaleY() { return scaleY.value; }
    public float offset() { return offset.value; }
    public boolean isPressed() { return pressed; }
    public int tabs() { return Math.round(maxValue) + 1; }

    /** Velocity in spans of the whole range per second, as Kyant's tracker reports it. */
    public float velocity() {
        return maxValue <= 0f ? 0f : value.velocity / maxValue;
    }

    /** Horizontal and vertical scale including the velocity squash (Kyant's layerBlock). */
    public float drawScaleX() {
        float v = velocity() / 10f;
        return scaleX.value / (1f - clamp(v * 0.75f, -0.2f, 0.2f));
    }

    public float drawScaleY() {
        float v = velocity() / 10f;
        return scaleY.value * (1f - clamp(v * 0.25f, -0.2f, 0.2f));
    }

    /** Finger down on the bubble. */
    public void press() {
        pressed = true;
        releasePending = false;
        press.target = 1f;
        scaleX.target = pressedScale;
        scaleY.target = pressedScale;
    }

    /** Finger up: deflate once the bubble is within 2.5% of the range of where it is going. */
    public void release() {
        pressed = false;
        releasePending = true;
        offset.target = 0f;
        maybeDeflate();
    }

    /** Drag by {@code tabs} tab widths (signed, already corrected for layout direction). */
    public void dragBy(float tabs, float rawPx) {
        value.target = clamp(value.target + tabs, 0f, maxValue);
        offset.value += rawPx;
        offset.target = offset.value;
    }

    /** Where a drag ends up: the nearest tab to the target. */
    public int settleIndex() {
        return Math.round(clamp(value.target, 0f, maxValue));
    }

    /** Programmatic selection (a tap on another tab): rise, travel, land, deflate. */
    public void animateTo(int index, boolean animate) {
        float to = clamp(index, 0f, maxValue);
        if (!animate) {
            value.snap(to);
            return;
        }
        // Already heading there (a drag that just ended, or a repeated selection callback).
        if (Math.abs(value.target - to) < 0.001f) return;
        boolean wasPressed = pressed;
        press();
        value.target = to;
        if (!wasPressed) release();
        else pressed = true;
    }

    /** Places the bubble without animation (layout changes, first frame). */
    public void snapTo(int index) {
        value.snap(clamp(index, 0f, maxValue));
    }

    /** Advances every spring. Returns true while anything is still moving. */
    public boolean step(float seconds) {
        float dt = clamp(seconds, 0f, MAX_FRAME);
        value.step(dt);
        press.step(dt);
        scaleX.step(dt);
        scaleY.step(dt);
        offset.step(dt);
        maybeDeflate();
        return !isIdle();
    }

    public boolean isIdle() {
        return !releasePending && value.atRest() && press.atRest() && scaleX.atRest()
                && scaleY.atRest() && offset.atRest();
    }

    private void maybeDeflate() {
        if (!releasePending) return;
        float threshold = Math.max(maxValue, 1f) * 0.025f;
        if (Math.abs(value.value - value.target) >= threshold) return;
        releasePending = false;
        press.target = 0f;
        scaleX.target = 1f;
        scaleY.target = 1f;
    }

    /**
     * The bar's rubber-band translation for the current drag offset (Kyant: 4dp × EaseOut of the
     * fraction of the bar's width dragged, signed).
     */
    public static float rubberBand(float offsetPx, float totalWidthPx, float reachPx) {
        if (totalWidthPx <= 0f) return 0f;
        float fraction = clamp(offsetPx / totalWidthPx, -1f, 1f);
        float a = Math.abs(fraction);
        // EaseOut = cubic-bezier(0, 0, 0.58, 1); 1 - (1 - a)² is within 0.03 of it on [0, 1].
        float eased = 1f - (1f - a) * (1f - a);
        return reachPx * Math.signum(fraction) * eased;
    }

    /**
     * Position along the tabs for a fractional index: linear between neighbouring tab centres,
     * so tabs of different widths still map exactly onto their own centres.
     */
    public static float interpolate(float[] values, float index) {
        if (values == null || values.length == 0) return 0f;
        if (values.length == 1) return values[0];
        float i = clamp(index, 0f, values.length - 1);
        int lo = (int) Math.floor(i);
        int hi = Math.min(values.length - 1, lo + 1);
        float t = i - lo;
        return values[lo] + (values[hi] - values[lo]) * t;
    }

    static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }
}
