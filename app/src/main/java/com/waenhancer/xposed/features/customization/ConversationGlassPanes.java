package com.waenhancer.xposed.features.customization;

import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.AbsListView;
import android.widget.FrameLayout;

import com.waenhancer.theme.GlassMaterialDrawable;
import com.waenhancer.theme.GlassPane;
import com.waenhancer.theme.GlassSpec;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * The conversation screen's header and composer as WaThemer builds them
 * ({@code GlassConversation.kt}, GPL-3.0, ayane-04/wathemer@d39b293).
 *
 * <p>The header holder and the footer float over the message list, and the list is padded with
 * {@code clipToPadding=false}, so messages rest clear of the chrome but scroll behind it. A
 * {@link GlassPane} under the header capsule and under the input row then transmits the list
 * moving behind them, over the wallpaper. WhatsApp's own views are never reparented: panes are
 * added beneath them and WhatsApp's opaque fills are cleared.</p>
 *
 * <p>Every change is recorded with its original value and undone when the surface is switched
 * off, so the screen returns to WhatsApp's native layout without being reopened.</p>
 */
final class ConversationGlassPanes {

    /** Inset of the header capsule from the screen edge. */
    private static final float PILL_INSET_DP = 6f;
    /** Trimmed off the Back button's band before the capsule is grown past it. */
    private static final float PILL_TRIM_DP = 4f;
    /** Deliberately more than fits: the capsule fills the header band, no slivers around it. */
    private static final float CAPSULE_GROW_DP = 8f;
    /** Resting clearance between the chrome and the first/last message. */
    private static final float CHROME_GAP_DP = 6f;
    /** Composer pill grown past input_layout so its icons are not flush. */
    private static final float COMPOSE_PAD_DP = 5f;
    /** Composer row lifted off the bottom edge so the pill reads as floating. */
    private static final float COMPOSE_LIFT_DP = 6f;
    /** Ceiling on the composer pill's corner radius: half the single-row height. */
    private static final float COMPOSE_MAX_RADIUS_DP = 24f;

    interface Host {
        boolean toolbarsEnabled();
        boolean composerEnabled();
        /** The material for an enabled surface; the panes ask on every draw. */
        GlassSpec material();
        void report(String key, String message);
    }

    /** One conversation window: what was changed and what to put back. */
    private static final class Screen {
        WeakReference<ViewGroup> holder = new WeakReference<>(null);
        WeakReference<ViewGroup> coordinator = new WeakReference<>(null);
        WeakReference<ViewGroup> footer = new WeakReference<>(null);
        WeakReference<ViewGroup> listHost = new WeakReference<>(null);
        WeakReference<AbsListView> list = new WeakReference<>(null);
        WeakReference<View> wallpaper = new WeakReference<>(null);

        GlassPane band, capsule, composer;
        View.OnLayoutChangeListener holderListener, footerListener;

        // Originals, recorded once when first changed.
        Integer holderBottomMargin;
        Float holderZ;
        final WeakHashMap<View, Drawable> clearedBackgrounds = new WeakHashMap<>();
        final WeakHashMap<View, Drawable> clearedForegrounds = new WeakHashMap<>();
        Integer listHostBottomMargin;
        Float footerTranslationY;
        int[] listPadding;
        Boolean listClipToPadding;

        boolean headerOn, footerOn;
    }

    private final Host host;
    private final WeakHashMap<View, Screen> screens = new WeakHashMap<>();
    private final Rect rect = new Rect();

    ConversationGlassPanes(Host host) {
        this.host = host;
    }

    /**
     * Whether a pane, not a background drawable, themes this view: the holder, the footer and the
     * views whose fill was cleared for a pane. The drawable path must skip them, or it would paint
     * an opaque material over the pane. Other views inside the chrome keep the drawable path.
     */
    boolean owns(View view) {
        for (Screen screen : screens.values()) {
            if (screen.headerOn && view == screen.holder.get()) return true;
            if (screen.footerOn && view == screen.footer.get()) return true;
            if (screen.clearedBackgrounds.containsKey(view)) return true;
        }
        return false;
    }

