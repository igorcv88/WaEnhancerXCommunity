package com.waenhancer.xposed.features.customization;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.widget.AbsListView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.PopupWindow;

import androidx.annotation.NonNull;

import com.waenhancer.config.GlassSurfaceCatalog;
import com.waenhancer.config.LiquidGlassOptics;
import com.waenhancer.config.LiquidGlassSettings;
import com.waenhancer.config.LiquidGlassSettings.Surface;
import com.waenhancer.theme.CaptureScheduler;
import com.waenhancer.theme.SurfaceRecovery;
import com.waenhancer.theme.RecordingState;
import com.waenhancer.theme.GlassTrace;
import com.waenhancer.theme.GlassMaterialDrawable;
import com.waenhancer.theme.GlassOptics;
import com.waenhancer.theme.LiveBudget;
import com.waenhancer.theme.GlassRenderer;
import com.waenhancer.theme.GlassSpec;
import com.waenhancer.theme.GlassSurface;
import com.waenhancer.theme.GlassPane;
import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.WppCore;
import com.waenhancer.xposed.core.devkit.Unobfuscator;
import com.waenhancer.xposed.utils.DesignUtils;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

/** App-wide presentation adapters. Keeps native hierarchy, IDs, listeners, IME and scrolling. */
public final class AppLiquidGlass extends Feature {
    private final WeakHashMap<View, WeakReference<Session>> sessions = new WeakHashMap<>();
    private final Map<Integer, Surface> resourceSurfaces = new java.util.HashMap<>();
    private final Set<Integer> unknownResources = new HashSet<>();
    private final Set<String> reported = new HashSet<>();
    private WeakReference<Session> foreground = new WeakReference<>(null);
    private final HomeGlassChrome home = new HomeGlassChrome(new HomeGlassChrome.Host() {
        @Override public boolean toolbarsEnabled() { return LiquidGlassSettings.isEnabled(prefs, Surface.TOOLBARS); }
        @Override public void report(String key, String message) { AppLiquidGlass.this.report(key, message); }
    });
    private final ConversationGlassPanes conversation = new ConversationGlassPanes(new ConversationGlassPanes.Host() {
        @Override public boolean toolbarsEnabled() { return LiquidGlassSettings.isEnabled(prefs, Surface.TOOLBARS); }
        @Override public boolean composerEnabled() { return LiquidGlassSettings.isEnabled(prefs, Surface.COMPOSER); }
        @Override public GlassSpec material(View root) {
            Session session = sessionFor(root);
            return session == null || session.closed ? null : session.resolvedMaterial();
        }
        @Override public void report(String key, String message) { AppLiquidGlass.this.report(key, message); }
    });

