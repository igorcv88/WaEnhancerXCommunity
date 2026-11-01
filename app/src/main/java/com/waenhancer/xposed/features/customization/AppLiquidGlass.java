package com.waenhancer.xposed.features.customization;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RotateDrawable;
import android.graphics.drawable.ScaleDrawable;
import android.os.SystemClock;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.view.ViewStub;
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
import com.waenhancer.theme.GlassProbe;
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


/** App-wide presentation adapters. Keeps native hierarchy, IDs, listeners, IME and scrolling. */
public final class AppLiquidGlass extends Feature {
    private final WeakHashMap<View, WeakReference<Session>> sessions = new WeakHashMap<>();
    private final Map<Integer, Surface> resourceSurfaces = new java.util.HashMap<>();
    private final Set<Integer> unknownResources = new HashSet<>();
    private final Set<String> reported = new HashSet<>();
    /** Direction metadata survives factory creation until the native row installs the drawable. */
    private final Map<GlassMaterialDrawable, Boolean> bubbleDirections =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());
    /** Callback-owned factory drawables that never entered a View background/foreground slot. */
    private final WeakHashMap<View, GlassMaterialDrawable> callbackBubbles = new WeakHashMap<>();
    /** Keep a stable tinted spec across draw frames so fallbacks/shaders are not rebuilt. */
    private final Map<GlassSpec, Map<Integer, GlassSpec>> tintedMaterials = new WeakHashMap<>();
    private volatile BubbleColors.Palette bubblePalette = new BubbleColors.Palette(0, 0, false);
    private WeakReference<Session> foreground = new WeakReference<>(null);
    private final HomeGlassChrome home = new HomeGlassChrome(new HomeGlassChrome.Host() {
        @Override public boolean toolbarsEnabled() { return LiquidGlassSettings.isEnabled(prefs, Surface.TOOLBARS); }
        @Override public boolean searchEnabled() { return LiquidGlassSettings.isEnabled(prefs, Surface.SEARCH); }
        @Override public void report(String key, String message) { AppLiquidGlass.this.report(key, message); }
    });
    private final ConversationGlassPanes conversation = new ConversationGlassPanes(new ConversationGlassPanes.Host() {
        @Override public boolean toolbarsEnabled() { return LiquidGlassSettings.isEnabled(prefs, Surface.CONVERSATION_HEADER); }
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
        refreshBubblePalette();
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
                conversation.setActivityWindow(decor, activity.getWindow());
                reloadPrefs();
                refreshBubblePalette();
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
        installVoiceActivationHooks();
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

    private void refreshBubblePalette() {
        try { bubblePalette = BubbleColors.resolvePalette(prefs); }
        catch (RuntimeException error) {
            bubblePalette = new BubbleColors.Palette(0, 0, false);
            report("bubble-palette", "custom bubble palette unavailable: " + error);
        }
        synchronized (tintedMaterials) { tintedMaterials.clear(); }
    }

    private GlassSpec bubbleMaterial(boolean outgoing, GlassSpec base) {
        if (base == null || !LiquidGlassSettings.bubbleEnabled(prefs, outgoing)) return null;
        int tint = bubblePalette.forSide(outgoing);
        if (tint == 0 || (tint >>> 24) == 0) return base;
        synchronized (tintedMaterials) {
            return tintedMaterials.computeIfAbsent(base, ignored -> new java.util.HashMap<>())
                    .computeIfAbsent(tint, base::withBubbleTint);
        }
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
            com.waenhancer.theme.KyantLens.publishOpacity(LiquidGlassSettings.opacityPercent(prefs),
                    LiquidGlassSettings.MATERIAL.recommendedOpacityPercent());
            com.waenhancer.theme.KyantLens.publishRimIntensity(LiquidGlassSettings.rimIntensity(prefs));
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

    private final java.util.Set<Method> rowDrawHooks = new java.util.HashSet<>();
    private final WeakHashMap<View, Boolean> nativeDrawRows = new WeakHashMap<>();

    /** Hook only implementations reached by a verified Fyn conversation row. Shared base
     * methods are filtered by identity, and nested scopes are restored even on native errors. */
    private void observeNativeRowDraw(View row) {
        nativeDrawRows.put(row, Boolean.TRUE);
        for (String name : new String[]{"draw", "onDraw", "dispatchDraw"}) {
            for (Class<?> type = row.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    Method method = type.getDeclaredMethod(name, Canvas.class);
                    if (rowDrawHooks.add(method)) XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            View host = (View) param.thisObject;
                            if (GlassPane.isCapturing() || !nativeDrawRows.containsKey(host)) return;
                            param.setObjectExtra("waex-row-scope", Boolean.TRUE);
                            param.setObjectExtra("waex-row-previous", GlassMaterialDrawable.enterRowDraw(
                                    host, (Canvas) param.args[0]));
                            param.setObjectExtra("waex-row-frame", GlassMaterialDrawable.currentRowDraw());
                        }
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.getObjectExtra("waex-row-scope") != null) {
                                GlassMaterialDrawable.DrawHost frame = (GlassMaterialDrawable.DrawHost)
                                        param.getObjectExtra("waex-row-frame");
                                GlassMaterialDrawable.leaveRowDraw((GlassMaterialDrawable.DrawHost)
                                        param.getObjectExtra("waex-row-previous"));
                                // Only the outermost draw() establishes that the native row
                                // really stopped painting the previously adopted factory.
                                if ("draw".equals(method.getName()) && frame != null
                                        && !frame.isNestedFor((View) param.thisObject)) {
                                    View host = (View) param.thisObject;
                                    Session session = sessionFor(host.getRootView());
                                    if (session != null) session.reconcileCanvasDraw(host, frame);
                                }
                            }
                        }
                    });
                    break;
                } catch (NoSuchMethodException absent) {
                    // Search the inherited implementation, not an obfuscated class name.
                } catch (RuntimeException | LinkageError error) {
                    report("bubble-draw-hook", "native row draw observation unavailable: " + error);
                    break;
                }
            }
        }
    }

    private void installBubbleHook() {
        try {
            // Use the same cached factory resolver as the already-working BubbleColors hook.
            // Requiring a unique broad DexKit string match here disabled glass on builds where
            // other, unrelated methods share that string.
            Method method = Unobfuscator.loadBubbleDrawableMethod(classLoader);
            if (!Drawable.class.isAssignableFrom(method.getReturnType())
                    || method.getParameterCount() == 0 || method.getParameterTypes()[0] != int.class) {
                throw new IllegalStateException("bubble factory signature changed");
            }
            XposedBridge.hookMethod(method, new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || !(param.args[0] instanceof Integer)) return;
                    // Identical direction convention to BubbleColors: 3 is my/right bubble.
                    boolean outgoing = ((Integer) param.args[0]) == 3;
                    if (!LiquidGlassSettings.bubbleEnabled(prefs, outgoing)) return;
                    Object value = param.getResult();
                    if (!(value instanceof Drawable) || value instanceof GlassMaterialDrawable) return;
                    // No row or activity captured by a global factory hook. Its callback resolves
                    // the owner after WhatsApp installs the returned drawable on the row.
                    GlassMaterialDrawable glass = new GlassMaterialDrawable(null, (Drawable) value,
                            () -> bubbleMaterial(outgoing, material(Surface.BUBBLES)), 16f, true);
                    bubbleDirections.put(glass, outgoing);
                    // The factory can be called before the row belongs to a watched window.
                    // Its drawable may also be nested inside a selector without a later
                    // View.setBackground call. Follow the real native callback chain when the
                    // wrapper draws, then adopt it on the next UI turn (never during capture).
                    glass.observeHost(host -> host.post(() -> {
                        callbackBubbles.put(host, glass);
                        Session session = sessionFor(host.getRootView());
                        if (session != null && !session.closed) {
                            session.adopt(host, glass);
                        }
                    }), () -> report("bubble-no-host-" + (outgoing ? "out" : "in"),
                            "bubble factory drawable was drawn without a View callback; "
                                    + "native bubble renderer may not expose a view background"));
                    param.setResult(glass);
                    report("bubble-factory-" + (outgoing ? "out" : "in"),
                            "bubble-factory side=" + (outgoing ? "outgoing" : "incoming") + " wrapped");
                }
            });
        } catch (Throwable error) {
            report("bubble-missing", "bubble resolver unavailable; bubbles keep native backgrounds: " + error);
        }
    }

    /** Find glass wrapped by WhatsApp's selector, inset, layer or ripple background. */
    private static GlassMaterialDrawable nestedBubble(Drawable drawable, int depth) {
        if (drawable == null || depth > 8) return null;
        if (drawable instanceof GlassMaterialDrawable) return (GlassMaterialDrawable) drawable;
        if (drawable instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) drawable;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                GlassMaterialDrawable found = nestedBubble(layers.getDrawable(i), depth + 1);
                if (found != null) return found;
            }
        }
        Drawable inner = null;
        if (drawable instanceof InsetDrawable) inner = ((InsetDrawable) drawable).getDrawable();
        else if (drawable instanceof ClipDrawable) inner = ((ClipDrawable) drawable).getDrawable();
        else if (drawable instanceof ScaleDrawable) inner = ((ScaleDrawable) drawable).getDrawable();
        else if (drawable instanceof RotateDrawable) inner = ((RotateDrawable) drawable).getDrawable();
        else {
            Drawable current = drawable.getCurrent();
            if (current != drawable) inner = current;
        }
        return nestedBubble(inner, depth + 1);
    }

    private static GlassMaterialDrawable bubbleOn(View view) {
        if (view == null) return null;
        GlassMaterialDrawable glass = nestedBubble(view.getBackground(), 0);
        if (glass == null) glass = nestedBubble(view.getForeground(), 0);
        if (glass == null && view instanceof ImageView) {
            glass = nestedBubble(((ImageView) view).getDrawable(), 0);
        }
        return glass;
    }

    private GlassMaterialDrawable bubbleFor(View view) {
        GlassMaterialDrawable glass = bubbleOn(view);
        if (glass != null) return glass;
        glass = callbackBubbles.get(view);
        return glass != null && glass.callbackOwner() == view ? glass : null;
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
                    if (child != null && isRecorderAnchor(child)) conversation.onRecorderSignal(child);
                    conversation.onBackgroundChanged((View) param.thisObject);
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
                    if (GlassProbe.enabled() && session != null
                            && (GlassProbe.composerNode(target) || conversation.owns(target))) {
                        GlassProbe.event("BACKGROUND_HOOK", target,
                                "new=" + GlassProbe.drawable((Drawable) param.args[0])
                                        + " paneOwns=" + conversation.owns(target)
                                        + " sessionInstalling=" + session.installing
                                        + " bound=" + session.bindings.containsKey(target));
                    }
                    boolean paneOwned = conversation.onBackgroundChanged(target, "background");
                    if (session != null && !session.installing && !paneOwned) {
                        session.rowContentChanged(target);
                        session.discoverLater(target);
                    }
                }
            });
            XposedBridge.hookMethod(View.class.getDeclaredMethod("setForeground", Drawable.class),
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (GlassPane.isCapturing()) return;
                            View target = (View) param.thisObject;
                            boolean paneOwned = conversation.onBackgroundChanged(target, "foreground");
                            Session session = sessionFor(target.getRootView());
                            if (session != null && !session.installing
                                    && !paneOwned
                                    && (nestedBubble((Drawable) param.args[0], 0) != null
                                        || session.bindings.containsKey(target))) session.discoverLater(target);
                        }
                    });
            XposedBridge.hookAllMethods(ImageView.class, "setImageDrawable", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (GlassPane.isCapturing()) return;
                    ImageView target = (ImageView) param.thisObject;
                    conversation.onBackgroundChanged(target);
                    Session session = sessionFor(target.getRootView());
                    if (session != null) session.rowContentChanged(target);
                    if (session != null && !session.installing
                            && (nestedBubble((Drawable) param.args[0], 0) != null
                                || session.bindings.containsKey(target))) session.discoverLater(target);
                }
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            report("discovery-hook-missing", "incremental discovery unavailable: " + error);
        }
    }

    /** Resolved once per process: this gate runs inside every app-wide setVisibility call. */
    private static int recorderStubId = -1, recorderDraftId = -1;

    private static boolean isRecorderAnchor(View view) {
        if (view == null) return false;
        int id = view.getId();
        if (id == View.NO_ID || id == 0) return false;
        if (recorderStubId == -1) {
            android.content.res.Resources resources = view.getResources();
            String pkg = view.getContext().getPackageName();
            recorderStubId = resources.getIdentifier("voice_note_stub", "id", pkg);
            recorderDraftId = resources.getIdentifier("voice_note_draft_layout_v2", "id", pkg);
        }
        // An integer comparison instead of a resource-name lookup and String per visibility change.
        return (recorderStubId != 0 && id == recorderStubId) || (recorderDraftId != 0 && id == recorderDraftId);
    }

    /** A recording can begin without a global layout or touch-driven redraw. The native
     * recorder's visibility/inflation and first timer tick explicitly wake the existing pane. */
    private void installVoiceActivationHooks() {
        try {
            XposedBridge.hookMethod(View.class.getDeclaredMethod("setVisibility", int.class),
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (GlassPane.isCapturing()) return;
                            View view = (View) param.thisObject;
                            if (isRecorderAnchor(view)) {
                                if (GlassProbe.enabled()) GlassProbe.event("NATIVE_VISIBILITY_HOOK", view,
                                        "requested=" + param.args[0]);
                                conversation.onRecorderSignal(view);
                            }
                        }
                    });
            XposedBridge.hookMethod(ViewStub.class.getDeclaredMethod("inflate"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (GlassPane.isCapturing()) return;
                    Object result = param.getResult();
                    if (result instanceof View && isRecorderAnchor((View) result)) {
                        if (GlassProbe.enabled()) GlassProbe.event("NATIVE_INFLATE_HOOK", (View) result, "");
                        conversation.onRecorderSignal((View) result);
                    }
                }
            });
            XposedBridge.hookMethod(TextView.class.getDeclaredMethod("setText",
                    CharSequence.class, TextView.BufferType.class), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (GlassPane.isCapturing()) return;
                    TextView view = (TextView) param.thisObject;
                    Session session = sessionFor(view.getRootView());
                    if (session != null) session.rowContentChanged(view);
                    View ancestor = view;
                    while (ancestor != null && !isRecorderAnchor(ancestor)) {
                        ancestor = ancestor.getParent() instanceof View ? (View) ancestor.getParent() : null;
                    }
                    if (ancestor == null) return;
                    CharSequence content = view.getText();
                    // A cheap lexical gate; the conversation adapter verifies the recorder
                    // ancestor and timer grammar without ever recording text in diagnostics.
                    if (content == null || content.length() < 4 || content.length() > 8
                            || (content.charAt(1) != ':' && content.charAt(2) != ':')) return;
                    if (GlassProbe.enabled()) GlassProbe.event("NATIVE_TIMER_CANDIDATE", view,
                            "length=" + content.length());
                    conversation.onRecorderSignal(view);
                }
            });
            report("voice-event-hooks", "recorder visibility, inflation and timer hooks installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            report("voice-event-hook-missing", "voice activation hooks unavailable: " + error);
        }
    }

    private void installTintObserver() {
        XC_MethodHook paletteChanged = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!GlassPane.isCapturing()) conversation.onComposerVisualChanged((View) param.thisObject);
            }
        };
        XposedBridge.hookAllMethods(ImageView.class, "setImageTintList", paletteChanged);
        XposedBridge.hookAllMethods(ImageView.class, "setColorFilter", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (GlassPane.isCapturing()) return;
                ImageView view = (ImageView) param.thisObject;
                // setColorFilter(int[, Mode]) carries the colour; setColorFilter(ColorFilter) does not.
                Object first = param.args.length > 0 ? param.args[0] : null;
                conversation.onImageColorFilterSet(view, first instanceof Integer ? (Integer) first : null);
                conversation.onComposerVisualChanged(view);
            }
        });
        XposedBridge.hookAllMethods(ImageView.class, "drawableStateChanged", paletteChanged);
        XposedBridge.hookAllMethods(TextView.class, "setTextColor", paletteChanged);
        XposedBridge.hookAllMethods(View.class, "setBackgroundTintList", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                View view = (View) param.thisObject;
                if (!GlassPane.isCapturing()) conversation.onBackgroundChanged(view, "tint");
                Session session = sessionFor(view.getRootView());
                if (session == null || session.installing) return;
                Binding binding = session.bindings.get(view);
                if (binding != null) binding.tintChanged = true;
            }
        });
        XposedBridge.hookAllMethods(View.class, "setBackgroundTintMode", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!GlassPane.isCapturing()) conversation.onBackgroundChanged((View) param.thisObject);
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

    private static boolean insideConversationHeader(View view) {
        for (View current = view; current != null; ) {
            if ("search_fragment_and_toolbar_holder".equals(resourceName(current))) return true;
            android.view.ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
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

    /** The native row must expose a Fyn background and a bounded main_layout child. The
     * text/voice routes are verified on device; other conversation_row_* types (including
     * photos and captions) use the same strict runtime checks before binding. */
    private static final class BubblePlacement {
        final boolean outgoing;
        final Rect region;
        BubblePlacement(boolean outgoing, Rect region) {
            this.outgoing = outgoing;
            this.region = region;
        }
    }

    private static BubblePlacement nativeRowPlacement(View row) {
        String name = resourceName(row);
        if (!name.startsWith("conversation_row_")
                || row.getWidth() <= 0 || row.getHeight() <= 0) return null;
        View content = findByName(row, "main_layout", row.getContext().getPackageName());
        if (content == null || !content.isShown() || content.getWidth() <= 0
                || content.getHeight() <= 0) return null;
        int[] rowLocation = new int[2], contentLocation = new int[2];
        row.getLocationOnScreen(rowLocation);
        content.getLocationOnScreen(contentLocation);
        int left = contentLocation[0] - rowLocation[0];
        int top = contentLocation[1] - rowLocation[1];
        int right = left + content.getWidth();
        int bottom = top + content.getHeight();
        float density = row.getResources().getDisplayMetrics().density;
        int leftGap = Math.max(0, left), rightGap = Math.max(0, row.getWidth() - right);
        // A centric/full-width container does not reveal message direction. Fail closed:
        // choosing the wrong side would violate the incoming/outgoing scope setting.
        if (Math.abs(leftGap - rightGap) < 12f * density) return null;
        int pad = Math.round(12f * density);
        Rect region = new Rect(Math.max(0, left - pad), Math.max(0, top - pad),
                Math.min(row.getWidth(), right + pad), Math.min(row.getHeight(), bottom + pad));
        return region.isEmpty() ? null : new BubblePlacement(leftGap > rightGap, region);
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
        private final WeakHashMap<View, WeakReference<GlassMaterialDrawable>> secondaryFactories = new WeakHashMap<>();
        private final WeakHashMap<View, Boolean> reportedRowOwners = new WeakHashMap<>();
        private final WeakHashMap<View, Surface> candidates = new WeakHashMap<>();
        private final WeakHashMap<TextView, BubbleTextState> bubbleTexts = new WeakHashMap<>();
        private final WeakHashMap<View, Boolean> pendingCanvasChecks = new WeakHashMap<>();

        /** A recycled row may stop drawing its factory bubble without changing its View.
         * The old canvas-owned binding then survives every scan, incorrectly retaining text
         * colours and GPU budget. Validate against the native draw itself, not elapsed time:
         * stationary rows legitimately draw infrequently. */
        void reconcileCanvasDraw(View row, GlassMaterialDrawable.DrawHost frame) {
            Binding binding = bindings.get(row);
            if (closed || suspended || binding == null || !binding.canvasOwned
                    || frame.saw(binding.glass) || pendingCanvasChecks.containsKey(row)) return;
            pendingCanvasChecks.put(row, Boolean.TRUE);
            // Do not modify the row background or traverse its children from inside draw().
            row.post(() -> {
                pendingCanvasChecks.remove(row);
                if (closed || suspended || !row.isAttachedToWindow()
                        || row.getRootView() != root.get() || bindings.get(row) != binding) return;
                releaseBudget(binding);
                binding.restore(row);
                restoreBubbleText(row);
                bindings.remove(row);
                GlassTrace.event(root.get(), binding.glass, row, "UNBOUND",
                        "canvas-factory-displaced", "role=BUBBLES row=" + resourceName(row));
                WeakReference<GlassMaterialDrawable> secondary = secondaryFactories.remove(row);
                GlassMaterialDrawable next = secondary == null ? null : secondary.get();
                // If WhatsApp now exposes the verified native row mask, make that the
                // persistent owner before considering the replacement factory. A reply
                // redraw must not promote another short-lived Canvas drawable first.
                bindNativeBubbleRow(row);
                if (next != null) {
                    next.setPixelSuppressed(false);
                    next.rearmCanvasHost();
                    if (frame.saw(next)) adopt(row, next);
                }
                Drawable nativeBackground = row.getBackground();
                if (!bindings.containsKey(row) && resourceName(row).startsWith("conversation_row_")
                        && nativeBackground != null
                        && "Fyn".equals(nativeBackground.getClass().getSimpleName())) {
                    pendingNativeBubbleRows.add(row);
                }
                row.invalidate();
            });
        }

        private void watchBubble(View row, Binding binding) {
            if (binding.surface != Surface.BUBBLES) return;
            WeakReference<View> rowRef = new WeakReference<>(row);
            binding.glass.observeCaptureDemand(() -> {
                View target = rowRef.get();
                if (closed || suspended || target == null || bindings.get(target) != binding) return;
                binding.presentation.rearm();
                binding.recovery.revalidate();
                scheduleFrame(1L);
            });
            binding.presentation.rearm();
            binding.recovery.revalidate();
            scheduleFrame(1L);
        }

        /** Native rebind signal, without inspecting message text, IDs or image bytes. */
        void rowContentChanged(View node) {
            if (closed || suspended || installing || GlassPane.isCapturing()) return;
            for (View row = node; row != null; row = row.getParent() instanceof View ? (View) row.getParent() : null) {
                Binding binding = bindings.get(row);
                if (binding != null && binding.surface == Surface.BUBBLES) {
                    if (!binding.contentDamagePending) {
                        binding.contentDamagePending = true;
                        binding.glass.invalidateCapture("rowRecycled");
                        discoverLater(row);
                    }
                    return;
                }
            }
        }

        private void promoteDeferred(View row) {
            WeakReference<GlassMaterialDrawable> ref = secondaryFactories.remove(row);
            GlassMaterialDrawable next = ref == null ? null : ref.get();
            if (next == null) return;
            next.setPixelSuppressed(false);
            next.rearmCanvasHost();
            Boolean side = bubbleDirections.get(next);
            if (row.isAttachedToWindow() && row.getRootView() == root.get() && side != null
                    && LiquidGlassSettings.bubbleEnabled(prefs, side) && next.isCanvasOwnedBy(row)) adopt(row, next);
            else next.attachMaterial(() -> null);
            row.invalidate();
            scheduleFrame(1L);
        }

        private final class BubbleTextState {
            ColorStateList nativeColors;
            int applied;
            WeakReference<View> bubbleOwner;
            BubbleTextState(ColorStateList colors, int applied, View owner) {
                nativeColors = colors; this.applied = applied;
                bubbleOwner = new WeakReference<>(owner);
            }
        }

        private void updateBubbleText(View bubble, Binding binding) {
            if (bubble == null || binding.outgoing == null) return;
            GlassSpec material = bubbleMaterial(binding.outgoing, resolvedMaterial());
            if (material == null) return;
            recolorBubbleText(bubble, bubble, material.contentColor, 0);
        }

        private void recolorBubbleText(View owner, View node, int color, int depth) {
            if (node == null || depth > 8) return;
            if (depth > 0 && node.getBackground() instanceof GlassMaterialDrawable) return;
            if (node instanceof TextView && !(node instanceof EditText)) {
                TextView text = (TextView) node;
                if (text.getText() != null && text.getText().length() > 0) {
                    BubbleTextState state = bubbleTexts.get(text);
                    if (state == null) {
                        state = new BubbleTextState(text.getTextColors(), color, owner);
                        bubbleTexts.put(text, state);
                    } else if (text.getCurrentTextColor() != state.applied) {
                        // WhatsApp recycled or restyled this TextView since our last pass.
                        state.nativeColors = text.getTextColors();
                    }
                    state.bubbleOwner = new WeakReference<>(owner);
                    state.applied = color;
                    if (text.getCurrentTextColor() != color) text.setTextColor(color);
                }
            }
            if (node instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) node;
                for (int i = 0; i < group.getChildCount(); i++) {
                    recolorBubbleText(owner, group.getChildAt(i), color, depth + 1);
                }
            }
        }

        private void restoreBubbleText(View node) {
            if (node == null) return;
            restoreBubbleText(node, 0);
        }

        private void restoreBubbleText(View node, int depth) {
            if (depth > 8) return;
            if (node instanceof TextView) {
                TextView text = (TextView) node;
                BubbleTextState state = bubbleTexts.remove(text);
                if (state != null && text.getCurrentTextColor() == state.applied) {
                    text.setTextColor(state.nativeColors);
                }
            }
            if (node instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) node;
                for (int i = 0; i < group.getChildCount(); i++) {
                    restoreBubbleText(group.getChildAt(i), depth + 1);
                }
            }
        }

        private void restoreAllBubbleText() {
            for (Map.Entry<TextView, BubbleTextState> entry : new ArrayList<>(bubbleTexts.entrySet())) {
                TextView text = entry.getKey();
                BubbleTextState state = entry.getValue();
                if (text != null && state != null && text.getCurrentTextColor() == state.applied) {
                    text.setTextColor(state.nativeColors);
                }
            }
            bubbleTexts.clear();
        }

        private void restoreDetachedBubbleText() {
            for (Map.Entry<TextView, BubbleTextState> entry : new ArrayList<>(bubbleTexts.entrySet())) {
                TextView text = entry.getKey();
                BubbleTextState state = entry.getValue();
                View owner = state == null ? null : state.bubbleOwner.get();
                if (text == null || state == null) continue;
                Binding binding = owner == null ? null : bindings.get(owner);
                if (binding != null && binding.outgoing != null
                        && (owner == text || isAncestorOf(owner, text))) continue;
                if (text.getCurrentTextColor() == state.applied) text.setTextColor(state.nativeColors);
                bubbleTexts.remove(text);
            }
        }
        /** Views that ignore setBackground (Material FABs); never retried in this window. */
        private final WeakHashMap<View, Boolean> refused = new WeakHashMap<>();
        private int bubbleRowsInspected;
        private final Set<View> pendingDiscovery = java.util.Collections.newSetFromMap(new WeakHashMap<>());
        /** Native Fyn rows can be discovered before their main_layout measures. Retain only
         * those incomplete rows for the next global layout, not the entire list tree. */
        private final Set<View> pendingNativeBubbleRows = java.util.Collections.newSetFromMap(new WeakHashMap<>());
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
                if (!((optics.corrected && optics.temporal) || optics.oneMate)
                        || !resolved.equals(cachedMaterial)) {
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
            // A changed bubble scope may need to adopt an existing nested factory drawable.
            // Rediscover once after leaving settings, never on every frame.
            discovered = false;
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
            scan(); // Geometry/source changes rearm their own surfaces; overlay layout is not motion.
            // Reconcile known candidates; no periodic full-tree discovery.
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
                home.frame(root.get());
                conversation.onFrame(root.get());
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
                    boolean mayCapture = binding.recovery.mayCapture(now)
                            && binding.presentation.mayCapture(now, binding.glass.hasPresentedLive());
                    if (eligible && !mayCapture) {
                        long delay = binding.recovery.retryDelay(now);
                        if (delay > 0 || binding.recovery.mayCapture(now)) {
                            if (binding.surface == Surface.BUBBLES && binding.glass.presentationPending())
                                delay = Math.max(delay, binding.presentation.retryDelay(now, false));
                            if (delay > 0) scheduleFrame(delay);
                        }
                    }
                    retryDue |= eligible && mayCapture && (binding.recovery.retryDue(now)
                            || binding.surface == Surface.BUBBLES && binding.glass.presentationPending());
                    fresh |= binding.glass.needsFreshCapture(eligible && mayCapture)
                            || eligible && mayCapture && binding.glass.capturePending();
                }
                GlassOptics optics = GlassOptics.current();
                // OneMate is the user-facing live renderer: it must always use sticky admissions
                // and damage-driven captures. It is not gated by the experimental classic switch.
                boolean temporal = (optics.corrected && optics.temporal) || optics.oneMate;
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
                    if (binding.viewportLeft != visibleRect.left || binding.viewportTop != visibleRect.top) {
                        binding.viewportLeft = visibleRect.left;
                        binding.viewportTop = visibleRect.top;
                        binding.presentation.rearm();
                    }
                    if (!binding.visible) {
                        binding.visible = true;
                        binding.presentation.rearm();
                        binding.recovery.revalidate();
                        binding.glass.releaseLive();
                    }
                    // Chrome drawables keep priority. For a conversation's many recyclable
                    // bubbles, admit from the visible bottom upward: otherwise old rows at
                    // the top spend the entire ledger and the messages next to the composer
                    // remain BUDGET_REFUSED (confirmed by PR #104 device trace).
                    long top = binding.rowBased
                            ? (1L << 19) + Math.max(0L, Math.min((1L << 19) - 1,
                                    view.getHeight() - visibleRect.bottom))
                            : Math.max(0L, Math.min((1L << 19) - 1, visibleRect.top));
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
                    int captureWidth = binding.rowBased && binding.glass.captureRegionWidth() > 0
                            ? binding.glass.captureRegionWidth() : target.getWidth();
                    int captureHeight = binding.rowBased && binding.glass.captureRegionHeight() > 0
                            ? binding.glass.captureRegionHeight() : target.getHeight();
                    long area = temporal && material != null
                            ? LiveBudget.cost(captureWidth, captureHeight, material, density, optics)
                            : (long) captureWidth * captureHeight;
                    if (binding.width != target.getWidth() || binding.height != target.getHeight()) {
                        binding.width = target.getWidth(); binding.height = target.getHeight();
                        binding.recovery.revalidate();
                        binding.glass.releaseLive();
                    }
                    if (!binding.recovery.mayCapture(now)) continue;
                    if (binding.surface == Surface.BUBBLES
                            && !binding.presentation.mayCapture(now, binding.glass.hasPresentedLive())) {
                        if (ledger != null) ledger.releaseDrawable(binding.glass);
                        continue;
                    }
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
                        binding.glass.captureAttempted();
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
                        // Publish the first texture with at most three row invalidations.
                        // An unpresentable route yields its budget and retries with backoff.
                        if (invalidateNow || binding.surface == Surface.BUBBLES
                                && binding.presentation.published(now, binding.glass.hasPresentedLive())) {
                            target.invalidate();
                            com.waenhancer.theme.BubbleLifecycleProbe.event("target-invalidate", target, binding.glass,
                                    "revision=" + binding.glass.damageRevision() + " " + binding.glass.captureStatus());
                        }
                        if (binding.surface == Surface.BUBBLES && !binding.glass.hasPresentedLive())
                            scheduleFrame(binding.presentation.retryDelay(now, false));
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
                    binding.contentDamagePending = false;
                    if (target.getRootView() != view) {
                        releaseBudget(binding);
                        binding.restore(target);
                        restoreBubbleText(target);
                        bindings.remove(target);
                    } else if (!LiquidGlassSettings.isEnabled(prefs, binding.surface)
                            || (binding.outgoing != null && !binding.rowBased
                                && !LiquidGlassSettings.bubbleEnabled(prefs, binding.outgoing))
                            || !target.isAttachedToWindow()
                            || conversation.owns(target)) {
                        releaseBudget(binding);
                        binding.restore(target);
                        restoreBubbleText(target);
                        bindings.remove(target);
                    } else if (binding.canvasOwned) {
                        if (!binding.glass.isCanvasOwnedBy(target)) {
                            releaseBudget(binding);
                            binding.restore(target);
                            restoreBubbleText(target);
                            bindings.remove(target);
                        }
                    } else if (binding.rowBased) {
                        BubblePlacement location = nativeRowPlacement(target);
                        Drawable mounted = target.getBackground();
                        boolean nativeMaskRebound = mounted != binding.glass && mounted != null
                                && "Fyn".equals(mounted.getClass().getSimpleName());
                        boolean stillOwned = location != null
                                && LiquidGlassSettings.bubbleEnabled(prefs, location.outgoing)
                                && (mounted == binding.glass
                                    || nativeMaskRebound && keepGlass(target, binding));
                        if (!stillOwned) {
                            releaseBudget(binding);
                            binding.restore(target);
                            restoreBubbleText(target);
                            bindings.remove(target);
                            if (target.isAttachedToWindow()) bindNativeBubbleRow(target);
                            promoteDeferred(target);
                        } else {
                            if (nativeMaskRebound) GlassTrace.event(root.get(), binding.glass, target,
                                    "RETAINED", "native-mask-rebound", "role=BUBBLES row=" + resourceName(target));
                            if (binding.outgoing == null || binding.outgoing != location.outgoing) {
                                restoreBubbleText(target);
                                binding.outgoing = location.outgoing;
                                final boolean side = location.outgoing;
                                binding.glass.attachMaterial(() -> bubbleMaterial(side, resolvedMaterial()));
                            }
                            binding.glass.setMaterialRegion(location.region);
                            updateBubbleText(target, binding);
                        }
                    } else if (binding.nested) {
                        // The wrapper is a child of WhatsApp's native background, not the view's
                        // background itself. Never replace the whole selector with the child.
                        GlassMaterialDrawable mounted = bubbleOn(target);
                        GlassMaterialDrawable replacement = mounted != null ? mounted : bubbleFor(target);
                        boolean stillOwned = mounted == binding.glass
                                || (mounted == null && binding.callbackOwned
                                    && binding.glass.callbackOwner() == target
                                    && replacement == binding.glass);
                        if (!stillOwned) {
                            releaseBudget(binding);
                            binding.restore(target);
                            restoreBubbleText(target);
                            bindings.remove(target);
                            // The previous binding blocked walk() earlier in this scan. Adopt
                            // WhatsApp's replacement now, instead of waiting for another tree
                            // discovery event after a recycled row changes its drawable.
                            if (replacement != null && replacement != binding.glass
                                    && bubbleDirections.containsKey(replacement)) {
                                adopt(target, replacement);
                            }
                        } else updateBubbleText(target, binding);
                    } else if (binding.outgoing != null && target.getBackground() != binding.glass) {
                        releaseBudget(binding);
                        binding.restore(target);
                        restoreBubbleText(target);
                        bindings.remove(target);
                        GlassMaterialDrawable next = bubbleFor(target);
                        if (next != null && bubbleDirections.containsKey(next)) adopt(target, next);
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
                        restoreBubbleText(target);
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
                // A row first walked at 0x0 has no bubble placement yet. Layout reconciliation
                // must retry it even though the one-time discovery flag is now true.
                for (View row : new ArrayList<>(pendingNativeBubbleRows)) {
                    if (row == null || !row.isAttachedToWindow() || row.getRootView() != view
                            || !LiquidGlassSettings.isEnabled(prefs, Surface.BUBBLES)) {
                        pendingNativeBubbleRows.remove(row);
                        continue;
                    }
                    if (!bindings.containsKey(row)) bindNativeBubbleRow(row);
                    View content = findByName(row, "main_layout", row.getContext().getPackageName());
                    if (bindings.containsKey(row) || (row.getWidth() > 0 && row.getHeight() > 0
                            && content != null && content.getWidth() > 0 && content.getHeight() > 0)) {
                        pendingNativeBubbleRows.remove(row);
                    }
                }
                for (Map.Entry<View, Binding> entry : bindings.entrySet()) {
                    if (entry.getValue().outgoing != null) updateBubbleText(entry.getKey(), entry.getValue());
                }
                for (View row : new ArrayList<>(secondaryFactories.keySet()))
                    if (row != null && !bindings.containsKey(row)) promoteDeferred(row);
                restoreDetachedBubbleText();
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
            long probeStart = GlassProbe.enabled() ? SystemClock.uptimeMillis() : 0L;
            if (probeStart != 0L) GlassProbe.event("DISCOVERY_START", root.get(),
                    "pending=" + pendingDiscovery.size() + " bindings=" + bindings.size());
            for (View candidate : new ArrayList<>(pendingDiscovery)) {
                if (candidate != null && candidate.isAttachedToWindow() && candidate.getRootView() == root.get()) walk(candidate, 0);
            }
            pendingDiscovery.clear();
            scan();
            if (probeStart != 0L) GlassProbe.event("DISCOVERY_END", root.get(),
                    "durationMs=" + (SystemClock.uptimeMillis() - probeStart)
                            + " bindings=" + bindings.size());
        }


        private void walk(View view, int depth) {
            if (depth > 24 || view == null || GlassSurface.isGlassHost(view) || view instanceof GlassPane) return;
            if (bubbleRowsInspected < 3 && view.getId() != View.NO_ID
                    && LiquidGlassSettings.isEnabled(prefs, Surface.BUBBLES)
                    && resourceName(view).startsWith("conversation_row_")) {
                int index = bubbleRowsInspected++;
                report("bubble-row-structure-" + index, "bubble row routing=" + describe(view)
                        + " descendants=" + bubbleStructure(view, 0));
            }
            GlassMaterialDrawable bubble = bubbleFor(view);
            if (bubble != null && bubbleDirections.containsKey(bubble) && !bindings.containsKey(view)) {
                Boolean outgoing = bubbleDirections.get(bubble);
                if (outgoing != null && LiquidGlassSettings.bubbleEnabled(prefs, outgoing)) {
                    adopt(view, bubble);
                } else {
                    bubble.attachMaterial(() -> null);
                }
            }
            if (bubble == null && !bindings.containsKey(view)) {
                String rowName = resourceName(view);
                Drawable rowBackground = view.getBackground();
                if (rowName.startsWith("conversation_row_")
                        && rowBackground != null
                        && "Fyn".equals(rowBackground.getClass().getSimpleName())
                        && LiquidGlassSettings.isEnabled(prefs, Surface.BUBBLES)) {
                    View content = findByName(view, "main_layout", view.getContext().getPackageName());
                    if (view.getWidth() <= 0 || view.getHeight() <= 0
                            || content == null || content.getWidth() <= 0 || content.getHeight() <= 0) {
                        pendingNativeBubbleRows.add(view);
                    }
                }
                bindNativeBubbleRow(view);
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

        /** The factory drawable is Canvas-owned on this build (no callback View), but the
         * native row background itself is a drawable and retains the bubble mask. Only bind
         * when the Fyn mask, asymmetric bounded main_layout and direction all agree. */
        private void bindNativeBubbleRow(View row) {
            if (!LiquidGlassSettings.isEnabled(prefs, Surface.BUBBLES) || bindings.containsKey(row)) return;
            BubblePlacement placement = nativeRowPlacement(row);
            if (placement == null || !LiquidGlassSettings.bubbleEnabled(prefs, placement.outgoing)) return;
            Drawable original = row.getBackground();
            if (original == null || original instanceof GlassMaterialDrawable
                    || !"Fyn".equals(original.getClass().getSimpleName())) return;
            observeNativeRowDraw(row);
            bind(row, Surface.BUBBLES, true);
            Binding binding = bindings.get(row);
            if (binding == null) return;
            binding.rowBased = true;
            watchBubble(row, binding);
            binding.outgoing = placement.outgoing;
            binding.glass.setMaterialRegion(placement.region);
            final boolean side = placement.outgoing;
            binding.glass.attachMaterial(() -> bubbleMaterial(side, resolvedMaterial()));
            updateBubbleText(row, binding);
            report("bubble-attached-row-" + (side ? "out" : "in"),
                    "native WhatsApp bubble mask adopted on " + resourceName(row)
                            + " side=" + (side ? "outgoing" : "incoming")
                            + " crop=" + placement.region.toShortString());
        }

        /** Bounded diagnostics for an unknown WhatsApp row: IDs/classes only, never text/media. */
        private String bubbleStructure(View view, int depth) {
            if (view == null || depth > 3) return "";
            StringBuilder out = new StringBuilder();
            out.append('[').append(resourceName(view)).append(':')
                    .append(view.getClass().getSimpleName());
            Drawable background = view.getBackground();
            if (background != null) out.append(" bg=").append(background.getClass().getSimpleName());
            out.append(']');
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount() && i < 8; i++) {
                    out.append(bubbleStructure(group.getChildAt(i), depth + 1));
                }
            }
            return out.toString();
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
            Boolean outgoing = bubbleDirections.get(glass);
            if (outgoing == null) return;
            boolean callbackOwned = bubbleOn(view) != glass && glass.callbackOwner() == view;
            boolean canvasOwned = glass.isCanvasOwnedBy(view);
            if (bubbleOn(view) != glass && !callbackOwned && !canvasOwned) return;
            Binding previous = bindings.get(view);
            if (previous != null) {
                if (previous.glass == glass) return;
                // On this WhatsApp build the Fyn row background and a canvas-drawn native
                // bubble factory can both be painted in one frame. The direct row glass is
                // already a valid live owner; adopting the late factory evicts it and leaves
                // the row opaque until a later capture, especially during quoted replies.
                // Keep the factory's own draw transparent while this verified native mask
                // remains mounted. If WhatsApp removes that mask, scan() releases the row
                // owner and re-arms the deferred factory's host callback for adoption.
                if (previous.rowBased && canvasOwned) {
                    Drawable mounted = view.getBackground();
                    BubblePlacement placement = nativeRowPlacement(view);
                    boolean nativeMask = mounted != null
                            && "Fyn".equals(mounted.getClass().getSimpleName());
                    boolean retained = placement != null
                            && LiquidGlassSettings.bubbleEnabled(prefs, placement.outgoing)
                            && (mounted == previous.glass || nativeMask && keepGlass(view, previous));
                    if (retained) {
                        if (previous.outgoing == null || previous.outgoing != placement.outgoing) {
                            restoreBubbleText(view);
                            previous.outgoing = placement.outgoing;
                            final boolean side = placement.outgoing;
                            previous.glass.attachMaterial(() -> bubbleMaterial(side, resolvedMaterial()));
                        }
                        previous.glass.setMaterialRegion(placement.region);
                        secondaryFactories.put(view, new WeakReference<>(glass));
                        glass.setPixelSuppressed(true);
                        glass.attachMaterial(() -> bubbleMaterial(outgoing, resolvedMaterial()));
                        if (!reportedRowOwners.containsKey(view)) {
                            reportedRowOwners.put(view, Boolean.TRUE);
                            GlassTrace.event(root.get(), previous.glass, view, "RETAINED",
                                    "row-owner-preferred", "role=BUBBLES factory=" + GlassTrace.id(glass));
                        }
                        return;
                    }
                }
                // One capture owner per native row. A quote factory drawn beside the bubble
                // cannot evict/rearm it on every draw. Promote only after native displacement.
                if (previous.canvasOwned && canvasOwned) {
                    secondaryFactories.put(view, new WeakReference<>(glass));
                    callbackBubbles.put(view, previous.glass);
                    glass.setPixelSuppressed(true);
                    glass.attachMaterial(() -> bubbleMaterial(outgoing, resolvedMaterial()));
                    return;
                }
                releaseBudget(previous);
                previous.restore(view);
                restoreBubbleText(view);
                bindings.remove(view);
            }
            boolean nested = view.getBackground() != glass;
            Binding binding = new Binding(view, nested ? view.getBackground() : glass.original(), Surface.BUBBLES, true);
            binding.glass = glass;
            binding.nested = nested;
            binding.callbackOwned = callbackOwned;
            binding.canvasOwned = canvasOwned;
            binding.outgoing = outgoing;
            bindings.put(view, binding);
            glass.setPixelSuppressed(false);
            watchBubble(view, binding);
            if (canvasOwned || callbackOwned) callbackBubbles.put(view, glass);
            if (canvasOwned && resourceName(view).startsWith("conversation_row_")) {
                observeNativeRowDraw(view);
            }
            glass.attachOwner(view);
            glass.attachMaterial(() -> bubbleMaterial(outgoing, resolvedMaterial()));
            view.invalidate();
            if (!nested && view.getBackgroundTintList() != null) view.setBackgroundTintList(null);
            updateBubbleText(view, binding);
            GlassTrace.event(root.get(), glass, view, "BOUND", "native-draw-owner",
                    "role=BUBBLES canvasOwned=" + canvasOwned + " nested=" + nested);
            report("bubble-attached-" + (outgoing ? "out" : "in") + (nested ? "-nested" : "-direct"),
                    "bubble-attached host=" + (nested ? "nested" : "direct") + " side="
                    + (outgoing ? "outgoing" : "incoming")
                    + " callbackOwner=" + callbackOwned + " " + describe(view));
        }

        /** A binding going away gives its slot back at once rather than when the GC notices. */
        private void releaseBudget(Binding binding) {
            View view = root.get();
            if (view != null && binding.glass != null) LiveBudget.forWindow(view).releaseDrawable(binding.glass);
        }

        void bind(View view, Surface surface, boolean nativeMask) {
            if (GlassProbe.enabled() && GlassProbe.composerNode(view)) {
                GlassProbe.event("BIND_ATTEMPT", view,
                        "surface=" + surface + " paneOwns=" + conversation.owns(view)
                                + " alreadyBound=" + bindings.containsKey(view));
            }
            if (view == null || bindings.containsKey(view) || refused.containsKey(view)
                    || view.getWidth() < 1 || view.getHeight() < 1
                    || view == root.get() || GlassSurface.isGlassHost(view) || view instanceof GlassPane
                    || conversation.owns(view)) return;
            // The home-header switch must not colour the conversation toolbar when its own
            // separate glass capsule is off. The latter owns this entire header subtree.
            if (surface == Surface.TOOLBARS && insideConversationHeader(view)) return;
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
                if (GlassProbe.enabled() && GlassProbe.composerNode(view)) {
                    GlassProbe.event("BIND_COMMIT", view,
                            "surface=" + surface + " assigned=" + (view.getBackground() == binding.glass)
                                    + " original=" + GlassProbe.drawable(binding.original));
                }
                if (binding.original != null) binding.original.setCallback(binding.glass);
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
                restoreBubbleText(target);
            }
            for (WeakReference<GlassMaterialDrawable> ref : secondaryFactories.values()) {
                GlassMaterialDrawable secondary = ref.get();
                if (secondary != null) { secondary.setPixelSuppressed(false); secondary.attachMaterial(() -> null); secondary.rearmCanvasHost(); }
            }
            secondaryFactories.clear(); reportedRowOwners.clear();
            bindings.clear(); candidates.clear(); pendingDiscovery.clear(); pendingNativeBubbleRows.clear();
            restoreAllBubbleText();
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
        int viewportLeft = Integer.MIN_VALUE, viewportTop = Integer.MIN_VALUE;
        boolean visible;
        Boolean outgoing;
        boolean nested;
        boolean callbackOwned;
        boolean rowBased;
        boolean canvasOwned;
        boolean contentDamagePending;
        final com.waenhancer.theme.PresentationRetry presentation = new com.waenhancer.theme.PresentationRetry();
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
            glass.observeCaptureDemand(null);
            glass.releaseLive();
            if (nested || canvasOwned) {
                // The native container still references this factory wrapper; make it paint its
                // original child until WhatsApp replaces the whole background.
                glass.attachMaterial(() -> null);
                if (canvasOwned) glass.rearmCanvasHost();
                return;
            }
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
