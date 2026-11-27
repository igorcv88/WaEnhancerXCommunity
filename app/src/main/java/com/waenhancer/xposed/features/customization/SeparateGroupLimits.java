package com.waenhancer.xposed.features.customization;

/** Navigation item limit for Separate Groups, kept free of Android types for unit tests. */
public final class SeparateGroupLimits {
    /** Room for the Groups tab next to the native ones. */
    public static final int MIN_NAVIGATION_ITEMS = 6;

    private SeparateGroupLimits() {}

    /** A larger native limit (the tablet navigation rail allows 7) is kept as is. */
    public static int atLeastNavigationItems(Object nativeLimit) {
        int limit = nativeLimit instanceof Integer ? (Integer) nativeLimit : 0;
        return Math.max(limit, MIN_NAVIGATION_ITEMS);
    }
}
