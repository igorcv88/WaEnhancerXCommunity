package com.waenhancer.xposed.features.customization;

import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.AbsListView;
import android.widget.TextView;
import com.waenhancer.theme.GlassProbe;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/** Metadata-only inventory for the date chip drawn over the cutout when chat rows go edge-to-edge. */
final class ConversationDateProbe {
    private long window = -1;
    private long lastAt = -1;
    private int snapshots;
    private String lastKey;

    void snapshot(View root, View holder, ViewGroup listHost, AbsListView list, int safeTop) {
        if (!GlassProbe.enabled() || root == null || holder == null || listHost == null
                || list == null || !list.isAttachedToWindow()) return;
        long now = SystemClock.uptimeMillis();
        if (window < 0 || now - window >= 60000) {
            window = now;
            lastAt = -1;
            snapshots = 0;
            lastKey = null;
        }
        if (snapshots >= 3 || (lastAt >= 0 && now - lastAt < 1800)) return;
        int first = list.getFirstVisiblePosition();
        int headerBottom = top(holder) + holder.getHeight();
        String key = first + ":" + headerBottom + ":" + top(list);
        if (key.equals(lastKey) && lastAt >= 0 && now - lastAt < 5000) return;
        lastAt = now;
        lastKey = key;
        snapshots++;

        float d = list.getResources().getDisplayMetrics().density;
        int bottom = headerBottom + Math.round(120f * d);
        ArrayList<View> candidates = new ArrayList<>();
        int[] visits = {350};
        collect(listHost, bottom, d, false, 0, visits, candidates);
        int listVisits = 350 - visits[0];
        // A native sticky date can be parented outside the list host, e.g. in a separate
        // overlay. Only named date-like nodes qualify in this secondary decor walk.
        visits[0] = 350;
        collect(root, bottom, d, true, 0, visits, candidates);
        Collections.sort(candidates, Comparator.comparingInt(ConversationDateProbe::top));

        int count = Math.min(26, candidates.size());
        Log.i(GlassProbe.TAG, "event=TOP_DATE_SUMMARY first=" + first + " safeTop=" + safeTop
                + " headerBottom=" + headerBottom + " listTop=" + top(list)
                + " listPaddingTop=" + list.getPaddingTop() + " listHostTop=" + top(listHost)
                + " candidates=" + candidates.size() + " emitted=" + count
                + " listVisited=" + listVisits + " rootVisited=" + (350 - visits[0])
                + " sample=" + snapshots);
        for (int i = 0; i < count; i++) {
            View view = candidates.get(i);
            Log.i(GlassProbe.TAG, "event=TOP_DATE_CANDIDATE item=" + i
                    + " sample=" + snapshots + " " + GlassProbe.describe(view)
                    + " parents=" + parents(view) + " clickable=" + view.isClickable());
        }
    }

    private static void collect(View node, int bottom, float density, boolean namedOnly,
                                int depth, int[] visits, ArrayList<View> out) {
        if (node == null || depth > 18 || visits[0]-- <= 0 || out.size() >= 40) return;
        if (node.isShown() && node.getWidth() > 0 && node.getHeight() > 0) {
            String role = GlassProbe.role(node).toLowerCase(java.util.Locale.ROOT);
            boolean named = role.contains("date") || role.contains("sticky")
                    || role.contains("day_header") || role.contains("day_separator");
            boolean text = node instanceof TextView;
            int y = top(node);
            if (y < bottom && y + node.getHeight() > 0
                    && node.getHeight() <= 90f * density
                    && node.getWidth() <= 460f * density
                    && (named || text && !namedOnly)
                    && !out.contains(node)) out.add(node);
        }
        if (node instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) node;
            for (int i = 0; i < group.getChildCount() && visits[0] > 0
                    && out.size() < 40; i++) {
                collect(group.getChildAt(i), bottom, density, namedOnly, depth + 1, visits, out);
            }
        }
    }

    private static String parents(View view) {
        StringBuilder out = new StringBuilder();
        ViewParent parent = view.getParent();
        for (int depth = 0; parent instanceof View && depth < 4; depth++) {
            View p = (View) parent;
            if (depth > 0) out.append('/');
            out.append(GlassProbe.role(p)).append(':').append(p.getClass().getSimpleName());
            parent = p.getParent();
        }
        return out.toString();
    }

    private static int top(View view) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return at[1];
    }
}
