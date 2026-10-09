package com.waenhancer.xposed.features.customization;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Lets the home chat lists scroll behind the header and the search pill, as the conversation
 * screen does behind its chrome.
 *
 * <p>Layout observed on 2.26.33.76 ({@code WaEnhancerX/HomeTree} dump, 2026-10-09): {@code content}
 * holds {@code pager_holder} (full height) and {@code header} (z=12) as siblings, so the pager
 * already runs under the header. Each chats page is a {@code ConversationsContainer} (a vertical
 * LinearLayout) holding {@code my_search_bar} (top margin = header clearance) and then the list,
 * which starts below the search bar. Calls already pads its list under the header itself.</p>
 *
 * <p>Changes, each recorded and undone when the header surface is switched off:</p>
 * <ul>
 *   <li>the list is pulled up to its container's top and the distance handed back as top
 *   padding with clipToPadding=false, so rows rest where they did but scroll up behind the
 *   search pill and the header (WaThemer's {@code extendList}, ayane-04/wathemer@d39b293);</li>
 *   <li>the search row is raised above the list in Z, so it draws (and takes touches) on top;</li>
 *   <li>the header's opaque {@code toolbar_container} fill is cleared, so the toolbar glass
 *   records the rows behind it.</li>
 * </ul>
 */
final class HomeGlassChrome {

    interface Host {
        boolean toolbarsEnabled();
        void report(String key, String message);
    }

    private static final float SEARCH_Z = 1f;

    private static final class ListState {
        final WeakReference<ViewGroup> container;
        final int marginTop, paddingTop;
        final boolean clipToPadding;
        int shift;
        ListState(View list, ViewGroup container) {
            this.container = new WeakReference<>(container);
            marginTop = ((ViewGroup.MarginLayoutParams) list.getLayoutParams()).topMargin;
            paddingTop = list.getPaddingTop();
            clipToPadding = ((ViewGroup) list).getClipToPadding();
        }
    }

    private final Host host;
    private final WeakHashMap<View, ListState> lists = new WeakHashMap<>();
    private final WeakHashMap<View, Float> raised = new WeakHashMap<>();
    private final WeakHashMap<View, Drawable> cleared = new WeakHashMap<>();
    /** Our transparent replacement per fill; any other background on the fill is native. */
    private final WeakHashMap<View, Drawable> replacements = new WeakHashMap<>();
    private final java.util.Map<String, Integer> ids = new java.util.HashMap<>();

    HomeGlassChrome(Host host) {
        this.host = host;
    }

    /** Called on every global layout of a window; cheap when nothing changed. */
    void sync(View root) {
        if (root == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        try {
            if (!host.toolbarsEnabled()) {
                restore(root);
                return;
            }
            View pager = find(root, "pager_holder");
            View header = find(root, "header");
            // A window that stops matching (resize, replaced hierarchy) gets its native state back.
            if (pager == null || header == null || pager.getParent() != header.getParent()) {
                restore(root);
                return;
            }
            // The header must sit over the pager's top, or rows would scroll behind nothing.
            if (header.getTop() > pager.getTop() || header.getHeight() <= 0
                    || header.getHeight() > pager.getHeight() / 3) {
                restore(root);
                host.report("home-geometry", "home header/pager geometry unexpected; home left native");
                return;
            }
            View fill = find(header, "toolbar_container");
            if (fill != null) clearFill(fill);
            for (View container : containers((ViewGroup) pager)) extend((ViewGroup) container);
        } catch (Throwable error) {
            host.report("home-sync-error", "home chrome skipped: " + error);
        }
    }

    /** The chats pages: ConversationsContainer, a vertical LinearLayout of search row and list. */
    private List<View> containers(ViewGroup pager) {
        List<View> out = new ArrayList<>();
        collect(pager, out, 0);
        return out;
    }

    private void collect(View view, List<View> out, int depth) {
        if (depth > 6 || !(view instanceof ViewGroup)) return;
        if (view.getClass().getName().contains("ConversationsContainer")) {
            out.add(view);
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out, depth + 1);
    }

    private void extend(ViewGroup container) {
        if (!(container instanceof LinearLayout)
                || ((LinearLayout) container).getOrientation() != LinearLayout.VERTICAL) {
            host.report("home-container", "chats container is not a vertical LinearLayout; page left native");
            return;
        }
        View list = null, search = null;
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            if (list == null && isList(child)) list = child;
            else if (list == null && search == null && name(child).equals("my_search_bar")) search = child;
        }
        if (list == null || !list.isLaidOut() || !(list.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;

        ListState state = lists.get(list);
        if (state == null) {
            state = new ListState(list, container);
            lists.put(list, state);
        }
        // Where the list would sit without our shift; recomputed every layout, so a search row
        // that changes height (or disappears) moves the rest position with it.
        int natural = list.getTop() + state.shift;
        if (natural < 0) return;
        if (natural != state.shift) {
            boolean atTop = !list.canScrollVertically(-1);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) list.getLayoutParams();
            lp.topMargin = state.marginTop - natural;
            list.setLayoutParams(lp);
            list.setPadding(list.getPaddingLeft(), state.paddingTop + natural, list.getPaddingRight(), list.getPaddingBottom());
            ((ViewGroup) list).setClipToPadding(false);
            state.shift = natural;
            host.report("home-list-extended", "home list extended under the chrome (shift=" + natural + ")");
            if (atTop) keepAtTop(list);
        }
        if (search != null && !raised.containsKey(search)) {
            raised.put(search, search.getTranslationZ());
            if (search.getTranslationZ() < SEARCH_Z) search.setTranslationZ(SEARCH_Z);
        }
    }

    /** One pre-draw after the padding change: back to the first row, as before the change. */
    private static void keepAtTop(View list) {
        if (list instanceof AbsListView) return; // only RecyclerView honours scrollBy here
        list.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                list.getViewTreeObserver().removeOnPreDrawListener(this);
                if (!list.isAttachedToWindow() || !list.canScrollVertically(-1)) return true;
                list.scrollBy(0, -1_000_000);
                return false; // the frame with the shifted rows is never shown
            }
        });
    }

    private void clearFill(View fill) {
        Drawable current = fill.getBackground();
        if (current == null || current == replacements.get(fill)) return;
        // Whatever WhatsApp set last is the native fill to restore, not the first one seen.
        cleared.put(fill, current);
        Drawable clear = new ColorDrawable(Color.TRANSPARENT);
        replacements.put(fill, clear);
        fill.setBackground(clear);
    }

    /** Undo everything recorded for this window. */
    void restore(View root) {
        for (java.util.Map.Entry<View, ListState> entry : new ArrayList<>(lists.entrySet())) {
            View list = entry.getKey();
            if (list == null || list.getRootView() != root) continue;
            ListState state = entry.getValue();
            if (list.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) list.getLayoutParams();
                lp.topMargin = state.marginTop;
                list.setLayoutParams(lp);
            }
            list.setPadding(list.getPaddingLeft(), state.paddingTop, list.getPaddingRight(), list.getPaddingBottom());
            ((ViewGroup) list).setClipToPadding(state.clipToPadding);
            lists.remove(list);
        }
        for (java.util.Map.Entry<View, Float> entry : new ArrayList<>(raised.entrySet())) {
            View search = entry.getKey();
            if (search == null || search.getRootView() != root) continue;
            search.setTranslationZ(entry.getValue());
            raised.remove(search);
        }
        for (java.util.Map.Entry<View, Drawable> entry : new ArrayList<>(cleared.entrySet())) {
            View fill = entry.getKey();
            if (fill == null || fill.getRootView() != root) continue;
            fill.setBackground(entry.getValue());
            cleared.remove(fill);
            replacements.remove(fill);
        }
    }

    private static boolean isList(View view) {
        if (view instanceof AbsListView) return true;
        for (Class<?> c = view.getClass(); c != null && c != View.class; c = c.getSuperclass()) {
            String name = c.getName();
            if (name.contains("RecyclerView") || name.contains("WDSList")) return true;
        }
        return false;
    }

    private View find(View root, String name) {
        Integer id = ids.get(name);
        if (id == null) {
            id = root.getResources().getIdentifier(name, "id", root.getContext().getPackageName());
            ids.put(name, id);
        }
        return id == 0 ? null : root.findViewById(id);
    }

    private static String name(View view) {
        int id = view.getId();
        if (id == View.NO_ID || id == 0) return "";
        try { return view.getResources().getResourceEntryName(id); }
        catch (android.content.res.Resources.NotFoundException e) { return ""; }
    }
}
