package com.waenhancer.theme;

/** One reference colour. No surface samples, contrast policy or per-control colour decisions. */
public final class ComposerPalette {
    public static final int CURRENT_PROFILE_ICON = 0xffffffff;
    private int color = CURRENT_PROFILE_ICON;
    public int color() { return color; }
    public boolean update(Integer effectiveMicColor) {
        if (effectiveMicColor == null || color == effectiveMicColor) return false;
        color = effectiveMicColor;
        return true;
    }
}
