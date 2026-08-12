package com.waenhancer.theme;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import java.util.function.Supplier;

import eightbitlab.com.blurview.BlurAlgorithm;
import eightbitlab.com.blurview.BlurView;
import eightbitlab.com.blurview.RenderEffectBlur;
import eightbitlab.com.blurview.RenderScriptBlur;

/**
 * One glass surface: a view wrapped in a host that captures the backdrop behind it and bends it.
 *
 * <p>This is the pattern the floating bottom bar arrived at, with the bar taken out of it. What
 * remains is entirely material — a host, a capture, a lens or the layered fallback, and the
 * handful of invariants that decide which of those is painted. The bar's own geometry (its height,
 * its margins, the blob under the selected tab) stayed with the bar, because none of it is true of
 * a search field or an input row.</p>
 *
 * <p>Wrapping is the whole mechanism: {@link LiquidLens} runs through
 * {@link View#setRenderEffect} on a view that is <em>already drawing</em> the captured backdrop, so
 * there has to be such a view, and it cannot be the hooked view itself — that one is busy drawing
 * WhatsApp's icons and labels, and a render effect there would refract those along with everything
 * else.</p>
 *
 * <h3>The invariants</h3>
 *
 * <p>Every one of these was learned by measuring a screenshot, and every one of them is the sort
 * of thing that gets dropped when the pattern is copied by hand into a second surface. They live
 * here so there is one copy:</p>
 *
 * <table>
 *   <tr><th></th><th>lensed</th><th>layered</th></tr>
 *   <tr><td>capture overlay</td><td>transparent</td><td>the spec's fill</td></tr>
 *   <tr><td>capture background</td><td>none</td><td>the layered stack</td></tr>
 *   <tr><td>capture blur radius</td><td>{@link LiquidLens#CAPTURE_BLUR_RADIUS}</td>
 *       <td>the library's</td></tr>
 *   <tr><td>outline clip</td><td>off</td><td>on</td></tr>
 *   <tr><td>elevation</td><td>{@value #LENSED_ELEVATION_DP}+{@value #LENSED_TRANSLATION_Z_DP}dp</td>
 *       <td>{@value #ELEVATION_DP}+{@value #TRANSLATION_Z_DP}dp</td></tr>
 * </table>
 *
 * <p>Two of those are subtler than they look. The capture must never be switched <em>off</em>
 * under a lens, only turned down: the blur view is what pulls the backdrop into the view in the
 * first place, and without it the shader's {@code content} arrives empty and it falls through to a
 * flat tint. And a backdrop that arrives already blurred has nothing left to bend — an even haze
 * displaced sideways is the same even haze — which is why the lensed column asks for a capture
 * that is almost sharp and does its own blurring, graduated, inside the shader.</p>
 *
 * <p>Whether a lens is affordable at all is {@link GlassBudget}'s decision, not this class's. A
 * refusal is not a failure: the surface paints itself with {@link GlassRenderer} and looks like a
 * lit pane rather than a lens.</p>
 */
public final class GlassSurface {

    /**
     * How much of the surface a descendant must cover before its background counts as the chrome
     * being replaced rather than as that element's own styling, and how deep to look.
     */
    private static final float FULL_BLEED_MIN_COVERAGE = 0.85f;
    private static final int FULL_BLEED_MAX_DEPTH = 3;

    /** Elevation and Z of a surface the user is not meant to see through, in dp. */
    public static final float ELEVATION_DP = 12f;
    public static final float TRANSLATION_Z_DP = 8f;

    /**
     * The same two for a lensed surface.
     *
     * <p>Much less. Twenty dp of combined Z casts the wide dark shadow that belongs under an opaque
     * floating object, and under a pane you are meant to be looking through it reads as a solid
     * slab sitting on the list. Glass separates itself from its backdrop by refracting it; it needs
     * only enough shadow to keep its lower edge from merging into a dark one.</p>
     */
    public static final float LENSED_ELEVATION_DP = 5f;
    public static final float LENSED_TRANSLATION_Z_DP = 2f;