    /** Called on every global layout of a window; cheap when nothing changed. */
    void sync(View root) {
        if (root == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        try {
            Screen screen = screens.get(root);
            if (screen == null && !host.toolbarsEnabled() && !host.composerEnabled()) return;
            ViewGroup holder = find(root, "search_fragment_and_toolbar_holder");
            ViewGroup footer = find(root, "footer");
            if (footer != null && findById(footer, "input_layout") == null) footer = null;
            if (screen == null) {
                if (holder == null && footer == null) return;
                screen = new Screen();
                screens.put(root, screen);
            }
            screen.coordinator = new WeakReference<>(find(root, "coordinator"));
            View wallpaper = findById(root, "conversation_background");
            screen.wallpaper = new WeakReference<>(wallpaper);

            if (footer != null && footer.getParent() instanceof ViewGroup) {
                ViewGroup listHost = listHost((ViewGroup) footer.getParent());
                if (listHost != null) {
                    screen.listHost = new WeakReference<>(listHost);
                    screen.list = new WeakReference<>(firstList(listHost));
                }
            }

            boolean wantHeader = host.toolbarsEnabled() && holder instanceof FrameLayout
                    && screen.coordinator.get() != null;
            boolean wantFooter = host.composerEnabled() && footer instanceof FrameLayout
                    && screen.listHost.get() != null;
            if (holder != null && !(holder instanceof FrameLayout)) {
                host.report("conv-holder-type", "conversation header is " + holder.getClass().getName()
                        + ", not a FrameLayout; header panes skipped");
            }
            if (footer != null && !(footer instanceof FrameLayout)) {
                host.report("conv-footer-type", "conversation footer is " + footer.getClass().getName()
                        + ", not a FrameLayout; composer pane skipped");
            }

            if (wantHeader) enableHeader(screen, holder); else if (screen.headerOn) disableHeader(screen);
            if (wantFooter) enableFooter(screen, footer); else if (screen.footerOn) disableFooter(screen);
            syncListPadding(screen);
            if (!screen.headerOn && !screen.footerOn) screens.remove(root);
        } catch (Throwable error) {
            host.report("conv-sync-error", "conversation panes skipped: " + error);
        }
    }

    // ── Header ────────────────────────────────────────────────────────────────────

    private void enableHeader(Screen screen, ViewGroup holder) {
        if (screen.holder.get() != holder) {
            // WhatsApp replaced the header: what was recorded belongs to a view that is gone.
            if (screen.headerOn) disableHeader(screen);
            screen.holder = new WeakReference<>(holder);
        }
        screen.headerOn = true;
        if (screen.holderListener == null) {
            screen.holderListener = (v, l, t, r, b, ol, ot, or, ob) -> {
                if (screen.headerOn) syncHeader(screen);
            };
            holder.addOnLayoutChangeListener(screen.holderListener);
        }
        syncHeader(screen);
    }

    /** Negative bottom margin grows the coordinator up under the header; Z keeps rows beneath it. */
    private void floatHeader(Screen screen, ViewGroup holder) {
        if (screen.holderBottomMargin != null) return;
        int h = holder.getHeight();
        if (h <= 0 || !(holder.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) holder.getLayoutParams();
        screen.holderBottomMargin = lp.bottomMargin;
        screen.holderZ = holder.getTranslationZ();
        lp.bottomMargin = -h;
        holder.setLayoutParams(lp);
        if (holder.getTranslationZ() < 1f) holder.setTranslationZ(1f);
        host.report("conv-header-float", "conversation header floated (h=" + h + ")");
    }

    private void syncHeader(Screen screen) {
        ViewGroup holder = screen.holder.get();
        if (holder == null || holder.getWidth() <= 0 || holder.getHeight() <= 0) return;
        floatHeader(screen, holder);
        float d = holder.getResources().getDisplayMetrics().density;
        clearBackground(screen, holder);

        // Wallpaper-only band behind the capsule: rows scrolling under it would flicker.
        if (screen.band == null || screen.band.getParent() != holder) {
            screen.band = newPane(holder, null, wallpaperOf(screen));
            holder.addView(screen.band, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // In search WhatsApp swaps the toolbar for the search bar in this holder.
        View searchBar = findById(holder, "search_view_toolbar");
        int inset = Math.round(PILL_INSET_DP * d);
        int l = inset, r = holder.getWidth() - inset, t, b;
        if (searchBar != null && searchBar.isShown() && searchBar.getWidth() > 0) {
            clearBackground(screen, searchBar);
            t = 0;
            b = holder.getHeight();
        } else {
            ViewGroup toolbar = toolbarOf(holder);
            if (toolbar == null || !toolbar.isShown()) return;
            clearBackground(screen, toolbar);
            if (toolbar.getForeground() != null) {
                if (!screen.clearedForegrounds.containsKey(toolbar)) {
                    screen.clearedForegrounds.put(toolbar, toolbar.getForeground());
                }
                toolbar.setForeground(null);
            }
            // The band comes from the Back button, not the bar: the elements do not share a height.
            View back = findById(toolbar, "whatsapp_toolbar_home");
            if (back == null || back.getWidth() <= 0 || !rectIn(holder, back)) return;
            int trim = Math.round(PILL_TRIM_DP * d), grow = Math.round(CAPSULE_GROW_DP * d);
            t = Math.max(0, rect.top + trim / 2 - grow);
            b = Math.min(holder.getHeight(), rect.bottom - trim / 2 + grow);
        }
        if (r <= l || b <= t) return;
        if (screen.capsule == null || screen.capsule.getParent() != holder) {
            screen.capsule = newPane(holder, screen.coordinator.get(), wallpaperOf(screen));
            holder.addView(screen.capsule, 1, new FrameLayout.LayoutParams(0, 0));
        }
        screen.capsule.setSource(screen.coordinator.get());
        place(screen.capsule, l, t, r, b, (b - t) / 2f);
    }

    private void disableHeader(Screen screen) {
        screen.headerOn = false;
        ViewGroup holder = screen.holder.get();
        removePane(screen.band);
        removePane(screen.capsule);
        screen.band = screen.capsule = null;
        if (holder != null) {
            if (screen.holderListener != null) holder.removeOnLayoutChangeListener(screen.holderListener);
            if (screen.holderBottomMargin != null
                    && holder.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) holder.getLayoutParams();
                lp.bottomMargin = screen.holderBottomMargin;
                holder.setLayoutParams(lp);
            }
            if (screen.holderZ != null) holder.setTranslationZ(screen.holderZ);
        }
        screen.holderListener = null;
        screen.holderBottomMargin = null;
        screen.holderZ = null;
        restoreUnder(screen, holder);
    }

    // ── Footer ────────────────────────────────────────────────────────────────────

    private void enableFooter(Screen screen, ViewGroup footer) {
        if (screen.footer.get() != footer) {
            if (screen.footerOn) disableFooter(screen);
            screen.footer = new WeakReference<>(footer);
        }
        screen.footerOn = true;
        if (screen.footerListener == null) {
            screen.footerListener = (v, l, t, r, b, ol, ot, or, ob) -> {
                if (screen.footerOn) syncFooter(screen);
            };
            footer.addOnLayoutChangeListener(screen.footerListener);
        }
        syncFooter(screen);
    }

    /** The list host grows under the footer; the footer rides up off the bottom edge. */
    private void floatFooter(Screen screen, ViewGroup footer) {
        if (screen.listHostBottomMargin != null) return;
        ViewGroup listHost = screen.listHost.get();
        int h = footer.getHeight();
        if (listHost == null || h <= 0
                || !(listHost.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
        AbsListView list = screen.list.get();
        boolean atEnd = list != null && restsAtEnd(list);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) listHost.getLayoutParams();
        screen.listHostBottomMargin = lp.bottomMargin;
        screen.footerTranslationY = footer.getTranslationY();
        lp.bottomMargin = -h;
        listHost.setLayoutParams(lp);
        footer.setTranslationY(-COMPOSE_LIFT_DP * footer.getResources().getDisplayMetrics().density);
        if (atEnd) keepAtEnd(list);
        host.report("conv-footer-float", "conversation footer floated (h=" + h + ")");
    }

    private void syncFooter(Screen screen) {
        ViewGroup footer = screen.footer.get();
        if (footer == null || footer.getWidth() <= 0 || footer.getHeight() <= 0) return;
        floatFooter(screen, footer);
        syncListPadding(screen);
        View input = findById(footer, "input_layout");
        if (input == null || goneAbove(input, footer) || input.getWidth() <= 0) {
            if (screen.composer != null) place(screen.composer, 0, 0, 0, 0, 0f);
            return;
        }
        clearBackground(screen, input);
        if (!rectIn(footer, input)) return;
        float d = footer.getResources().getDisplayMetrics().density;
        int pad = Math.round(COMPOSE_PAD_DP * d);
        // The send disc's own screen margin; the pill's left edge mirrors it or the corner clips.
        int sideFloor = 0;
        View send = findById(footer, "conversation_entry_action_button");
        Rect inputRect = new Rect(rect);
        if (send != null && send.getWidth() > 0 && rectIn(footer, send)) {
            sideFloor = Math.max(0, footer.getWidth() - rect.right);
        }
        int l = Math.max(sideFloor, inputRect.left - pad);
        int t = Math.max(0, inputRect.top - pad);
        int r = Math.min(footer.getWidth(), inputRect.right + pad);
        int b = Math.min(footer.getHeight(), inputRect.bottom + pad);
        if (r <= l || b <= t) return;
        if (screen.composer == null || screen.composer.getParent() != footer) {
            screen.composer = newPane(footer, screen.listHost.get(), wallpaperOf(screen));
            // Index 0: WhatsApp's icons and the text field keep painting on top.
            footer.addView(screen.composer, 0, new FrameLayout.LayoutParams(0, 0));
        }
        screen.composer.setSource(screen.listHost.get());
        place(screen.composer, l, t, r, b, Math.min((b - t) / 2f, COMPOSE_MAX_RADIUS_DP * d));
    }

    private void disableFooter(Screen screen) {
        screen.footerOn = false;
        ViewGroup footer = screen.footer.get();
        removePane(screen.composer);
        screen.composer = null;
        ViewGroup listHost = screen.listHost.get();
        if (listHost != null && screen.listHostBottomMargin != null
                && listHost.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) listHost.getLayoutParams();
            lp.bottomMargin = screen.listHostBottomMargin;
            listHost.setLayoutParams(lp);
        }
        if (footer != null) {
            if (screen.footerListener != null) footer.removeOnLayoutChangeListener(screen.footerListener);
            if (screen.footerTranslationY != null) footer.setTranslationY(screen.footerTranslationY);
        }
        screen.footerListener = null;
        screen.listHostBottomMargin = null;
        screen.footerTranslationY = null;
        restoreUnder(screen, footer);
    }

    // ── List ──────────────────────────────────────────────────────────────────────

    /** Padding parks messages clear of the chrome; clipToPadding=false lets them scroll behind it. */
    private void syncListPadding(Screen screen) {
        AbsListView list = screen.list.get();
        if (list == null) return;
        boolean header = screen.headerOn && screen.holderBottomMargin != null;
        boolean footer = screen.footerOn && screen.listHostBottomMargin != null;
        if (!header && !footer) {
            if (screen.listPadding != null) {
                int[] p = screen.listPadding;
                list.setPadding(p[0], p[1], p[2], p[3]);
                if (screen.listClipToPadding != null) list.setClipToPadding(screen.listClipToPadding);
                screen.listPadding = null;
                screen.listClipToPadding = null;
            }
            return;
        }
        if (screen.listPadding == null) {
            screen.listPadding = new int[]{list.getPaddingLeft(), list.getPaddingTop(),
                    list.getPaddingRight(), list.getPaddingBottom()};
            screen.listClipToPadding = list.getClipToPadding();
        }
        int gap = Math.round(CHROME_GAP_DP * list.getResources().getDisplayMetrics().density);
        ViewGroup holder = screen.holder.get();
        ViewGroup footerView = screen.footer.get();
        int top = header && holder != null ? holder.getHeight() + gap : screen.listPadding[1];
        int bottom = footer && footerView != null
                ? footerView.getHeight() + Math.round(-footerView.getTranslationY()) + gap
                : screen.listPadding[3];
        if (list.getClipToPadding()) list.setClipToPadding(false);
        if (list.getPaddingTop() != top || list.getPaddingBottom() != bottom) {
            boolean atEnd = restsAtEnd(list);
            list.setPadding(list.getPaddingLeft(), top, list.getPaddingRight(), bottom);
            if (atEnd) keepAtEnd(list);
        }
    }

    /** The last row is on screen and ends inside the content edge; an empty list never rests. */
    private static boolean restsAtEnd(AbsListView list) {
        int count = list.getCount();
        View last = list.getChildCount() > 0 ? list.getChildAt(list.getChildCount() - 1) : null;
        return count > 0 && last != null && list.getLastVisiblePosition() == count - 1
                && last.getBottom() <= list.getHeight() - list.getPaddingBottom() + 1;
    }

    /** One pre-draw after a padding change: pin the end again if nothing else moved the list. */
    private static void keepAtEnd(AbsListView list) {
        int first = list.getFirstVisiblePosition(), count = list.getCount();
        ViewTreeObserver observer = list.getViewTreeObserver();
        observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                ViewTreeObserver live = observer.isAlive() ? observer : list.getViewTreeObserver();
                live.removeOnPreDrawListener(this);
                if (!list.isAttachedToWindow() || list.getCount() != count
                        || list.getFirstVisiblePosition() != first || !list.canScrollVertically(1)) return true;
                list.setSelection(list.getCount() - 1);
                return false; // the frame with the last row under the pill is never shown
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private GlassPane newPane(ViewGroup parent, View source, List<View> underlay) {
        GlassPane pane = new GlassPane(parent.getContext());
        pane.setSource(source);
        pane.setUnderlay(underlay);
        Supplier<GlassSpec> material = host::material;
        pane.setSpec(material);
        // Decoration only; TalkBack must keep reading WhatsApp's own controls.
        pane.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return pane;
    }

    private static void place(GlassPane pane, int l, int t, int r, int b, float radius) {
        if (!(pane.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) pane.getLayoutParams();
        int w = Math.max(0, r - l), h = Math.max(0, b - t);
        pane.setCornerRadius(radius);
        if (lp.width == w && lp.height == h && lp.leftMargin == l && lp.topMargin == t) return;
        lp.width = w;
        lp.height = h;
        lp.leftMargin = l;
        lp.topMargin = t;
        lp.gravity = Gravity.TOP | Gravity.START;
        pane.setLayoutParams(lp);
    }

    private static void removePane(GlassPane pane) {
        if (pane != null && pane.getParent() instanceof ViewGroup) ((ViewGroup) pane.getParent()).removeView(pane);
    }

    /** WhatsApp's opaque fill would cover the pane; recorded so switching off restores it. */
    private static void clearBackground(Screen screen, View view) {
        Drawable current = view.getBackground();
        if (current instanceof ColorDrawable && ((ColorDrawable) current).getColor() == Color.TRANSPARENT
                && screen.clearedBackgrounds.containsKey(view)) return;
        if (current == null && !screen.clearedBackgrounds.containsKey(view)) return;
        if (!screen.clearedBackgrounds.containsKey(view)) {
            // A static material bound before the pane took over is not WhatsApp's own fill.
            Drawable original = current instanceof GlassMaterialDrawable
                    ? ((GlassMaterialDrawable) current).original() : current;
            screen.clearedBackgrounds.put(view, original);
        }
        view.setBackground(new ColorDrawable(Color.TRANSPARENT));
    }

    /** Puts back fills and foregrounds cleared under {@code root}. */
    private static void restoreUnder(Screen screen, View root) {
        if (root == null) return;
        for (View view : new ArrayList<>(screen.clearedBackgrounds.keySet())) {
            if (view != null && (view == root || isAncestor(root, view))) {
                view.setBackground(screen.clearedBackgrounds.remove(view));
            }
        }
        for (View view : new ArrayList<>(screen.clearedForegrounds.keySet())) {
            if (view != null && (view == root || isAncestor(root, view))) {
                view.setForeground(screen.clearedForegrounds.remove(view));
            }
        }
    }

    private static List<View> wallpaperOf(Screen screen) {
        View wallpaper = screen.wallpaper.get();
        return wallpaper == null ? Collections.emptyList() : Collections.singletonList(wallpaper);
    }

    /** {@link #rect} = {@code child}'s bounds in {@code parent}'s coordinates. */
    private boolean rectIn(ViewGroup parent, View child) {
        rect.set(0, 0, child.getWidth(), child.getHeight());
        try {
            parent.offsetDescendantRectToMyCoords(child, rect);
            return true;
        } catch (IllegalArgumentException notDescendant) {
            return false;
        }
    }

    /** GONE between a view and the footer is the composer removed; INVISIBLE is its animation. */
    private static boolean goneAbove(View view, View stop) {
        int hops = 0;
        for (View v = view; v != null && hops < 8; v = parentView(v), hops++) {
            if (v.getVisibility() == View.GONE) return true;
            if (v == stop) return false;
        }
        return false;
    }

    private static ViewGroup toolbarOf(ViewGroup holder) {
        for (int i = 0; i < holder.getChildCount(); i++) {
            View child = holder.getChildAt(i);
            if (child instanceof ViewGroup && !(child instanceof GlassPane)
                    && child.getClass().getName().contains("Toolbar")) return (ViewGroup) child;
        }
        return null;
    }

    /** The footer's sibling holding the message list; child 0 is a zero-height clipper. */
    private static ViewGroup listHost(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof ViewGroup && child.getHeight() > 0 && containsList(child, 0)) {
                return (ViewGroup) child;
            }
        }
        return null;
    }

    private static boolean containsList(View view, int depth) {
        if (view instanceof AbsListView || view.getClass().getName().contains("ConversationListView")) return true;
        if (depth >= 3 || !(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (containsList(group.getChildAt(i), depth + 1)) return true;
        }
        return false;
    }

    private static AbsListView firstList(ViewGroup root) {
        if (root instanceof AbsListView) return (AbsListView) root;
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof AbsListView) return (AbsListView) child;
            if (child instanceof ViewGroup) {
                AbsListView found = firstList((ViewGroup) child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static ViewGroup find(View root, String name) {
        View view = findById(root, name);
        return view instanceof ViewGroup ? (ViewGroup) view : null;
    }

    /** Resolved once per name: this runs on every global layout of every window. */
    private static final java.util.Map<String, Integer> IDS = new java.util.HashMap<>();

    private static View findById(View root, String name) {
        Integer id = IDS.get(name);
        if (id == null) {
            id = root.getResources().getIdentifier(name, "id", root.getContext().getPackageName());
            IDS.put(name, id);
        }
        return id == 0 ? null : root.findViewById(id);
    }

    private static View parentView(View view) {
        ViewParent parent = view.getParent();
        return parent instanceof View ? (View) parent : null;
    }

    private static boolean isAncestor(View ancestor, View view) {
        for (View v = parentView(view); v != null; v = parentView(v)) if (v == ancestor) return true;
        return false;
    }
}
