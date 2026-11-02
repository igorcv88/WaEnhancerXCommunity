package com.waenhancer.theme;

/**
 * Detects when a shared-wallpaper bubble moved without re-running its {@code draw()}.
 *
 * <p>RecyclerView scrolls by offsetting row RenderNodes and replays their recorded display
 * lists; the BitmapShader matrix recorded in the last draw would keep sampling the old wallpaper
 * region. The pre-draw pass compares the current texture-to-local transform with the one used in
 * the last draw and invalidates only rows that moved, so the row re-records with the same shared
 * texture (no re-blur, no reallocation).</p>
 */
public final class TextureAlignment {
    /** Sub-pixel translation is below sampling resolution. */
    public static final float EPSILON_PX = 0.5f;
    private final float[] drawn = new float[9];
    private boolean valid;

    public void recordDrawn(float[] values) {
        System.arraycopy(values, 0, drawn, 0, 9);
        valid = true;
    }

    public void reset() {
        valid = false;
    }

    /** True when the last draw used a different transform than {@code current}. */
    public boolean moved(float[] current) {
        if (!valid) return false; // nothing presented yet: the first draw computes it
        return !same(drawn, current, EPSILON_PX);
    }

    static boolean same(float[] a, float[] b, float epsilonPx) {
        // Translation in pixels; scale/skew are unitless and must match closely.
        if (Math.abs(a[2] - b[2]) > epsilonPx || Math.abs(a[5] - b[5]) > epsilonPx) return false;
        int[] linear = {0, 1, 3, 4, 6, 7, 8};
        for (int i : linear) if (Math.abs(a[i] - b[i]) > 1e-4f) return false;
        return true;
    }
}
