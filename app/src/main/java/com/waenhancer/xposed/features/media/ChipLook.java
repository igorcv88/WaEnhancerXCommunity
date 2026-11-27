package com.waenhancer.xposed.features.media;

/**
 * Reads the look of a rendered native chip from its pixels (pure Java, JVM-tested).
 *
 * <p>WhatsApp's chips are Material {@code Chip}s: the view is a 48dp touch target and the visible
 * pill is drawn inset inside it by a {@code ChipDrawable} with its own fill, outline and text
 * colours, which the view's public text attributes do not report. Instead of depending on
 * obfuscated Chip methods, the hook draws one native chip into a bitmap and this class derives
 * the visible pill, its fill, outline, outline width, content (text/icon) colour and content
 * padding from the pixels. Every value has a sanity check; a failed read returns {@code null}
 * and the caller keeps its fallback.</p>
 */
final class ChipLook {
    /** Visible pill box inside the view, in px. */
    final int left, top, right, bottom;
    final int fill, stroke, strokeWidth, content;
    /** Distance from the pill's left edge to the first content pixel, and from the last one to the right edge. */
    final int contentStart, contentEnd;

    private ChipLook(int left, int top, int right, int bottom, int fill, int stroke, int strokeWidth, int content,
                     int contentStart, int contentEnd) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.fill = fill;
        this.stroke = stroke;
        this.strokeWidth = strokeWidth;
        this.content = content;
        this.contentStart = contentStart;
        this.contentEnd = contentEnd;
    }

    int height() {
        return bottom - top;
    }

    /** Colours closer than this (sum of channel differences, alpha included) count as equal. */
    static final int SAME = 48;

    /**
     * @param argb row-major pixels of the chip view drawn on a transparent bitmap
     * @param minPillPx the smallest plausible visible pill height (e.g. 20dp)
     */
    static ChipLook read(int[] argb, int width, int height, int minPillPx) {
        if (argb == null || width <= 0 || height <= 0 || argb.length < width * height) return null;
        // Visible pill: rows and columns that hold any non-transparent pixel.
        int top = -1, bottom = -1, left = width, right = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (alpha(argb[y * width + x]) > 16) {
                    if (top < 0) top = y;
                    bottom = y;
                    if (x < left) left = x;
                    if (x > right) right = x;
                }
            }
        }
        if (top < 0 || bottom - top + 1 < minPillPx || right - left + 1 < minPillPx) return null;
        bottom += 1;
        right += 1;
        int h = bottom - top;
        int cx = (left + right) / 2;
        // Fill: above the text line, below the outline.
        int fill = argb[(top + Math.max(1, Math.round(h * 0.2f))) * width + cx];
        // Outline: the pixels from the top edge down that differ from the fill.
        int strokeWidth = 0;
        int stroke = 0;
        for (int y = top; y < top + h / 4; y++) {
            int pixel = argb[y * width + cx];
            if (distance(pixel, fill) <= SAME) break;
            if (strokeWidth == 0 || distance(pixel, fill) > distance(stroke, fill)) stroke = pixel;
            strokeWidth++;
        }
        // Content band (icon + text), between the outlines.
        int bandTop = top + Math.round(h * 0.3f);
        int bandBottom = bottom - Math.round(h * 0.3f);
        int inner = Math.max(strokeWidth, 1) + Math.round(h * 0.25f); // past the rounded ends
        int first = -1, last = -1, content = 0, best = -1;
        for (int x = left + inner; x < right - inner; x++) {
            for (int y = bandTop; y < bandBottom; y++) {
                int pixel = argb[y * width + x];
                int d = distance(pixel, fill);
                if (d <= SAME) continue;
                if (first < 0) first = x;
                last = x;
                if (d > best) {
                    best = d;
                    content = pixel;
                }
            }
        }
        if (first < 0 || best < 96) return null; // no readable text/icon
        return new ChipLook(left, top, right, bottom, fill, strokeWidth == 0 ? 0 : stroke, strokeWidth,
                content | 0xFF000000, first - left, right - 1 - last);
    }

    static int alpha(int color) {
        return color >>> 24;
    }

    static int distance(int a, int b) {
        return Math.abs((a >>> 24) - (b >>> 24)) + Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF))
                + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)) + Math.abs((a & 0xFF) - (b & 0xFF));
    }
}