    /** What a surface needs to know about itself. Everything else is worked out from the spec. */
    public static final class Config {

        final Supplier<GlassSpec> spec;
        final GlassBudget.Kind kind;
        final float cornerRadiusDp;
        final boolean elevated;
        final boolean takeOverTargetBackground;

        private Config(Builder builder) {
            this.spec = builder.spec;
            this.kind = builder.kind;
            this.cornerRadiusDp = builder.cornerRadiusDp;
            this.elevated = builder.elevated;
            this.takeOverTargetBackground = builder.takeOverTargetBackground;
        }

        /**
         * @param spec where this surface's description comes from, asked again on every refresh.
         *             A supplier rather than a value because the answer changes — with the user's
         *             settings, with night mode, and for the adaptive variants with whatever is
         *             behind the surface at the time. Note that no preference is read in this
         *             package: the caller owns that, and hands the result here.
         */
        public static Builder of(Supplier<GlassSpec> spec) {
            return new Builder(spec);
        }

        public static final class Builder {
            private final Supplier<GlassSpec> spec;
            private GlassBudget.Kind kind = GlassBudget.Kind.LAYERED;
            private float cornerRadiusDp = 0f;
            private boolean elevated = true;
            private boolean takeOverTargetBackground = true;

            private Builder(Supplier<GlassSpec> spec) {
                this.spec = spec;
            }

            /** What this surface costs to draw. Defaults to the cheap answer. */
            public Builder kind(GlassBudget.Kind value) {
                this.kind = value;
                return this;
            }

            /** Requested corner radius; clamped to half the surface's height. See {@link #cornerRadiusPx}. */
            public Builder cornerRadiusDp(float value) {
                this.cornerRadiusDp = value;
                return this;
            }

            /** Whether the host casts a shadow at all. Off for a surface flush with its container. */
            public Builder elevated(boolean value) {
                this.elevated = value;
                return this;
            }

            /**
             * Whether the wrapped view's own background is taken over.
             *
             * <p>Usually yes: what is being replaced is the solid rectangle WhatsApp drew there,
             * and leaving it in place puts an opaque sheet between the capture and the content.
             * Off for a target whose background carries something else the surface must not
             * lose.</p>
             */
            public Builder takeOverTargetBackground(boolean value) {
                this.takeOverTargetBackground = value;
                return this;
            }

            public Config build() {
                return new Config(this);
            }
        }
    }

    private final View target;
    private final FrameLayout host;
    private final BlurView blurView;
    private final ViewGroup blurRoot;
    private final Config config;

    /** Where the target came from, so {@link #detach} can put it back exactly there. */
    private final ViewGroup originalParent;
    private final int originalIndex;
    private final ViewGroup.LayoutParams originalLayoutParams;
    private final Drawable originalBackground;

    /** Descendant backgrounds taken over along with the target's, and what they were. */
    private final java.util.Map<View, Drawable> takenOverDescendants =
            new java.util.LinkedHashMap<>();

    private ViewTreeObserver.OnPreDrawListener visibilitySync;

    private BackdropSampler sampler;
    private int backdropColor;
    private Float captureRadius;
    private String paintKey;
    private boolean lensed;
    private boolean detached;

    private GlassSurface(View target, FrameLayout host, BlurView blurView, ViewGroup blurRoot,
                         Config config, ViewGroup originalParent, int originalIndex,
                         ViewGroup.LayoutParams originalLayoutParams, Drawable originalBackground) {
        this.target = target;
        this.host = host;
        this.blurView = blurView;
        this.blurRoot = blurRoot;
        this.config = config;
        this.originalParent = originalParent;
        this.originalIndex = originalIndex;
        this.originalLayoutParams = originalLayoutParams;
        this.originalBackground = originalBackground;
    }

