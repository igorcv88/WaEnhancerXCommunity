package com.waenhancer.xposed.features.customization;

import android.content.res.Resources;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;

import java.util.WeakHashMap;

/**
 * Read-only dump of the home window's layout, logged as {@code WaEnhancerX/HomeTree}.
 *
 * <p>Floating the home header over the chat list (as the conversation screen does) needs the
 * real hierarchy of the installed WhatsApp build: which parent holds the header and the pager,
 * how each tab page stacks its search bar and list, and their layout params. WaThemer's ids
 * ({@code header}, {@code pager_holder}) come from another build, so they are not assumed here.
 * This logs the hierarchy instead and changes nothing.</p>
 *
 * <p>A dump runs when the window first shows a list and again whenever the number of lists grows
 * (a tab opened for the first time), at most {@link #MAX_DUMPS} times per window.</p>
 */
final class HomeTreeProbe {
    private static final String TAG = "WaEnhancerX/HomeTree";
    private static final int MAX_DUMPS = 4;
    private static final int MAX_DEPTH = 18;
    private static final int MAX_LINES = 400;

    private static final class State { int lists; int dumps; }
    private final WeakHashMap<View, State> windows = new WeakHashMap<>();

    /** Cheap when nothing changed: one id lookup and a list count, on global layout. */
    void sync(View root) {
        if (root == null) return;
        Resources res = root.getResources();
        String pkg = root.getContext().getPackageName();
        // Home only: the conversation screen and dialogs have neither of these.
        if (find(root, res, pkg, "pager_holder") == null && find(root, res, pkg, "conversations_coordinator_layout") == null
                && find(root, res, pkg, "header") == null) return;
        State state = windows.get(root);
        if (state == null) windows.put(root, state = new State());
        if (state.dumps >= MAX_DUMPS) return;
        int lists = countLists(root, 0);
        if (lists == 0 || lists <= state.lists) return;
        state.lists = lists;
        state.dumps++;
        int[] budget = {0};
        Log.i(TAG, "dump " + state.dumps + "/" + MAX_DUMPS + " lists=" + lists + " screen="
                + res.getDisplayMetrics().widthPixels + "x" + res.getDisplayMetrics().heightPixels);
        dump(root, 0, budget);
        if (budget[0] >= MAX_LINES) Log.i(TAG, "truncated at " + MAX_LINES + " lines");
    }

    /** getIdentifier scans the whole name table; this runs on every global layout. */
    private static final java.util.Map<String, Integer> IDS = new java.util.HashMap<>();

    private static View find(View root, Resources res, String pkg, String name) {
        Integer cached = IDS.get(name);
        int id = cached != null ? cached : res.getIdentifier(name, "id", pkg);
        if (cached == null) IDS.put(name, id);
        return id == 0 ? null : root.findViewById(id);
    }

    /** By hierarchy, not the leaf name: WhatsApp's lists are subclasses (WDSList and others). */
    private static boolean isList(View view) {
        if (view instanceof AbsListView) return true;
        for (Class<?> c = view.getClass(); c != null && c != View.class; c = c.getSuperclass()) {
            String name = c.getName();
            if (name.contains("RecyclerView") || name.contains("WDSList")) return true;
        }
        return false;
    }

    private static int countLists(View view, int depth) {
        if (depth > MAX_DEPTH) return 0;
        int n = isList(view) && view.isShown() ? 1 : 0;
        if (view instanceof ViewGroup && !isList(view)) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) n += countLists(group.getChildAt(i), depth + 1);
        }
        return n;
    }

    private static void dump(View view, int depth, int[] budget) {
        if (depth > MAX_DEPTH || budget[0]++ >= MAX_LINES) return;
        Log.i(TAG, describe(view, depth));
        // A list's rows are content, not layout: two are enough to show how rows sit.
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        int limit = isList(view) ? Math.min(2, group.getChildCount()) : group.getChildCount();
        for (int i = 0; i < limit; i++) dump(group.getChildAt(i), depth + 1, budget);
    }

    private static String describe(View v, int depth) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < depth; i++) out.append(' ');
        out.append(v.getClass().getName()).append(" id=").append(name(v))
                .append(" vis=").append(v.getVisibility() == View.VISIBLE ? "V" : v.getVisibility() == View.GONE ? "G" : "I")
                .append(" ltrb=").append(v.getLeft()).append(',').append(v.getTop()).append(',')
                .append(v.getRight()).append(',').append(v.getBottom())
                .append(" pad=").append(v.getPaddingLeft()).append(',').append(v.getPaddingTop()).append(',')
                .append(v.getPaddingRight()).append(',').append(v.getPaddingBottom());
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null) {
            out.append(" lp=").append(lp.getClass().getName()).append(' ').append(size(lp.width)).append('x').append(size(lp.height));
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                out.append(" margin=").append(m.leftMargin).append(',').append(m.topMargin).append(',')
                        .append(m.rightMargin).append(',').append(m.bottomMargin);
            }
        }
        if (v.getBackground() != null) out.append(" bg=").append(v.getBackground().getClass().getSimpleName());
        if (v.getZ() != 0f) out.append(" z=").append(v.getZ());
        if (v.getTranslationY() != 0f) out.append(" ty=").append(v.getTranslationY());
        if (v instanceof ViewGroup && !((ViewGroup) v).getClipToPadding()) out.append(" clipToPadding=false");
        if (v instanceof ViewGroup) out.append(" children=").append(((ViewGroup) v).getChildCount());
        return out.toString();
    }

    private static String size(int spec) {
        return spec == ViewGroup.LayoutParams.MATCH_PARENT ? "match" : spec == ViewGroup.LayoutParams.WRAP_CONTENT ? "wrap" : String.valueOf(spec);
    }

    private static String name(View view) {
        int id = view.getId();
        if (id == View.NO_ID || id == 0) return "-";
        if (id == android.R.id.list) return "android:list";
        try { return view.getResources().getResourceEntryName(id); }
        catch (Resources.NotFoundException e) { return Integer.toHexString(id); }
    }
}
