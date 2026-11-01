package com.waenhancer.xposed.features.customization;

import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import com.waenhancer.theme.GlassProbe;

/** Opt-in footer-only metadata. Independent quota survives the general capture log budget. */
final class ComposerBackingProbe {
    private static long window = -1;
    private static int gates, properties, nodes;
    private static final java.util.WeakHashMap<View, String> lastGates = new java.util.WeakHashMap<>();
    private long until;
    private boolean snapshot;
    private long voiceWindow = -1;
    private int voiceSnapshots;
    private String lastVoiceKey;
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
    /**
     * Focused recorder inventory for the red microphone and opaque playback strip. The general
     * footer DFS spends its 32-node budget on the input subtree before reaching the recorder.
     * This recorder-only walk is opt-in, limited to four distinct states/minute and 36 lines
     * per state. It reads IDs, geometry and drawable types only, never user content.
     */
    void voiceSnapshot(ViewGroup recorder, View inactiveInput, long epoch) {
        if (!GlassProbe.enabled() || recorder == null) return;
        long now = SystemClock.uptimeMillis();
        if (voiceWindow < 0 || now - voiceWindow >= 60000) {
            voiceWindow = now;
            voiceSnapshots = 0;
            lastVoiceKey = null;
        }
        View preview = findByRole(recorder, "voice_note_draft_preview_v2", 0, new int[]{160});
        String key = System.identityHashCode(recorder) + ":" + GlassProbe.role(recorder) + ":"
                + recorder.getWidth() + "x" + recorder.getHeight() + ":" + epoch + ":"
                + (preview == null ? "none" : preview.getVisibility());
        if (key.equals(lastVoiceKey) || voiceSnapshots >= 4) return;
        lastVoiceKey = key;
        voiceSnapshots++;
        emit("COMPOSER_VOICE_ROOT", recorder, "epoch=" + epoch + " state=" + voiceSnapshots);
        if (inactiveInput != null)
            emit("COMPOSER_VOICE_INPUT", inactiveInput, "epoch=" + epoch + " state=" + voiceSnapshots);
        java.util.ArrayList<View> candidates = new java.util.ArrayList<>();
        collectRecorderNodes(recorder, recorder.getWidth(), 0, new int[]{160}, candidates);
        int count = Math.min(34, candidates.size());
        for (int i = 0; i < count; i++) {
            View view = candidates.get(i);
            String extra = "epoch=" + epoch + " state=" + voiceSnapshots + " item=" + i;
            if (view instanceof ImageView) {
                ImageView image = (ImageView) view;
                extra += " image=" + GlassProbe.drawable(image.getDrawable())
                        + " imageTint=" + image.getImageTintList();
            }
            emit("COMPOSER_VOICE_NODE", view, extra);
        }
    }
    private static View findByRole(View node, String role, int depth, int[] visits) {
        if (node == null || depth > 10 || visits[0]-- <= 0) return null;
        if (role.equals(GlassProbe.role(node))) return node;
        if (node instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) node;
            for (int i = 0; i < group.getChildCount() && visits[0] > 0; i++) {
                View match = findByRole(group.getChildAt(i), role, depth + 1, visits);
                if (match != null) return match;
            }
        }
        return null;
    }
    private static void collectRecorderNodes(View node, int recorderWidth, int depth,
                                             int[] visits, java.util.ArrayList<View> out) {
        if (node == null || depth > 10 || visits[0]-- <= 0 || out.size() >= 40) return;
        String role = GlassProbe.role(node);
        boolean named = role.startsWith("voice_note_") || role.contains("record")
                || role.contains("mic") || role.contains("audio") || role.contains("wave")
                || role.contains("play") || role.contains("pause");
        boolean icon = node instanceof ImageView && node.getWidth() > 0 && node.getHeight() > 0
                && node.getWidth() <= 192 && node.getHeight() <= 192;
        boolean wideBacking = node instanceof ViewGroup && node.getBackground() != null
                && recorderWidth > 0 && node.getWidth() * 5 >= recorderWidth * 3;
        if (named || icon || wideBacking) out.add(node);
        if (node instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) node;
            for (int i = 0; i < group.getChildCount() && visits[0] > 0 && out.size() < 40; i++)
                collectRecorderNodes(group.getChildAt(i), recorderWidth, depth + 1, visits, out);
        }
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
