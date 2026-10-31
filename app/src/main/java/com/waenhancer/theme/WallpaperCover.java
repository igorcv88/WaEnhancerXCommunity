package com.waenhancer.theme;

/** Uniform cover of an effective source rectangle, centred and clipped to the target. */
public final class WallpaperCover {
    private WallpaperCover() { }
    public static boolean calculate(float left, float top, float right, float bottom,
                                    float width, float height, float[] out) {
        float sw = right - left, sh = bottom - top;
        if (!(sw > 0 && sh > 0 && width > 0 && height > 0)
                || !Float.isFinite(sw + sh + width + height + left + top)) return false;
        float scale = Math.max(width / sw, height / sh);
        out[0] = scale;
        out[1] = width * .5f - (left + right) * .5f * scale;
        out[2] = height * .5f - (top + bottom) * .5f * scale;
        return true;
    }
}
