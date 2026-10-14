package com.waenhancer.theme;

import android.annotation.SuppressLint;
import android.content.Context;
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

    /**
     * Size of WhatsApp's own Material active indicator (the small green pill around the selected
     * icon), which is the resting state: the bubble is born from it when motion starts, grows into
     * the glass bubble, and shrinks back into it on arrival.
     */
    static final float INDICATOR_W_DP = 64f;
    static final float INDICATOR_H_DP = 32f;
    /** Below this press progress the bubble is hidden and the native indicator stands alone. */
    public static final float VISIBLE_PROGRESS = 0.01f;
    /** Opacity of the accent wash at the start of the morph, fading out as glass takes over. */
    static final float MORPH_ACCENT_ALPHA = 0.45f;
    private final Paint morphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /**
     * A private copy of WhatsApp's own indicator drawable, so the morph starts and ends on exactly
     * its colour and shape. Null until the bar finds it; the accent wash stands in meanwhile.
     */
    private android.graphics.drawable.Drawable indicator;

    /** Sets the native indicator look the bubble is born from and lands back into. */
    public void setIndicatorDrawable(android.graphics.drawable.Drawable drawable) {
        indicator = drawable;
    }

    /** Where the tabs pager is, as a fractional bubble index; NaN when unknown. */
    public interface PagerSource {
        float position();
    }

    private PagerSource pagerSource;
    private float lastPagerPosition = Float.NaN;
    /** When the pager position last changed (uptime ms). */
    private long pagerChangedAt;
    /**
     * How long the pager must hold still before a follow ends. Time rather than a count of
     * polls: the pager is polled from both the window's pre-draw and the bubble's own frame, so
     * two polls can fall in one vsync, and a one-frame pause in a slow swipe would end the follow
     * and pulse the bubble.
     */
    static final long PAGER_SETTLE_MS = 80L;
    /** How close to a whole page the pager must rest for a swipe to count as finished. */
    static final float PAGE_SETTLED_EPSILON = 0.01f;
    /** True while {@link #frame()} runs, so scheduling from inside it keeps the frame clock. */
    private boolean inFrame;
    /** After a drag commits a tab, the pager's own scroll toward it must not move the bubble. */
    private boolean waitPagerSettle;
    /** Uptime after which {@link #waitPagerSettle} no longer blocks a follow, settled or not. */
    private long waitPagerUntil;
    private final android.view.ViewTreeObserver.OnPreDrawListener preDraw = () -> {
        onWindowFrame();
        return true;
    };
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

    /**
     * Polled every window frame (and every bubble frame while following). Reading the pager's
     * scroll position works whatever class the host app's pager is; the method hooks this
     * replaced depended on androidx names that this WhatsApp build does not use.
     */
    public void setPagerSource(PagerSource source) {
        pagerSource = source;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(preDraw);
    }

    /** Once per window frame, before it draws. */
    private void onWindowFrame() {
        pollPager();
        // The resting lens shows a recording of the capture view; when that view is about to
        // redraw (the list moved behind the bar), redraw with it, or the bubble would show a
        // stale copy. Never invalidates on its own, so it cannot start a frame loop.
        if (lensed && motion != null && motion.pressProgress() > VISIBLE_PROGRESS
                && captureView != null && captureView.isDirty() && !isDirty()) invalidate();
    }

    /** Follows the pager while it moves; settles when it has been still for two frames. */
    private void pollPager() {
        if (pagerSource == null || motion == null) return;
        float position;
        try {
            position = pagerSource.position();
        } catch (Throwable t) {
            return;
        }
        if (Float.isNaN(position)) return;
        boolean changed = Float.isNaN(lastPagerPosition)
                || Math.abs(position - lastPagerPosition) > 0.0005f;
        boolean first = Float.isNaN(lastPagerPosition);
        lastPagerPosition = position;
        long now = android.os.SystemClock.uptimeMillis();
        if (first) {
            pagerChangedAt = now;
            return;
        }
        if (changed) {
            pagerChangedAt = now;
            if (!waitPagerSettle || now > waitPagerUntil) follow(position);
        } else if (now - pagerChangedAt >= PAGER_SETTLE_MS
                && Math.abs(position - Math.round(position)) < PAGE_SETTLED_EPSILON) {
            // Still *and* on a page. A finger resting mid-swipe holds the pager still between
            // pages; ending the follow there deflated the bubble under the finger and re-inflated
            // it when the swipe resumed.
            waitPagerSettle = false;
            if (following) endFollow();
        }
        if (following && !frameScheduled) schedule();
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
            boolean there = Math.abs(motion.targetValue() - selected) < 0.05f;
            if (there && motion.isGliding()) {
                // Already gliding there; a layout pass must not cut the tap's travel short.
            } else if (there) {
                // A swipe that just ended leaves the bubble on (or a hair from) the selected tab:
                // place it rather than starting a rise-travel-land for a sub-pixel distance.
                motion.snapTo(selected);
            } else {
                // "Remove animations" (animator scale 0) is honoured; any other scale is not: the
                // tap's timing is fixed (see BubbleMotion.GLIDE_END).
                motion.animateTo(selected, animate && android.animation.ValueAnimator.areAnimatorsEnabled());
            }
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
        if (!following) {
            // Rise into the lens for the length of the swipe, as a tap's travel does, so the
            // bubble moves as glass rather than as a flat pill.
            following = true;
            motion.lift();
            schedule();
        }
        motion.follow(index);
        invalidate();
    }

    /** The swipe ended: deflate onto the tab, then selection reports drive the bubble again. */
    public void endFollow() {
        if (!following) return;
        following = false;
        if (motion != null && !motion.isPressed()) {
            motion.release();
            schedule();
        }
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
        holdPagerFollow();
        expected = index;
        expectedUntil = android.os.SystemClock.uptimeMillis() + EXPECT_MS;
        selected = index;
    }

    /**
     * A tap on another tab is about to make the host app move its pager there, often in a single
     * frame. The bubble must glide there when the selection is reported instead of following
     * the pager's jump (which put it on the new tab at once, to swell and collapse in place). The
     * hold ends once the pager rests on a page, and after {@link #EXPECT_MS} in any case.
     */
    public void holdPagerFollow() {
        long now = android.os.SystemClock.uptimeMillis();
        waitPagerSettle = true;
        pagerChangedAt = now;
        waitPagerUntil = now + EXPECT_MS;
    }

    // ---------------------------------------------------------------------------------- frame

    private void schedule() {
        if (frameScheduled) return;
        frameScheduled = true;
        // Only a loop that is starting resets the clock; one continued from inside a frame keeps
        // it, or every frame would integrate 1/60 s whatever the refresh rate.
        if (!inFrame) lastFrameNanos = 0L;
        postOnAnimation(this::frame);
    }

    private void frame() {
        frameScheduled = false;
        if (motion == null) return;
        inFrame = true;
        try {
            stepFrame();
        } finally {
            inFrame = false;
        }
    }

    private void stepFrame() {
        // The pager can stop between window frames; keep polling while following so the end of
        // a swipe is seen even if nothing else redraws.
        if (following) pollPager();
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
        if ((moving || following) && !frameScheduled) {
            frameScheduled = true;
            postOnAnimation(this::frame);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        getViewTreeObserver().removeOnPreDrawListener(preDraw);
        frameScheduled = false;
        lastPagerPosition = Float.NaN;
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
        float fullW = Math.max(1f, BubbleMotion.interpolate(widths, value) - 2f * INSET_DP * density);
        float fullH = Math.max(1f, getHeight() - 2f * INSET_DP * density);
        float progress = motion.pressProgress();
        // At rest WhatsApp's own small green indicator is the indicator; the bubble only exists
        // while something moves.
        if (progress < VISIBLE_PROGRESS) return;
        // Born from the native indicator: at low progress the bubble has the indicator's size,
        // and grows to its own (then the pressed scale) as it rises; on landing it shrinks back.
        // The morph sizes the shape, not its content: scaling the canvas by it shrank the icons
        // to about 60% just as the bubble handed over to the native pill, which then popped them
        // back to full size (a dim, small icon for the last ~100 ms of every landing).
        float w = Math.max(1f, fullW * morphScale(INDICATOR_W_DP * density / fullW, progress));
        float h = Math.max(1f, fullH * morphScale(INDICATOR_H_DP * density / fullH, progress));
        float left = cx - w / 2f;
        float top = (getHeight() - h) / 2f;
        float radius = h / 2f;
        float sx = settleScale(motion.drawScaleX(), progress);
        float sy = settleScale(motion.drawScaleY(), progress);

        int save = canvas.save();
        canvas.translate(left, top);
        canvas.scale(sx, sy, w / 2f, h / 2f);
        try {
            if (!drawLens(canvas, left, top, Math.round(w), Math.round(h), radius, progress,
                    progress)) {
                // No live lens here (fallback material, another surface's recording): a wash
                // and a hairline edge stand in for it.
                drawTint(canvas, w, h, radius, progress);
                drawRestEdge(canvas, w, h, radius, progress);
            }
            // The indicator it was born from, fading out as the glass takes over (and back in as
            // it lands): at zero progress the bubble is exactly WhatsApp's pill, so the hand-off
            // to the real one shows no colour jump.
            float fade = (1f - progress) * (1f - progress);
            if (indicator != null) {
                if (fade > 0.004f) {
                    // The native look is pill *and* icon (WhatsApp draws its icon over its pill;
                    // the bubble draws above the tab row, so the pill copy alone covered the
                    // icon). Composite both at full opacity in one layer and fade that layer, so
                    // the landing ends exactly as the native tab looks.
                    rect.set(0f, 0f, w, h);
                    int layer = canvas.saveLayerAlpha(rect, Math.round(fade * 255f));
                    indicator.setBounds(0, 0, Math.round(w), Math.round(h));
                    indicator.setAlpha(255);
                    indicator.draw(canvas);
                    drawIcons(canvas, left, top, w, h, radius);
                    canvas.restoreToCount(layer);
                }
            } else {
                float green = MORPH_ACCENT_ALPHA * fade;
                if (green > 0.004f) {
                    morphPaint.setColor((Math.round(green * 255f) << 24) | (accent & 0xFFFFFF));
                    rect.set(0f, 0f, w, h);
                    canvas.drawRoundRect(rect, radius, radius, morphPaint);
                }
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    /**
     * The spring scale (pressed size, velocity squash) eased back to 1 over the last 30% of the
     * deflation. The scale springs are slower than the press, so without this the bubble handed
     * over to the native pill while still a few percent larger than it.
     */
    static float settleScale(float scale, float progress) {
        float x = Math.max(0f, Math.min(1f, progress / 0.3f));
        float weight = x * x * (3f - 2f * x);
        return 1f + (scale - 1f) * weight;
    }

    /** The tab row redrawn inside the bubble's shape, in the bubble's (scaled) space. */
    private void drawIcons(Canvas canvas, float left, float top, float w, float h, float radius) {
        if (tabs == null || LiveBackdrop.isCapturing()) return;
        int save = canvas.save();
        try {
            iconClip.reset();
            rect.set(0f, 0f, w, h);
            iconClip.addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW);
            canvas.clipPath(iconClip);
            canvas.translate(-left, -top);
            drawSibling(canvas, tabs, null);
        } catch (Throwable ignored) {
            // Decoration only.
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private final android.graphics.Path iconClip = new android.graphics.Path();

    /**
     * Scale from the native indicator's size ({@code fromFraction} of the bubble's) to the
     * bubble's own as progress rises; ease-out, so most of the growth happens early in the rise.
     */
    static float morphScale(float fromFraction, float progress) {
        float from = Math.max(0.05f, Math.min(1f, fromFraction));
        float p = Math.max(0f, Math.min(1f, progress));
        float eased = 1f - (1f - p) * (1f - p);
        return from + (1f - from) * eased;
    }

    private void drawTint(Canvas canvas, float w, float h, float radius, float progress) {
        KyantLens.Params params = KyantLens.bubble(dark, progress, density);
        int color = lensed ? params.tint : ((Math.round(0.15f * 255f) << 24) | (accent & 0xFFFFFF));
        if ((color >>> 24) == 0) return;
        tintPaint.setColor(color);
        rect.set(0f, 0f, w, h);
        canvas.drawRoundRect(rect, radius, radius, tintPaint);
    }

    /**
     * A hairline edge so the translucent rest wash reads as a pane of glass rather than a smudge.
     * It fades out as the lens, which draws its own lit rim, takes over.
     */
    private void drawRestEdge(Canvas canvas, float w, float h, float radius, float progress) {
        rect.set(0f, 0f, w, h);
        float edge = (1f - Math.max(0f, Math.min(1f, progress))) * REST_EDGE_ALPHA;
        if (lensed && edge > 0.004f) {
            float stroke = Math.max(1f, density);
            edgePaint.setStrokeWidth(stroke);
            edgePaint.setColor((Math.round(edge * 255f) << 24) | (dark ? 0xFFFFFF : 0x000000));
            rect.inset(stroke / 2f, stroke / 2f);
            canvas.drawRoundRect(rect, radius - stroke / 2f, radius - stroke / 2f, edgePaint);
        }
    }

    /** Opacity of the rest hairline edge. */
    static final float REST_EDGE_ALPHA = 0.16f;
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    {
        edgePaint.setStyle(Paint.Style.STROKE);
    }

    /** The pressed lens. False when it cannot run here, so the caller draws the tint instead. */
    private boolean drawLens(Canvas canvas, float left, float top, int w, int h, float radius,
                             float progress, float glassLevel) {
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
                // The tab row in its own colours. OneMate tints it with the accent because its
                // icons are monochrome; WhatsApp already colours the selected tab, and the tint
                // turned every icon green for the length of each transition (a green flash).
                drawSibling(in, tabs, null);
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
            lens.update(KyantLens.bubble(dark, progress, glassLevel, density), w, h, radius, 0f, 0f);
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
