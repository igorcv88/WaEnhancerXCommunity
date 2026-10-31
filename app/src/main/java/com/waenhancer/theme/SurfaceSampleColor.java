package com.waenhancer.theme;

/** Dominant quantized colour: sparse text/controls must not choose their own contrast. */
public final class SurfaceSampleColor {
    private SurfaceSampleColor() { }
    public static int dominant(int[] pixels) {
        int[] counts = new int[512];
        int best = 0;
        for (int c : pixels) {
            int key = ((c >>> 21) & 7) << 6 | ((c >>> 13) & 7) << 3 | ((c >>> 5) & 7);
            if (++counts[key] > counts[best]) best = key;
        }
        long r = 0, g = 0, b = 0; int count = 0;
        for (int c : pixels) {
            int key = ((c >>> 21) & 7) << 6 | ((c >>> 13) & 7) << 3 | ((c >>> 5) & 7);
            if (key != best) continue;
            r += (c >>> 16) & 255; g += (c >>> 8) & 255; b += c & 255; count++;
        }
        return count == 0 ? 0 : 0xff000000 | (int)(r / count) << 16 | (int)(g / count) << 8 | (int)(b / count);
    }
}
