package com.waenhancer.theme;

import android.os.SystemClock;
import android.util.Log;
import android.view.View;

/** Opt-in metadata only, with a quota independent of recorder/capture spam. */
public final class BubbleLifecycleProbe {
    private static long window = -1;
    private static int lifecycle, presentation;
    private BubbleLifecycleProbe() { }
    public static void event(String reason, View row, Object owner, String detail) {
        if (!GlassProbe.enabled()) return;
        long now = SystemClock.uptimeMillis();
        if (window < 0 || now - window >= 60000) { window = now; lifecycle = presentation = 0; }
        boolean presenting = reason.equals("first-presented") || reason.equals("target-invalidate");
        if (presenting ? presentation++ >= 120 : lifecycle++ >= 120) return;
        Log.i(GlassProbe.TAG, "event=BUBBLE_LIFECYCLE t=" + now + " reason=" + reason
                + " row=" + GlassTrace.id(row) + " owner=" + GlassTrace.id(owner) + " " + detail);
    }
}
