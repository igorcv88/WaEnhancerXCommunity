package com.waenhancer.theme;

import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.view.View;

/**
 * The refracting rim of a liquid-glass surface.
 *
 * <p>Blur alone cannot make glass look liquid. A blurred pane hides its backdrop evenly, which is
 * exactly what frost does; a lens leaves the middle nearly clear and does its work at the edge,
 * where it bends the backdrop inward and gathers the light passing through it. That bend is the
 * difference between the two materials, and it is what this class adds.</p>
 *
 * <p>Implemented as a {@link RuntimeShader} run over the blur view's own output, so what it
 * displaces is the real backdrop the blur already produced rather than an approximation of it.
 * {@code RuntimeShader} arrived in Android 13; {@link #isSupported()} reports whether this device
 * can have it, and callers fall back to {@link GlassRenderer}'s layered rim, which reads as a lit
 * bevel rather than true refraction.</p>
 */
public final class LiquidLens {

    /**
     * The lens.
     *
     * <p>Distance to the rounded-rectangle edge drives everything. {@code depth} is 0 at the very
     * edge and 1 once we are a full rim width inside, so the displacement falls away quadratically
     * from the edge and is gone by the time it reaches the body of the pill — a lens profile,
     * not a uniform warp. The backdrop is sampled from further in than the pixel being drawn,
     * which is what makes content appear to compress and slide as it passes under the rim.</p>
     *
     * <p>The light term is the same profile raised much higher, so it is confined to a thin band
     * right at the edge: that is the caustic a real pane concentrates where it is thickest. It is
     * scaled by the sampled alpha to stay premultiplied.</p>
     */
    private static final String SHADER = ""
            + "uniform shader content;\n"
            + "uniform float2 uSize;\n"
            + "uniform float uRadius;\n"
            + "uniform float uRim;\n"
            + "uniform float uStrength;\n"
            + "uniform float uLight;\n"
            + "\n"
            + "float sdRoundRect(float2 p, float2 halfSize, float r) {\n"
            + "    float2 q = abs(p) - halfSize + r;\n"
            + "    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 halfSize = uSize * 0.5;\n"
            + "    float2 p = coord - halfSize;\n"
            + "    float radius = min(uRadius, min(halfSize.x, halfSize.y));\n"
            + "    float d = sdRoundRect(p, halfSize, radius);\n"
            + "    float depth = clamp(-d / max(uRim, 1.0), 0.0, 1.0);\n"
            + "    float profile = 1.0 - depth;\n"
            + "    float bend = profile * profile * uStrength * uRim;\n"
            + "    float2 dir = p / max(length(p), 0.0001);\n"
            + "    half4 c = content.eval(coord - dir * bend);\n"
            + "    float light = pow(profile, 6.0) * uLight;\n"
            + "    return half4(c.rgb + half3(light) * c.a, c.a);\n"
            + "}\n";

    /** Cap on the displacement, as a fraction of the rim width. Beyond this the rim smears. */
    private static final float MAX_DISPLACEMENT = 0.55f;

    /** Brightness of the caustic band at full lens strength. */
    private static final float MAX_LIGHT = 0.22f;

    /**
     * What each view's current effect was built from.
     *
     * <p>The shader bakes in the view's size and radius, so it has to be rebuilt when those
     * change — but callers refresh on every layout pass, and allocating a {@code RuntimeShader}
     * and a {@code RenderEffect} per pass would put that cost on every frame of a scroll for no
     * visible difference.</p>
     */
    private static final java.util.WeakHashMap<View, String> installed = new java.util.WeakHashMap<>();

    private LiquidLens() { }

    /** Whether this device can run the lens at all. */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    /**
     * Puts the lens on {@code view}, or takes it off when the spec does not call for one.
     *
     * <p>Safe to call on every layout pass: the effect is rebuilt only because the view's size and
     * radius are baked into it, and both change with the bar's geometry.</p>
     *
     * @param view           the view whose rendered output is the backdrop to bend
     * @param spec           the resolved surface
     * @param cornerRadiusPx the surface's corner radius, already clamped to the view
     * @param density        display density
     * @return true when a lens is now installed
     */
    public static boolean apply(View view, GlassSpec spec, float cornerRadiusPx, float density) {
        if (view == null) return false;
        if (!isSupported() || spec == null || spec.lensStrength <= 0f) {
            clear(view);
            return false;
        }
        int width = view.getWidth();
        int height = view.getHeight();
        if (width <= 0 || height <= 0) return false;

        String key = width + "x" + height + ":" + cornerRadiusPx + ":" + spec.lensStrength
                + ":" + spec.rimWidthDp + ":" + density;
        if (key.equals(installed.get(view))) return true;

        try {
            float rim = Math.max(1f, spec.rimWidthDp * density);
            // A rim wider than half the bar would have the two edges bending into each other.
            rim = Math.min(rim, height * 0.5f);

            RuntimeShader shader = new RuntimeShader(SHADER);
            shader.setFloatUniform("uSize", width, height);
            shader.setFloatUniform("uRadius", cornerRadiusPx);
            shader.setFloatUniform("uRim", rim);
            shader.setFloatUniform("uStrength", spec.lensStrength * MAX_DISPLACEMENT);
            shader.setFloatUniform("uLight", spec.lensStrength * MAX_LIGHT);

            view.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"));
            installed.put(view, key);
            return true;
        } catch (Throwable ignored) {
            // A device that refuses the shader keeps the layered rim rather than losing the bar.
            clear(view);
            return false;
        }
    }

    /** Removes any lens previously installed on {@code view}. */
    public static void clear(View view) {
        if (view == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        try {
            view.setRenderEffect(null);
            installed.remove(view);
        } catch (Throwable ignored) {
        }
    }
}
