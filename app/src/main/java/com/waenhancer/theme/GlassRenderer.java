package com.waenhancer.theme;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * Paints the surface a {@link GlassSpec} describes.
 *
 * <p>Everything here is presentation: the decisions were already made by the spec. Keeping the
 * split means the bottom bar in the WhatsApp process, the settings preview and any future glass
 * surface share one description of glass and differ only in what they draw it on.</p>
 */
public final class GlassRenderer {

    private GlassRenderer() { }

    /**
     * Builds the layered background for a glass surface.
     *
     * <p>Bottom to top: the translucent fill and its luminous edge, then the refraction glow
     * rising from the lower edge, then the specular highlight along the top. Layers whose colour
     * the variant left transparent are skipped rather than drawn as no-ops, which is what keeps
     * the overdraw of {@code STABLE} identical to the flat background it replaces.</p>
     *
     * @param cornerRadiusPx corner radius in pixels, already scaled by density
     */
    public static Drawable background(GlassSpec spec, float cornerRadiusPx, float density) {
        GradientDrawable base = new GradientDrawable();
        base.setShape(GradientDrawable.RECTANGLE);
        base.setCornerRadius(cornerRadiusPx);
        base.setColor(spec.fillColor);
        base.setStroke(Math.max(1, Math.round(spec.strokeWidthDp * density)), spec.strokeColor);

        boolean hasRefraction = alpha(spec.refractionColor) > 0;
        boolean hasHighlight = alpha(spec.highlightColor) > 0;
        if (!hasRefraction && !hasHighlight) {
            return base;
        }

        java.util.ArrayList<Drawable> layers = new java.util.ArrayList<>(3);
        layers.add(base);

        if (hasRefraction) {
            // Rises from the bottom edge, where a real pane would gather the light bent through
            // it. Drawn beneath the highlight so the two do not cancel out in the middle.
            GradientDrawable refraction = new GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{spec.refractionColor, transparent(spec.refractionColor)});
            refraction.setShape(GradientDrawable.RECTANGLE);
            refraction.setCornerRadius(cornerRadiusPx);
            layers.add(refraction);
        }

        if (hasHighlight) {
            GradientDrawable highlight = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{spec.highlightColor, transparent(spec.highlightColor)});
            highlight.setShape(GradientDrawable.RECTANGLE);
            highlight.setCornerRadius(cornerRadiusPx);
            layers.add(highlight);
        }

        LayerDrawable stack = new LayerDrawable(layers.toArray(new Drawable[0]));
        if (hasHighlight) {
            // Confine the specular band to the top edge. Left full-height it reads as a wash over
            // the whole surface instead of a lit edge.
            int index = layers.size() - 1;
            stack.setLayerInset(index, 0, 0, 0, Math.round(cornerRadiusPx * 1.5f));
        }
        return stack;
    }

    /**
     * Resolves a spec for a surface in the given context, filling in the two things only the
     * device can answer.
     *
     * <p>Every glass surface goes through here so blur capability and reduced motion are
     * detected once, in one place, instead of each surface deciding for itself.</p>
     */
    public static GlassSpec resolveFor(Context context, String variantKey, boolean dark,
                                       int fillColor, int accentColor, float opacityPercent) {
        return GlassSpec.resolve(GlassSpec.Variant.from(variantKey), dark, fillColor, accentColor,
                opacityPercent, blurSupported(context), reduceMotion(context));
    }

    /** Paints {@code view} as a glass surface. */
    public static void apply(android.view.View view, GlassSpec spec, float cornerRadiusDp) {
        if (view == null) return;
        float density = view.getResources().getDisplayMetrics().density;
        view.setBackground(background(spec, cornerRadiusDp * density, density));
    }

    /**
     * Whether this device should get real blur.
     *
     * <p>Blur is a per-frame readback of everything behind the surface. Low-RAM devices and
     * devices in power-save pay for that in dropped frames, so they get the opaque fallback
     * instead — {@link GlassSpec} already raises the fill opacity to compensate.</p>
     */
    public static boolean blurSupported(Context context) {
        if (context == null) return false;
        try {
            ActivityManager activity =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (activity != null && activity.isLowRamDevice()) return false;

            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (power != null && power.isPowerSaveMode()) return false;
        } catch (Throwable ignored) {
            // A host that denies these services is not a reason to refuse blur outright.
        }
        // Below S the RenderScript path still blurs; it is slower but present.
        return true;
    }

    /**
     * Whether the user has asked the system to reduce motion.
     *
     * <p>Read from the animation scales, which is the signal available to a hooked process on
     * every supported release; a scale of zero is the platform's own "animations off".</p>
     */
    public static boolean reduceMotion(Context context) {
        if (context == null) return false;
        try {
            float transition = Settings.Global.getFloat(context.getContentResolver(),
                    Settings.Global.TRANSITION_ANIMATION_SCALE, 1f);
            float animator = Settings.Global.getFloat(context.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            return transition == 0f || animator == 0f;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Blur radius in pixels for the blur library, or {@code 0} when the spec asked for none. */
    public static float blurRadiusPx(GlassSpec spec, float density) {
        if (spec.blurRadiusDp <= 0f) return 0f;
        // The blur libraries clamp above 25; going past it costs frames without looking blurrier.
        return Math.min(25f, spec.blurRadiusDp * Math.min(density, 1.5f));
    }

    private static int alpha(int color) {
        return (color >>> 24) & 0xFF;
    }

    private static int transparent(int color) {
        return color & 0x00FFFFFF;
    }
}