    /**
     * Wraps {@code target} where it stands.
     *
     * <p>The host takes the target's own layout params <em>verbatim</em> and the target is given
     * {@code MATCH_PARENT} inside it. That is not tidiness: a target in a horizontal
     * {@code LinearLayout} carries a weight, and a host built with fresh params instead of the
     * inherited ones loses it and collapses the row. The same mistake with a {@code wrap_content}
     * is what once turned the bottom bar into a window-tall pill.</p>
     *
     * @param target   the view to put behind glass; must currently be attached to a ViewGroup
     * @param blurRoot the view tree the capture reads from: an ancestor that draws the content
     *                 meant to show through. It may contain this surface's own host — see
     *                 {@code CaptureExcludedHost} — and it had better draw the wallpaper and the
     *                 list, because a root that draws neither leaves the capture showing nothing
     *                 but the window background, flat
     * @return the surface, or {@code null} when the target cannot be wrapped
     */
    public static GlassSurface wrap(View target, ViewGroup blurRoot, Config config) {
        if (target == null || config == null) return null;
        ViewParent parent = target.getParent();
        if (!(parent instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) parent;
        int index = group.indexOfChild(target);
        ViewGroup.LayoutParams hostLp = target.getLayoutParams();
        FrameLayout.LayoutParams targetLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        return install(group, index, hostLp, target, targetLp, blurRoot, config);
    }

    /**
     * Wraps a target the caller has already detached, or is placing somewhere new.
     *
     * <p>{@link #wrap} is this with the parent, the index and the layout params read off the
     * target. Callers that are moving a view — the bottom bar lifts itself out of WhatsApp's own
     * container and into the overlay root — have their own answers for all three.</p>
     */
    public static GlassSurface install(ViewGroup parent, int index, ViewGroup.LayoutParams hostLp,
                                       View target, FrameLayout.LayoutParams targetLp,
                                       ViewGroup blurRoot, Config config) {
        if (parent == null || target == null || config == null) return null;
        try {
            Context ctx = target.getContext();
            float density = ctx.getResources().getDisplayMetrics().density;

            FrameLayout host = new CaptureExcludedHost(ctx);
            host.setClipChildren(false);
            host.setClipToPadding(false);
            // A transparent shape of the right radius: nothing to see, but it is what the outline
            // provider reads, and therefore what shape the shadow is cast in.
            host.setBackground(outlineShape(config.cornerRadiusDp * density));
            host.setClipToOutline(false);
            host.setOutlineProvider(ViewOutlineProvider.BACKGROUND);

            BlurView blurView = new BlurView(ctx);
            blurView.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            host.addView(blurView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            ViewGroup previousParent = null;
            int previousIndex = -1;
            ViewGroup.LayoutParams previousLp = target.getLayoutParams();
            ViewParent existing = target.getParent();
            if (existing instanceof ViewGroup) {
                previousParent = (ViewGroup) existing;
                previousIndex = previousParent.indexOfChild(target);
                previousParent.removeView(target);
            }
            host.addView(target, targetLp);

            // The host's shadow and the lens's own feathered edge both fall outside the host's
            // bounds; a parent still clipping its children cuts both off square.
            parent.setClipChildren(false);
            parent.setClipToPadding(false);
            parent.addView(host, Math.max(0, Math.min(index, parent.getChildCount())), hostLp);

            GlassSurface surface = new GlassSurface(target, host, blurView, blurRoot, config,
                    previousParent, previousIndex, previousLp, target.getBackground());
            surface.setupCapture();
            surface.followTargetVisibility();
            surface.refresh();
            return surface;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Whether this view is a wrapper installed by this class.
     *
     * <p>A host inherits the target's layout params, so it lands at the same size and in the same
     * place — which means a discovery that identifies its target by shape and position will happily
     * identify a host as well, wrap it in a second host, and go round again. The scroll button did
     * exactly that: twenty wrappers in six seconds and the whole lens budget spent. Discovery has
     * to be able to recognise our own work.</p>
     */
    public static boolean isGlassHost(View view) {
        return view instanceof CaptureExcludedHost;
    }

    /** The wrapper that now holds the target. This, not the target, is what moves and animates. */
    public FrameLayout host() {
        return host;
    }

    /** The view the lens is installed on. Exposed for {@link LiquidLens#updateActive}. */
    public BlurView captureView() {
        return blurView;
    }

    /** Whether a lens is currently painting this surface. */
    public boolean isLensed() {
        return lensed;
    }

    /**
     * This surface as it currently looks, backdrop included.
     *
     * <p>The config's supplier answers what the variant is; this answers what it looks like right
     * now. For the adaptive variants those differ, and they have to: colours computed once from the
     * theme cannot react to the content scrolling underneath.</p>
     */
    public GlassSpec currentSpec() {
        GlassSpec base = config.spec.get();
        if (base == null) return null;
        if (backdropColor == 0) return base;
        return base.adaptTo(backdropColor, isNightMode(target.getContext()));
    }

    /**
     * Brings the surface up to date: what is behind it, whether it may bend that, and how.
     *
     * <p>Cheap enough for every layout pass. The backdrop sample throttles itself, the lens is
     * rebuilt only when something baked into the shader changed, and both the paint and the
     * capture radius are compared before being written — {@code setBlurRadius} invalidates the view
     * whether or not the number moved.</p>
     */
    public void refresh() {
        if (detached) return;
        try {
            GlassSpec base = config.spec.get();
            if (base == null) return;

            // Sampling draws part of the view tree, so it is posted rather than run from inside
            // the layout callback that got us here: reading a hierarchy while it is still settling
            // is how a sampler records a half-laid-out frame.
            if (base.adaptive) host.post(this::sampleBackdrop);

            GlassSpec spec = currentSpec();
            if (spec == null) return;

            boolean wantsLens = LiquidLens.isActiveFor(spec)
                    && GlassBudget.shared().grant(this, config.kind, spec);

            boolean lensRunning = false;
            if (wantsLens) {
                lensRunning = LiquidLens.apply(blurView, spec, cornerRadiusPx(), density());
                // A lens that has not run yet because the surface is not measured keeps its slot;
                // the next layout pass is what it is waiting for. A lens the driver rejected does
                // not: isSupported() goes false for the whole process, and the slot has to go back
                // so it is not held by a surface that will never use it.
                if (!lensRunning && !LiquidLens.isSupported()) {
                    GlassBudget.shared().release(this);
                    wantsLens = false;
                }
            } else {
                GlassBudget.shared().release(this);
                LiquidLens.clear(blurView);
            }
            lensed = wantsLens;

            // Both follow the result, and that word is doing work. Painting used to follow the
            // intent, on the reasoning that intent and result differ only for a device whose
            // driver refuses the shader. They also differ for a surface that is not measured yet,
            // and there the lensed painting - no background, no fill, no outline clip, because the
            // shader owns all three - leaves the capture on screen as a hard-edged blurred
            // rectangle. That is what the scroll button showed: an unshaped blur, since the lens
            // that was going to shape it never ran. A surface still waiting for its lens gets the
            // layered look, which is a working surface, and switches when the lens actually runs.
            paint(spec, lensRunning);
            applyCaptureBlur(spec, lensRunning);
            if (config.elevated) applyElevation(lensRunning);
        } catch (Throwable ignored) {
            // A surface that cannot refresh keeps whatever it last painted. Throwing here would
            // take the host app's layout pass down with it.
        }
    }

    /**
     * Keeps the wrapper as visible as the thing it wraps.
     *
     * <p>Visibility belongs to the target, and the host is a view the host app has never heard of:
     * when WhatsApp hides the scroll-to-bottom button the target goes away inside a wrapper that
     * stays exactly where it was, and what is left on screen is a pane of glass with nothing in it.
     * The floating bar solved the same problem by hooking the one class whose visibility it cared
     * about; a surface discovered by shape has no class to hook, so the state is mirrored
     * instead.</p>
     *
     * <p>A pre-draw listener rather than a layout one: going to {@code GONE} does not necessarily
     * lay the host out again, and the check is two comparisons. Alpha comes along for the ride so a
     * button that fades in does not arrive behind glass that is already at full strength.</p>
     */
    private void followTargetVisibility() {
        visibilitySync = () -> {
            if (!detached) {
                int wanted = target.getVisibility();
                if (host.getVisibility() != wanted) host.setVisibility(wanted);
                if (host.getAlpha() != target.getAlpha()) host.setAlpha(target.getAlpha());
            }
            return true;
        };
        try {
            host.getViewTreeObserver().addOnPreDrawListener(visibilitySync);
        } catch (Throwable ignored) {
        }
    }

    /** Undoes the wrap, putting the target back where it was found. */
    public void detach() {
        if (detached) return;
        detached = true;
        try {
            GlassBudget.shared().release(this);
            LiquidLens.clear(blurView);
            if (visibilitySync != null) {
                host.getViewTreeObserver().removeOnPreDrawListener(visibilitySync);
                visibilitySync = null;
            }
            host.removeView(target);
            ViewParent parent = host.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(host);
            }
            if (config.takeOverTargetBackground) {
                target.setBackground(originalBackground);
                for (java.util.Map.Entry<View, Drawable> entry : takenOverDescendants.entrySet()) {
                    entry.getKey().setBackground(entry.getValue());
                }
                takenOverDescendants.clear();
            }
            if (originalParent != null && target.getParent() == null) {
                int index = Math.max(0, Math.min(originalIndex, originalParent.getChildCount()));
                if (originalLayoutParams != null) {
                    originalParent.addView(target, index, originalLayoutParams);
                } else {
                    originalParent.addView(target, index);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * The corner radius in pixels, never larger than the surface can carry.
     *
     * <p>"Fully rounded" is stored as a very large dp figure standing in for "half the height"
     * rather than as a measurement. Handing that to a lens that solves for the distance to its own
     * edge puts every pixel outside the shape.</p>
     */
    private float cornerRadiusPx() {
        float requested = config.cornerRadiusDp * density();
        int height = blurView.getHeight();
        if (height <= 0) return requested;
        return Math.min(requested, height / 2f);
    }

    private float density() {
        return target.getContext().getResources().getDisplayMetrics().density;
    }

    /**
     * Points the blur library at the tree it should capture.
     *
     * <p>Once, at install. The radius is keyed off the spec rather than off the capture: a device
     * that cannot blur at all asked for zero, and that decision is the spec's to make — it has
     * already paid for it with the higher fill opacity in {@link GlassSpec#resolve}.</p>
     */
    private void setupCapture() {
        try {
            Context ctx = target.getContext();
            BlurAlgorithm algorithm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? new RenderEffectBlur()
                    : new RenderScriptBlur(ctx);
            Drawable windowBackground = null;
            View rootView = target.getRootView();
            if (rootView != null) windowBackground = rootView.getBackground();

            GlassSpec spec = config.spec.get();
            float radius = spec == null ? 0f : GlassRenderer.blurRadius(spec);
            ViewGroup root = blurRoot != null ? blurRoot : (ViewGroup) host.getParent();
            blurView.setupWith(root, algorithm)
                    .setFrameClearDrawable(windowBackground)
                    .setBlurRadius(Math.max(1f, radius));
            blurView.setBlurEnabled(radius > 0f);
            captureRadius = Math.max(1f, radius);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Paints the two surfaces the effect is made of, when what they should look like changed.
     *
     * <p>Guarded by a key rather than run every time: this allocates drawables, and it is reached
     * from a layout listener.</p>
     */
    private void paint(GlassSpec spec, boolean lensedNow) {
        String key = lensedNow + ":" + spec.fillColor + ":" + spec.strokeColor + ":"
                + Math.round(cornerRadiusPx());
        if (key.equals(paintKey)) return;
        paintKey = key;

        float density = density();
        if (lensedNow) {
            // The shader owns the fill, the rim and the shape's own antialiased coverage, so
            // nothing else may paint them. A background here would be a flat wash with no detail
            // in it, and the lens would spend itself refracting that instead of the backdrop —
            // which is what made an earlier build read as tinted grey. An outline clip would hard
            // cut the edge the shader just feathered.
            blurView.setBackground(null);
            blurView.setOverlayColor(Color.TRANSPARENT);
            blurView.setClipToOutline(false);
        } else {
            // The layered stack already carries the fill in its base, and it is drawn over the
            // blurred capture. Setting the overlay to the same colour applies it a second time,
            // which is the surface reading at twice the opacity the user configured - the same
            // mistake as painting the fill on the target, one layer further in.
            blurView.setBackground(GlassRenderer.background(spec, cornerRadiusPx(), density));
            blurView.setOverlayColor(Color.TRANSPARENT);
            blurView.setClipToOutline(true);
        }

        if (config.takeOverTargetBackground) {
            target.setBackgroundTintList(null);
            target.setBackground(targetBackground(spec, lensedNow, density));
            target.setClipToOutline(false);
            target.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            takeOverFullBleedDescendants();
        }
    }

    /**
     * Clears any descendant that paints the chrome we are replacing.
     *
     * <p>The view a hook finds is not always the view that draws. On WhatsApp's message input the
     * rounded capsule is painted by a child of the container the resource name points at, and
     * taking over only the container leaves that child covering the glass: the surface measured as
     * {@code (32,43,49)}, identical across 520 pixels, unchanged by two separate corrections to how
     * the fill was composed — because none of it was ever visible. The tell was the ghost around
     * the icons surviving at about a tenth of its strength, which is a near-opaque sheet sitting
     * over it.</p>
     *
     * <p>Only descendants that cover essentially the whole surface are touched. A background on
     * something smaller is that element's own styling — a badge, a selected state, an avatar ring —
     * and is not ours to remove.</p>
     */
    private void takeOverFullBleedDescendants() {
        if (!(target instanceof ViewGroup)) return;
        collectFullBleed((ViewGroup) target, 0);
        for (View view : takenOverDescendants.keySet()) {
            if (view.getBackground() != null) {
                view.setBackgroundTintList(null);
                view.setBackground(null);
            }
        }
    }

    private void collectFullBleed(ViewGroup group, int depth) {
        if (depth > FULL_BLEED_MAX_DEPTH) return;
        int targetArea = target.getWidth() * target.getHeight();
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == null) continue;
            int area = child.getWidth() * child.getHeight();
            if (child.getBackground() != null && targetArea > 0
                    && area >= targetArea * FULL_BLEED_MIN_COVERAGE) {
                if (!takenOverDescendants.containsKey(child)) {
                    takenOverDescendants.put(child, child.getBackground());
                }
            }
            if (child instanceof ViewGroup) collectFullBleed((ViewGroup) child, depth + 1);
        }
    }

    /**
     * What the wrapped view itself contributes: an edge, or nothing at all.
     *
     * <p>Never the fill. That belongs to the captured layer beneath, and drawing it in both places
     * makes the surface read as twice the opacity the user configured.</p>
     */
    private Drawable targetBackground(GlassSpec spec, boolean lensedNow, float density) {
        if (lensedNow) {
            // The lens derives its rim from the same distance field it refracts with, so it
            // already traces the true outline. A stroke over that is a second edge at a slightly
            // different radius, and reads as the drawn border it is.
            return new ColorDrawable(Color.TRANSPARENT);
        }
        GradientDrawable edge = new GradientDrawable();
        edge.setShape(GradientDrawable.RECTANGLE);
        edge.setCornerRadius(cornerRadiusPx());
        edge.setColor(Color.TRANSPARENT);
        edge.setStroke(Math.max(1, Math.round(spec.strokeWidthDp * density)), spec.strokeColor);
        return edge;
    }

    /** How much blur the capture itself should do, given whether a lens is actually running. */
    private void applyCaptureBlur(GlassSpec spec, boolean lensRunning) {
        try {
            float wanted = lensRunning
                    ? LiquidLens.CAPTURE_BLUR_RADIUS
                    : Math.max(1f, GlassRenderer.blurRadius(spec));
            if (captureRadius != null && Math.abs(captureRadius - wanted) < 0.01f) return;
            blurView.setBlurRadius(wanted);
            captureRadius = wanted;
        } catch (Throwable ignored) {
        }
    }

    private void applyElevation(boolean lensedNow) {
        float density = density();
        host.setElevation((lensedNow ? LENSED_ELEVATION_DP : ELEVATION_DP) * density);
        host.setTranslationZ((lensedNow ? LENSED_TRANSLATION_Z_DP : TRANSLATION_Z_DP) * density);
    }

    /** Takes one throttled reading of what is behind the surface, and repaints if it moved. */
    private void sampleBackdrop() {
        if (detached) return;
        try {
            if (sampler == null) sampler = new BackdropSampler();
            View root = blurRoot != null ? blurRoot : host.getRootView();
            int sampled = sampler.sample(root, host, SystemClock.uptimeMillis());
            if (sampled == 0 || sampled == backdropColor) return;
            backdropColor = sampled;

            GlassSpec spec = currentSpec();
            if (spec == null) return;
            // The adapted tint reaches a lensed surface through the shader's uniform, and
            // fillColor is part of the lens's cache key, so re-applying is what installs it.
            if (lensed) {
                LiquidLens.apply(blurView, spec, cornerRadiusPx(), density());
            }
            paint(spec, lensed);
            host.invalidate();
        } catch (Throwable ignored) {
        }
    }

    /**
     * The wrapper, with one job beyond holding the target: staying out of the capture.
     *
     * <p>The blur library captures by drawing the whole root tree into a bitmap, and it skips only
     * the capture view itself. The target sits <em>beside</em> that view rather than inside it, so
     * it is captured — and a blurred copy of the surface's own contents is then painted underneath
     * the sharp ones. On the conversation input row that measured as an 8-luma skirt reaching 40px
     * out from each icon, and as a blurred ghost of the camera button left behind after WhatsApp
     * hid it.</p>
     *
     * <p>The capture is told apart from a real frame by its canvas: the library draws into a
     * software canvas backed by a bitmap, while a view in a hardware-accelerated window is drawn
     * into a hardware one. So a software canvas in a hardware window is the capture pass, and this
     * host draws nothing into it. A window that is genuinely software-rendered is left alone,
     * because there the test cannot tell the two apart and a surface that never draws is a worse
     * failure than a halo.</p>
     *
     * <p>This is also what frees the caller from finding an ancestor that draws the content but
     * does not contain this host. For most surfaces no such ancestor exists.</p>
     */
    private static final class CaptureExcludedHost extends FrameLayout {

        CaptureExcludedHost(Context context) {
            super(context);
        }

        @Override
        protected void dispatchDraw(android.graphics.Canvas canvas) {
            if (isHardwareAccelerated() && !canvas.isHardwareAccelerated()) return;
            super.dispatchDraw(canvas);
        }
    }

    private static GradientDrawable outlineShape(float cornerRadiusPx) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(cornerRadiusPx);
        shape.setColor(Color.TRANSPARENT);
        return shape;
    }

    private static boolean isNightMode(Context context) {
        if (context == null) return false;
        int mode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }
}
