package com.waenhancer.xposed.features.customization;

import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import com.waenhancer.theme.GlassProbe;

/** Opt-in footer-only metadata. Independent quota survives the general capture log budget. */
final class ComposerBackingProbe {
    private static long window = -1;
    private static int gates, properties, nodes;
    private static final java.util.WeakHashMap<View, String> lastGates = new java.util.WeakHashMap<>();
    private long until;
    private boolean snapshot;
    private static boolean allow(int kind) {
        long now = SystemClock.uptimeMillis();
        if (!GlassProbe.enabled()) return false;
        if (window < 0 || now - window >= 60000) { window = now; gates = properties = nodes = 0; lastGates.clear(); }
        if (kind == 0) return gates++ < 40;
        if (kind == 1) return properties++ < 48;
        return nodes++ < 128;
    }
    void arm() { until = SystemClock.uptimeMillis() + 1500; snapshot = true; }
    void property(View view) {
        if (SystemClock.uptimeMillis() <= until && allow(1)) emit("COMPOSER_PROPERTY", view, "");
    }
    static void gate(View view, String detail) {
        if (!GlassProbe.enabled()) return;
        if (detail.equals(lastGates.get(view)) && SystemClock.uptimeMillis() - window < 60000) return;
        if (allow(0)) { lastGates.put(view, detail); emit("INPUT_BACKING_GATE", view, detail); }
    }
    void snapshot(ViewGroup footer, String route) {
        if (!snapshot || !GlassProbe.enabled() || SystemClock.uptimeMillis() > until) return;
        snapshot = false;
        walk(footer, route, 0, new int[]{32});
    }
    private static void walk(View view, String route, int depth, int[] remaining) {
        if (depth > 8 || remaining[0]-- <= 0) return;
        if (allow(2)) emit("COMPOSER_BACKING_NODE", view, "route=" + route + " depth=" + depth);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount() && remaining[0] > 0; i++)
            walk(((ViewGroup) view).getChildAt(i), route, depth + 1, remaining);
    }
    private static void emit(String event, View view, String detail) {
        int index = view.getParent() instanceof ViewGroup ? ((ViewGroup) view.getParent()).indexOfChild(view) : -1;
        Log.i(GlassProbe.TAG, "event=" + event + " " + GlassProbe.describe(view)
                + " fg=" + GlassProbe.drawable(view.getForeground())
                + " tint=" + (view.getBackgroundTintList() == null ? "none"
                        : "ColorStateList@" + Integer.toHexString(System.identityHashCode(view.getBackgroundTintList())))
                + " tintMode=" + view.getBackgroundTintMode() + " alpha=" + view.getAlpha()
                + " z=" + view.getZ() + " index=" + index + " " + detail);
    }
}
