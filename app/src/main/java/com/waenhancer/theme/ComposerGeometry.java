package com.waenhancer.theme;

/** Keeps the composer capsule clear of the native action disc, including RTL layouts. */
public final class ComposerGeometry {
    private ComposerGeometry() { }
    public static int[] separate(int left, int right, int top, int bottom,
                                 int actionLeft, int actionRight, int actionTop, int actionBottom, int gap) {
        if (actionBottom <= top || actionTop >= bottom) {
            return new int[]{left, right};
        }
        if ((long) actionLeft + actionRight >= (long) left + right) right = Math.min(right, actionLeft - gap);
        else left = Math.max(left, actionRight + gap);
        return new int[]{left, Math.max(left, right)};
    }
}
