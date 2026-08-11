package com.waenhancer.theme;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * The moving body of a liquid-glass bar.
 *
 * <p>A pane of glass that never reacts to being touched reads as a picture of glass. This is the
 * part that moves: a blob sitting under the selected tab that springs across when the selection
 * changes, stretching along the direction of travel while it is fast and settling back to a round
 * pill as it arrives — the way a drop of liquid deforms when it is flung and re-forms when it
 * stops.</p>
 *
 * <h3>Why the spring is written out by hand</h3>
 *
 * <p>The obvious dependency, {@code androidx.dynamicanimation}, is not on this project's
 * classpath, and adding a library to code that runs inside another app's process costs more than
 * the twelve lines of integration below. It is a critically damped spring stepped once per frame,
 * which is all the motion here needs.</p>
 */
@SuppressLint("ViewConstructor")
public final class LiquidMorph extends View {

    public interface StateListener {
        void onMorphState(float centerX, float width, float height, float cornerRadius);
    }

    /** Spring stiffness. Higher arrives sooner and overshoots less. */
    private static final float STIFFNESS = 420f;
    /** Damping ratio. Just under 1 leaves a trace of overshoot, which reads as liquid. */
    private static final float DAMPING = 0.86f;
    /** Below this speed and distance the spring is done and the frame loop stops. */
    private static final float REST_VELOCITY = 2f;
    private static final float REST_DISTANCE = 0.5f;
    /** Longest frame the integrator will accept, so a stalled process cannot explode the spring. */
    private static final float MAX_FRAME_SECONDS = 1f / 30f;

    /** How far the blob stretches at speed, as a fraction of its width per 1000px/s. */
    private static final float STRETCH_PER_SPEED = 0.18f;
    /** Ceiling on that stretch. */
    private static final float MAX_STRETCH = 0.45f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF blob = new RectF();

    private float centerX;
    private float centerXVelocity;
    private float targetCenterX;

    private float width;
    private float widthVelocity;
    private float targetWidth;

    private float cornerRadius;
    private boolean hasTarget;
    private long lastFrameNanos;
    private boolean renderBlob = true;
    private StateListener stateListener;

    public LiquidMorph(Context context) {
        super(context);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
    }

    /**
     * How opaque the blob is painted.
     *
     * <p>Deliberately faint. This is flat paint laid over a surface that is otherwise built from
     * refraction, so it has no glass character of its own and every bit of opacity it carries
     * reads as a pale sticker on top of the pane rather than as part of it. At the 0.30 it
     * started at, over a dark bar, it was the most prominent thing on the whole surface.</p>
     */
    private static final float BLOB_ALPHA = 0.10f;

    /** Paints the blob in the colour this surface's spec calls for. */
    public void applySpec(GlassSpec spec) {
        if (spec == null) return;
        int tint = (spec.highlightColor >>> 24) != 0 ? spec.highlightColor : spec.contentColor;
        paint.setColor(SemanticTheme.withAlpha(tint, BLOB_ALPHA));
    }

    /** Uses the spring as a state driver while letting the lens render the selected shape. */
    public void setStateListener(StateListener listener, boolean renderBlob) {
        stateListener = listener;
        this.renderBlob = renderBlob;
        publishState();
    }

    /**
     * Sends the blob to a tab.
     *
     * <p>The first call places it outright — a blob that springs in from the left edge on the
     * first frame after launch would look like a glitch rather than a response.</p>
     *
     * @param bounds       the selected tab's bounds in this view's coordinates
     * @param cornerRadius radius of the blob
     */
    public void moveTo(RectF bounds, float cornerRadius) {
        this.cornerRadius = cornerRadius;
        targetCenterX = bounds.centerX();
        targetWidth = bounds.width();

        if (!hasTarget) {
            hasTarget = true;
            centerX = targetCenterX;
            width = targetWidth;
            invalidate();
            publishState();
            return;
        }
        lastFrameNanos = 0L;
        postOnAnimation(this::step);
    }

    private void step() {
        long now = System.nanoTime();
        float seconds = lastFrameNanos == 0L
                ? 1f / 60f
                : Math.min(MAX_FRAME_SECONDS, (now - lastFrameNanos) / 1_000_000_000f);
        lastFrameNanos = now;

        centerXVelocity = spring(centerX, targetCenterX, centerXVelocity, seconds);
        centerX += centerXVelocity * seconds;
        widthVelocity = spring(width, targetWidth, widthVelocity, seconds);
        width += widthVelocity * seconds;

        boolean settled = Math.abs(centerXVelocity) < REST_VELOCITY
                && Math.abs(targetCenterX - centerX) < REST_DISTANCE
                && Math.abs(widthVelocity) < REST_VELOCITY
                && Math.abs(targetWidth - width) < REST_DISTANCE;
        if (settled) {
            centerX = targetCenterX;
            width = targetWidth;
            centerXVelocity = 0f;
            widthVelocity = 0f;
        } else {
            postOnAnimation(this::step);
        }
        invalidate();
        publishState();
    }

    private void publishState() {
        if (stateListener != null && hasTarget && width > 0f && getHeight() > 0) {
            stateListener.onMorphState(centerX, width, getHeight(), cornerRadius);
        }
    }

    /** One step of a critically damped spring, returning the new velocity. */
    private static float spring(float value, float target, float velocity, float seconds) {
        float displacement = value - target;
        float damping = 2f * DAMPING * (float) Math.sqrt(STIFFNESS);
        float acceleration = (-STIFFNESS * displacement) - (damping * velocity);
        return velocity + acceleration * seconds;
    }

    /**
      * Takes exactly the space it is given and asks for none.
      *
      * <p>Decoration must not drive the layout it decorates. Left to {@code View}'s default, an
      * unmeasured {@code MATCH_PARENT} child of a {@code wrap_content} FrameLayout answers the
      * {@code AT_MOST} spec of the first measure pass with the entire available height — and the
      * host then wraps to that, which put a window-tall pill over the whole screen on the one
      * variant that installs this view. Reporting zero leaves the pill's height to the tab bar,
      * and the second pass hands this view the result.</p>
      */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(givenOrNothing(widthMeasureSpec),
                givenOrNothing(heightMeasureSpec));
    }

    private static int givenOrNothing(int measureSpec) {
        return MeasureSpec.getMode(measureSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(measureSpec) : 0;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!renderBlob || !hasTarget || width <= 0f) return;

        // Stretch along the direction of travel and thin out across it, conserving roughly the
        // area the blob had at rest. This is the whole of the liquid read: a rigid rectangle
        // sliding between tabs looks like a tab indicator, not like something flowing.
        float speed = Math.abs(centerXVelocity);
        float stretch = Math.min(MAX_STRETCH, (speed / 1000f) * STRETCH_PER_SPEED);
        float drawWidth = width * (1f + stretch);
        float height = getHeight() * (1f - stretch * 0.5f);
        float top = (getHeight() - height) / 2f;

        blob.set(centerX - drawWidth / 2f, top, centerX + drawWidth / 2f, top + height);
        canvas.drawRoundRect(blob, cornerRadius, cornerRadius, paint);
    }
}
