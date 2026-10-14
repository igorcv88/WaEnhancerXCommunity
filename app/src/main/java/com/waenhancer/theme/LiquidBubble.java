package com.waenhancer.theme;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.BlendModeColorFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.os.Build;
import android.view.View;

/**
 * The bottom bar's selected-tab bubble, as OneMate draws it (Kyant0's liquid bottom tabs).
 *
 * <p>At rest it is a faint pill under the selected tab. On press it rises: it grows to
 * {@link #PRESSED_SCALE} of its size (past the bar's own edges), its lens switches on with depth
 * and dispersion, its rim lights up and an inner shadow sinks into its top. While pressed it
 * follows the finger along the bar, squashing in the direction of travel, and on release it
 * settles on the nearest tab and deflates. {@link BubbleMotion} owns all of that motion.</p>
 *
 * <h3>What the lens sees</h3>
 *
 * <p>OneMate's bubble refracts the page behind the bar plus an accent-tinted copy of the tab row.
 * This view records the same two things from its own host: the bar's capture view (the blurred
 * page, without the bar's own lens or tint) and the tab row through an accent {@code SRC_IN}
 * filter. Both are siblings drawn <em>before</em> this view inside the glass host, and the host is
 * excluded from the capture view's own recording, so neither can contain this view: the render
 * tree stays acyclic. Nothing is recorded at rest, where the bubble is only a tint.</p>
 */
@SuppressLint("ViewConstructor")
public final class LiquidBubble extends View {

    /** OneMate's pressedScale (78dp over a 56dp bubble). */
    public static final float PRESSED_SCALE = 78f / 56f;
    /** Bubble height inside the bar: the bar less 4dp of padding each side (64dp → 56dp). */
    static final float INSET_DP = 4f;

    public interface Listener {
        /** Bar response: OneMate scales the bar up by 16dp of width while pressed. */
        void onBarState(float pressProgress, float rubberBandPx);
    }

    private final float density;
    private BubbleMotion motion;
    private float[] centers = new float[0];
    private float[] widths = new float[0];
    private int selected;
    private View captureView;
    private View tabs;
    private int accent = 0xFF25D366;
    private boolean dark = true;
    private boolean lensed;
    private Listener listener;
    private long lastFrameNanos;
    private boolean frameScheduled;
    /** A tab a finished drag has selected, until the host app reports it selected. */
    private int expected = -1;
    private long expectedUntil;
    /** How long a selection report that disagrees with a finished drag is ignored. */
    static final long EXPECT_MS = 700L;

    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tabTint = new Paint();
    private int tabTintColor;
    private final RectF rect = new RectF();
    private RenderNode source;
    private RenderNode glass;
    private KyantLens lens;

