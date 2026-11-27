package com.waenhancer.xposed.features.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;

public class ChipLookTest {
    private static final int W = 300, H = 144;
    private static final int FILL = 0xFF0B1014, STROKE = 0xFF2E353A, TEXT = 0xFFC8CDD1;

    /** A 48dp-style view (144px) with a 96px pill inset 24px vertically and 6px horizontally. */
    private static int[] chip(int fill, int stroke, int strokeWidth, boolean content) {
        int[] px = new int[W * H];
        for (int y = 24; y < 120; y++) {
            for (int x = 6; x < 294; x++) {
                boolean edge = y < 24 + strokeWidth || y >= 120 - strokeWidth || x < 6 + strokeWidth || x >= 294 - strokeWidth;
                px[y * W + x] = edge ? stroke : fill;
            }
        }
        if (content) {
            fillRect(px, 40, 50, 91, 95, TEXT);   // icon
            fillRect(px, 110, 55, 251, 91, TEXT); // label
            px[70 * W + 200] = 0xFF8A9094;        // anti-aliased edge pixel: not the content colour
        }
        return px;
    }

    private static void fillRect(int[] px, int l, int t, int r, int b, int color) {
        for (int y = t; y < b; y++) Arrays.fill(px, y * W + l, y * W + r, color);
    }

    @Test
    public void readsTheInsetPillItsColoursAndContentPadding() {
        ChipLook look = ChipLook.read(chip(FILL, STROKE, 3, true), W, H, 60);
        assertNotNull(look);
        assertEquals(6, look.left);
        assertEquals(24, look.top);
        assertEquals(294, look.right);
        assertEquals(120, look.bottom);
        assertEquals("visible pill, not the 48dp touch target", 96, look.height());
        assertEquals(FILL, look.fill);
        assertEquals(STROKE, look.stroke);
        assertEquals(3, look.strokeWidth);
        assertEquals("strongest content pixel, not an anti-aliased one", TEXT, look.content);
        assertEquals(34, look.contentStart);
        assertEquals(43, look.contentEnd);
    }

    @Test
    public void transparentFillWithOutlineAndFilledChipWithoutOutline() {
        ChipLook outlined = ChipLook.read(chip(0x00000000, STROKE, 2, true), W, H, 60);
        assertNotNull(outlined);
        assertEquals(0x00000000, outlined.fill);
        assertEquals(2, outlined.strokeWidth);
        assertEquals(TEXT, outlined.content);

        ChipLook filled = ChipLook.read(chip(0xFF103529, 0xFF103529, 0, true), W, H, 60);
        assertNotNull(filled);
        assertEquals(0, filled.strokeWidth);
        assertEquals(0, filled.stroke);
    }

    @Test
    public void unreadableChipsReturnNull() {
        assertNull("nothing drawn", ChipLook.read(new int[W * H], W, H, 60));
        assertNull("pill smaller than plausible", ChipLook.read(chip(FILL, STROKE, 3, true), W, H, 200));
        assertNull("no text or icon", ChipLook.read(chip(FILL, STROKE, 3, false), W, H, 60));
        assertNull(ChipLook.read(null, W, H, 60));
        assertNull("short buffer", ChipLook.read(new int[10], W, H, 60));
    }
}
