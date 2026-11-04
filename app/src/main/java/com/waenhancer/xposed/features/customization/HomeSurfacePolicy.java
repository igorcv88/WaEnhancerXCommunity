package com.waenhancer.xposed.features.customization;

/** Small, pure lifecycle decisions shared by the home glass and FAB hooks. */
public final class HomeSurfacePolicy {
    private HomeSurfacePolicy() { }

    /** Styling must not reveal a FAB which WhatsApp hid for the previous page. */
    public static boolean mayStyleFab(boolean nativeVisible) {
        return nativeVisible;
    }

    /** A newly visible search bar must acquire a live backdrop on its first frame. */
    public static boolean captureNewSearch(boolean search, boolean eligible,
                                           boolean wasVisible, boolean mayCapture) {
        return search && eligible && !wasVisible && mayCapture;
    }
}
