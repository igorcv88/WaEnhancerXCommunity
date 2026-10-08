package com.waenhancer.theme;

/**
 * First-order smoothing of an adaptation colour, in linear light:
 * {@code x += (1 - e^(-Δt/τ)) · (target - x)}.
 *
 * <p>Used for the floating bar's sampled backdrop colour, which arrives as a step every 250 ms.
 * Applied to the parameter, never to the backdrop image, so it cannot leave trails. τ is
 * {@link #TAU_MS}, inside the 80–160 ms range the optical report proposes: fast enough that
 * legibility follows a bright image entering under the bar, slow enough that a scroll does not
 * flicker the tint.</p>
 */
public final class ColorSmoother {

    public static final double TAU_MS = 120d;
    /** Closer than this (linear, per channel) counts as arrived. */
    static final double EPSILON = 0.002d;

    private final double[] current = new double[3];
    private boolean has;
    private long lastMs;

    /** Moves toward {@code target} for the time since the last step; returns the colour now. */
    public int step(int target, long nowMs) {
        double[] goal = linear(target);
        if (!has) {
            System.arraycopy(goal, 0, current, 0, 3);
            has = true;
            lastMs = nowMs;
            return target;
        }
        double dt = Math.max(0d, nowMs - lastMs);
        lastMs = nowMs;
        double k = 1d - Math.exp(-dt / TAU_MS);
        for (int i = 0; i < 3; i++) current[i] += k * (goal[i] - current[i]);
        if (converged(target)) {
            System.arraycopy(goal, 0, current, 0, 3);
            return target;
        }
        return encode();
    }

    /** Whether the smoothed colour has reached {@code target}. */
    public boolean converged(int target) {
        if (!has) return true;
        double[] goal = linear(target);
        for (int i = 0; i < 3; i++) if (Math.abs(goal[i] - current[i]) > EPSILON) return false;
        return true;
    }

    private int encode() {
        int r = (int) Math.round(LensModel.fromLinear(current[0]) * 255d);
        int g = (int) Math.round(LensModel.fromLinear(current[1]) * 255d);
        int b = (int) Math.round(LensModel.fromLinear(current[2]) * 255d);
        return 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static double[] linear(int color) {
        return new double[]{
                LensModel.toLinear(((color >> 16) & 0xFF) / 255d),
                LensModel.toLinear(((color >> 8) & 0xFF) / 255d),
                LensModel.toLinear((color & 0xFF) / 255d)};
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }
}
