package com.waenhancer.theme;

/** A safe wallpaper is useful decoration, but does not substitute for a required message source. */
public enum CaptureContent {
    CAPTURE_LIVE_CONTENT, CAPTURE_WALLPAPER_ONLY, SOURCE_UNAVAILABLE;

    public static CaptureContent classify(boolean safeSource, boolean safeWallpaper) {
        return safeSource ? CAPTURE_LIVE_CONTENT : safeWallpaper ? CAPTURE_WALLPAPER_ONLY : SOURCE_UNAVAILABLE;
    }

    public boolean completesRecovery(boolean requiresLiveContent) {
        return this == CAPTURE_LIVE_CONTENT || !requiresLiveContent && this == CAPTURE_WALLPAPER_ONLY;
    }
}
