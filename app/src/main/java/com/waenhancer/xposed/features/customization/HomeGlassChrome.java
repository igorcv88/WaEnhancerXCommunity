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
        boolean searchEnabled();
        void report(String key, String message);
    }

    private static final float SEARCH_Z = 1f;

    private static final class ListState {
        final WeakReference<ViewGroup> container;
        final int marginTop;
        int paddingTop;
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
    /** The header and its fill from the last matching sync, for the per-frame check. */
    private WeakReference<View> headerRef = new WeakReference<>(null);
    private WeakReference<View> fillRef = new WeakReference<>(null);
    private String headerSignature;

    HomeGlassChrome(Host host) {
        this.host = host;
    }

    /** Called on every global layout of a window; cheap when nothing changed. */
    void sync(View root) {
        if (root == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        try {
            // Rows run behind the chrome for either surface: the search pill needs them as much
            // as the header does. Only the header's fill belongs to the Headers switch.
            if (!host.toolbarsEnabled() && !host.searchEnabled()) {
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
            headerRef = new WeakReference<>(header);
            fillRef = new WeakReference<>(fill);
            if (fill != null) {
                if (host.toolbarsEnabled()) clearFill(fill);
                else restoreFill(fill);
            }
            for (View container : containers((ViewGroup) pager)) extend((ViewGroup) container);
        } catch (Throwable error) {
            host.report("home-sync-error", "home chrome skipped: " + error);
        }
    }

    /**
     * Called from the window's pre-draw, before the glass records: keeps the header fill clear
     * between layouts and logs the header chain whenever its backgrounds change.
     *
     * <p>{@link #sync} runs on global layout only. WhatsApp can swap or recolour the fill on
     * scroll without a layout (its lift state), and an opaque fill under the toolbar glass hides
     * the rows behind the header in the window and in the toolbar's recording alike.</p>
     */
    void frame(View root) {
        if (root == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        View header = headerRef.get();
        if (header == null || header.getRootView() != root || !header.isAttachedToWindow()) return;
        try {
            View fill = fillRef.get();
            String action = "";
            if (fill != null && host.toolbarsEnabled()) {
                Drawable ours = replacements.get(fill);
                Drawable current = fill.getBackground();
                if (current != null && current != ours) {
                    clearFill(fill);
                    action = " action=fill-replaced";
                } else if (ours instanceof ColorDrawable && ((ColorDrawable) ours).getColor() != Color.TRANSPARENT) {
                    // Our replacement was recoloured in place (a cast to ColorDrawable and setColor).
                    action = " action=fill-recoloured:" + Integer.toHexString(((ColorDrawable) ours).getColor());
                    ((ColorDrawable) ours).setColor(Color.TRANSPARENT);
                }
            }
            String signature = "header=" + describe(header.getBackground())
                    + " fill=" + (fill == null ? "absent" : describe(fill.getBackground())
                        + (fill.getBackground() != null && fill.getBackground() == replacements.get(fill) ? "(cleared)" : ""))
                    + " headerZ=" + header.getZ() + " headerAlpha=" + header.getAlpha();
            if (!action.isEmpty() || !signature.equals(headerSignature)) {
                headerSignature = signature;
                com.waenhancer.theme.GlassTrace.event(root, header, fill, "HOME_CHROME", "header-chain",
                        signature + action + " toolbars=" + host.toolbarsEnabled() + " search=" + host.searchEnabled());
            }
        } catch (Throwable error) {
            host.report("home-frame-error", "home chrome frame check skipped: " + error);
        }
    }

    private static String describe(Drawable drawable) {
        if (drawable == null) return "none";
        String name = drawable.getClass().getSimpleName();
        if (drawable instanceof ColorDrawable) {
            return name + "#" + Integer.toHexString(((ColorDrawable) drawable).getColor());
        }
        return name + "@" + Integer.toHexString(System.identityHashCode(drawable)) + "/a" + drawable.getAlpha();
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

        // A pending layout means getTop() and the margins disagree; the next pass comes back here.
        if (container.isLayoutRequested() || list.isLayoutRequested()) return;
        ListState state = lists.get(list);
        if (state == null) {
            state = new ListState(list, container);
            lists.put(list, state);
        } else if (list.getPaddingTop() != state.paddingTop + state.shift) {
            // WhatsApp reset the padding itself: that value is the new native baseline.
            host.report("home-list-native-padding", "home list padding changed natively ("
                    + (state.paddingTop + state.shift) + " -> " + list.getPaddingTop() + "); re-basing");
            state.paddingTop = list.getPaddingTop();
            state.shift = 0;
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) list.getLayoutParams();
            lp.topMargin = state.marginTop;
            list.setLayoutParams(lp);
            return;
        }
        // Where the list would rest without our shift, from what stacks above it. Unlike getTop()
        // this never reads a position from before the last change, which made the shift double,
        // the list oscillate on every layout and the end-of-list pin yank rows back up.
        int natural = naturalTop(container, list) + state.marginTop;
        if (natural < 0) return;
        if (natural != state.shift) {
            boolean atTop = !list.canScrollVertically(-1);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) list.getLayoutParams();
            lp.topMargin = state.marginTop - natural;
            list.setLayoutParams(lp);
            list.setPadding(list.getPaddingLeft(), state.paddingTop + natural, list.getPaddingRight(), list.getPaddingBottom());
            ((ViewGroup) list).setClipToPadding(false);
            boolean first = state.shift == 0;
            state.shift = natural;
            host.report("home-list-extended", "home list extended under the chrome (shift=" + natural + ")");
            if (atTop && first) keepAtTop(list);
        }
        if (search != null && !raised.containsKey(search)) {
            raised.put(search, search.getTranslationZ());
            if (search.getTranslationZ() < SEARCH_Z) search.setTranslationZ(SEARCH_Z);
        }
    }

    /** Container padding plus every visible sibling stacked above the list, with their margins. */
    private static int naturalTop(ViewGroup container, View list) {
        int top = container.getPaddingTop();
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child == list) return top;
            if (child.getVisibility() == View.GONE) continue;
            top += child.getMeasuredHeight();
            if (child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
                top += m.topMargin + m.bottomMargin;
            }
        }
        return top;
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

    private void restoreFill(View fill) {
        Drawable original = cleared.remove(fill);
        replacements.remove(fill);
        if (original != null) fill.setBackground(original);
    }

    /** Undo everything recorded for this window. */
    void restore(View root) {
        View header = headerRef.get();
        if (header == null || header.getRootView() == root) {
            headerRef = new WeakReference<>(null);
            fillRef = new WeakReference<>(null);
            headerSignature = null;
        }
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