    public AppLiquidGlass(@NonNull ClassLoader loader, @NonNull SharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override public void doHook() {
        // Always register lifecycle: switches can be enabled after WhatsApp has started.
        WppCore.addListenerActivity((activity, state) -> {
            if (activity == null || activity.getWindow() == null) return;
            View decor = activity.getWindow().getDecorView();
            if (state == WppCore.ActivityChangeState.ChangeType.ENDED) {
                Session session = sessionFor(decor);
                if (session != null) session.suspend();
                if (foreground.get() == session) foreground.clear();
            } else if (state == WppCore.ActivityChangeState.ChangeType.DESTROYED) {
                Session session = sessionFor(decor);
                if (session != null) session.close();
            } else if (state == WppCore.ActivityChangeState.ChangeType.RESUMED) {
                reloadPrefs();
                publishOptics();
                Session session = watch(decor);
                foreground = new WeakReference<>(session);
                if (session != null) {
                    GlassTrace.resumed(decor);
                    session.cachedMaterial = null;
                    session.resume();
                    session.scan();
                    decor.invalidate();
                }
            }
        });
        installCaptureGuards();
        installDiscoveryHooks();
        installTintObserver();
        installBubbleHook();
        XposedBridge.hookAllMethods(Dialog.class, "show", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Dialog dialog = (Dialog) param.thisObject;
                if (!LiquidGlassSettings.hasAppSurfaces(prefs) || dialog.getWindow() == null) return;
                View decor = dialog.getWindow().getDecorView();
                decor.post(() -> {
                    Session session = watch(decor);
                    if (session == null) return;
                    // Platform alert panel, not the entire full-screen dialog decor.
                    View panel = findByName(decor, "parentPanel", "android");
                    if (panel != null && LiquidGlassSettings.isEnabled(prefs, Surface.PANELS)) session.bind(panel, Surface.PANELS, false);
                    session.scan();
                });
            }
        });
        XC_MethodHook popupHook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!LiquidGlassSettings.hasAppSurfaces(prefs)) return;
                PopupWindow popup = (PopupWindow) param.thisObject;
                View content = popup.getContentView();
                if (content == null || !popup.isShowing()) return;
                content.post(() -> {
                    if (!popup.isShowing()) return;
                    View decor = content.getRootView();
                    Session session = watch(decor);
                    if (session == null) return;
                    View target = content;
                    // PopupBackgroundView draws the native shell; content is not reparented.
                    if (content.getParent() instanceof View && ((View) content.getParent()).getBackground() != null) {
                        target = (View) content.getParent();
                    }
                    if (LiquidGlassSettings.isEnabled(prefs, Surface.PANELS)) session.bind(target, Surface.PANELS, false);
                    session.scan();
                });
            }
        };
        XposedBridge.hookAllMethods(PopupWindow.class, "showAtLocation", popupHook);
        XposedBridge.hookAllMethods(PopupWindow.class, "showAsDropDown", popupHook);
    }

    /**
     * Publishes the optical-correction switches to every glass surface in this process, the
     * floating bar included. Read on every resume, so a change made in the module app applies
     * when WhatsApp comes back, without a restart; surfaces rebuild their effect on the next
     * capture because the switches are part of every effect's key.
     */
    private void publishOptics() {
        GlassOptics optics;
        try {
            optics = LiquidGlassOptics.read(prefs);
        } catch (Throwable error) {
            optics = GlassOptics.LEGACY;
            report("optics-read", "optics switches unreadable; original renderer: " + error);
        }
        if (GlassOptics.publish(optics)) {
            XposedBridge.log("[LiquidGlass/App] optics " + optics.key());
            for (WeakReference<Session> reference : new ArrayList<>(sessions.values())) {
                Session session = reference == null ? null : reference.get();
                if (session == null) continue;
                session.cachedMaterial = null;
                for (Binding binding : session.bindings.values()) {
                    binding.recovery.revalidate();
                    binding.glass.releaseLive();
                }
                View root = session.root.get();
                if (root != null) root.invalidate();
            }
        }
    }

    private void installBubbleHook() {
        try {
            // Same semantic anchor used by WaThemer. A reused anchor with several matches
            // disables only bubble glass instead of attaching to an arbitrary first result.
            var dexkit = Unobfuscator.getDexKit();
            if (dexkit == null) throw new IllegalStateException("DexKit not initialized");
            var candidates = dexkit.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .addUsingString("Unreachable code: direction=").returnType(Drawable.class)));
            if (candidates.size() != 1) {
                throw new IllegalStateException("expected one bubble factory, found " + candidates.size());
            }
            Method method = candidates.get(0).getMethodInstance(classLoader);
            if (!Drawable.class.isAssignableFrom(method.getReturnType())) return;
            XposedBridge.hookMethod(method, new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!LiquidGlassSettings.isEnabled(prefs, Surface.BUBBLES)) return;
                    Object value = param.getResult();
                    if (!(value instanceof Drawable) || value instanceof GlassMaterialDrawable) return;
                    // No row or activity captured by a global factory hook. Its callback resolves
                    // the owner after WhatsApp installs the returned drawable on the row.
                    param.setResult(new GlassMaterialDrawable(null, (Drawable) value,
                            () -> material(Surface.BUBBLES), 16f, true));
                    report("bubble-factory", "semantic bubble hook triggered");
                }
            });
        } catch (Throwable error) {
            report("bubble-missing", "bubble resolver unavailable; bubbles keep native backgrounds: " + error);
        }
    }

    private GlassSpec material(Surface surface) {
        Session session = foreground.get();
        if (session == null || !LiquidGlassSettings.isEnabled(prefs, surface)) return null;
        return session.resolvedMaterial();
    }

    private Session sessionFor(View root) {
        WeakReference<Session> reference = sessions.get(root);
        return reference == null ? null : reference.get();
    }

    private Session watch(View root) {
        Session existing = sessionFor(root);
        if (existing != null && !existing.closed) return existing;
        if (root == null) return null;
        Session session = new Session(root);
        sessions.put(root, new WeakReference<>(session));
        session.attach();
        return session;
    }

    /**
     * Touch feedback must stay out of a pane's recording.
     *
     * <p>A ripple or list selector drawn into a {@link GlassPane}'s RenderNode arms its animator
     * against that node, and the frame's own draw then throws "Target already set!". While a pane
     * records, ripples keep their static layers and list selectors are held back.</p>
     */
    private void installCaptureGuards() {
        try {
            XposedBridge.hookMethod(android.graphics.drawable.RippleDrawable.class.getDeclaredMethod("draw", Canvas.class),
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!GlassPane.isCapturing() || !((Canvas) param.args[0]).isHardwareAccelerated()) return;
                    android.graphics.drawable.RippleDrawable ripple = (android.graphics.drawable.RippleDrawable) param.thisObject;
                    Canvas canvas = (Canvas) param.args[0];
                    try {
                        for (int i = 0; i < ripple.getNumberOfLayers(); i++) {
                            if (ripple.getId(i) != android.R.id.mask) ripple.getDrawable(i).draw(canvas);
                        }
                        param.setResult(null);
                    } catch (RuntimeException | LinkageError error) { param.setThrowable(error); }
                }
            });
            XposedBridge.hookMethod(AbsListView.class.getDeclaredMethod("drawSelector", Canvas.class),
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (GlassPane.isCapturing()) param.setResult(null);
                }
            });
            report("capture-guards", "ripple and list selector capture guards installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            GlassPane.disableCapture();
            report("capture-guards-missing", "live panes disabled; static material only: " + error);
        }
    }

    private void installDiscoveryHooks() {
        try {
            XC_MethodHook childrenAdded = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (GlassPane.isCapturing()) return;
                    View child = (View) param.args[0];
                    Session session = sessionFor(((View) param.thisObject).getRootView());
                    if (session != null && child != null) session.discoverLater(child);
                }
            };
            XposedBridge.hookMethod(ViewGroup.class.getDeclaredMethod("addView", View.class, int.class,
                    ViewGroup.LayoutParams.class), childrenAdded);
            XposedBridge.hookMethod(ViewGroup.class.getDeclaredMethod("attachViewToParent", View.class, int.class,
                    ViewGroup.LayoutParams.class), childrenAdded);
            XposedBridge.hookMethod(ViewGroup.class.getDeclaredMethod("addViewInLayout", View.class, int.class,
                    ViewGroup.LayoutParams.class, boolean.class), childrenAdded);
            XposedBridge.hookMethod(View.class.getDeclaredMethod("setBackgroundDrawable", Drawable.class),
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (GlassPane.isCapturing()) return;
                    View target = (View) param.thisObject;
                    Session session = sessionFor(target.getRootView());
                    if (session != null && !session.installing) session.discoverLater(target);
                }
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            report("discovery-hook-missing", "incremental discovery unavailable: " + error);
        }
    }

    private void installTintObserver() {
        XposedBridge.hookAllMethods(View.class, "setBackgroundTintList", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                View view = (View) param.thisObject;
                Session session = sessionFor(view.getRootView());
                if (session == null || session.installing) return;
                Binding binding = session.bindings.get(view);
                if (binding != null) binding.tintChanged = true;
            }
        });
    }

    private Surface surfaceFor(View view) {
        int id = view.getId();
        if (id == View.NO_ID || id == 0 || unknownResources.contains(id)) return null;
        Surface cached = resourceSurfaces.get(id);
        if (cached != null) return cached;
        try { cached = GlassSurfaceCatalog.surface(view.getResources().getResourceEntryName(id)); }
        catch (android.content.res.Resources.NotFoundException ignored) { }
        if (cached == null) unknownResources.add(id); else resourceSurfaces.put(id, cached);
        return cached;
    }

    private void report(String key, String message) {
        if (reported.add(key)) XposedBridge.log("[LiquidGlass/App] " + message);
    }

    /** Whether {@code ancestor} contains {@code view}, strictly. */
    static boolean isAncestorOf(View ancestor, View view) {
        for (android.view.ViewParent p = view.getParent(); p instanceof View; p = p.getParent()) {
            if (p == ancestor) return true;
        }
        return false;
    }

    private static String resourceName(View view) {
        int id = view.getId();
        if (id == View.NO_ID || id == 0) return view.getClass().getSimpleName();
        try { return view.getResources().getResourceEntryName(id); }
        catch (android.content.res.Resources.NotFoundException e) { return Integer.toHexString(id); }
    }

    /** Resource name, size and the nearest named ancestors (the tab page shows up among them). */
    private static String describe(View view) {
        StringBuilder out = new StringBuilder("name=").append(resourceName(view))
                .append(" size=").append(view.getWidth()).append('x').append(view.getHeight())
                .append(" path=");
        int named = 0;
        for (android.view.ViewParent p = view.getParent(); p instanceof View && named < 6; p = p.getParent()) {
            View parent = (View) p;
            if (parent.getId() == View.NO_ID || parent.getId() == 0) continue;
            out.append(named++ == 0 ? "" : "<").append(resourceName(parent));
        }
        return out.toString();
    }

    private static View findByName(View root, String name, String pkg) {
        int id = root.getResources().getIdentifier(name, "id", pkg);
        return id == 0 ? null : root.findViewById(id);
    }

    /**
     * GPU budget per window: live surfaces are taken top to bottom until their area reaches one
     * window's worth or this count; the rest paint the static material. Top-to-bottom keeps the
     * choice stable while scrolling, so no surface flickers between live and static.
     */
    private static final int MAX_LIVE_PER_WINDOW = 32;
    private static final float MAX_LIVE_AREA_FRACTION = 1f;

    private final class Session implements ViewTreeObserver.OnGlobalLayoutListener,
            ViewTreeObserver.OnPreDrawListener, ViewTreeObserver.OnScrollChangedListener,
            View.OnAttachStateChangeListener {
        private final WeakReference<View> root;
        private final WeakHashMap<View, Binding> bindings = new WeakHashMap<>();
        private final WeakHashMap<View, Surface> candidates = new WeakHashMap<>();
        /** Views that ignore setBackground (Material FABs); never retried in this window. */
        private final WeakHashMap<View, Boolean> refused = new WeakHashMap<>();
        private final Set<View> pendingDiscovery = java.util.Collections.newSetFromMap(new WeakHashMap<>());
        private boolean discoveryPosted;
        private boolean discovered;
        private boolean installing;
        private boolean closed;
        private boolean suspended;
        private boolean observing;
        private GlassSpec cachedMaterial;
        private long materialAt;
        private final CaptureScheduler scheduler = new CaptureScheduler();
        private boolean loggedCaptureFailure;
        private String loggedBudget;
        private final ArrayList<View> targets = new ArrayList<>();
        private final ArrayList<View> visible = new ArrayList<>();
        private final android.graphics.Rect visibleRect = new android.graphics.Rect();
        private long[] order = new long[16];
        private long[] areas = new long[16];
        private long pendingFrameAt = Long.MAX_VALUE;
        private final Runnable frame = this::invalidateFrame;
        private void invalidateFrame() {
            pendingFrameAt = Long.MAX_VALUE;
            View view = root.get();
            if (!closed && !suspended && view != null && view.isAttachedToWindow()) view.invalidate();
        }
        private final Runnable discovery = this::runDiscovery;

        private void scheduleFrame(long delay) {
            View view = root.get();
            if (closed || suspended || view == null) return;
            long at = SystemClock.uptimeMillis() + Math.max(1L, delay);
            if (at >= pendingFrameAt) return;
            view.removeCallbacks(frame);
            pendingFrameAt = at;
            view.postDelayed(frame, Math.max(1L, delay));
        }

        private void cancelPending() {
            View view = root.get();
            if (view != null) { view.removeCallbacks(frame); view.removeCallbacks(discovery); }
            pendingFrameAt = Long.MAX_VALUE;
            discoveryPosted = false;
        }


        Session(View root) {
            this.root = new WeakReference<>(root);
        }

        GlassSpec resolvedMaterial() {
            View view = root.get();
            if (view == null) return null;
            long now = SystemClock.uptimeMillis();
            if (cachedMaterial == null || now - materialAt >= 1000) {
                GlassSpec resolved = GlassRenderer.resolveFor(view.getContext(),
                        LiquidGlassSettings.MATERIAL.key(), DesignUtils.isNightMode(view.getContext()),
                        0, DesignUtils.getPrimaryColor(), LiquidGlassSettings.opacityPercent(prefs));
                GlassOptics optics = GlassOptics.current();
                if (optics.clearProfile) resolved = resolved.clearProfile();
                // Temporal group: keep the previous object when nothing changed, so effects keyed
                // on it stay put. Legacy re-resolves a new object every second, as before.
                if (!(optics.corrected && optics.temporal) || !resolved.equals(cachedMaterial)) {
                    cachedMaterial = resolved;
                }
                materialAt = now;
            }
            return cachedMaterial;
        }

        void attach() {
            View view = root.get();
            if (view == null) return;
            observe(true);
            view.addOnAttachStateChangeListener(this);
            GlassTrace.event(view, this, view, "BOUND", "attach", "");
        }

        private void observe(boolean enable) {
            View view = root.get();
            if (view == null || observing == enable) return;
            ViewTreeObserver observer = view.getViewTreeObserver();
            if (!observer.isAlive()) return;
            if (enable) {
                observer.addOnGlobalLayoutListener(this);
                observer.addOnPreDrawListener(this);
                observer.addOnScrollChangedListener(this);
            } else {
                observer.removeOnGlobalLayoutListener(this);
                observer.removeOnPreDrawListener(this);
                observer.removeOnScrollChangedListener(this);
            }
            observing = enable;
        }

        void suspend() {
            if (closed || suspended) return;
            suspended = true;
            GlassTrace.suspended(root.get());
            cancelPending();
            observe(false);
            conversation.setSuspended(root.get(), true);
            for (Binding binding : bindings.values()) {
                binding.recovery.suspend();
                // Retain UI ownership, never keep chat pixels across a visibility epoch.
                binding.glass.releaseLive();
            }
            GlassTrace.event(root.get(), this, root.get(), "SUSPENDED", "activity-stop", "bindings=" + bindings.size());
        }

        void resume() {
            if (closed) return;
            boolean returning = suspended;
            suspended = false;
            observe(true);
            conversation.setSuspended(root.get(), false);
            if (returning) for (Binding binding : bindings.values()) {
                binding.recovery.revalidate();
                binding.glass.releaseLive();
            }
            scheduler.activity(SystemClock.uptimeMillis());
            if (!pendingDiscovery.isEmpty()) runDiscovery();
            GlassTrace.event(root.get(), this, root.get(), "REVALIDATING", "activity-resume", "bindings=" + bindings.size());
        }

        private void trace(Binding binding, SurfaceRecovery.State before, String reason) {
            binding.glass.setCaptureDisabled(binding.recovery.state() == SurfaceRecovery.State.DISABLED);
            if (before == binding.recovery.state()) return;
            GlassTrace.transition(root.get(), binding.glass, root.get(), before, binding.recovery, reason,
                    "role=" + binding.surface + " specKey=" + GlassOptics.current().key()
                    + ":" + (cachedMaterial == null ? "none" : Integer.toHexString(cachedMaterial.hashCode()))
                    + " " + binding.glass.captureStatus());
        }

        @Override public void onGlobalLayout() {
            scheduler.activity(SystemClock.uptimeMillis());
            scan(); // Reconcile known candidates; no periodic full-tree discovery.
        }

        @Override public void onScrollChanged() {
            scheduler.activity(SystemClock.uptimeMillis());
        }

        /**
         * Records every live surface of this window in one pass, before the frame draws: every
         * frame while something moves, at a slow heartbeat at rest. One pass per frame keeps all
         * recordings on the same draw order, which is what keeps them from recording each other.
         */
        @Override public boolean onPreDraw() {
            try {
                if (closed || suspended || GlassPane.isCapturing()) return true;
                GlassTrace.frame(root.get());
                if (bindings.isEmpty()) return true;
                long now = SystemClock.uptimeMillis();
                boolean fresh = false;
                boolean retryDue = false;
                for (Map.Entry<View, Binding> entry : bindings.entrySet()) {
                    View target = entry.getKey();
                    Binding binding = entry.getValue();
                    boolean eligible = target != null && target.isAttachedToWindow() && target.isShown()
                            && target.getWidth() > 0 && target.getHeight() > 0
                            && target.getGlobalVisibleRect(visibleRect) && binding.glass.wantsLiveCapture();
                    boolean mayCapture = binding.recovery.mayCapture(now);
                    if (eligible && !mayCapture) {
                        long delay = binding.recovery.retryDelay(now);
                        if (delay > 0) scheduleFrame(delay);
                    }
                    retryDue |= eligible && binding.recovery.retryDue(now);
                    fresh |= binding.glass.needsFreshCapture(eligible && mayCapture);
                }
                GlassOptics optics = GlassOptics.current();
                boolean temporal = optics.corrected && optics.temporal;
                int decision = scheduler.decide(now, temporal, fresh, retryDue);
                View view = root.get();
                if (view == null) return true;
                if ((decision & CaptureScheduler.CAPTURE) == 0) {
                    if ((decision & CaptureScheduler.INVALIDATE_LATER) != 0) {
                        scheduleFrame(scheduler.delayMs());
                    }
                    return true;
                }
                boolean invalidateNow = (decision & CaptureScheduler.INVALIDATE_NOW) != 0;
                boolean invalidateLater = (decision & CaptureScheduler.INVALIDATE_LATER) != 0;
                // Scratch storage reused across frames: this runs on every frame while scrolling.
                targets.clear();
                for (View target : bindings.keySet()) if (target != null) targets.add(target);
                int count = 0;
                if (order.length < targets.size()) {
                    order = new long[targets.size() * 2];
                    areas = new long[targets.size() * 2];
                }
                visible.clear();
                // Temporal group: one sticky ledger per window (see LiveBudget).
                LiveBudget ledger = temporal ? LiveBudget.forWindow(view) : null;
                for (View target : targets) {
                    Binding binding = bindings.get(target);
                    if (binding == null) continue;
                    // Off screen or hidden: nothing to show, so no recording either.
                    if (!target.isShown() || !target.getGlobalVisibleRect(visibleRect)) {
                        binding.visible = false;
                        binding.glass.releaseLive();
                        if (ledger != null) ledger.releaseDrawable(binding.glass);
                        continue;
                    }
                    if (!binding.visible) {
                        binding.visible = true;
                        binding.recovery.revalidate();
                        binding.glass.releaseLive();
                    }
                    // Top to bottom, then left to right; the index rides in the low bits.
                    long top = Math.max(-(1L << 19), Math.min((1L << 19) - 1, visibleRect.top)) + (1L << 19);
                    long left = Math.max(0L, Math.min((1L << 20) - 1, visibleRect.left));
                    areas[count] = (long) visibleRect.width() * visibleRect.height();
                    order[count] = (top << 40) | (left << 20) | count;
                    visible.add(target);
                    count++;
                }
                java.util.Arrays.sort(order, 0, count);
                long budget = (long) (MAX_LIVE_AREA_FRACTION * view.getWidth() * view.getHeight());
                long used = 0;
                int live = 0;
                int panes = 0;
                float density = view.getResources().getDisplayMetrics().density;
                GlassSpec material = temporal ? resolvedMaterial() : null;
                if (ledger != null) {
                    // One budget per window for all live glass (LG-12), in effective pixel-passes:
                    // panes are admitted first in their own pre-draw, the drawables get the rest.
                    budget = ledger.capacity();
                    used = ledger.paneSpend();
                    panes = ledger.paneCount();
                    live = panes;
                }
                for (int k = 0; k < count; k++) {
                    int i = (int) (order[k] & 0xFFFFF);
                    View target = visible.get(i);
                    Binding binding = bindings.get(target);
                    if (binding == null) continue;
                    if (!binding.glass.wantsLiveCapture()) {
                        binding.glass.releaseLive();
                        if (ledger != null) ledger.releaseDrawable(binding.glass);
                        continue;
                    }
                    long area = temporal && material != null
                            ? LiveBudget.cost(target.getWidth(), target.getHeight(), material, density, optics)
                            : areas[i];
                    if (binding.width != target.getWidth() || binding.height != target.getHeight()) {
                        binding.width = target.getWidth(); binding.height = target.getHeight();
                        binding.recovery.revalidate();
                        binding.glass.releaseLive();
                    }
                    if (!binding.recovery.mayCapture(now)) continue;
                    // Sticky with the ledger: a live surface keeps its slot while visible, so a
                    // layout change cannot flip surfaces between glass and fallback.
                    // The count limit, like the area one, applies only to new admissions: a holder
                    // keeps priority whatever order this frame visits surfaces in.
                    boolean admitted;
                    if (ledger != null) {
                        admitted = (ledger.holdsDrawable(binding.glass)
                                || panes + ledger.drawableCount() < MAX_LIVE_PER_WINDOW)
                                && ledger.admitDrawable(binding.glass, area);
                    } else {
                        admitted = live < MAX_LIVE_PER_WINDOW && used + area <= budget;
                    }
                    binding.glass.setBudgetStatus(admitted ? "admitted" : "refused");
                    if (!admitted) {
                        binding.glass.releaseLive(RecordingState.BUDGET_REFUSED);
                        if (ledger != null) ledger.releaseDrawable(binding.glass);
                        continue;
                    }
                    boolean captured;
                    try {
                        captured = binding.glass.captureBehind(target);
                    } catch (Throwable error) {
                        SurfaceRecovery.State before = binding.recovery.state();
                        binding.recovery.failed(now, error instanceof LinkageError);
                        binding.glass.releaseLive(RecordingState.TEMPORARILY_UNAVAILABLE);
                        if (ledger != null) ledger.releaseDrawable(binding.glass);
                        trace(binding, before, "capture-exception");
                        if (binding.recovery.failures() == 1) XposedBridge.log(error);
                        long delay = binding.recovery.retryDelay(now);
                        if (delay > 0) scheduleFrame(delay);
                        continue;
                    }
                    SurfaceRecovery.State before = binding.recovery.state();
                    if (captured) {
                        binding.recovery.ready();
                        GlassTrace.captured(view, binding.glass, view);
                        trace(binding, before, "capture-published");
                        live++;
                        used += area;
                        if (invalidateNow) target.invalidate();
                    } else {
                        binding.recovery.failed(now, binding.glass.capturePermanentlyUnavailable());
                        binding.glass.releaseLive(RecordingState.TEMPORARILY_UNAVAILABLE);
                        if (ledger != null) ledger.releaseDrawable(binding.glass);
                        trace(binding, before, "capture-not-ready");
                        long delay = binding.recovery.retryDelay(now);
                        if (delay > 0) scheduleFrame(delay);
                    }
                }
                visible.clear();
                targets.clear();
                if (live > 0 && invalidateLater) scheduleFrame(scheduler.delayMs());
                if (temporal) {
                    String budgetLine = panes + " panes, " + (live - panes) + " drawables live";
                    if (!budgetLine.equals(loggedBudget)) {
                        loggedBudget = budgetLine;
                        report("budget-" + budgetLine, "live glass budget: " + budgetLine
                                + " of " + MAX_LIVE_PER_WINDOW + ", cost " + used + "/" + budget
                                + " px-passes");
                    }
                }
            } catch (Throwable error) {
                if (!loggedCaptureFailure) {
                    loggedCaptureFailure = true;
                    report("live-capture-error", "live surface capture skipped: " + error);
                }
            }
            return true; // never cancel the host's frame
        }

        void scan() {
            if (closed || suspended || GlassPane.isCapturing()) return;
            View view = root.get();
            if (view == null) return;

            // Before the early return below: switching the panes off must still restore the screen.
            conversation.sync(view);
            home.sync(view);
            try {
                for (Map.Entry<View, Binding> entry : new ArrayList<>(bindings.entrySet())) {
                    View target = entry.getKey();
                    if (target == null) continue;
                    Binding binding = entry.getValue();
                    if (target.getRootView() != view) {
                        releaseBudget(binding);
                        binding.restore(target);
                        bindings.remove(target);
                    } else if (!LiquidGlassSettings.isEnabled(prefs, binding.surface) || !target.isAttachedToWindow()
                            || conversation.owns(target)) {
                        releaseBudget(binding);
                        binding.restore(target);
                        bindings.remove(target);
                    } else if (target.getBackground() != binding.glass
                            && !(target.getBackground() instanceof GlassMaterialDrawable)
                            && keepGlass(target, binding)) {
                        // WhatsApp swapped the native background under the glass (the home toolbar
                        // does so on scroll and tab changes): the glass, and its live recording,
                        // stay; the new drawable becomes the one restored later.
                        continue;
                    } else if (target.getBackground() != binding.glass) {
                        // A native rebind wins. Capture its new background rather than restoring
                        // an old drawable over whatever WhatsApp just changed.
                        releaseBudget(binding);
                        binding.restore(target);
                        bindings.remove(target);
                        if (target.getBackground() instanceof GlassMaterialDrawable) adopt(target, (GlassMaterialDrawable) target.getBackground());
                        else bind(target, binding.surface, binding.nativeMask);
                    }
                }
                if (!LiquidGlassSettings.hasAppSurfaces(prefs)) return;
                if (!discovered) { discovered = true; walk(view, 0); }
                for (Map.Entry<View, Surface> entry : new ArrayList<>(candidates.entrySet())) {
                    View candidate = entry.getKey();
                    if (candidate != null && candidate.isAttachedToWindow() && candidate.getRootView() == view
                            && LiquidGlassSettings.isEnabled(prefs, entry.getValue())) bind(candidate, entry.getValue(), false);
                }
            } catch (Throwable error) {
                report("scan-error", "surface scan skipped: " + error);
            }
        }


        void discoverLater(View view) {
            if (closed || GlassPane.isCapturing()) return;
            pendingDiscovery.add(view);
            if (suspended) return;
            if (discoveryPosted) return;
            View decor = root.get();
            if (decor == null) return;
            discoveryPosted = true;
            decor.post(discovery);
        }

        private void runDiscovery() {
            discoveryPosted = false;
            if (closed || suspended) return;
            for (View candidate : new ArrayList<>(pendingDiscovery)) {
                if (candidate != null && candidate.isAttachedToWindow() && candidate.getRootView() == root.get()) walk(candidate, 0);
            }
            pendingDiscovery.clear();
            scan();
        }


        private void walk(View view, int depth) {
            if (depth > 24 || view == null || GlassSurface.isGlassHost(view) || view instanceof GlassPane) return;
            if (view.getBackground() instanceof GlassMaterialDrawable && !bindings.containsKey(view)) {
                GlassMaterialDrawable glass = (GlassMaterialDrawable) view.getBackground();
                if (LiquidGlassSettings.isEnabled(prefs, Surface.BUBBLES)) {
                    adopt(view, glass);
                } else {
                    glass.restoreCallback();
                    view.setBackground(glass.original());
                }
            }
            Surface surface = surfaceFor(view);
            if (surface != null) {
                candidates.put(view, surface);
                if (LiquidGlassSettings.isEnabled(prefs, surface)) bind(view, surface, false);
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i), depth + 1);
            }
        }

        /** Puts the existing glass back over a replaced native background; false if refused. */
        private boolean keepGlass(View view, Binding binding) {
            Drawable replacement = view.getBackground();
            installing = true;
            try {
                binding.original = replacement;
                // The tint WhatsApp set with the new background is the native one to restore later.
                binding.state = new BackgroundState(view);
                binding.tintChanged = false;
                binding.glass.replaceOriginal(replacement);
                if (view.getBackgroundTintList() != null) view.setBackgroundTintList(null);
                view.setBackground(binding.glass);
                // setBackground detached the replaced drawable; it draws through the glass now.
                if (replacement != null) replacement.setCallback(binding.glass);
                boolean kept = view.getBackground() == binding.glass;
                if (kept) report("kept-" + binding.surface, binding.surface + " native background replaced under the glass; glass kept");
                return kept;
            } finally { installing = false; }
        }

        private void adopt(View view, GlassMaterialDrawable glass) {
            Binding binding = new Binding(view, glass.original(), Surface.BUBBLES, true);
            binding.glass = glass;
            bindings.put(view, binding);
            glass.attachMaterial(() -> LiquidGlassSettings.isEnabled(prefs, Surface.BUBBLES) ? resolvedMaterial() : null);
        }

        /** A binding going away gives its slot back at once rather than when the GC notices. */
        private void releaseBudget(Binding binding) {
            View view = root.get();
            if (view != null && binding.glass != null) LiveBudget.forWindow(view).releaseDrawable(binding.glass);
        }

        void bind(View view, Surface surface, boolean nativeMask) {
            if (view == null || bindings.containsKey(view) || refused.containsKey(view)
                    || view.getWidth() < 1 || view.getHeight() < 1
                    || view == root.get() || GlassSurface.isGlassHost(view) || view instanceof GlassPane
                    || conversation.owns(view)) return;
            // One pane per surface, innermost wins: WhatsApp nests ids of one surface (my_search_bar
            // holds search_bar), and binding both drew a glass pill inside a second glass pill.
            for (Map.Entry<View, Binding> entry : new ArrayList<>(bindings.entrySet())) {
                View other = entry.getKey();
                if (other == null || entry.getValue().surface != surface) continue;
                if (isAncestorOf(view, other)) return;          // a bound descendant already shows it
                if (isAncestorOf(other, view)) {                 // the bound one is the outer container
                    GlassTrace.event(root.get(), entry.getValue().glass, other, "UNBOUND", "inner-surface-wins",
                            "role=" + surface + " " + describe(other) + " inner=" + resourceName(view));
                    releaseBudget(entry.getValue());
                    entry.getValue().restore(other);
                    bindings.remove(other);
                }
            }
            try {
                Binding binding = new Binding(view, view.getBackground(), surface, nativeMask);
                binding.glass = new GlassMaterialDrawable(view, binding.original,
                        () -> LiquidGlassSettings.isEnabled(prefs, surface) ? resolvedMaterial() : null, GlassSurfaceCatalog.radiusDp(surface), nativeMask);
                bindings.put(view, binding);
                installing = true;
                view.setBackgroundTintList(null);
                view.setBackground(binding.glass);
                if (view.getBackground() != binding.glass) {
                    // Material's FloatingActionButton ignores setBackground. Without this guard every
                    // scan saw a "native rebind", restored and bound again: hundreds of binds per
                    // second on home's fab_second, each one clearing the button's tint.
                    bindings.remove(view);
                    view.setBackgroundTintList(binding.state.tint);
                    refused.put(view, Boolean.TRUE);
                    GlassTrace.event(root.get(), binding.glass, view, "REFUSED", "background-ignored",
                            "role=" + surface + " " + describe(view));
                    report("refused-" + surface, surface + " left native: " + resourceName(view)
                            + " ignores setBackground");
                    return;
                }
                view.setPadding(binding.left, binding.top, binding.right, binding.bottom);

                report("surface-" + surface, surface + " background installed; native layout retained");
                // Which view each surface binds, and under which tab: needed to tell home tabs apart.
                GlassTrace.event(root.get(), binding.glass, view, "BOUND", "surface-bind",
                        "role=" + surface + " " + describe(view));
                diagnosticTriggered();
            } catch (Throwable error) {
                Binding binding = bindings.remove(view);
                if (binding != null) binding.restore(view);
                report("bind-" + surface, surface + " unchanged: " + error);
            } finally { installing = false; }
        }

        void close() {
            GlassTrace.suspended(root.get());
            if (closed) return;
            closed = true;
            cancelPending();
            observe(false);
            if (foreground.get() == this) foreground.clear();
            GlassTrace.event(root.get(), this, root.get(), "RELEASED", "destroy-or-detach", "");
            View view = root.get();
            if (view != null) {
                ViewTreeObserver observer = view.getViewTreeObserver();
                if (observer.isAlive()) {
                    observer.removeOnGlobalLayoutListener(this);
                    observer.removeOnPreDrawListener(this);
                    observer.removeOnScrollChangedListener(this);
                }
                view.removeOnAttachStateChangeListener(this);
                sessions.remove(view);
                conversation.release(view);
            }
            for (Map.Entry<View, Binding> entry : new ArrayList<>(bindings.entrySet())) {
                View target = entry.getKey();
                if (target == null) continue;
                releaseBudget(entry.getValue());
                entry.getValue().restore(target);
            }
            bindings.clear(); candidates.clear(); pendingDiscovery.clear();
        }
        @Override public void onViewAttachedToWindow(View view) { }
        @Override public void onViewDetachedFromWindow(View view) { close(); }
    }

    private static final class BackgroundState {
        final ColorStateList tint;
        BackgroundState(View view) {
            tint = view.getBackgroundTintList();
        }
    }

    private static final class Binding {
        Drawable original;
        final Surface surface;
        final boolean nativeMask;
        BackgroundState state;
        final int left, top, right, bottom;
        GlassMaterialDrawable glass;
        boolean tintChanged;
        int width, height;
        boolean visible;
        final SurfaceRecovery recovery = new SurfaceRecovery();
        Binding(View view, Drawable original, Surface surface, boolean nativeMask) {
            this.original = original;
            this.surface = surface;
            this.nativeMask = nativeMask;
            state = new BackgroundState(view);
            left = view.getPaddingLeft(); top = view.getPaddingTop();
            right = view.getPaddingRight(); bottom = view.getPaddingBottom();
        }
        void restore(View view) {
            recovery.release();
            glass.releaseLive();
            if (view.getBackground() == glass) {
                glass.restoreCallback();
                // Preserve native padding/tint updates made while glass was active.
                int l = view.getPaddingLeft(), t = view.getPaddingTop();
                int r = view.getPaddingRight(), b = view.getPaddingBottom();
                boolean restoreTint = !tintChanged && view.getBackgroundTintList() == null;
                android.graphics.PorterDuff.Mode liveMode = view.getBackgroundTintMode();
                view.setBackground(original);
                if (restoreTint) view.setBackgroundTintList(state.tint);
                view.setBackgroundTintMode(liveMode);
                view.setPadding(l, t, r, b);
            }
        }
    }

    @NonNull @Override public String getPluginName() { return "Liquid Glass: app surfaces"; }
}