    public LiquidBubble(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** The views the pressed lens records: the bar's capture view and its tab row. */
    public void setSources(View captureView, View tabs) {
        this.captureView = captureView;
        this.tabs = tabs;
        // No outline: the Z below is only for draw order and must not cast a shadow.
        setOutlineProvider(null);
        keepAboveTabs();
    }

    /**
     * Draws above the tab row. A ViewGroup sorts children by Z before index, and the hooked tab
     * row keeps its own elevation, so being the last child was not enough: the real icons were
     * drawn over the bubble's magnified copy of them, two icons per tab.
     */
    private void keepAboveTabs() {
        if (tabs == null) return;
        float wanted = tabs.getZ() + 1f - getElevation();
        if (getTranslationZ() != wanted) setTranslationZ(wanted);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Theme inputs; {@code lensed} false keeps the bubble a plain tint (no live glass). */
    public void setStyle(int accentColor, boolean darkTheme, boolean lensed) {
        boolean changed = accent != accentColor || dark != darkTheme || this.lensed != lensed;
        accent = accentColor;
        dark = darkTheme;
        this.lensed = lensed;
        if (changed) invalidate();
    }

    /**
     * Tab geometry in this view's coordinates, and the selected tab. Re-sent on every layout; a
     * change of tab count rebuilds the motion, anything else re-targets it.
     */
    public void setTabs(float[] tabCenters, float[] tabWidths, int selectedIndex, boolean animate) {
        if (tabCenters == null || tabWidths == null || tabCenters.length == 0
                || tabCenters.length != tabWidths.length) return;
        keepAboveTabs();
        centers = tabCenters.clone();
        widths = tabWidths.clone();
        int reported = Math.max(0, Math.min(selectedIndex, centers.length - 1));
        if (expected >= 0) {
            boolean stale = android.os.SystemClock.uptimeMillis() > expectedUntil;
            if (reported == expected || stale) expected = -1;
            else reported = expected;
        }
        selected = reported;
        if (motion == null || motion.tabs() != centers.length) {
            motion = new BubbleMotion(centers.length, selected, PRESSED_SCALE);
        } else if (!motion.isPressed() && !following) {
            // A swipe that just ended leaves the bubble on (or a hair from) the selected tab:
            // place it rather than starting a rise-travel-land for a sub-pixel distance.
            if (Math.abs(motion.targetValue() - selected) < 0.05f) motion.snapTo(selected);
            else motion.animateTo(selected, animate);
        }
        schedule();
        invalidate();
    }

    public int tabAt(float x) {
        if (centers.length == 0) return -1;
        int best = 0;
        for (int i = 1; i < centers.length; i++) {
            if (Math.abs(centers[i] - x) < Math.abs(centers[best] - x)) best = i;
        }
        return best;
    }

    public int selectedIndex() {
        return selected;
    }

    // ------------------------------------------------------------------ gestures (from the bar)

    public void press() {
        if (motion == null) return;
        following = false;
        motion.press();
        schedule();
    }

    /** A drag of {@code dx} px along the bar (positive right). */
    public void drag(float dx) {
        if (motion == null || widths.length == 0) return;
        float average = 0f;
        for (float w : widths) average += w;
        average /= widths.length;
        if (average <= 0f) return;
        // Index order follows the menu's children; in RTL the first child is on the right, so a
        // rightward drag moves toward lower indices.
        boolean ltr = centers.length < 2 || centers[centers.length - 1] >= centers[0];
        motion.dragBy(dx / average * (ltr ? 1f : -1f), dx);
        schedule();
    }

    /** True while a page swipe drives the bubble's position (see {@link #follow}). */
    private boolean following;

    /**
     * Follows a page swipe: {@code index} is the fractional tab the pager is showing. The bubble
     * tracks it frame by frame, flat (no rise), instead of waiting for the page to settle and
     * then travelling. Ignored while a finger holds the bubble.
     */
    public void follow(float index) {
        if (motion == null || motion.isPressed() || expected >= 0) return;
        following = true;
        motion.follow(index);
        invalidate();
    }

    /** The swipe ended: selection reports drive the bubble again. */
    public void endFollow() {
        following = false;
    }

    /** Finger up or cancel. Returns the tab the bubble settles on. */
    public int release() {
        if (motion == null) return selected;
        int index = motion.settleIndex();
        motion.release();
        schedule();
        return index;
    }

    /**
     * The caller is about to select {@code index} on the bubble's behalf. Selection reports that
     * still name the previous tab are ignored for {@link #EXPECT_MS}, because the host app marks
     * the new tab selected only after its click listener has run.
     */
    public void expect(int index) {
        expected = index;
        expectedUntil = android.os.SystemClock.uptimeMillis() + EXPECT_MS;
        selected = index;
    }

    // ---------------------------------------------------------------------------------- frame

    private void schedule() {
        if (frameScheduled) return;
        frameScheduled = true;
        lastFrameNanos = 0L;
        postOnAnimation(this::frame);
    }

    private void frame() {
        frameScheduled = false;
        if (motion == null) return;
        long now = System.nanoTime();
        float seconds = lastFrameNanos == 0L ? 1f / 60f : (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;
        boolean moving = motion.step(seconds);
        if (listener != null) {
            float total = getWidth();
            listener.onBarState(motion.pressProgress(), BubbleMotion.rubberBand(motion.offset(),
                    total, BubbleMotion.RUBBER_BAND_DP * density));
        }
        invalidate();
        if (moving) {
            frameScheduled = true;
            postOnAnimation(this::frame);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        frameScheduled = false;
        if (source != null) source.discardDisplayList();
        if (glass != null) glass.discardDisplayList();
    }

    /** Takes exactly the space it is given, as LiquidMorph does: decoration must not drive layout. */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(given(widthMeasureSpec), given(heightMeasureSpec));
    }

    private static int given(int spec) {
        return MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(spec) : 0;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (motion == null || centers.length == 0 || getHeight() <= 0) return;
        float value = motion.value();
        float cx = BubbleMotion.interpolate(centers, value);
        float w = Math.max(1f, BubbleMotion.interpolate(widths, value) - 2f * INSET_DP * density);
        float h = Math.max(1f, getHeight() - 2f * INSET_DP * density);
        float left = cx - w / 2f;
        float top = (getHeight() - h) / 2f;
        float radius = h / 2f;
        float progress = motion.pressProgress();
        float sx = motion.drawScaleX();
        float sy = motion.drawScaleY();

        int save = canvas.save();
        canvas.translate(left, top);
        canvas.scale(sx, sy, w / 2f, h / 2f);
        try {
            if (!(progress > 0.002f && drawLens(canvas, left, top, Math.round(w), Math.round(h),
                    radius, progress))) {
                drawTint(canvas, w, h, radius, progress);
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private void drawTint(Canvas canvas, float w, float h, float radius, float progress) {
        KyantLens.Params params = KyantLens.bubble(accent, progress, density);
        int color = lensed ? params.tint : ((Math.round(0.15f * 255f) << 24) | (accent & 0xFFFFFF));
        if ((color >>> 24) == 0) return;
        tintPaint.setColor(color);
        rect.set(0f, 0f, w, h);
        canvas.drawRoundRect(rect, radius, radius, tintPaint);
    }

    /** The pressed lens. False when it cannot run here, so the caller draws the tint instead. */
    private boolean drawLens(Canvas canvas, float left, float top, int w, int h, float radius,
                             float progress) {
        if (!lensed || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || !KyantLens.isAvailable() || !canvas.isHardwareAccelerated()
                || !(canvas instanceof RecordingCanvas) || LiveBackdrop.isCapturing()
                || w <= 0 || h <= 0) {
            return false;
        }
        try {
            if (source == null) source = new RenderNode("WAEX bubble source");
            if (glass == null) glass = new RenderNode("WAEX bubble glass");
            if (lens == null) lens = new KyantLens();

            source.setPosition(0, 0, w, h);
            RecordingCanvas in = source.beginRecording(w, h);
            try {
                in.translate(-left, -top);
                drawSibling(in, captureView, null);
                if (tabTintColor != (accent | 0xFF000000)) {
                    tabTintColor = accent | 0xFF000000;
                    tabTint.setColorFilter(new BlendModeColorFilter(tabTintColor, BlendMode.SRC_IN));
                }
                drawSibling(in, tabs, tabTint);
            } finally {
                source.endRecording();
            }
            glass.setPosition(0, 0, w, h);
            RecordingCanvas out = glass.beginRecording(w, h);
            try {
                out.drawRenderNode(source);
            } finally {
                glass.endRecording();
            }
            lens.update(KyantLens.bubble(accent, progress, density), w, h, radius, 0f, 0f);
            if (lens.effect() == null) return false;
            glass.setRenderEffect(lens.effect());
            ((RecordingCanvas) canvas).drawRenderNode(glass);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Draws a sibling of this view (same parent) in this view's coordinates. */
    private void drawSibling(Canvas canvas, View sibling, Paint layer) {
        if (sibling == null || sibling == this || sibling.getVisibility() != VISIBLE
                || sibling.getParent() != getParent()) return;
        int save = layer != null
                ? canvas.saveLayer(null, layer)
                : canvas.save();
        canvas.translate(sibling.getLeft() - getLeft() + sibling.getTranslationX() - getTranslationX(),
                sibling.getTop() - getTop() + sibling.getTranslationY() - getTranslationY());
        sibling.draw(canvas);
        canvas.restoreToCount(save);
    }
}
