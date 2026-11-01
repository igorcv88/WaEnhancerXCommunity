package com.waenhancer.xposed.features.customization;

import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
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
    void voiceSnapshot(ViewGroup footer, ViewGroup recorder, View inactiveInput, long epoch) {
        if (!GlassProbe.enabled() || footer == null || recorder == null) return;
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
        // The visible red microphone and second grey timer lie beside the trash, while the
        // verified voice_note_flashing_recording_view was GONE and 0x0 on this build. Search
        // the whole footer's left recorder lane, not just the recorder subtree: an animated
        // view may belong to a sibling/parent overlay. Metadata only; no TextView contents.
        snapshotLeftRecorderLane(footer, recorder, epoch);
    }

    private static void snapshotLeftRecorderLane(ViewGroup footer, ViewGroup recorder, long epoch) {
        int[] at = new int[2];
        recorder.getLocationOnScreen(at);
        float density = recorder.getResources().getDisplayMetrics().density;
        int right = at[0] + Math.min(recorder.getWidth(), Math.round(140f * density));
        int bottom = at[1] + recorder.getHeight();
        java.util.ArrayList<View> matches = new java.util.ArrayList<>();
        collectLeftLane(footer, at[0], at[1], right, bottom, density,
                new int[]{300}, 0, matches);
        // Prefer the recorder's lower-left lane (where the red mic flashes over the trash),
        // even when an earlier input subtree contains many small leaves.
        matches.sort((a, b) -> Integer.compare(screenBottom(b), screenBottom(a)));
        int count = Math.min(matches.size(), 30);
        Log.i(GlassProbe.TAG, "event=COMPOSER_LEFT_LANE_SUMMARY epoch=" + epoch
                + " candidates=" + matches.size() + " emitted=" + count);
        for (int i = 0; i < count; i++) {
            View view = matches.get(i);
            String detail = "epoch=" + epoch + " item=" + i + " interactive="
                    + (view.isClickable() || view.isFocusable());
            if (view instanceof TextView) {
                TextView text = (TextView) view;
                android.graphics.drawable.Drawable[] compound = text.getCompoundDrawables();
                int icons = 0;
                for (android.graphics.drawable.Drawable drawable : compound) if (drawable != null) icons++;
                detail += " compoundIcons=" + icons + " textColor="
                        + Integer.toHexString(text.getCurrentTextColor());
            } else if (view instanceof ImageView) {
                ImageView image = (ImageView) view;
                detail += " image=" + GlassProbe.drawable(image.getDrawable())
                        + " imageTint=" + (image.getImageTintList() == null ? "none" : "set");
            }
            emit("COMPOSER_LEFT_LANE_NODE", view, detail);
        }
    }

    private static void collectLeftLane(View node, int left, int top, int right, int bottom,
                                        float density, int[] visits, int depth,
                                        java.util.ArrayList<View> out) {
        if (node == null || depth > 13 || visits[0]-- <= 0 || out.size() >= 64) return;
        if (node.isShown() && node.getWidth() > 0 && node.getHeight() > 0) {
            int[] pos = new int[2];
            node.getLocationOnScreen(pos);
            boolean overlaps = pos[0] < right && pos[0] + node.getWidth() > left
                    && pos[1] < bottom && pos[1] + node.getHeight() > top;
            boolean compact = node.getWidth() <= 240f * density
                    && node.getHeight() <= 180f * density;
            boolean relevant = node instanceof ImageView || node instanceof TextView
                    || !(node instanceof ViewGroup) || node.getBackground() != null;
            if (overlaps && compact && relevant) out.add(node);
        }
        if (node instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) node;
            for (int i = 0; i < group.getChildCount() && visits[0] > 0 && out.size() < 64; i++) {
                collectLeftLane(group.getChildAt(i), left, top, right, bottom, density,
                        visits, depth + 1, out);
            }
        }
    }

    private static int screenBottom(View view) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return at[1] + view.getHeight();
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
