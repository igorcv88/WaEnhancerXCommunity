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
    /** Measured stable voice panel height on the current 3x-density WhatsApp layout. */
    private static final float VOICE_ENVELOPE_DP = 140f;

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
        long voiceSignalUntil;
        final WeakHashMap<View, Float> duplicateReplyAlpha = new WeakHashMap<>();
        final WeakHashMap<View, Float> recordingMicOffsets = new WeakHashMap<>();
        final WeakHashMap<ViewGroup, Boolean> recordingClipChildren = new WeakHashMap<>();
        Integer listHostBottomMargin;
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
        if (screen.capsule != null) screen.capsule.setSuspended(suspended);
        if (screen.composer != null) screen.composer.setSuspended(suspended);
    }

    /** Native recorder visibility/timer transitions precede global layout on some builds. */
    void onRecorderSignal(View changed) {
        if (changed == null || !host.composerEnabled()) return;
        Screen screen = screens.get(changed.getRootView());
        if (screen == null || !screen.footerOn || screen.suspended) return;
        ViewGroup footer = screen.footer.get();
        if (footer == null || !isAncestor(footer, changed)) return;
        boolean recorder = false;
        for (View node = changed; node != footer && node != null; node = parentView(node)) {
            String name = resourceName(node);
            if ("voice_note_stub".equals(name) || "voice_note_draft_layout_v2".equals(name)) {
                recorder = true;
                break;
            }
        }
        if (!recorder || (changed instanceof TextView
                && !isElapsedTime(((TextView) changed).getText()))) return;
        screen.voiceSignalUntil = SystemClock.uptimeMillis() + 900L;
        // Geometry is already known for the footer. Reuse its one persistent pane now;
        // the one-shot next-frame retry covers native ViewStub inflation/first measurement.
        syncFooter(screen);
        // A visibility signal alone does not guarantee another frame; actively wake the
        // existing pane's pre-draw capture while WhatsApp is inflating the recorder.
        if (screen.composer != null) screen.composer.invalidate();
        footer.invalidate();
        footer.postOnAnimation(() -> {
            if (screen.footerOn && screen.footer.get() == footer && !screen.suspended) syncFooter(screen);
        });
        host.report("voice-recorder-signal", "voice glass synchronized from native recording UI signal");
    }

    /** Called on every global layout of a window; cheap when nothing changed. */
    void sync(View root) {
        if (root == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        try {
            Screen screen = screens.get(root);
            if (screen == null && !host.toolbarsEnabled() && !host.composerEnabled()) return;
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
        if (holder.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) holder.getLayoutParams();
            if (screen.holderTopMargin == null) screen.holderTopMargin = lp.topMargin;
            int spacing = Math.round(8f * holder.getResources().getDisplayMetrics().density);
            int target = screen.holderTopMargin + screen.fullscreenTopInset + spacing;
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
            window.setAttributes(attrs);
        }
        screen.systemBarsBehaviorChanged = false;
        screen.fullscreenOwned = false;
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
        // Plain wallpaper continuity; only the capsule owns an optical contour.
        if (screen.band == null || screen.band.getParent() != holder) {
            screen.band = new WallpaperUnderlay(holder.getContext());
            holder.addView(screen.band, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        screen.band.setWallpaper(screen.wallpaper.get());
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
            holder.addView(screen.capsule, 1, new FrameLayout.LayoutParams(0, 0));
        }
        screen.capsule.setSource(source);
        screen.capsule.setUnderlay(wallpaperOf(screen));
        place(screen.capsule, l, t, r, b, (b - t) / 2f);
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
                if (screen.footerOn) syncFooter(screen);
            };
            footer.addOnLayoutChangeListener(screen.footerListener);
        }
        syncFooter(screen);
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
        ViewGroup footer = screen.footer.get();
        if (footer == null || footer.getWidth() <= 0 || footer.getHeight() <= 0) return;
        floatFooter(screen, footer);
        syncListPadding(screen);
        View input = findById(footer, "input_layout");
        ViewGroup voice = findVoicePanel(footer, input);
        if (voice == null && SystemClock.uptimeMillis() <= screen.voiceSignalUntil) {
            // At record start WhatsApp has not populated the elapsed-time label yet. The
            // recorder's named stub is visible before that first timer text/layout pass.
            voice = signaledVoicePanel(footer, input);
        }
        if (voice != null || input == null || !input.isShown()
                || goneAbove(input, footer) || input.getWidth() <= 0) {
            restoreReplyContrast(screen);
            if (voice == null || !rectIn(footer, voice)) {
                if (voice == null) host.report("voice-panel-route-missing",
                        "voice recorder panel not resolved; " + describeFooter(footer));
                restoreVoiceBackgrounds(screen);
                restoreRecordingGlassClip(screen);
                restoreDuplicateReply(screen);
                restoreRecordingMic(screen);
                if (screen.composer != null) place(screen.composer, 0, 0, 0, 0, 0f);
                return;
            }
            float d = footer.getResources().getDisplayMetrics().density;
            int pad = Math.round(COMPOSE_PAD_DP * d);
            // voice_note_stub initially measures as a short strip. Using its bounds made only
            // that strip glass, while the full recorder remained opaque for ~1.7 seconds on
            // the captured device trace. Glass already belongs to the footer, so cover its
            // stable envelope from the first timer-bearing recording frame.
            int l = pad, t = pad;
            int r = footer.getWidth() - pad;
            int b = footer.getHeight() - pad;
            View quote = findReplyPreview(footer);
            int expectedHeight = Math.round(VOICE_ENVELOPE_DP * d)
                    + (quote != null && quote.isShown() ? quote.getHeight() : 0);
            if (footer.getHeight() < expectedHeight) {
                // The native 115px stub becomes a ~414px recording panel only several
                // seconds later (confirmed on the phone). Reserve its known envelope at
                // *start*, extending the single glass pane above the short footer. Keep
                // the recorder's actual UI geometry and touch targets under WhatsApp.
                t -= expectedHeight - footer.getHeight();
                allowRecordingGlassOverflow(screen, footer);
            }
            View action = findById(footer, "conversation_entry_action_button");
            if (action != null && action.isShown()
                    && action.getWidth() <= 72f * d && action.getHeight() <= 72f * d
                    && rectIn(footer, action)) {
                int[] edges = ComposerGeometry.separate(l, r, t, b,
                        rect.left, rect.right, rect.top, rect.bottom,
                        Math.max(1, Math.round(2f * d)));
                l = edges[0]; r = edges[1];
            }
            if (r <= l || b <= t) return;
            // Keep the same GlassPane and the same safe recording source as the normal input.
            // Only its mask geometry changes: waveform, elapsed time and buttons stay native.
            if (screen.composer == null || screen.composer.getParent() != footer) {
                screen.composer = newPane(screen, footer, screen.listHost.get(), wallpaperOf(screen));
                footer.addView(screen.composer, 0, new FrameLayout.LayoutParams(0, 0));
            }
            clearVoiceBackgrounds(screen, voice);
            suppressDuplicateReply(screen, footer);
            alignRecordingMic(screen, voice);
            screen.composer.setSource(screen.listHost.get());
            screen.composer.setUnderlay(wallpaperOf(screen));
            place(screen.composer, l, t, r, b, Math.min((b - t) / 2f, COMPOSE_MAX_RADIUS_DP * d));
            String geometry = l + ":" + t + ":" + r + ":" + b;
            if (!screen.voiceRecording || !geometry.equals(screen.voiceGeometryKey)) {
                screen.voiceGeometryKey = geometry;
                screen.voiceRecording = true;
                host.report("voice-glass-bound", "voice panel glass follows native recording UI; "
                        + "panel=" + resourceName(voice) + " geometry=" + geometry);
                GlassTrace.event(screen.root.get(), screen.composer, voice, "VOICE_RECORDING",
                        "panel-bound", "geometry=" + geometry);
            }
            return;
        }
        restoreVoiceBackgrounds(screen);
        restoreRecordingGlassClip(screen);
        restoreDuplicateReply(screen);
        restoreRecordingMic(screen);
        clearBackground(screen, input);
        syncComposerIcons(screen, footer);
        syncReplyContrast(screen, footer);
        if (!rectIn(footer, input)) return;
        float d = footer.getResources().getDisplayMetrics().density;
        int pad = Math.round(COMPOSE_PAD_DP * d);
        // The send disc's own screen margin; the pill's left edge mirrors it or the corner clips.
        int sideFloor = 0;
        Rect actionRect = null;
        View send = findById(footer, "conversation_entry_action_button");
        Rect inputRect = new Rect(rect);
        if (send != null && send.getWidth() > 0 && rectIn(footer, send)) {
            actionRect = new Rect(rect);
            sideFloor = Math.max(0, Math.min(rect.left, footer.getWidth() - rect.right));
        }
        int l = Math.max(sideFloor, inputRect.left - pad);
        int t = Math.max(0, inputRect.top - pad);
        int r = Math.min(footer.getWidth(), inputRect.right + pad);
        int b = Math.min(footer.getHeight(), inputRect.bottom + pad);
        if (actionRect != null && send.isShown()) {
            int[] edges = ComposerGeometry.separate(l, r, t, b, actionRect.left, actionRect.right,
                    actionRect.top, actionRect.bottom, Math.max(1, Math.round(2f * d)));
            l = edges[0]; r = edges[1];
        }
        if (r <= l || b <= t) return;
        String geometryKey = l + ":" + t + ":" + r + ":" + b + ":" + actionRect;
        if (!geometryKey.equals(screen.geometryKey)) {
            screen.geometryKey = geometryKey;
            GlassTrace.event(screen.root.get(), screen.composer, screen.listHost.get(), "GEOMETRY",
                    "composer-action-gap", "capsule=" + l + "," + t + "," + r + "," + b
                    + " action=" + actionRect);
        }
        if (screen.composer == null || screen.composer.getParent() != footer) {
            screen.composer = newPane(screen, footer, screen.listHost.get(), wallpaperOf(screen));
            // Index 0: WhatsApp's icons and the text field keep painting on top.
            footer.addView(screen.composer, 0, new FrameLayout.LayoutParams(0, 0));
        }
        screen.composer.setSource(screen.listHost.get());
        screen.composer.setUnderlay(wallpaperOf(screen));
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
        if (footer.getWidth() <= 0) return null;
        float density = footer.getResources().getDisplayMetrics().density;
        return voicePanelIn(footer, footer, input, 0, density);
    }

    private static ViewGroup signaledVoicePanel(ViewGroup footer, View input) {
        View voice = findById(footer, "voice_note_stub");
        if (!(voice instanceof ViewGroup) || !voice.isShown()
                || (input != null && isAncestor(voice, input))) return null;
        float d = footer.getResources().getDisplayMetrics().density;
        return voice.getWidth() >= footer.getWidth() * 0.65f
                && voice.getHeight() >= 24f * d ? (ViewGroup) voice : null;
    }

    /** WhatsApp may render composer and voice-draft quotes under different version-specific
     * IDs. Identify two distinct visible quote subtrees by semantics and position, rather than
     * assuming the older voice_note_draft_quoted_message_frame resource still exists. */
    private void suppressDuplicateReply(Screen screen, ViewGroup footer) {
        // In builds exposing the two known slots, the voice draft's quoted frame is the
        // second copy. Prefer this exact structural match to visual guesses: hide only
        // while the normal composer quote is also visible, and restore on recording exit.
        View composerQuote = findReplyPreview(footer);
        View voiceQuote = findById(footer, "voice_note_draft_quoted_message_frame");
        if (composerQuote != null && voiceQuote != null && composerQuote != voiceQuote
                && composerQuote.isShown() && voiceQuote.isShown()
                && !isAncestor(composerQuote, voiceQuote)
                && !isAncestor(voiceQuote, composerQuote)) {
            if (!screen.duplicateReplyAlpha.containsKey(voiceQuote)) {
                screen.duplicateReplyAlpha.put(voiceQuote, voiceQuote.getAlpha());
            }
            if (voiceQuote.getAlpha() != 0f) voiceQuote.setAlpha(0f);
            host.report("double-voice-reply-exact", "second native voice reply frame suppressed");
            return;
        }
        ArrayList<View> previews = new ArrayList<>();
        collectReplyPreviews(footer, previews, 0);
        if (previews.size() < 2) {
            restoreDuplicateReply(screen);
            host.report("voice-reply-candidates-" + previews.size(),
                    "voice reply candidates=" + previews.size() + " footer=" + describeFooter(footer)
                            + " named=" + describeReplyNodes(footer, 0));
            return;
        }
        previews.sort((left, right) -> Integer.compare(screenTop(left), screenTop(right)));
        View keep = previews.get(0);
        if (screen.duplicateReplyAlpha.containsKey(keep)) {
            Float nativeAlpha = screen.duplicateReplyAlpha.remove(keep);
            if (keep.getAlpha() == 0f) keep.setAlpha(nativeAlpha);
        }
        for (int i = 1; i < previews.size(); i++) {
            View duplicate = previews.get(i);
            if (Math.abs(screenTop(duplicate) - screenTop(keep)) < 8f
                    * footer.getResources().getDisplayMetrics().density) continue;
            if (!screen.duplicateReplyAlpha.containsKey(duplicate)) {
                screen.duplicateReplyAlpha.put(duplicate, duplicate.getAlpha());
            }
            if (duplicate.getAlpha() != 0f) duplicate.setAlpha(0f);
            host.report("double-voice-reply-suppressed", "duplicate voice reply pixels suppressed: "
                    + resourceName(keep) + " / " + resourceName(duplicate)
                    + " (same footer, separate positions)");
        }
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

    /** The WhatsApp red recording microphone sometimes keeps its compact-row position
     * after the reply expands the recorder. Move only a named, small mic indicator to the
     * native elapsed-time baseline; never touch the voice action disc or red trash button. */
    private void alignRecordingMic(Screen screen, ViewGroup voice) {
        TextView timer = findElapsedLabel(voice, 0);
        ViewGroup footer = screen.footer.get();
        if (timer == null || footer == null || timer.getHeight() <= 0) return;
        View mic = findRecordingMic(footer, timer, 0);
        if (mic == null || mic.getHeight() <= 0) {
            host.report("voice-mic-route-missing", "voice mic not located; "
                    + describeRecordingIcons(footer, timer, 0));
            return;
        }
        if (!screen.recordingMicOffsets.containsKey(mic)) {
            screen.recordingMicOffsets.put(mic, mic.getTranslationY());
        }
        int[] timerLocation = new int[2], micLocation = new int[2];
        timer.getLocationOnScreen(timerLocation);
        mic.getLocationOnScreen(micLocation);
        int difference = timerLocation[1] + timer.getHeight() / 2
                - micLocation[1] - mic.getHeight() / 2;
        float d = voice.getResources().getDisplayMetrics().density;
        if (Math.abs(difference) > 3f * d && Math.abs(difference) < 180f * d) {
            mic.setTranslationY(mic.getTranslationY() + difference);
            host.report("voice-mic-aligned", "native voice mic " + resourceName(mic)
                    + " aligned with elapsed-time row by " + difference + "px");
        }
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
        // Obfuscated IDs are common for the tiny blinking red mic. A bounded positional
        // fallback targets an ImageView just below and beside the native recording timer;
        // the large delete/send controls and waveform are outside these bounds.
        if (node instanceof ImageView && node.getWidth() >= 6f * density
                && node.getWidth() < 28f * density && node.getHeight() >= 6f * density
                && node.getHeight() < 28f * density && node != timer) {
            int[] label = new int[2], icon = new int[2];
            timer.getLocationOnScreen(label);
            node.getLocationOnScreen(icon);
            int xDiff = Math.abs((icon[0] + node.getWidth() / 2)
                    - (label[0] + timer.getWidth() / 2));
            int yDiff = icon[1] - label[1];
            if (xDiff <= 55f * density && yDiff >= -8f * density
                    && yDiff <= 58f * density) return node;
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
        for (java.util.Map.Entry<View, Float> entry :
                new ArrayList<>(screen.recordingMicOffsets.entrySet())) {
            View view = entry.getKey();
            if (view != null) view.setTranslationY(entry.getValue());
        }
        screen.recordingMicOffsets.clear();
    }

    private static ViewGroup voicePanelIn(ViewGroup parent, ViewGroup footer, View input,
                                          int depth, float density) {
        if (depth > 4) return null;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (!(child instanceof ViewGroup) || child instanceof GlassPane || !child.isShown()
                    || child == input || (input != null && isAncestor(input, child))) continue;
            ViewGroup group = (ViewGroup) child;
            // A shared footer wrapper may contain both the regular input and the recorder;
            // never enlarge the voice lens to cover that whole wrapper.
            boolean wrapsInput = input != null && isAncestor(group, input);
            if (!wrapsInput && group.getWidth() >= footer.getWidth() * 0.65f
                    && group.getHeight() >= 40f * density
                    && group.getHeight() <= 280f * density
                    && containsRecordingTimer(group, 0)) return group;
            ViewGroup nested = voicePanelIn(group, footer, input, depth + 1, density);
            if (nested != null) return nested;
        }
        return null;
    }

    private static boolean containsRecordingTimer(View view, int depth) {
        if (!view.isShown() || depth > 8) return false;
        if (view instanceof TextView && isElapsedTime(((TextView) view).getText())) return true;
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (containsRecordingTimer(group.getChildAt(i), depth + 1)) return true;
        }
        return false;
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
        view.setBackground(cleared);
    }

    private static void restoreVoiceBackgrounds(Screen screen) {
        for (View view : new ArrayList<>(screen.voiceClearDrawables.keySet())) {
            if (view != null && view.getBackground() == screen.voiceClearDrawables.get(view)) {
                view.setBackground(screen.voiceBackgrounds.get(view));
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
        }
    }

    private static void restoreRecordingGlassClip(Screen screen) {
        for (java.util.Map.Entry<ViewGroup, Boolean> entry :
                new ArrayList<>(screen.recordingClipChildren.entrySet())) {
            ViewGroup group = entry.getKey();
            if (group != null) group.setClipChildren(entry.getValue());
        }
        screen.recordingClipChildren.clear();
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

    private static View findReplyPreview(ViewGroup footer) {
        String[] names = {"quoted_message_preview_container_v2", "quoted_message_preview_container",
                "composer_quoted_message_frame", "quoted_message_frame"};
        for (String name : names) {
            View candidate = findById(footer, name);
            if (candidate != null && candidate.isShown()) return candidate;
        }
        return findSemanticReply(footer, 0);
    }

    private static View findSemanticReply(View view, int depth) {
        if (!(view instanceof ViewGroup) || depth > 7) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (!child.isShown()) continue;
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
                ? holder.getHeight() + (screen.fullscreenOwned ? screen.fullscreenTopInset : 0) + gap
                : screen.listPadding[1];
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
