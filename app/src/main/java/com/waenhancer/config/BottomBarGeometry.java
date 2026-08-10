package com.waenhancer.config;

/**
 * Resolved vertical geometry of the floating bottom bar.
 *
 * <p>This is the single answer to "how tall is the pill, and where does the content sit inside
 * it". It exists because the editor's preview and the hooked bar previously each decided that
 * for themselves, and disagreed in both directions:</p>
 *
 * <ul>
 *   <li>In automatic mode the hooked bar handed {@code WRAP_CONTENT} to WhatsApp's tab frame.
 *       That view is a custom {@code ViewGroup} that does not implement {@code wrap_content}, so
 *       its measure pass fell through to {@code View.getDefaultSize}, which under an
 *       {@code AT_MOST} spec returns the <em>entire</em> available height — a pill as tall as
 *       the window. The preview, being an ordinary {@code LinearLayout}, wrapped correctly and
 *       showed nothing of the sort. Automatic height is therefore computed here, as an exact
 *       pixel figure, and the bar is never asked to measure itself.</li>
 *   <li>In manual mode the outer height was forced but the content inside was left alone, so a
 *       pill shorter than its natural content drew the label on top of the icon instead of
 *       fitting to the space. The content is now fitted, by the ladder in
 *       {@link #resolve}.</li>
 * </ul>
 *
 * <p>Free of Android types so it is covered by JVM tests, in the same spirit as
 * {@link com.waenhancer.theme.GlassSpec}: a preview and the real surface cannot disagree about
 * geometry they did not each compute.</p>
 */
public final class BottomBarGeometry {

    /** How far the icon may be shrunk to fit a short manual height, in dp. */
    public static final int MIN_ICON_DP = 12;

    /** Multiplier from label text size to the line box a single-line label occupies. */
    private static final float LABEL_LINE_FACTOR = 1.30f;

    /** Outer height of the pill in pixels, padding included. Always an exact figure. */
    public final int pillHeightPx;
    /** Vertical padding actually applied; may be below the requested value on a short pill. */
    public final int verticalPaddingPx;
    /** Icon side actually applied; may be below the requested value on a short pill. */
    public final int iconSizePx;
    /** Icon-to-label gap actually applied; the first thing given up when space runs out. */
    public final int spacingPx;
    /** Height of the label's line box, or {@code 0} when the label had to be dropped. */
    public final int labelHeightPx;
    /** False when the pill is too short to show a label at all. */
    public final boolean labelVisible;
    /** True when the requested metrics did not fit and something above was reduced. */
    public final boolean compressed;

    private BottomBarGeometry(int pillHeightPx, int verticalPaddingPx, int iconSizePx,
                              int spacingPx, int labelHeightPx, boolean labelVisible,
                              boolean compressed) {
        this.pillHeightPx = pillHeightPx;
        this.verticalPaddingPx = verticalPaddingPx;
        this.iconSizePx = iconSizePx;
        this.spacingPx = spacingPx;
        this.labelHeightPx = labelHeightPx;
        this.labelVisible = labelVisible;
        this.compressed = compressed;
    }

    /**
     * Resolves the geometry for one bar.
     *
     * <p>In automatic mode the pill is exactly as tall as its content needs and nothing is
     * reduced. In manual mode the height is the user's, and the content is fitted into it by
     * giving up, in order: the icon-to-label gap, then the vertical padding, then icon size down
     * to {@link #MIN_ICON_DP}, and finally the label itself. The order is deliberate — the gap is
     * decoration, the icon still identifies the tab at 12dp, and a clipped label is worse than no
     * label.</p>
     *
     * @param manualHeight      true when the user chose a fixed height
     * @param manualHeightDp    that fixed height
     * @param iconSizeDp        requested icon side
     * @param spacingDp         requested icon-to-label gap
     * @param labelTextSp       label text size
     * @param paddingVerticalDp requested padding above and below the content
     * @param density           display density
     * @param fontScale         the user's font scale, so a large-text device is not clipped
     */
    public static BottomBarGeometry resolve(boolean manualHeight, int manualHeightDp,
                                            int iconSizeDp, int spacingDp, float labelTextSp,
                                            int paddingVerticalDp, float density,
                                            float fontScale) {
        float scale = density <= 0f ? 1f : density;
        float fonts = fontScale <= 0f ? 1f : fontScale;

        int icon = px(iconSizeDp, scale);
        int spacing = px(spacingDp, scale);
        int padding = px(paddingVerticalDp, scale);
        int label = Math.round(labelTextSp * scale * fonts * LABEL_LINE_FACTOR);
        int minIcon = px(MIN_ICON_DP, scale);

        if (!manualHeight) {
            int height = 2 * padding + icon + spacing + label;
            return new BottomBarGeometry(height, padding, icon, spacing, label, true, false);
        }

        int height = Math.max(px(manualHeightDp, scale), minIcon);
        boolean compressed = false;

        // 1. The gap goes first: it is decoration, and losing it costs no information.
        int inner = height - 2 * padding;
        if (icon + spacing + label > inner) {
            spacing = Math.max(0, inner - icon - label);
            compressed = true;
        }

        // 2. Then the padding, down to nothing.
        if (icon + spacing + label > height - 2 * padding) {
            padding = Math.max(0, (height - icon - spacing - label) / 2);
            compressed = true;
        }

        // 3. Then the icon, but never below the size at which it still reads as an icon.
        inner = height - 2 * padding;
        if (icon + spacing + label > inner) {
            icon = Math.max(minIcon, inner - spacing - label);
            compressed = true;
        }

        // 4. Only then the label. A clipped label is worse than an honest icon-only bar.
        inner = height - 2 * padding;
        boolean labelVisible = true;
        if (icon + spacing + label > inner) {
            labelVisible = false;
            label = 0;
            spacing = 0;
            icon = Math.max(minIcon, Math.min(icon, inner));
            compressed = true;
        }

        return new BottomBarGeometry(height, padding, icon, spacing, label, labelVisible,
                compressed);
    }

    /** Height available to the tab content once the pill's own padding is taken out. */
    public int contentHeightPx() {
        return Math.max(0, pillHeightPx - 2 * verticalPaddingPx);
    }

    /** Height the icon, gap and label occupy together. */
    public int naturalContentHeightPx() {
        return iconSizePx + spacingPx + labelHeightPx;
    }

    /**
     * Top offset that centres the content in the space available to it.
     *
     * <p>The tab items are stretched to {@link #contentHeightPx()} so their own layout has a
     * definite box to work in; this is the slack left over when the content is shorter than that
     * box.</p>
     */
    public int contentTopOffsetPx() {
        return Math.max(0, (contentHeightPx() - naturalContentHeightPx()) / 2);
    }

    private static int px(int dp, float density) {
        return Math.max(0, (int) (dp * density + 0.5f));
    }
}
