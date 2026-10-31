package com.waenhancer.xposed.features.customization;

import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.SystemClock;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.AbsListView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.waenhancer.theme.GlassMaterialDrawable;
import com.waenhancer.theme.GlassPane;
import com.waenhancer.theme.GlassSpec;
import com.waenhancer.theme.WallpaperUnderlay;
import com.waenhancer.theme.ComposerGeometry;
import com.waenhancer.theme.GlassTrace;
import com.waenhancer.theme.GlassProbe;
import com.waenhancer.theme.ComposerTransition;
import com.waenhancer.theme.RecorderLayout;

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
        GlassSpec material(View root);
        void report(String key, String message);
    }

    /** One conversation window: what was changed and what to put back. */
    private static final class Screen {
        WeakReference<ViewGroup> holder = new WeakReference<>(null);
        WeakReference<ViewGroup> headerSource = new WeakReference<>(null);
        WeakReference<ViewGroup> coordinator = new WeakReference<>(null);
        WeakReference<ViewGroup> footer = new WeakReference<>(null);
        WeakReference<ViewGroup> listHost = new WeakReference<>(null);
        WeakReference<AbsListView> list = new WeakReference<>(null);
        WeakReference<View> wallpaper = new WeakReference<>(null);

        WallpaperUnderlay band;
        GlassPane capsule, composer;
        WeakReference<View> root = new WeakReference<>(null);
        boolean suspended;
        boolean syncingFooter;
        boolean recorderWakeQueued;
        final ComposerTransition transition = new ComposerTransition();
        ViewTreeObserver.OnPreDrawListener footerPreDraw;
        final Rect committedPane = new Rect();
        final Rect candidatePane = new Rect();
        int committedFooterHeight;
        int originalSystemUiVisibility;
        int originalFitInsetsTypes;
        int originalFitInsetsSides;
        boolean originalFitInsetsIgnoringVisibility;
        final WeakHashMap<ViewGroup, Boolean> recordingClipPadding = new WeakHashMap<>();
        String geometryKey;
        View.OnLayoutChangeListener holderListener, footerListener;

        // Originals, recorded once when first changed.
        Integer holderBottomMargin;
        Integer holderTopMargin;
        WeakReference<Window> window = new WeakReference<>(null);
        boolean fullscreenOwned;
        boolean statusBarWasVisible = true;
        int originalSystemBarsBehavior;
        boolean systemBarsBehaviorChanged;
        int originalCutoutMode;
        int fullscreenTopInset;
        Float holderZ;
        final WeakHashMap<View, Drawable> clearedBackgrounds = new WeakHashMap<>();
        final WeakHashMap<View, Drawable> clearedForegrounds = new WeakHashMap<>();
        /** The native icon tint, restored exactly when the composer glass is disabled. */
        final WeakHashMap<ImageView, ColorStateList> originalInputIconTints = new WeakHashMap<>();
        final WeakHashMap<View, Drawable> voiceBackgrounds = new WeakHashMap<>();
        final WeakHashMap<View, Drawable> voiceClearDrawables = new WeakHashMap<>();
        final WeakHashMap<TextView, ColorStateList> replyTextColors = new WeakHashMap<>();
        final WeakHashMap<TextView, Integer> replyAppliedColors = new WeakHashMap<>();
        final WeakHashMap<ImageView, ColorStateList> replyIconTints = new WeakHashMap<>();
        boolean voiceRecording;
        String voiceGeometryKey;
        final WeakHashMap<View, Float> duplicateReplyAlpha = new WeakHashMap<>();
        /** Native {translationX, translationY} of the red recording indicator before any change. */
        final WeakHashMap<View, float[]> recordingMicOffsets = new WeakHashMap<>();
        /** Incremented on each global layout; with the fingerprint it gates per-frame work. */
        long layoutGeneration, sampledGeneration = -1L;
        int footerFingerprint;
        /** Decoration (native backing clear, quote, mic) still owed for the current geometry. */
        boolean decorationPending;
        int decorationAttempts;
        Object decoratedMaterial;
        String laneKey;
        int topTraces;
        String topKey;
        final int[] location = new int[2];
        final WeakHashMap<ViewGroup, Boolean> recordingClipChildren = new WeakHashMap<>();
        Integer listHostBottomMargin;
        Integer listHostTopMargin;
        WeakReference<ViewGroup> topExtendedHost = new WeakReference<>(null);
        final WeakHashMap<ViewGroup, boolean[]> topClipFlags = new WeakHashMap<>();
        Integer floatedFooterHeight;
        Float footerTranslationY;
        int[] listPadding;
        Boolean listClipToPadding;

        boolean headerOn, footerOn;
    }

    private final Host host;
    private final WeakHashMap<View, Screen> screens = new WeakHashMap<>();
    private final WeakHashMap<View, WeakReference<Window>> windows = new WeakHashMap<>();
    private final Rect rect = new Rect();

    ConversationGlassPanes(Host host) {
        this.host = host;
    }

    /** The lifecycle callback owns the activity/window identity; never infer it from a View. */
    void setActivityWindow(View root, Window window) {
        if (root == null || window == null) return;
        WeakReference<Window> reference = new WeakReference<>(window);
        windows.put(root, reference);
        Screen screen = screens.get(root);
        if (screen != null) screen.window = reference;
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
            if (screen.voiceBackgrounds.containsKey(view)) return true;
        }
        return false;
    }

    /**
     * The window is going away: undo everything and drop its entry. The entry's panes reach the
     * window's root through their parents, so a weak key alone would never let it be collected.
     */
    void release(View root) {
        Screen screen = root == null ? null : screens.remove(root);
        if (root != null) windows.remove(root);
        if (screen == null) return;
        try {
            if (screen.headerOn) disableHeader(screen);
            if (screen.footerOn) disableFooter(screen);
        } catch (Throwable error) {
            host.report("conv-release-error", "conversation panes release failed: " + error);
        }
    }

    void setSuspended(View root, boolean suspended) {
        Screen screen = screens.get(root);
        if (screen == null) return;
        screen.suspended = suspended;
        if (!suspended) {
            // A resumed (re-entered) chat measures its top continuity again.
            screen.topTraces = 0;
            screen.topKey = null;
            screen.sampledGeneration = -1L;
        }
        if (screen.capsule != null) screen.capsule.setSuspended(suspended);
        if (screen.composer != null) screen.composer.setSuspended(suspended);
    }

    /**
     * Native recorder visibility/inflation (and its first timer text) can precede the global
     * layout that swaps input and recorder. Each genuine start/end opens one transition epoch and
     * wakes exactly one decoration frame; timer ticks within an epoch wake nothing. Decoration
     * never runs on WhatsApp's visibility/inflation stack and never consumes touch.
     */
    void onRecorderSignal(View changed) {
        if (changed == null || !host.composerEnabled()) return;
        Screen screen = screens.get(changed.getRootView());
        if (screen == null || !screen.footerOn || screen.suspended) {
            if (GlassProbe.enabled()) GlassProbe.event("RECORDER_SIGNAL_UNROUTED", changed,
                    "screen=" + (screen != null) + " footerOn=" + (screen != null && screen.footerOn));
            return;
        }
        ViewGroup footer = screen.footer.get();
        if (footer == null || !isAncestor(footer, changed)) {
            if (GlassProbe.enabled()) GlassProbe.event("RECORDER_SIGNAL_OUTSIDE_FOOTER", changed,
                    "footer=" + GlassTrace.id(footer));
            return;
        }
        View anchor = null;
        for (View node = changed; node != footer && node != null; node = parentView(node)) {
            if (isRecorderAnchor(node)) {
                anchor = node;
                break;
            }
        }
        if (anchor == null || (changed instanceof TextView
                && !isElapsedTime(((TextView) changed).getText()))) return;
        long now = SystemClock.uptimeMillis();
        boolean shown = recorderShown(footer);
        boolean accepted = screen.transition.signal(shown, now);
        if (GlassProbe.enabled()) GlassProbe.event("RECORDER_SIGNAL", anchor,
                "accepted=" + accepted + " shown=" + shown
                        + " epoch=" + screen.transition.epoch()
                        + " state=" + screen.transition.state()
                        + " footer=" + GlassTrace.id(footer));
        // Physical panel swaps within an epoch still need reconciliation.
        screen.sampledGeneration = -1L;
        screen.decorationPending = true;
        screen.decorationAttempts = 0;
        if (accepted) GlassProbe.event("RECORDER_MODE_CHANGED", footer,
                "recording=" + shown + " epoch=" + screen.transition.epoch());
        if (accepted) GlassTrace.event(screen.root.get(), screen.composer, anchor,
                shown ? "RECORD_START_SIGNAL" : "RECORD_END_SIGNAL", "native-recorder-visibility",
                "epoch=" + screen.transition.epoch() + " anchor=" + resourceName(anchor)
                        + " nativeT=" + now);
        footer.postInvalidateOnAnimation();
        // The native visibility hook often fires before the recorder's layout invalidates its
        // host. Queue an immediate read on the UI loop; a touch/scroll must not be required to
        // publish the first recording frame (or to restore the input on cancel).
        if (screen.recorderWakeQueued) return;
        screen.recorderWakeQueued = true;
        final long signalEpoch = screen.transition.epoch();
        footer.post(() -> {
            screen.recorderWakeQueued = false;
            if (GlassProbe.enabled()) GlassProbe.event("RECORDER_WAKE", footer,
                    "signalEpoch=" + signalEpoch + " currentEpoch=" + screen.transition.epoch()
                            + " active=" + screen.footerOn + " suspended=" + screen.suspended);
            if (screen.footerOn && !screen.suspended && screen.footer.get() == footer) syncFooter(screen);
        });
    }

    private static boolean isRecorderAnchor(View view) {
        int id = view.getId();
        return id != View.NO_ID && id != 0
                && (id == idOf(view, "voice_note_stub") || id == idOf(view, "voice_note_draft_layout_v2"));
    }

    private static boolean recorderShown(ViewGroup footer) {
        return activeVoicePanel(footer, findById(footer, "input_layout")) != null;
    }

    /** Called on every global layout of a window; cheap when nothing changed. */
    void sync(View root) {
        if (root == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        try {
            Screen screen = screens.get(root);
            if (screen == null && !host.toolbarsEnabled() && !host.composerEnabled()) return;
            if (screen != null) screen.layoutGeneration++;
            ViewGroup holder = find(root, "search_fragment_and_toolbar_holder");
            ViewGroup footer = find(root, "footer");
            if (footer != null && findById(footer, "input_layout") == null
                    && !(screen != null && screen.footer.get() == footer)
                    && findVoicePanel(footer, null) == null) footer = null;
            if (screen == null) {
                if (holder == null && footer == null) return;
                screen = new Screen();
                screen.root = new WeakReference<>(root);
                WeakReference<Window> window = windows.get(root);
                if (window != null) screen.window = window;
                screens.put(root, screen);
            }
            screen.coordinator = new WeakReference<>(find(root, "coordinator"));
            View wallpaper = findById(root, "conversation_background");
            screen.wallpaper = new WeakReference<>(wallpaper);

            ViewGroup listHost = footer != null && footer.getParent() instanceof ViewGroup
                    ? listHost((ViewGroup) footer.getParent()) : null;

            screen.headerSource = new WeakReference<>(listHost);
            if (screen.listHost.get() == null && listHost != null) {
                screen.listHost = new WeakReference<>(listHost);
                screen.list = new WeakReference<>(firstList(listHost));
            }
            // A restored chat can replace its list after the first discovery. Padding a detached
            // list leaves the live one clipped below the header until the chat is re-entered.
            AbsListView paddedList = screen.list.get();
            ViewGroup knownHost = screen.listHost.get();
            if (knownHost != null && (paddedList == null || !paddedList.isAttachedToWindow())) {
                AbsListView live = firstList(knownHost);
                if (live != null && live != paddedList) {
                    restoreListPadding(screen);
                    screen.list = new WeakReference<>(live);
                    host.report("conv-list-rebound", "conversation list replaced; padding moved to live list");
                }
            }

            boolean wantHeader = host.toolbarsEnabled() && holder instanceof FrameLayout
                    && screen.coordinator.get() != null;
            boolean wantFooter = host.composerEnabled() && footer instanceof FrameLayout
                    && listHost != null;
            if (holder != null && !(holder instanceof FrameLayout)) {
                host.report("conv-holder-type", "conversation header is " + holder.getClass().getName()
                        + ", not a FrameLayout; header panes skipped");
            }
            if (footer != null && !(footer instanceof FrameLayout)) {
                host.report("conv-footer-type", "conversation footer is " + footer.getClass().getName()
                        + ", not a FrameLayout; composer pane skipped");
            }

            if (wantHeader) enableHeader(screen, holder); else if (screen.headerOn) disableHeader(screen);
            if (wantFooter) enableFooter(screen, footer, listHost); else if (screen.footerOn) disableFooter(screen);
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
        enableConversationFullscreen(screen, holder);
        if (screen.holderListener == null) {
            screen.holderListener = (v, l, t, r, b, ol, ot, or, ob) -> {
                if (screen.headerOn) syncHeader(screen);
            };
            holder.addOnLayoutChangeListener(screen.holderListener);
        }
        syncHeader(screen);
    }

    /** Hide only the status bar in a glass-header conversation. WhatsApp's navigation bar,
     * keyboard and other activities are not modified. Restore window flags on exit. */
    private void enableConversationFullscreen(Screen screen, ViewGroup holder) {
        View root = screen.root.get();
        Window window = screen.window.get();
        if (window == null || root == null) return;
        if (!screen.fullscreenOwned) {
            WindowManager.LayoutParams original = window.getAttributes();
            screen.originalSystemUiVisibility = window.getDecorView().getSystemUiVisibility();
            screen.originalFitInsetsTypes = original.getFitInsetsTypes();
            screen.originalFitInsetsSides = original.getFitInsetsSides();
            screen.originalFitInsetsIgnoringVisibility = original.isFitInsetsIgnoringVisibility();
            // Explicit top-only edge-to-edge policy. Keeping navigation/IME fitting native
            // avoids replacing WhatsApp's inset listener or breaking adjustResize.
            original.setFitInsetsTypes(screen.originalFitInsetsTypes
                    & ~WindowInsets.Type.statusBars() & ~WindowInsets.Type.displayCutout());
            // LAYOUT_STABLE is deliberately not set: with it, legacy fitsSystemWindows views are
            // given the *hidden* status-bar height as system-window insets (stable insets), so a
            // WhatsApp root that fits system windows keeps a top padding with nothing drawn in
            // it: the black strip above the floating header.
            window.getDecorView().setSystemUiVisibility((screen.originalSystemUiVisibility
                    & ~View.SYSTEM_UI_FLAG_LAYOUT_STABLE) | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            screen.originalCutoutMode = original.layoutInDisplayCutoutMode;
            WindowInsets insets = root.getRootWindowInsets();
            if (insets != null) {
                screen.statusBarWasVisible = insets.isVisible(WindowInsets.Type.statusBars());
                screen.fullscreenTopInset = insets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.statusBars()).top;
                if (insets.getDisplayCutout() != null) {
                    screen.fullscreenTopInset = Math.max(screen.fullscreenTopInset,
                            insets.getDisplayCutout().getSafeInsetTop());
                }
            }
            original.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(original);
            // FLAG_FULLSCREEN ignores SOFT_INPUT_ADJUST_RESIZE on Android. The chat composer
            // needs native IME resizing, so hide the status bar via insets only.
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                screen.originalSystemBarsBehavior = controller.getSystemBarsBehavior();
                screen.systemBarsBehaviorChanged = true;
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.statusBars());
            }
            screen.fullscreenOwned = true;
            host.report("conversation-fullscreen", "conversation status bar hidden; navigation and IME preserved");
        }
        WindowInsets currentInsets = root.getRootWindowInsets();
        if (currentInsets != null) {
            // The header stays below the line the status bar/cutout would occupy, whether or not
            // WhatsApp's own root still pads for it. With a padded parent (the accepted build)
            // this is the same screen position as before; with a parent at y=0 the name does
            // not move up into the camera area.
            int cutout = currentInsets.getDisplayCutout() == null ? 0
                    : currentInsets.getDisplayCutout().getSafeInsetTop();
            int statusLine = currentInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.statusBars()).top;
            screen.fullscreenTopInset = Math.max(cutout, statusLine);
        }
        if (holder.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) holder.getLayoutParams();
            if (screen.holderTopMargin == null) screen.holderTopMargin = lp.topMargin;
            int spacing = Math.round(1f * holder.getResources().getDisplayMetrics().density);
            int parentTop = holder.getParent() instanceof View ? screenTop((View) holder.getParent()) : 0;
            int target = screen.holderTopMargin + Math.max(0, screen.fullscreenTopInset - parentTop) + spacing;
            if (lp.topMargin != target) {
                lp.topMargin = target;
                holder.setLayoutParams(lp);
            }
        }
    }

    private void restoreConversationFullscreen(Screen screen) {
        if (!screen.fullscreenOwned) return;
        Window window = screen.window.get();
        if (window != null) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                if (screen.statusBarWasVisible) controller.show(WindowInsets.Type.statusBars());
                if (screen.systemBarsBehaviorChanged) {
                    controller.setSystemBarsBehavior(screen.originalSystemBarsBehavior);
                }
            }
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.layoutInDisplayCutoutMode = screen.originalCutoutMode;
            attrs.setFitInsetsTypes(screen.originalFitInsetsTypes);
            attrs.setFitInsetsSides(screen.originalFitInsetsSides);
            attrs.setFitInsetsIgnoringVisibility(screen.originalFitInsetsIgnoringVisibility);
            window.getDecorView().setSystemUiVisibility(screen.originalSystemUiVisibility);
            window.setAttributes(attrs);
        }
        screen.systemBarsBehaviorChanged = false;
        screen.fullscreenOwned = false;
    }

    /** Negative bottom margin grows the coordinator up under the header; Z keeps rows beneath it. */
    private void floatHeader(Screen screen, ViewGroup holder) {
        int h = holder.getHeight();
        if (h <= 0 || !(holder.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) holder.getLayoutParams();
        if (screen.holderBottomMargin == null) {
            screen.holderBottomMargin = lp.bottomMargin;
            screen.holderZ = holder.getTranslationZ();
        }
        if (lp.bottomMargin == -h - lp.topMargin) return;
        // Offset the complete header reservation, including its safe-area margin. The list
        // then starts at the parent's origin and gets one coordinate-derived resting pad.
        lp.bottomMargin = -h - lp.topMargin;
        holder.setLayoutParams(lp);
        if (holder.getTranslationZ() < 1f) holder.setTranslationZ(1f);
        host.report("conv-header-float", "conversation header floated (h=" + h + ")");
    }

    private void syncHeader(Screen screen) {
        ViewGroup holder = screen.holder.get();
        if (holder == null || holder.getWidth() <= 0 || holder.getHeight() <= 0) return;
        float d = holder.getResources().getDisplayMetrics().density;

        // Resolve every required view and the capsule geometry first: a WhatsApp build that
        // renamed the toolbar or Back button must leave the header untouched, not half-floated.
        View searchBar = findById(holder, "search_view_toolbar");
        ViewGroup toolbar = null;
        int inset = Math.round(PILL_INSET_DP * d);
        int l = inset, r = holder.getWidth() - inset, t, b;
        if (searchBar != null && searchBar.isShown() && searchBar.getWidth() > 0) {
            t = 0;
            b = holder.getHeight();
        } else {
            searchBar = null;
            toolbar = toolbarOf(holder);
            if (toolbar == null || !toolbar.isShown()) return;
            // The band comes from the Back button, not the bar: the elements do not share a height.
            View back = findById(toolbar, "whatsapp_toolbar_home");
            if (back == null || back.getWidth() <= 0 || !rectIn(holder, back)) {
                host.report("conv-header-geometry", "conversation Back button not found; header left native");
                return;
            }
            int trim = Math.round(PILL_TRIM_DP * d), grow = Math.round(CAPSULE_GROW_DP * d);
            t = Math.max(0, rect.top + trim / 2 - grow);
            b = Math.min(holder.getHeight(), rect.bottom - trim / 2 + grow);
        }
        if (r <= l || b <= t) return;

        extendListToWindowTop(screen);
        floatHeader(screen, holder);
        clearBackground(screen, holder);
        if (searchBar != null) {
            clearBackground(screen, searchBar);
        } else {
            clearBackground(screen, toolbar);
            if (toolbar.getForeground() != null) {
                if (!screen.clearedForegrounds.containsKey(toolbar)) {
                    screen.clearedForegrounds.put(toolbar, toolbar.getForeground());
                }
                toolbar.setForeground(null);
            }
        }
        // The continuity layer is behind the entire conversation, never inside the header
        // in front of scrolling messages. It draws the current native wallpaper, not a tint.
        View content = screen.root.get() == null ? null : screen.root.get().findViewById(android.R.id.content);
        if (content instanceof FrameLayout && screen.wallpaper.get() != null) {
            FrameLayout backgroundHost = (FrameLayout) content;
            // A view inside android.R.id.content can never paint above content's top. When the
            // window still lays content out below y=0 (a native top inset), the continuity layer
            // is hosted by the decor instead, beneath the whole content tree.
            View decor = screen.root.get();
            if (decor instanceof FrameLayout && decor != content
                    && ((screen.fullscreenOwned && screenTop(content) > 0)
                        || (screen.band != null && screen.band.getParent() == decor))) {
                // Sticky for this screen: re-hosting on every inset change would churn views.
                backgroundHost = (FrameLayout) decor;
            }
            if (screen.band == null || screen.band.getParent() != backgroundHost) {
                removePane(screen.band);
                screen.band = new WallpaperUnderlay(backgroundHost.getContext());
                screen.band.setFillViewport(true);
                backgroundHost.addView(screen.band, 0, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            screen.band.setWallpaper(screen.wallpaper.get());
        }
        View source = screen.headerSource.get();
        if (screen.capsule == null || screen.capsule.getParent() != holder) {
            screen.capsule = newPane(screen, holder, source, wallpaperOf(screen));
            screen.capsule.setTopWashStrength(0.12f);
            screen.capsule.setSourceResolver(() -> {
                View root = screen.root.get();
                ViewGroup footer = root == null ? null : find(root, "footer");
                return footer != null && footer.getParent() instanceof ViewGroup
                        ? listHost((ViewGroup) footer.getParent()) : null;
            });
            holder.addView(screen.capsule, 0, new FrameLayout.LayoutParams(0, 0));
        }
        screen.capsule.setSource(source);
        screen.capsule.setUnderlay(wallpaperOf(screen));
        place(screen.capsule, l, t, r, b, (b - t) / 2f);
        traceTopContinuity(screen, holder, content);
    }

    /**
     * Geometry-only evidence for the status/cutout region, on first entry and after each
     * re-entry (at most three distinct measurements per epoch): where decor, content, wallpaper,
     * continuity layer, list and header actually are, the top insets, and which ancestors of the
     * list clip. No content, names or pixels.
     */
    private void traceTopContinuity(Screen screen, ViewGroup holder, View content) {
        if (screen.topTraces >= 3) return;
        View root = screen.root.get();
        AbsListView list = screen.list.get();
        if (root == null || list == null || !list.isLaidOut()) return;
        StringBuilder clips = new StringBuilder();
        for (View node = list; node != null && node != root; node = parentView(node)) {
            if (node instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) node;
                clips.append(group.getClipChildren() ? 'C' : 'c').append(group.getClipToPadding() ? 'P' : 'p');
                if (node.getPaddingTop() > 0) clips.append('^').append(node.getPaddingTop());
                clips.append('/');
            }
        }
        WindowInsets insets = root.getRootWindowInsets();
        int status = insets == null ? -1 : insets.getInsets(WindowInsets.Type.statusBars()).top;
        int stable = insets == null ? -1 : insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top;
        int cutout = insets == null || insets.getDisplayCutout() == null ? 0 : insets.getDisplayCutout().getSafeInsetTop();
        View wallpaper = screen.wallpaper.get();
        View band = screen.band;
        String key = "decor=" + screenTop(root) + " content=" + (content == null ? -1 : screenTop(content))
                + "+" + (content == null ? 0 : content.getPaddingTop())
                + " wallpaper=" + (wallpaper == null ? -1 : screenTop(wallpaper))
                + " band=" + (band == null ? -1 : screenTop(band)) + "/" + (band == null ? 0 : band.getHeight())
                + " bandHost=" + (band == null || band.getParent() == null ? "none"
                        : band.getParent() == root ? "decor" : "content")
                + " list=" + screenTop(list) + "+" + list.getPaddingTop() + " clipToPadding=" + list.getClipToPadding()
                + " header=" + screenTop(holder) + "/" + holder.getHeight()
                + " insets(status=" + status + " stable=" + stable + " cutout=" + cutout + ")"
                + " anchor=" + screen.fullscreenTopInset + " ancestors=" + clips;
        if (key.equals(screen.topKey)) return;
        screen.topKey = key;
        screen.topTraces++;
        GlassTrace.event(root, screen.capsule, list, "TOP_CONTINUITY", "measured", key);
    }

    /** Grow only the message-list host into the visual inset; touch chrome stays safe. */
    private static void extendListToWindowTop(Screen screen) {
        ViewGroup listHost = screen.listHost.get();
        View root = screen.root.get();
        if (listHost == null || root == null || !listHost.isLaidOut()
                || !(listHost.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
        if (screen.topExtendedHost.get() != listHost) {
            restoreListTop(screen);
            screen.topExtendedHost = new WeakReference<>(listHost);
        }
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) listHost.getLayoutParams();
        if (screen.listHostTopMargin == null) screen.listHostTopMargin = lp.topMargin;
        int nativeTop = screenTop(listHost) - (lp.topMargin - screen.listHostTopMargin);
        int overlap = Math.max(0, nativeTop - screenTop(root));
        // Only consume the known system-safe strip, never an arbitrary application offset.
        overlap = Math.min(overlap, screen.fullscreenTopInset);
        int target = screen.listHostTopMargin - overlap;
        if (lp.topMargin != target) { lp.topMargin = target; listHost.setLayoutParams(lp); }
        for (View node = parentView(listHost); node != null && node != root; node = parentView(node)) {
            if (!(node instanceof ViewGroup)) continue;
            ViewGroup group = (ViewGroup) node;
            if (!screen.topClipFlags.containsKey(group)) screen.topClipFlags.put(group,
                    new boolean[]{group.getClipChildren(), group.getClipToPadding()});
            if (group.getClipChildren()) group.setClipChildren(false);
            if (group.getClipToPadding()) group.setClipToPadding(false);
        }
    }

    private static void restoreListTop(Screen screen) {
        ViewGroup listHost = screen.topExtendedHost.get();
        if (listHost != null && screen.listHostTopMargin != null
                && listHost.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) listHost.getLayoutParams();
            lp.topMargin = screen.listHostTopMargin;
            listHost.setLayoutParams(lp);
        }
        screen.listHostTopMargin = null;
        screen.topExtendedHost = new WeakReference<>(null);
        for (java.util.Map.Entry<ViewGroup, boolean[]> entry : screen.topClipFlags.entrySet()) {
            entry.getKey().setClipChildren(entry.getValue()[0]);
            entry.getKey().setClipToPadding(entry.getValue()[1]);
        }
        screen.topClipFlags.clear();
    }

    private void disableHeader(Screen screen) {
        screen.headerOn = false;
        ViewGroup holder = screen.holder.get();
        removePane(screen.band);
        removePane(screen.capsule);
        screen.band = null;
        screen.capsule = null;
        if (holder != null) {
            if (screen.holderListener != null) holder.removeOnLayoutChangeListener(screen.holderListener);
            if (screen.holderBottomMargin != null
                    && holder.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) holder.getLayoutParams();
                lp.bottomMargin = screen.holderBottomMargin;
                holder.setLayoutParams(lp);
            }
            if (screen.holderTopMargin != null
                    && holder.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) holder.getLayoutParams();
                lp.topMargin = screen.holderTopMargin;
                holder.setLayoutParams(lp);
            }
            if (screen.holderZ != null) holder.setTranslationZ(screen.holderZ);
        }
        restoreListTop(screen);
        restoreConversationFullscreen(screen);
        screen.holderListener = null;
        screen.holderTopMargin = null;
        screen.holderBottomMargin = null;
        screen.holderZ = null;
        restoreUnder(screen, holder);
    }

    // ── Footer ────────────────────────────────────────────────────────────────────

    private void enableFooter(Screen screen, ViewGroup footer, ViewGroup listHost) {
        if (screen.footer.get() != footer || screen.listHost.get() != listHost) {
            // WhatsApp replaced the footer or its list: restore the old views with their own
            // recorded values before recording the new ones.
            if (screen.footerOn) disableFooter(screen);
            AbsListView list = firstList(listHost);
            if (screen.list.get() != list) restoreListPadding(screen);
            screen.footer = new WeakReference<>(footer);
            screen.listHost = new WeakReference<>(listHost);
            screen.list = new WeakReference<>(list);
        }
        screen.footerOn = true;
        if (screen.footerListener == null) {
            screen.footerListener = (v, l, t, r, b, ol, ot, or, ob) -> {
                if (screen.footerOn && !screen.syncingFooter) footer.postInvalidateOnAnimation();
            };
            screen.footerPreDraw = () -> {
                if (screen.footerOn && !screen.suspended) syncFooter(screen);
                return true;
            };
            footer.getViewTreeObserver().addOnPreDrawListener(screen.footerPreDraw);
            footer.addOnLayoutChangeListener(screen.footerListener);
        }
        // The pre-draw observer samples all native controls in the same layout epoch.
    }

    /** The list host grows under the footer; the footer rides up off the bottom edge. */
    private void floatFooter(Screen screen, ViewGroup footer) {
        ViewGroup listHost = screen.listHost.get();
        int h = footer.getHeight();
        if (listHost == null || h <= 0
                || !(listHost.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
        if (screen.listHostBottomMargin != null) {
            // A quoted reply or locked voice recording increases the footer's height. The
            // message-list host must extend behind the *new* height, including the strip next
            // to the action disc. Only adjust on a measured height transition: reacting to a
            // stale top/margin on every layout caused oscillation in the old home chrome.
            if (screen.floatedFooterHeight != null && screen.floatedFooterHeight == h) return;
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) listHost.getLayoutParams();
            lp.bottomMargin = -h;
            screen.floatedFooterHeight = h;
            listHost.setLayoutParams(lp);
            return;
        }
        AbsListView list = screen.list.get();
        boolean atEnd = list != null && restsAtEnd(list);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) listHost.getLayoutParams();
        screen.listHostBottomMargin = lp.bottomMargin;
        screen.floatedFooterHeight = h;
        screen.footerTranslationY = footer.getTranslationY();
        lp.bottomMargin = -h;
        listHost.setLayoutParams(lp);
        footer.setTranslationY(-COMPOSE_LIFT_DP * footer.getResources().getDisplayMetrics().density);
        if (atEnd) keepAtEnd(list);
        host.report("conv-footer-float", "conversation footer floated (h=" + h + ")");
    }

    private void syncFooter(Screen screen) {
        if (screen.syncingFooter || screen.suspended || !screen.footerOn) return;
        screen.syncingFooter = true;
        try {
            syncFooterFrame(screen);
        } catch (Throwable error) {
            // Only decoration is rolled back; native recording and its gestures are untouched.
            disableFooter(screen);
            host.report("voice-decoration-failed", "native composer retained: " + error);
        } finally {
            screen.syncingFooter = false;
        }
    }

    /** Bounded next-frame retries for decoration owed while the replacement glass is not live. */
    private static final int DECORATION_RETRIES = 8;

    private void syncFooterFrame(Screen screen) {
        ViewGroup footer = screen.footer.get();
        if (footer == null || footer.getWidth() <= 0 || footer.getHeight() <= 0) {
            if (GlassProbe.enabled()) GlassProbe.event("FOOTER_UNMEASURED", footer,
                    "epoch=" + screen.transition.epoch());
            return;
        }
        View input = findById(footer, "input_layout");
        // This observer runs on every frame the window draws (including list scrolling). Without
        // a layout, a native translation or a pending transition nothing here can change, so the
        // tree walks, string keys and diagnostics below are skipped.
        Object material = host.material(screen.root.get());
        int fingerprint = footerFingerprint(screen, footer, input, material);
        if (fingerprint == screen.footerFingerprint && screen.sampledGeneration == screen.layoutGeneration
                && !screen.transition.pending() && !screen.decorationPending) return;
        if (GlassProbe.enabled()) GlassProbe.event("FOOTER_SAMPLE", footer,
                "epoch=" + screen.transition.epoch() + " state=" + screen.transition.state()
                        + " fingerprintChanged=" + (fingerprint != screen.footerFingerprint)
                        + " layoutChanged=" + (screen.sampledGeneration != screen.layoutGeneration)
                        + " pending=" + screen.transition.pending()
                        + " decorationPending=" + screen.decorationPending
                        + " input=" + GlassProbe.describe(input));
        screen.footerFingerprint = fingerprint;
        screen.sampledGeneration = screen.layoutGeneration;
        if (screen.composer != null && !screen.committedPane.isEmpty()) {
            Rect placed = screen.committedPane;
            screen.composer.restoreDecorationBounds(placed.left, placed.top, placed.right, placed.bottom,
                    Math.min(placed.height() / 2f, COMPOSE_MAX_RADIUS_DP
                            * footer.getResources().getDisplayMetrics().density));
        }
        long now = SystemClock.uptimeMillis();
        float d = footer.getResources().getDisplayMetrics().density;
        ViewGroup voice = activeVoicePanel(footer, input);
        if (screen.transition.signal(voice != null, now)) {
            GlassProbe.event("RECORDER_MODE_CHANGED", footer, "recording=" + (voice != null)
                    + " epoch=" + screen.transition.epoch());
            GlassTrace.event(screen.root.get(), screen.composer, footer,
                    voice != null ? "RECORD_START_SIGNAL" : "RECORD_END_SIGNAL",
                    "native-layout-reconciled", "epoch=" + screen.transition.epoch());
        }
        if (voice != null && rectIn(footer, voice)) {
            if (GlassProbe.enabled()) GlassProbe.event("ROUTE_VOICE", voice,
                    "epoch=" + screen.transition.epoch() + " quote="
                            + GlassProbe.describe(findReplyPreview(footer)));
            syncVoiceFrame(screen, footer, voice, now, d);
            return;
        }
        boolean inputUsable = voice == null && input != null && input.isShown()
                && !goneAbove(input, footer) && input.getWidth() > 0 && rectIn(footer, input);
        if (inputUsable && syncInputFrame(screen, footer, input, material, now, d)) return;
        if (GlassProbe.enabled()) GlassProbe.event("ROUTE_UNRESOLVED", footer,
                "epoch=" + screen.transition.epoch() + " voice=" + GlassProbe.describe(voice)
                        + " inputUsable=" + inputUsable + " input=" + GlassProbe.describe(input));
        // Neither route is measurable in this frame: WhatsApp is swapping input and recorder, or
        // this build's recorder cannot be identified. Keep the persistent pane at its last valid
        // geometry; never blank it on a transient frame (the "floating icons" after cancel).
        int flags = screen.transition.sample(ComposerTransition.Route.UNRESOLVED, null, now);
        if ((flags & ComposerTransition.HIDE) != 0) {
            host.report("voice-panel-route-missing",
                    "composer route not resolved; " + describeFooter(footer));
            restoreVoiceDecoration(screen);
            if (screen.composer != null) screen.composer.setVisibility(View.INVISIBLE);
            GlassTrace.event(screen.root.get(), screen.composer, footer, "COMPOSER_HIDDEN",
                    "route-unresolved", "epoch=" + screen.transition.epoch()
                            + " bridgeMaxMs=" + ComposerTransition.BRIDGE_MAX_MS);
        } else if ((flags & ComposerTransition.HOLD) != 0) {
            footer.postInvalidateOnAnimation();
        }
    }

    /** The persistent composer pane follows the native recorder; its controls stay native. */
    private void syncVoiceFrame(Screen screen, ViewGroup footer, ViewGroup voice, long now, float d) {
        restoreReplyContrast(screen);
        int pad = Math.round(COMPOSE_PAD_DP * d);
        // Measured native screen-space bounds mapped into the footer, never a guessed panel.
        int[] voiceRect = rectOf(footer, voice);
        suppressDuplicateReply(screen, footer);
        View quote = findReplyPreview(footer);
        int[] quoteRect = quote != null && quote.getWidth() > 0 && quote.getHeight() > 0
                ? rectOf(footer, quote) : null;
        int[] envelope = RecorderLayout.union(voiceRect, quoteRect);
        if (envelope == null) return;
        int l = pad, t = Math.min(pad, envelope[1]);
        int r = footer.getWidth() - pad;
        int b = envelope[3] + pad;
        // The recorder draft can be taller than the footer's first measurement; clamp only to
        // the footer's parent, the extent this pane can safely draw into.
        View parent = parentView(footer);
        if (parent != null) {
            int[] extent = rectOf(footer, parent);
            t = Math.max(t, extent[1]);
            b = Math.min(b, extent[3]);
        }
        View action = findById(footer, "conversation_entry_action_button");
        int[] actionRect = null;
        if (action != null && action.isShown()
                && action.getWidth() <= 72f * d && action.getHeight() <= 72f * d
                && rectIn(footer, action)) {
            actionRect = new int[]{rect.left, rect.top, rect.right, rect.bottom};
            int[] edges = ComposerGeometry.separate(l, r, t, b,
                    rect.left, rect.right, rect.top, rect.bottom,
                    Math.max(1, Math.round(2f * d)));
            l = edges[0]; r = edges[1];
        }
        if (r <= l || b <= t) return;
        String geometry = l + ":" + t + ":" + r + ":" + b;
        int flags = screen.transition.sample(ComposerTransition.Route.VOICE,
                footer.getWidth() + ":" + footer.getHeight() + ":" + geometry, now);
        if (GlassProbe.enabled()) GlassProbe.event("VOICE_GEOMETRY", voice,
                "epoch=" + screen.transition.epoch() + " flags=" + flags
                        + " envelope=" + RecorderLayout.describe(envelope)
                        + " quote=" + RecorderLayout.describe(quoteRect)
                        + " action=" + RecorderLayout.describe(actionRect)
                        + " target=" + geometry + " previous=" + screen.committedPane.toShortString());
        ensureComposer(screen, footer);
        screen.composer.setVisibility(View.VISIBLE);
        screen.composer.setSource(screen.listHost.get());
        screen.composer.setUnderlay(wallpaperOf(screen));
        if ((flags & ComposerTransition.PRESENT) != 0) {
            if (t < 0 || b > footer.getHeight()) allowRecordingGlassOverflow(screen, footer);
            presentComposer(screen, l, t, r, b, d);
            screen.decorationPending = true;
            screen.decorationAttempts = 0;
        }
        // Quote slots and the mic can change without changing the recorder envelope. This runs
        // on every sampled frame (already gated by layout generation and footer fingerprint),
        // not only while the backdrop publication below is pending.
        suppressDuplicateReply(screen, footer);
        layoutRecordingMic(screen, footer, voice, quoteRect, actionRect, d);
        if (screen.decorationPending) {
            if (GlassProbe.enabled()) GlassProbe.event("VOICE_BACKING_GATE", voice,
                    "epoch=" + screen.transition.epoch() + " paneLive=" + screen.composer.hasLiveRecording()
                            + " attempts=" + screen.decorationAttempts
                            + " nativeCleared=" + screen.voiceClearDrawables.size());
            // Clear WhatsApp's opaque recorder backing only once replacement glass is live for
            // this geometry; until then the native backing stays (no transparent hole, no slab).
            if (screen.composer.hasLiveRecording()) {
                clearVoiceBackgrounds(screen, voice);
                screen.decorationPending = false;
            } else if (++screen.decorationAttempts > DECORATION_RETRIES) {
                screen.decorationPending = false;
                host.report("voice-glass-not-live", "voice glass not live after "
                        + DECORATION_RETRIES + " frames; native recorder backing kept");
            }
        }
        // Recycled native recorder holders can restore their opaque background while retaining
        // the same geometry. A sampled native background replacement must be cleared again.
        if (screen.composer.hasLiveRecording()) {
            clearVoiceBackgrounds(screen, voice);
            if (quote != null) clearReplyBacking(screen, footer, quote);
        }
        if ((flags & ComposerTransition.RESERVE) != 0) reserveFooter(screen, footer);
        if (screen.transition.pending() || screen.decorationPending) footer.postInvalidateOnAnimation();
        if ((flags & ComposerTransition.PRESENT) != 0
                && (!screen.voiceRecording || !geometry.equals(screen.voiceGeometryKey))) {
            screen.voiceGeometryKey = geometry;
            screen.voiceRecording = true;
            host.report("voice-glass-bound", "voice panel glass follows native recording UI; "
                    + "panel=" + resourceName(voice) + " geometry=" + geometry);
            GlassTrace.event(screen.root.get(), screen.composer, voice, "VOICE_RECORDING",
                    "panel-present", "geometry=" + geometry + " footer=" + footer.getWidth() + "x"
                            + footer.getHeight() + " quote=" + RecorderLayout.describe(quoteRect)
                            + " state=" + screen.transition.state() + " epoch=" + screen.transition.epoch()
                            + " startToMeasuredMs=" + screen.transition.startToMeasuredMs()
                            + " live=" + screen.composer.hasLiveRecording());
        }
    }

    /** Normal input row; returns false when it is not measurable yet (an unresolved frame). */
    private boolean syncInputFrame(Screen screen, ViewGroup footer, View input, Object material,
                                   long now, float d) {
        boolean wasRecording = screen.voiceRecording || !screen.voiceClearDrawables.isEmpty()
                || !screen.recordingMicOffsets.isEmpty() || !screen.duplicateReplyAlpha.isEmpty();
        if (wasRecording && !screen.transition.recording()) restoreVoiceDecoration(screen);
        Rect inputRect = new Rect(rect);
        View inputQuote = findReplyPreview(footer);
        if (inputQuote != null && inputQuote.getAlpha() > 0f) {
            int[] q = rectOf(footer, inputQuote);
            inputRect.union(q[0], q[1], q[2], q[3]);
        }
        int pad = Math.round(COMPOSE_PAD_DP * d);
        // The send disc's own screen margin; the pill's left edge mirrors it or the corner clips.
        int sideFloor = 0;
        Rect actionRect = null;
        View send = findById(footer, "conversation_entry_action_button");
        if (send == null || !send.isShown() || send.getWidth() <= 0 || send.getHeight() <= 0) {
            return false;
        }
        if (rectIn(footer, send)) {
            actionRect = new Rect(rect);
            sideFloor = Math.max(0, Math.min(rect.left, footer.getWidth() - rect.right));
        }
        int l = Math.max(sideFloor, inputRect.left - pad);
        int t = Math.max(0, inputRect.top - pad);
        int r = Math.min(footer.getWidth(), inputRect.right + pad);
        int b = Math.min(footer.getHeight(), inputRect.bottom + pad);
        if (actionRect != null) {
            int[] edges = ComposerGeometry.separate(l, r, t, b, actionRect.left, actionRect.right,
                    actionRect.top, actionRect.bottom, Math.max(1, Math.round(2f * d)));
            l = edges[0]; r = edges[1];
        }
        if (r <= l || b <= t || !new Rect(0, 0, footer.getWidth(), footer.getHeight()).contains(inputRect)
                || actionRect == null || Rect.intersects(new Rect(l, t, r, b), actionRect)) return false;
        String geometryKey = l + ":" + t + ":" + r + ":" + b + ":" + actionRect;
        int flags = screen.transition.sample(ComposerTransition.Route.INPUT,
                footer.getWidth() + ":" + footer.getHeight() + ":" + geometryKey, now);
        if (GlassProbe.enabled()) GlassProbe.event("INPUT_GEOMETRY", input,
                "epoch=" + screen.transition.epoch() + " flags=" + flags
                        + " wasRecording=" + wasRecording + " reply="
                        + GlassProbe.describe(findReplyPreview(footer))
                        + " target=" + l + "," + t + "," + r + "," + b
                        + " previous=" + screen.committedPane.toShortString()
                        + " action=" + actionRect);
        if ((flags & ComposerTransition.PRESENT) != 0) {
            ensureComposer(screen, footer);
            screen.composer.setSource(screen.listHost.get());
            screen.composer.setUnderlay(wallpaperOf(screen));
            presentComposer(screen, l, t, r, b, d);
            if (wasRecording) {
                GlassTrace.event(screen.root.get(), screen.composer, input, "NORMAL_RESTORED",
                        "input-measured", "epoch=" + screen.transition.epoch()
                                + " endToNormalMs=" + screen.transition.endToNormalMs()
                                + " live=" + screen.composer.hasLiveRecording());
            }
        }
        // An in-place WhatsApp background/quote rebind is independent of size. Re-clear after
        // the new pane is live; never expose an empty hole while capture is still pending.
        boolean mayClearInput = screen.composer != null && !screen.transition.recording()
                && screen.composer.hasLiveRecording()
                && screen.committedPane.left == l && screen.committedPane.top == t
                && screen.committedPane.right == r && screen.committedPane.bottom == b;
        if (GlassProbe.enabled()) GlassProbe.event("INPUT_BACKING_GATE", input,
                "epoch=" + screen.transition.epoch() + " mayClear=" + mayClearInput
                        + " recorderState=" + screen.transition.recording()
                        + " paneLive=" + (screen.composer != null && screen.composer.hasLiveRecording())
                        + " cleared=" + screen.clearedBackgrounds.containsKey(input));
        if (mayClearInput) {
            clearBackground(screen, footer);
            clearBackground(screen, input);
            View preview = findReplyPreview(footer);
            if (preview != null) clearReplyBacking(screen, footer, preview);
            clearBackingChain(screen, footer, input);
            View edit = findById(input, "edit_layout");
            if (edit != null) clearBackingChain(screen, footer, edit);
        }
        if ((flags & ComposerTransition.PRESENT) != 0 || wasRecording
                || !java.util.Objects.equals(material, screen.decoratedMaterial)) {
            // Icon tint and reply contrast walk the footer: only on a new geometry or material.
            screen.decoratedMaterial = material;
            syncComposerIcons(screen, footer);
            syncReplyContrast(screen, footer);
        }
        if ((flags & ComposerTransition.RESERVE) != 0) reserveFooter(screen, footer);
        // A recorder signal whose recorder never became measurable owes no voice decoration;
        // left set, it would disable the per-frame fast path for the rest of the chat.
        if (!screen.transition.recording()) screen.decorationPending = false;
        if (screen.transition.pending()) footer.postInvalidateOnAnimation();
        if (!geometryKey.equals(screen.geometryKey) && (flags & ComposerTransition.PRESENT) != 0) {
            screen.geometryKey = geometryKey;
            GlassTrace.event(screen.root.get(), screen.composer, screen.listHost.get(), "GEOMETRY",
                    "composer-action-gap", "capsule=" + l + "," + t + "," + r + "," + b
                    + " action=" + actionRect + " state=" + screen.transition.state());
        }
        return true;
    }

    private void ensureComposer(Screen screen, ViewGroup footer) {
        if (screen.composer == null || screen.composer.getParent() != footer) {
            screen.composer = newPane(screen, footer, screen.listHost.get(), wallpaperOf(screen));
            screen.composer.setDecorationOnly(true);
            final GlassPane pane = screen.composer;
            pane.setOnLiveRecordingReady(() -> {
                if (!screen.footerOn || screen.suspended || screen.footer.get() != footer
                        || screen.composer != pane || !pane.hasLiveRecording()) return;
                // Recapture may finish after the 8-frame native-backing retry budget. Wake the
                // footer exactly once for this capture epoch to apply pending decoration.
                screen.sampledGeneration = -1L;
                if (screen.transition.recording()) {
                    screen.decorationPending = true;
                    screen.decorationAttempts = 0;
                }
                footer.postInvalidateOnAnimation();
            });
            // Index 0: WhatsApp's icons, waveform and text field keep painting on top.
            footer.addView(screen.composer, 0, new FrameLayout.LayoutParams(0, 0));
        }
    }

    /** Same pane identity, new mask: placed in this pre-draw and recaptured for its new size. */
    private static void presentComposer(Screen screen, int l, int t, int r, int b, float density) {
        GlassPane pane = screen.composer;
        if (GlassProbe.enabled()) GlassProbe.event("PANE_COMMIT_BEFORE", pane,
                "epoch=" + screen.transition.epoch()
                        + " previous=" + screen.committedPane.toShortString()
                        + " target=" + l + "," + t + "," + r + "," + b);
        pane.setVisibility(View.VISIBLE);
        pane.setTranslationY(0f);
        float radius = Math.min((b - t) / 2f, COMPOSE_MAX_RADIUS_DP * density);
        pane.placeNow(l, t, r, b, radius);
        screen.committedPane.set(l, t, r, b);
        if (GlassProbe.enabled()) GlassProbe.event("PANE_COMMIT_AFTER", pane,
                "epoch=" + screen.transition.epoch() + " live=" + pane.hasLiveRecording()
                        + " committed=" + screen.committedPane.toShortString());
    }

    /** List margin/padding follow only a settled footer: one layout transaction per geometry. */
    private void reserveFooter(Screen screen, ViewGroup footer) {
        screen.committedFooterHeight = footer.getHeight();
        floatFooter(screen, footer);
        syncListPadding(screen);
    }

    private void restoreVoiceDecoration(Screen screen) {
        if (GlassProbe.enabled()) GlassProbe.event("VOICE_RESTORE_BEFORE", screen.footer.get(),
                "epoch=" + screen.transition.epoch()
                        + " backgrounds=" + screen.voiceClearDrawables.size()
                        + " quoteAlphas=" + screen.duplicateReplyAlpha.size()
                        + " micOffsets=" + screen.recordingMicOffsets.size());
        restoreVoiceBackgrounds(screen);
        restoreRecordingGlassClip(screen);
        restoreDuplicateReply(screen);
        restoreRecordingMic(screen);
        screen.decorationPending = false;
        screen.laneKey = null;
        if (GlassProbe.enabled()) GlassProbe.event("VOICE_RESTORE_AFTER", screen.footer.get(),
                "epoch=" + screen.transition.epoch()
                        + " backgrounds=" + screen.voiceClearDrawables.size()
                        + " quoteAlphas=" + screen.duplicateReplyAlpha.size()
                        + " micOffsets=" + screen.recordingMicOffsets.size());
    }

    /** Allocation-free summary of the native footer state that decoration depends on. */
    private static int footerFingerprint(Screen screen, ViewGroup footer, View input, Object material) {
        int hash = 17;
        hash = 31 * hash + footer.getWidth();
        hash = 31 * hash + footer.getHeight();
        hash = 31 * hash + footer.getChildCount();
        hash = 31 * hash + (material == null ? 0 : material.hashCode());
        hash = mix(hash, screen, footer);
        hash = mix(hash, screen, input);
        hash = mix(hash, screen, findById(footer, "conversation_entry_action_button"));
        hash = mix(hash, screen, findById(footer, "voice_note_stub"));
        hash = mix(hash, screen, findById(footer, "voice_note_draft_layout_v2"));
        hash = mix(hash, screen, findById(footer, "composer_quoted_message_frame"));
        hash = mix(hash, screen, findById(footer, "voice_note_draft_quoted_message_frame"));
        hash = mix(hash, screen, findById(footer, "quoted_message_preview_container_v2"));
        hash = mix(hash, screen, findById(footer, "quoted_message_preview_container"));
        return hash;
    }

    private static int mix(int hash, Screen screen, View view) {
        if (view == null) return 31 * hash;
        boolean shown = view.isShown();
        hash = 31 * hash + (shown ? 1 : 2);
        hash = 31 * hash + System.identityHashCode(view);
        hash = 31 * hash + System.identityHashCode(view.getBackground());
        if (!shown) return hash;
        view.getLocationOnScreen(screen.location);
        hash = 31 * hash + view.getWidth();
        hash = 31 * hash + view.getHeight();
        hash = 31 * hash + screen.location[0];
        hash = 31 * hash + screen.location[1];
        return 31 * hash + Float.floatToIntBits(view.getAlpha());
    }

    /** {@code view}'s bounds in {@code base}'s coordinates, through screen space. */
    private static int[] rectOf(View base, View view) {
        int[] baseLocation = new int[2], location = new int[2];
        base.getLocationOnScreen(baseLocation);
        view.getLocationOnScreen(location);
        int left = location[0] - baseLocation[0], top = location[1] - baseLocation[1];
        return new int[]{left, top, left + view.getWidth(), top + view.getHeight()};
    }

    private void disableFooter(Screen screen) {
        screen.footerOn = false;
        screen.transition.detach();
        screen.committedFooterHeight = 0;
        ViewGroup footer = screen.footer.get();
        if (screen.composer != null) screen.composer.setOnLiveRecordingReady(null);
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
            if (screen.footerPreDraw != null && footer.getViewTreeObserver().isAlive()) {
                footer.getViewTreeObserver().removeOnPreDrawListener(screen.footerPreDraw);
            }
            if (screen.footerTranslationY != null) footer.setTranslationY(screen.footerTranslationY);
        }
        screen.footerListener = null;
        screen.footerPreDraw = null;
        screen.listHostBottomMargin = null;
        screen.floatedFooterHeight = null;
        screen.footerTranslationY = null;
        restoreVoiceBackgrounds(screen);
        restoreRecordingGlassClip(screen);
        restoreDuplicateReply(screen);
        restoreRecordingMic(screen);
        restoreReplyContrast(screen);
        for (java.util.Map.Entry<ImageView, ColorStateList> icon :
                new ArrayList<>(screen.originalInputIconTints.entrySet())) {
            if (icon.getKey() != null) icon.getKey().setImageTintList(icon.getValue());
        }
        screen.originalInputIconTints.clear();
        restoreUnder(screen, footer);
        syncListPadding(screen);
    }

    // ── List ──────────────────────────────────────────────────────────────────────

    /**
     * Attachment and camera are WhatsApp-owned ImageViews, not part of the glass shader. Their
     * original grey tint can lose contrast when the opaque input background is removed. Follow
     * the native microphone's current tint when exposed, or the resolved glass content colour.
     * Restrict discovery to semantic resource names inside the composer: never recolour message
     * thumbnails, emoji, send, microphone or arbitrary neighbouring controls.
     */
    private void syncComposerIcons(Screen screen, ViewGroup footer) {
        View micButton = findById(footer, "conversation_entry_action_button");
        ImageView mic = firstImage(micButton, 0);
        ColorStateList microphoneTint = mic == null ? null : mic.getImageTintList();
        GlassSpec material = host.material(screen.root.get());
        int contentColor = material == null ? Color.WHITE : material.contentColor;
        int color = microphoneTint == null
                ? contentColor : microphoneTint.getColorForState(
                        mic.getDrawableState(), microphoneTint.getDefaultColor());
        int matched = tintComposerIcons(screen, footer, color, 0);
        if (matched != 3) host.report("composer-icon-routing-" + matched,
                "composer icons resolved=" + matched + " (attachment=1, camera=2); "
                        + "inspect footer resource names on this WhatsApp build if either is absent");
    }

    private static ImageView firstImage(View view, int depth) {
        if (view instanceof ImageView) return (ImageView) view;
        if (!(view instanceof ViewGroup) || depth > 5) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            ImageView icon = firstImage(group.getChildAt(i), depth + 1);
            if (icon != null) return icon;
        }
        return null;
    }

    private static int tintComposerIcons(Screen screen, View view, int color, int depth) {
        if (view == null || depth > 9) return 0;
        String name = null;
        if (view.getId() != View.NO_ID) {
            try { name = view.getResources().getResourceEntryName(view.getId()); }
            catch (android.content.res.Resources.NotFoundException ignored) { }
        }
        boolean namedIcon = name != null && (name.contains("attach") || name.contains("camera"));
        if (namedIcon) {
            ImageView image = firstImage(view, 0);
            if (image != null) {
                if (!screen.originalInputIconTints.containsKey(image)) {
                    screen.originalInputIconTints.put(image, image.getImageTintList());
                }
                if (image.getImageTintList() == null
                        || image.getImageTintList().getDefaultColor() != color) {
                    image.setImageTintList(ColorStateList.valueOf(color));
                }
                return (name.contains("attach") ? 1 : 0) | (name.contains("camera") ? 2 : 0);
            }
            return 0;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int resolved = 0;
            for (int i = 0; i < group.getChildCount(); i++) {
                resolved |= tintComposerIcons(screen, group.getChildAt(i), color, depth + 1);
            }
            return resolved;
        }
        return 0;
    }

    /**
     * The voice recorder replaces the input row with a wide native panel. Match a visible
     * elapsed-time label under a sibling of input_layout, rather than any obfuscated class pin
     * or the microphone's expanding action button. No message text is logged or retained.
     * A missing match leaves the native recording UI alone and never manufactures a fallback.
     */
    private ViewGroup findVoicePanel(ViewGroup footer, View input) {
        return activeVoicePanel(footer, input);
    }

    /** A retained stub is not recording state. A usable native editor wins over it. */
    private static ViewGroup activeVoicePanel(ViewGroup footer, View input) {
        View draft = findById(footer, "voice_note_draft_layout_v2");
        View stub = findById(footer, "voice_note_stub");
        com.waenhancer.theme.ComposerRoute.Panel panel = com.waenhancer.theme.ComposerRoute.resolve(
                measuredPanel(draft, footer, input), hasVisibleEditor(input, 0), measuredPanel(stub, footer, input));
        return panel == com.waenhancer.theme.ComposerRoute.Panel.DRAFT ? (ViewGroup) draft
                : panel == com.waenhancer.theme.ComposerRoute.Panel.STUB ? (ViewGroup) stub : null;
    }

    private static boolean measuredPanel(View view, ViewGroup footer, View input) {
        return footer.getWidth() > 0 && view instanceof ViewGroup && visuallyShown(view)
                && view.getWidth() >= footer.getWidth() * 0.65f && view.getHeight() > 0
                && (input == null || !isAncestor(view, input));
    }

    private static boolean hasVisibleEditor(View view, int depth) {
        if (view == null || !view.isShown() || view.getAlpha() <= 0f || depth > 8) return false;
        if (view instanceof android.widget.EditText) return view.isEnabled()
                && view.getWidth() > 0 && view.getHeight() > 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                if (hasVisibleEditor(group.getChildAt(i), depth + 1)) return true;
        }
        return false;
    }

    /** WhatsApp may render composer and voice-draft quotes under different version-specific
     * IDs. Identify two distinct visible quote subtrees by semantics and position, rather than
     * assuming the older voice_note_draft_quoted_message_frame resource still exists. */
    private void suppressDuplicateReply(Screen screen, ViewGroup footer) {
        // In builds exposing the two known slots, the voice draft's quoted frame is the
        // recording owner. Prefer this exact structural match to visual guesses: hide the composer only
        // while the normal composer quote is also visible, and restore on recording exit.
        View composerQuote = findById(footer, "composer_quoted_message_frame");
        View voiceQuote = findById(footer, "voice_note_draft_quoted_message_frame");
        if (composerQuote != null && voiceQuote != null && composerQuote != voiceQuote
                && composerQuote.isShown()
                && composerQuote.getHeight() > 0 && voiceQuote.isShown() && voiceQuote.getHeight() > 0
                && !isAncestor(composerQuote, voiceQuote)
                && !isAncestor(voiceQuote, composerQuote)) {
            if (!screen.duplicateReplyAlpha.containsKey(composerQuote)) {
                screen.duplicateReplyAlpha.put(composerQuote, composerQuote.getAlpha());
            }
            if (composerQuote.getAlpha() != 0f) composerQuote.setAlpha(0f);
            host.report("double-voice-reply-exact", "inactive composer quote suppressed while native draft owns reply");
            return;
        }
        View draft = findById(footer, "voice_note_draft_layout_v2");
        ArrayList<View> previews = new ArrayList<>();
        collectReplyPreviews(footer, previews, 0);
        View owned = null;
        for (View preview : previews) {
            if (draft != null && visuallyShown(draft) && isAncestor(draft, preview)) {
                owned = preview;
                break;
            }
        }
        if (owned != null) {
            for (View preview : previews) {
                if (preview == owned || isAncestor(preview, owned) || isAncestor(owned, preview)) continue;
                if (!screen.duplicateReplyAlpha.containsKey(preview))
                    screen.duplicateReplyAlpha.put(preview, preview.getAlpha());
                if (preview.getAlpha() != 0f) preview.setAlpha(0f);
            }
            return;
        }
        restoreDuplicateReply(screen);
    }

    private static void collectReplyPreviews(View node, List<View> out, int depth) {
        if (node == null || !node.isShown() || depth > 9) return;
        String name = resourceName(node);
        boolean semantic = name != null && (name.contains("quoted_message")
                || name.contains("reply_preview") || name.contains("quoted_preview"));
        ViewGroup footer = null;
        for (View current = node; current != null; current = parentView(current)) {
            if ("footer".equals(resourceName(current)) && current instanceof ViewGroup) {
                footer = (ViewGroup) current;
                break;
            }
        }
        float d = node.getResources().getDisplayMetrics().density;
        if (semantic && node instanceof ViewGroup && node.getHeight() >= 22f * d
                && node.getHeight() <= 180f * d
                && footer != null && node.getWidth() >= footer.getWidth() * 0.4f) {
            // Keep the outermost named preview. A nested frame is the same native quote,
            // not a second copy. Some voice-note previews have only one TextView and an
            // ImageView, so counting text nodes was incorrectly rejecting them.
            out.add(node);
            return;
        }
        if (node instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) node;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectReplyPreviews(group.getChildAt(i), out, depth + 1);
            }
        }
    }

    /** Bounded geometry-only reply dump when the build has different semantic IDs. */
    private static String describeReplyNodes(View node, int depth) {
        if (node == null || depth > 6) return "";
        String name = resourceName(node);
        StringBuilder text = new StringBuilder();
        if (name.contains("quote") || name.contains("reply") || name.contains("draft")) {
            text.append('[').append(name).append(' ').append(node.getWidth()).append('x')
                    .append(node.getHeight()).append(" top=").append(screenTop(node)).append(']');
        }
        if (node instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) node;
            for (int i = 0; i < group.getChildCount() && i < 16; i++) {
                text.append(describeReplyNodes(group.getChildAt(i), depth + 1));
            }
        }
        return text.toString();
    }

    private static int screenTop(View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return location[1];
    }

    private static void restoreDuplicateReply(Screen screen) {
        for (java.util.Map.Entry<View, Float> entry :
                new ArrayList<>(screen.duplicateReplyAlpha.entrySet())) {
            View view = entry.getKey();
            if (view != null && view.getAlpha() == 0f) view.setAlpha(entry.getValue());
        }
        screen.duplicateReplyAlpha.clear();
    }

    /**
     * Places the small red recording indicator on the start side of the native waveform,
     * vertically centred on the recording row and clear of the timer, trash, pause/send, action
     * disc and quote (see {@link RecorderLayout#micOffset}). Offsets are recomputed from the
     * indicator's native translation every time, so repeated layouts never accumulate. Only that
     * indicator moves; when no placement satisfies the constraints it is restored to native.
     */
    private void layoutRecordingMic(Screen screen, ViewGroup footer, ViewGroup voice,
                                    int[] quoteRect, int[] actionRect, float d) {
        TextView timer = findElapsedLabel(voice, 0);
        View mic = findRecordingMic(voice, timer, 0);
        if (mic == null || mic.getWidth() <= 0 || mic.getHeight() <= 0) {
            host.report("voice-mic-route-missing", "voice mic not located; "
                    + (timer == null ? "timer=none" : describeRecordingIcons(footer, timer, 0)));
            return;
        }
        float[] original = screen.recordingMicOffsets.get(mic);
        if (original == null) {
            original = new float[]{mic.getTranslationX(), mic.getTranslationY()};
            screen.recordingMicOffsets.put(mic, original);
        }
        int[] now = rectOf(footer, mic);
        int shiftX = Math.round(mic.getTranslationX() - original[0]);
        int shiftY = Math.round(mic.getTranslationY() - original[1]);
        int[] base = {now[0] - shiftX, now[1] - shiftY, now[2] - shiftX, now[3] - shiftY};
        View waveView = findById(voice, "voice_note_draft_audio_visualizer");
        int[] wave = waveView != null && waveView.isShown() && waveView.getWidth() > 0
                && waveView.getHeight() > 0 && waveView != mic ? rectOf(footer, waveView) : null;
        int[] timerRect = timer != null && timer.getWidth() > 0 ? rectOf(footer, timer) : null;
        ArrayList<int[]> obstacles = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        int[] voiceRect = rectOf(footer, voice);
        collectObstacles(footer, voice, mic, waveView, voiceRect, obstacles, names, 0);
        if (actionRect != null) { obstacles.add(actionRect); names.add("action"); }
        if (quoteRect != null) { obstacles.add(quoteRect); names.add("quote"); }
        int[] bounds = RecorderLayout.union(new int[]{0, 0, footer.getWidth(), footer.getHeight()}, voiceRect);
        boolean rtl = footer.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int gap = Math.max(1, Math.round(2f * d));
        int tolerance = Math.max(1, Math.round(3f * d));
        int[] offset;
        if (wave != null) {
            offset = RecorderLayout.micOffset(base, wave, obstacles.toArray(new int[0][]), bounds,
                    gap, tolerance, rtl);
        } else if (timerRect != null) {
            // Without a measured waveform the horizontal slot is unknown: centre on the
            // elapsed-time row only, and only if that vertical move is collision-free.
            int dy = Math.abs((base[1] + base[3]) / 2 - (timerRect[1] + timerRect[3]) / 2) <= tolerance
                    ? 0 : (timerRect[1] + timerRect[3]) / 2 - (base[1] + base[3]) / 2;
            int[] moved = {base[0], base[1] + dy, base[2], base[3] + dy};
            boolean free = RecorderLayout.contains(bounds, moved);
            for (int[] obstacle : obstacles) {
                if (obstacle != timerRect && RecorderLayout.intersects(moved, obstacle)) free = false;
            }
            offset = free ? new int[]{0, dy} : null;
        } else {
            host.report("voice-mic-await-layout", "voice indicator awaits measured waveform/timer");
            return;
        }
        float targetX = offset == null ? original[0] : original[0] + offset[0];
        float targetY = offset == null ? original[1] : original[1] + offset[1];
        if (mic.getTranslationX() != targetX) mic.setTranslationX(targetX);
        if (mic.getTranslationY() != targetY) mic.setTranslationY(targetY);
        String key = RecorderLayout.describe(base) + "|" + RecorderLayout.describe(wave) + "|"
                + RecorderLayout.describe(timerRect) + "|" + obstacles.size() + "|" + (offset == null);
        if (key.equals(screen.laneKey)) return;
        screen.laneKey = key;
        StringBuilder rects = new StringBuilder();
        for (int i = 0; i < obstacles.size(); i++) {
            rects.append(' ').append(names.get(i)).append('=').append(RecorderLayout.describe(obstacles.get(i)));
        }
        boolean quoteLane = RecorderLayout.quoteAbove(quoteRect, wave, timerRect, gap);
        GlassTrace.event(screen.root.get(), screen.composer, mic, "VOICE_LANES",
                offset == null ? "mic-unplaceable-native-kept" : "mic-placed",
                "epoch=" + screen.transition.epoch() + " rtl=" + rtl + " mic=" + resourceName(mic)
                        + " M=" + RecorderLayout.describe(base)
                        + " offset=" + (offset == null ? "none" : offset[0] + "," + offset[1])
                        + " W=" + RecorderLayout.describe(wave) + " T=" + RecorderLayout.describe(timerRect)
                        + " Q=" + RecorderLayout.describe(quoteRect) + " quoteAbove=" + quoteLane
                        + " G=" + RecorderLayout.describe(new int[]{screen.committedPane.left,
                        screen.committedPane.top, screen.committedPane.right, screen.committedPane.bottom})
                        + rects);
        if (!quoteLane) host.report("voice-quote-lane-overlap",
                "voice quote overlaps recording row; native quote layout left unchanged");
    }

    /**
     * Visible measured leaf controls of the recorder that the indicator must not cover. Large
     * leaves (backing images) and zero-size stubs are not controls; the waveform is the target
     * row, not an obstacle. Resource names are kept for diagnostics only.
     */
    private static void collectObstacles(ViewGroup footer, View node, View mic, View wave, int[] voiceRect,
                                         List<int[]> out, List<String> names, int depth) {
        if (node == null || !node.isShown() || depth > 8 || out.size() >= 24) return;
        if (node == mic || node == wave || node instanceof GlassPane) return;
        if (isAncestor(node, mic) || (wave != null && isAncestor(node, wave))) {
            ViewGroup group = (ViewGroup) node;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectObstacles(footer, group.getChildAt(i), mic, wave, voiceRect, out, names, depth + 1);
            }
            return;
        }
        boolean leaf = !(node instanceof ViewGroup) || ((ViewGroup) node).getChildCount() == 0
                || node.isClickable();
        if (!leaf) {
            ViewGroup group = (ViewGroup) node;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectObstacles(footer, group.getChildAt(i), mic, wave, voiceRect, out, names, depth + 1);
            }
            return;
        }
        if (node.getWidth() <= 0 || node.getHeight() <= 0) return;
        long area = (long) node.getWidth() * node.getHeight();
        long voiceArea = (long) Math.max(1, voiceRect[2] - voiceRect[0]) * Math.max(1, voiceRect[3] - voiceRect[1]);
        if (area * 2 > voiceArea) return;
        out.add(rectOf(footer, node));
        names.add(resourceName(node));
    }

    private static TextView findElapsedLabel(View node, int depth) {
        if (node == null || !node.isShown() || depth > 8) return null;
        if (node instanceof TextView && isElapsedTime(((TextView) node).getText())) return (TextView) node;
        if (node instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) node;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView result = findElapsedLabel(group.getChildAt(i), depth + 1);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static View findRecordingMic(View node, TextView timer, int depth) {
        if (node == null || !node.isShown() || depth > 8) return null;
        String name = resourceName(node);
        float density = node.getResources().getDisplayMetrics().density;
        if (node instanceof ImageView && node.getWidth() > 0 && node.getHeight() > 0
                && node.getWidth() < 48f * density && node.getHeight() < 48f * density
                && (name.contains("mic") || name.contains("recording_indicator"))
                && !name.contains("action") && !name.contains("button")) {
            return node;
        }
        if (node instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) node;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = findRecordingMic(group.getChildAt(i), timer, depth + 1);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static String describeRecordingIcons(View node, TextView timer, int depth) {
        if (node == null || depth > 5) return "";
        StringBuilder out = new StringBuilder();
        if (node instanceof ImageView && node.isShown()) {
            int[] pos = new int[2];
            node.getLocationOnScreen(pos);
            out.append('[').append(resourceName(node)).append(' ')
                    .append(node.getWidth()).append('x').append(node.getHeight())
                    .append(" y=").append(pos[1] - screenTop(timer)).append(']');
        }
        if (node instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) node;
            for (int i = 0; i < group.getChildCount() && i < 12; i++) {
                out.append(describeRecordingIcons(group.getChildAt(i), timer, depth + 1));
            }
        }
        return out.toString();
    }

    private static void restoreRecordingMic(Screen screen) {
        for (java.util.Map.Entry<View, float[]> entry :
                new ArrayList<>(screen.recordingMicOffsets.entrySet())) {
            View view = entry.getKey();
            if (view == null) continue;
            view.setTranslationX(entry.getValue()[0]);
            view.setTranslationY(entry.getValue()[1]);
        }
        screen.recordingMicOffsets.clear();
    }

    /** One or two hours/minutes/seconds separators; reject free text and message timestamps. */
    private static boolean isElapsedTime(CharSequence value) {
        if (value == null || value.length() < 4 || value.length() > 8) return false;
        int separators = 0, digits = 0, sinceSeparator = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == ':') {
                if (sinceSeparator < 1 || sinceSeparator > 2 || ++separators > 2) return false;
                sinceSeparator = 0;
            } else if (ch >= '0' && ch <= '9') {
                digits++;
                sinceSeparator++;
                if (sinceSeparator > 2) return false;
            } else return false;
        }
        return separators >= 1 && sinceSeparator == 2 && digits >= 3;
    }

    /** Diagnostic IDs and dimensions only: never include a TextView's content or message data. */
    private static String describeFooter(ViewGroup footer) {
        StringBuilder out = new StringBuilder("footer=").append(resourceName(footer))
                .append('(').append(footer.getWidth()).append('x')
                .append(footer.getHeight()).append(") children=");
        for (int i = 0; i < footer.getChildCount() && i < 12; i++) {
            View child = footer.getChildAt(i);
            out.append('[').append(resourceName(child)).append(' ')
                    .append(child.getClass().getSimpleName()).append(' ')
                    .append(child.getWidth()).append('x').append(child.getHeight())
                    .append(" visibility=").append(child.getVisibility()).append(']');
        }
        return out.toString();
    }

    /**
     * Remove only the recorder's own backing fill and an optional full-bleed immediate wrapper.
     * Waveform, timer, buttons, images and hit targets keep their native views and backgrounds.
     */
    private static void clearVoiceBackgrounds(Screen screen, ViewGroup recorder) {
        clearVoiceBackground(screen, recorder);
        ViewGroup footer = screen.footer.get();
        // WhatsApp may put the opaque fill on edit_layout / emoji_popup_anchor rather than on
        // voice_note_stub itself. Clear only the full-area chain leading to this known recorder;
        // unrelated action discs, waveform children and message content remain untouched.
        if (footer != null) {
            for (View ancestor = parentView(recorder); ancestor != null && ancestor != footer;
                    ancestor = parentView(ancestor)) {
                if (ancestor.getWidth() >= footer.getWidth() * 0.8f
                        && ancestor.getHeight() >= footer.getHeight() * 0.8f) {
                    clearVoiceBackground(screen, ancestor);
                }
            }
            if (footer != recorder) clearVoiceBackground(screen, footer);
        }
        for (int i = 0; i < recorder.getChildCount(); i++) {
            View child = recorder.getChildAt(i);
            if (child instanceof ViewGroup && child.getWidth() >= recorder.getWidth() * 0.9f
                    && child.getHeight() >= recorder.getHeight() * 0.9f) {
                clearVoiceBackground(screen, child);
            }
        }
    }

    private static void clearVoiceBackground(Screen screen, View view) {
        Drawable original = view.getBackground();
        if (original == screen.voiceClearDrawables.get(view)) return;
        if (original == null) return;
        if (original instanceof GlassMaterialDrawable) {
            original = ((GlassMaterialDrawable) original).original();
        }
        screen.voiceBackgrounds.put(view, original);
        ColorDrawable cleared = new ColorDrawable(Color.TRANSPARENT);
        screen.voiceClearDrawables.put(view, cleared);
        setBackgroundPreservingPadding(view, cleared);
        if (GlassProbe.enabled()) GlassProbe.event("VOICE_BG_CLEARED", view,
                "original=" + GlassProbe.drawable(original)
                        + " cleared=" + (view.getBackground() == cleared));
    }

    private static void restoreVoiceBackgrounds(Screen screen) {
        for (View view : new ArrayList<>(screen.voiceClearDrawables.keySet())) {
            if (view != null && view.getBackground() == screen.voiceClearDrawables.get(view)) {
                setBackgroundPreservingPadding(view, screen.voiceBackgrounds.get(view));
                if (GlassProbe.enabled()) GlassProbe.event("VOICE_BG_RESTORED", view,
                        "expected=" + GlassProbe.drawable(screen.voiceBackgrounds.get(view)));
            }
        }
        screen.voiceBackgrounds.clear();
        screen.voiceClearDrawables.clear();
        screen.voiceRecording = false;
        screen.voiceGeometryKey = null;
    }

    /** ViewGroups clip children by default even when a short recorder stub draws its larger
     * animation outside the footer. Change only the small ancestor chain and restore on exit. */
    private static void allowRecordingGlassOverflow(Screen screen, ViewGroup footer) {
        ViewGroup listHost = screen.listHost.get();
        View node = footer;
        for (int depth = 0; depth < 4 && node instanceof ViewGroup && node != listHost;
                depth++, node = parentView(node)) {
            ViewGroup group = (ViewGroup) node;
            if (!screen.recordingClipChildren.containsKey(group)) {
                screen.recordingClipChildren.put(group, group.getClipChildren());
            }
            if (group.getClipChildren()) group.setClipChildren(false);
            if (!screen.recordingClipPadding.containsKey(group)) {
                screen.recordingClipPadding.put(group, group.getClipToPadding());
            }
            if (group.getClipToPadding()) group.setClipToPadding(false);
        }
    }

    private static void restoreRecordingGlassClip(Screen screen) {
        for (java.util.Map.Entry<ViewGroup, Boolean> entry :
                new ArrayList<>(screen.recordingClipChildren.entrySet())) {
            ViewGroup group = entry.getKey();
            if (group != null) group.setClipChildren(entry.getValue());
        }
        screen.recordingClipChildren.clear();
        for (java.util.Map.Entry<ViewGroup, Boolean> entry :
                new ArrayList<>(screen.recordingClipPadding.entrySet())) {
            if (entry.getKey() != null) entry.getKey().setClipToPadding(entry.getValue());
        }
        screen.recordingClipPadding.clear();
    }

    /** Keep quoted reply text readable over the translucent composer without losing hierarchy. */
    private void syncReplyContrast(Screen screen, ViewGroup footer) {
        View preview = findReplyPreview(footer);
        if (preview == null) {
            restoreReplyContrast(screen);
            return;
        }
        GlassSpec material = host.material(screen.root.get());
        int contentColor = material == null ? Color.WHITE : material.contentColor;
        tintReplyChildren(screen, preview, contentColor, 0, new int[1]);
    }

    private static boolean visuallyShown(View view) {
        if (!view.isShown()) return false;
        for (View node = view; node != null; node = parentView(node))
            if (node.getAlpha() <= 0f) return false;
        return true;
    }

    private static View findReplyPreview(ViewGroup footer) {
        View draft = findById(footer, "voice_note_draft_layout_v2");
        if (draft != null && visuallyShown(draft)) {
            ArrayList<View> previews = new ArrayList<>();
            collectReplyPreviews(draft, previews, 0);
            for (View preview : previews) if (visuallyShown(preview)) return preview;
        }
        String[] names = {"voice_note_draft_quoted_message_frame", "quoted_message_preview_container_v2", "quoted_message_preview_container",
                "composer_quoted_message_frame", "quoted_message_frame"};
        for (String name : names) {
            View candidate = findById(footer, name);
            if (candidate != null && visuallyShown(candidate)) return candidate;
        }
        return findSemanticReply(footer, 0);
    }

    private static View findSemanticReply(View view, int depth) {
        if (!(view instanceof ViewGroup) || depth > 7) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (!visuallyShown(child)) continue;
            String name = resourceName(child);
            if (name != null && (name.contains("quoted") || name.contains("reply_preview"))
                    && child instanceof ViewGroup && replyTextCount(child, 0) >= 2) return child;
            View nested = findSemanticReply(child, depth + 1);
            if (nested != null) return nested;
        }
        return null;
    }

    private static String resourceName(View view) {
        if (view == null || view.getId() == View.NO_ID) return "no-id";
        try { return view.getResources().getResourceEntryName(view.getId()); }
        catch (android.content.res.Resources.NotFoundException ignored) { return "unknown-id"; }
    }

    private static int replyTextCount(View view, int depth) {
        if (view instanceof TextView) return 1;
        if (!(view instanceof ViewGroup) || depth > 5) return 0;
        ViewGroup group = (ViewGroup) view;
        int count = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            count += replyTextCount(group.getChildAt(i), depth + 1);
            if (count >= 2) return count;
        }
        return count;
    }

    private static void tintReplyChildren(Screen screen, View view, int rgb, int depth,
                                          int[] textIndex) {
        if (!view.isShown() || depth > 7) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (!screen.replyTextColors.containsKey(text)) {
                screen.replyTextColors.put(text, text.getTextColors());
            }
            float opacity = textIndex[0]++ == 0 ? 0.96f : 0.86f;
            int color = Color.argb(Math.round(255f * opacity),
                    Color.red(rgb), Color.green(rgb), Color.blue(rgb));
            if (text.getCurrentTextColor() != color) text.setTextColor(color);
            screen.replyAppliedColors.put(text, color);
            return;
        }
        if (view instanceof ImageView) {
            String id = resourceName(view);
            if (id != null && (id.contains("close") || id.contains("dismiss"))) {
                ImageView icon = (ImageView) view;
                if (!screen.replyIconTints.containsKey(icon)) {
                    screen.replyIconTints.put(icon, icon.getImageTintList());
                }
                icon.setImageTintList(ColorStateList.valueOf(Color.argb(225,
                        Color.red(rgb), Color.green(rgb), Color.blue(rgb))));
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                tintReplyChildren(screen, group.getChildAt(i), rgb, depth + 1, textIndex);
            }
        }
    }

    private static void restoreReplyContrast(Screen screen) {
        for (TextView view : new ArrayList<>(screen.replyTextColors.keySet())) {
            if (view != null && screen.replyAppliedColors.containsKey(view)
                    && view.getCurrentTextColor() == screen.replyAppliedColors.get(view)) {
                view.setTextColor(screen.replyTextColors.get(view));
            }
        }
        screen.replyTextColors.clear();
        screen.replyAppliedColors.clear();
        for (ImageView view : new ArrayList<>(screen.replyIconTints.keySet())) {
            if (view != null) view.setImageTintList(screen.replyIconTints.get(view));
        }
        screen.replyIconTints.clear();
    }

    /** Padding parks messages clear of the chrome; clipToPadding=false lets them scroll behind it. */
    private void syncListPadding(Screen screen) {
        AbsListView list = screen.list.get();
        if (list == null) return;
        boolean header = screen.headerOn && screen.holderBottomMargin != null;
        boolean footer = screen.footerOn && screen.listHostBottomMargin != null;
        if (!header && !footer) {
            restoreListPadding(screen);
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
        int top = header && holder != null
                ? Math.max(0, screenTop(holder) + holder.getHeight() - screenTop(list)) + gap
                : screen.listPadding[1];
        int bottom = footer && footerView != null
                ? (screen.floatedFooterHeight == null ? footerView.getHeight() : screen.floatedFooterHeight)
                        + Math.round(-footerView.getTranslationY()) + gap
                : screen.listPadding[3];
        if (list.getClipToPadding()) list.setClipToPadding(false);
        if (list.getPaddingTop() != top || list.getPaddingBottom() != bottom) {
            boolean atEnd = restsAtEnd(list);
            list.setPadding(list.getPaddingLeft(), top, list.getPaddingRight(), bottom);
            if (atEnd) keepAtEnd(list);
        }
    }

    /** Puts the recorded padding back on the list it was recorded from and forgets the baseline. */
    private static void restoreListPadding(Screen screen) {
        AbsListView list = screen.list.get();
        if (list != null && screen.listPadding != null) {
            int[] p = screen.listPadding;
            list.setPadding(p[0], p[1], p[2], p[3]);
            if (screen.listClipToPadding != null) list.setClipToPadding(screen.listClipToPadding);
        }
        screen.listPadding = null;
        screen.listClipToPadding = null;
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

    private GlassPane newPane(Screen screen, ViewGroup parent, View source, List<View> underlay) {
        GlassPane pane = new GlassPane(parent.getContext());
        pane.setRequiresLiveContent(true);
        pane.setSource(source);
        pane.setUnderlay(underlay);
        Supplier<GlassSpec> material = () -> host.material(screen.root.get());
        pane.setSpec(material);
        pane.setSuspended(screen.suspended);
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

    private static void removePane(View pane) {
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
        setBackgroundPreservingPadding(view, new ColorDrawable(Color.TRANSPARENT));
        if (GlassProbe.enabled()) GlassProbe.event("INPUT_BG_CLEARED", view,
                "saved=" + GlassProbe.drawable(screen.clearedBackgrounds.get(view)));
    }

    private static void setBackgroundPreservingPadding(View view, Drawable drawable) {
        int left = view.getPaddingLeft(), top = view.getPaddingTop();
        int right = view.getPaddingRight(), bottom = view.getPaddingBottom();
        view.setBackground(drawable);
        if (view.getPaddingLeft() != left || view.getPaddingTop() != top
                || view.getPaddingRight() != right || view.getPaddingBottom() != bottom)
            view.setPadding(left, top, right, bottom);
    }

    private static void clearBackingChain(Screen screen, ViewGroup footer, View node) {
        for (View view = node; view != null && view != footer; view = parentView(view)) {
            clearBackground(screen, view);
        }
    }

    /** Clear structural quote containers, preserving media, text and action backgrounds. */
    private static void clearReplyBacking(Screen screen, ViewGroup footer, View quote) {
        clearBackingChain(screen, footer, quote);
        clearQuoteContainers(screen, quote, 0);
    }

    private static void clearQuoteContainers(Screen screen, View node, int depth) {
        if (!(node instanceof ViewGroup) || depth > 8) return;
        String name = resourceName(node);
        if (name.contains("quoted") || name.contains("reply_preview")) clearBackground(screen, node);
        ViewGroup group = (ViewGroup) node;
        for (int i = 0; i < group.getChildCount(); i++) clearQuoteContainers(screen, group.getChildAt(i), depth + 1);
    }

    /** Puts back fills and foregrounds cleared under {@code root}. */
    private static void restoreUnder(Screen screen, View root) {
        if (root == null) return;
        for (View view : new ArrayList<>(screen.clearedBackgrounds.keySet())) {
            if (view != null && (view == root || isAncestor(root, view))) {
                setBackgroundPreservingPadding(view, screen.clearedBackgrounds.remove(view));
                if (GlassProbe.enabled()) GlassProbe.event("INPUT_BG_RESTORED", view,
                        "owner=" + GlassTrace.id(root));
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
        if (child != parent && !isAncestor(parent, child)) return false;
        int[] parentLocation = new int[2], childLocation = new int[2];
        parent.getLocationOnScreen(parentLocation);
        child.getLocationOnScreen(childLocation);
        rect.set(childLocation[0] - parentLocation[0], childLocation[1] - parentLocation[1],
                childLocation[0] - parentLocation[0] + child.getWidth(),
                childLocation[1] - parentLocation[1] + child.getHeight());
        return true;
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
        int id = idOf(root, name);
        return id == 0 ? null : root.findViewById(id);
    }

    private static int idOf(View root, String name) {
        Integer id = IDS.get(name);
        if (id == null) {
            id = root.getResources().getIdentifier(name, "id", root.getContext().getPackageName());
            IDS.put(name, id);
        }
        return id;
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
