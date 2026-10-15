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

    /*
     * A tap's choreography, in seconds of real time: rise on the old tab, travel, land on the new
     * one. 450 ms in all, agreed with the user against the page swipe they liked (about 480 ms at
     * their 0.33x animator scale); iOS's and Material's default springs settle in 300-500 ms.
     * Fixed rather than scaled by the system animator setting: the springs it replaces landed in
     * about 200 ms, which read as an in-place pop at 0.33x.
     */
    static final float GLIDE_RISE = 0.12f;
    static final float GLIDE_TRAVEL_END = 0.30f;
    static final float GLIDE_END = 0.45f;
    private boolean gliding;
    private float glideTime, glideFrom, glideTo;

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

    public boolean isGliding() { return gliding; }

    /** Finger down on the bubble. */
    public void press() {
        stopGlide();
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
            stopGlide();
            value.snap(to);
            return;
        }
        // Already heading there (a drag that just ended, or a repeated selection callback).
        if (Math.abs(value.target - to) < 0.001f) return;
        if (pressed) {
            // A finger holds the bubble: let the value spring carry it, as before.
            value.target = to;
            return;
        }
        glideTo(to);
    }

    /**
     * Starts (or redirects) the tap choreography toward {@code to}. A glide that interrupts a
     * rise or another glide continues from the bubble's current height and position.
     */
    private void glideTo(float to) {
        float p = clamp(press.value, 0f, 1f);
        // The point of the rise that matches the current height, so the press never jumps.
        float riseAt = GLIDE_RISE * (1f - (float) Math.cbrt(1f - p));
        glideTime = gliding ? Math.min(glideTime, riseAt) : riseAt;
        gliding = true;
        glideFrom = value.value;
        glideTo = to;
        value.target = to;
        releasePending = false;
        scaleX.target = pressedScale;
        scaleY.target = pressedScale;
        applyGlide();
    }

    private void applyGlide() {
        float t = glideTime;
        float p;
        if (t < GLIDE_RISE) {
            float x = 1f - t / GLIDE_RISE;
            p = 1f - x * x * x;                       // ease-out: lifts at once, then slows
        } else if (t < GLIDE_TRAVEL_END) {
            p = 1f;
        } else {
            p = 1f - easeInOut((t - GLIDE_TRAVEL_END) / (GLIDE_END - GLIDE_TRAVEL_END));
            scaleX.target = 1f;
            scaleY.target = 1f;
        }
        press.value = press.target = clamp(p, 0f, 1f);
        press.velocity = 0f;
        float span = GLIDE_TRAVEL_END - GLIDE_RISE;
        float x = clamp((t - GLIDE_RISE) / span, 0f, 1f);
        value.value = glideFrom + (glideTo - glideFrom) * easeInOut(x);
        // Analytic velocity, so the travel still squashes the bubble along its motion.
        value.velocity = (x > 0f && x < 1f) ? (glideTo - glideFrom) * easeInOutSlope(x) / span : 0f;
    }

    private void stopGlide() {
        if (!gliding) return;
        gliding = false;
        value.velocity = 0f;
    }

    /** Cubic ease-in-out on [0, 1]. */
    static float easeInOut(float x) {
        x = clamp(x, 0f, 1f);
        return x < 0.5f ? 4f * x * x * x : 1f - (float) Math.pow(-2f * x + 2f, 3) / 2f;
    }

    static float easeInOutSlope(float x) {
        x = clamp(x, 0f, 1f);
        float y = x < 0.5f ? x : 1f - x;
        return 12f * y * y;
    }

    /**
     * Rises as on a press, without a finger: {@link #isPressed()} stays false. Used while a page
     * swipe moves the bubble; {@link #release()} deflates it.
     */
    public void lift() {
        stopGlide();
        releasePending = false;
        press.target = 1f;
        scaleX.target = pressedScale;
        scaleY.target = pressedScale;
    }

    /** Places the bubble at a fractional index without animation (a page swipe in progress). */
    public void follow(float index) {
        stopGlide();
        value.snap(clamp(index, 0f, maxValue));
    }

    /** Places the bubble without animation (layout changes, first frame). */
    public void snapTo(int index) {
        stopGlide();
        value.snap(clamp(index, 0f, maxValue));
    }

    /** Advances every spring. Returns true while anything is still moving. */
    public boolean step(float seconds) {
        float dt = clamp(seconds, 0f, MAX_FRAME);
        if (gliding) {
            // The timeline runs on wall time: a long frame must not stretch the fixed 450 ms.
            // Only the springs need the MAX_FRAME cap, for integration stability.
            glideTime += Math.max(0f, seconds);
            applyGlide();
            if (glideTime >= GLIDE_END) {
                gliding = false;
                value.snap(glideTo);
                press.snap(0f);
            }
        } else {
            value.step(dt);
            press.step(dt);
        }
        scaleX.step(dt);
        scaleY.step(dt);
        offset.step(dt);
        maybeDeflate();
        return !isIdle();
    }

    public boolean isIdle() {
        return !gliding && !releasePending && value.atRest() && press.atRest() && scaleX.atRest()
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
