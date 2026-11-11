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
    /** Glass-row content views whose colour is held: view -> owning session (UI thread). */
    private final WeakHashMap<View, Session> contrastOwners = new WeakHashMap<>();
    /** Set while this module writes those colours, so its own setter calls are not answered. */
    private boolean applyingContrast;
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
        refreshBubbleScope();
        // Queue the last verified wallpaper preview before registering the heavier hooks:
        // an Activity can start rendering rows while the cache is still loading.
        prepareBubbleDiskCache();
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
            } else if (state == WppCore.ActivityChangeState.ChangeType.STARTED
                    && "Conversation".equals(activity.getClass().getSimpleName())) {
                // STARTED precedes RESUMED and the first frame. Attach the wallpaper
                // observer now, so the first global layout can queue its isolated
                // texture while WhatsApp is still setting up the conversation.
                // Do not substitute a previous chat's unverified wallpaper.
                reloadPrefs();
                refreshBubbleScope();
                refreshBubblePalette();
                hookPendingBubbleClasses();
                publishOptics();
                Session session = watch(decor);
                if (session != null) {
                    session.activityWindow = new WeakReference<>(activity.getWindow());
                    session.resume();
                    session.syncSharedBubbles();
                    com.waenhancer.theme.ConversationBackdropSource.probe(
                            "BUBBLE_EARLY_PREWARM", "stage=started");
                }
            } else if (state == WppCore.ActivityChangeState.ChangeType.RESUMED) {
                conversation.setActivityWindow(decor, activity.getWindow());
                reloadPrefs();
                refreshBubbleScope();
                refreshBubblePalette();
                hookPendingBubbleClasses();
                publishOptics();
                Session session = watch(decor);
                foreground = new WeakReference<>(session);
                if (session != null) {
                    session.activityWindow = new WeakReference<>(activity.getWindow());
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
        installContrastGuards();
        installSharedBubbleFactoryHook();
        installSharedBubbleDateFactoryHook();
        observeRowsEarly();
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
            com.waenhancer.theme.KyantLens.publishBubbleColourAmount(LiquidGlassSettings.bubbleColourAmount(prefs));
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
    /**
     * IDs of verified conversation rows (conversation_row_*). A row inflated after the last
     * discovery is hosted from its first draw instead of painting natively until the posted
     * discovery binds it (a visible blink, and a native row if no discovery followed).
     */
    private volatile int[] nativeRowIds = new int[0];

    /**
     * Message lists that hold a verified conversation_row_*: every direct child of such a list
     * is a message row. View-once and call rows use other IDs (device trace 2026-11-03:
     * registered incoming bubbles drawn on 1440x222 row canvases with frame=none) and stayed
     * native. Identity only; weak.
     */
    private final Map<Object, Boolean> rowLists =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private boolean messageRow(View view) {
        return knownRowId(view.getId()) || (view.getParent() != null && rowLists.containsKey(view.getParent()));
    }

    private boolean knownRowId(int id) {
        if (id == View.NO_ID) return false;
        for (int known : nativeRowIds) if (known == id) return true;
        return false;
    }

    private void rememberRowId(int id) {
        if (id == View.NO_ID || knownRowId(id)) return;
        synchronized (this) {
            int[] next = java.util.Arrays.copyOf(nativeRowIds, nativeRowIds.length + 1);
            next[next.length - 1] = id;
            nativeRowIds = next;
        }
    }

    /** Hook only implementations reached by a verified Fyn conversation row. Shared base
     * methods are filtered by identity, and nested scopes are restored even on native errors. */
    private void observeNativeRowDraw(View row) {
        nativeDrawRows.put(row, Boolean.TRUE);
        rememberRowId(row.getId());
        if (row.getParent() != null && knownRowId(row.getId())) rowLists.put(row.getParent(), Boolean.TRUE);
        for (String name : new String[]{"draw", "onDraw", "dispatchDraw"}) {
            for (Class<?> type = row.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    Method method = type.getDeclaredMethod(name, Canvas.class);
                    hookRowDraw(method);
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

    /**
     * Last blurred wallpaper, quarter resolution, in WhatsApp's own cache directory (OS-clearable,
     * excluded from backups): shown at once by the first chat after a restart (owner choice
     * 2026-11-03, option 2). Deleted when bubble glass is off.
     */
    private void prepareBubbleDiskCache() {
        try {
            android.content.Context app = com.waenhancer.xposed.utils.Utils.getApplication();
            if (app == null) return;
            if (bubbleScopeCache == LiquidGlassSettings.BubbleScope.OFF)
                com.waenhancer.theme.ConversationBackdropSource.clearDisk(app.getCacheDir());
            else if (com.waenhancer.theme.NativeBubbleGlass.supported())
                com.waenhancer.theme.ConversationBackdropSource.preloadDisk(app.getCacheDir());
        } catch (Throwable error) {
            report("bubble-disk-cache", "blurred wallpaper cache unavailable: " + error);
        }
    }

    /** Row resource names whose IDs are known before any discovery (cold start). */
    static final String[] KNOWN_ROW_NAMES = {
            "conversation_row_text", "conversation_row_voice_note", "conversation_row_image",
            "conversation_row_sticker", "conversation_row_video", "conversation_row_document",
            "conversation_row_audio", "conversation_row_gif", "conversation_row_location",
            "conversation_row_contact"};

    /**
     * Cold start: the first chat after WhatsApp starts drew its first frames before discovery had
     * hooked any row draw, so bubbles painted natively for a moment (owner report 2026-11-03).
     * View.draw is hooked up front and known row IDs are resolved by name; the hook filters by
     * ID exactly as after discovery. Rows that override draw() are still hooked at discovery.
     */
    private void observeRowsEarly() {
        if (bubbleScopeCache == LiquidGlassSettings.BubbleScope.OFF
                || !com.waenhancer.theme.NativeBubbleGlass.supported()) return;
        try {
            android.content.Context app = com.waenhancer.xposed.utils.Utils.getApplication();
            if (app == null) return;
            for (String name : KNOWN_ROW_NAMES) {
                int id = app.getResources().getIdentifier(name, "id", app.getPackageName());
                if (id != 0) rememberRowId(id);
            }
            hookRowDraw(View.class.getDeclaredMethod("draw", Canvas.class));
        } catch (Throwable error) {
            report("bubble-draw-hook", "early row draw observation unavailable: " + error);
        }
    }

    private void hookRowDraw(Method method) {
        if (!rowDrawHooks.add(method)) return;
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                View host = (View) param.thisObject;
                // A pane capture runs in pre-draw, before the real frame, and makes dirty
                // rows re-record their own RenderNode inside it. That recording is the
                // one the real frame reuses, so only a draw straight into the capture
                // canvas is skipped.
                if (GlassPane.isCaptureCanvas((Canvas) param.args[0])) return;
                if (!nativeDrawRows.containsKey(host)) {
                    // Same row class and ID as a verified row: host it now. The
                    // bubble hook still needs this window's session and renderer.
                    if (!messageRow(host) || bubbleScopeCache == LiquidGlassSettings.BubbleScope.OFF) return;
                    nativeDrawRows.put(host, Boolean.TRUE);
                    // Discovery skips rows registered here, so the list is learned here too.
                    if (knownRowId(host.getId()) && host.getParent() != null) rowLists.put(host.getParent(), Boolean.TRUE);
                }
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
    }

    /**
     * Native bubble drawables returned by WhatsApp's direction factory, with their side. The
     * factory result is never wrapped or replaced: WhatsApp keeps its own type, bounds, tail and
     * recycling. Glass is painted from inside the drawable's own draw call instead.
     */
    /** Raw factory direction per drawable (3 = outgoing, as in BubbleColors). */
    private final Map<Drawable, Integer> bubbleSides =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());
    /**
     * Side per factory ConstantState. The factory runs when a chat opens; rows bound later while
     * scrolling can receive copies of a cached bubble (same ConstantState, new instance) that never
     * pass through the factory. Device trace 2026-11-03: not_registered grew with scrolling while
     * the affected bubbles stayed native (transparent/semi-opaque) with no LOST event.
     */
    private final Map<Drawable.ConstantState, Integer> bubbleStateSides =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());
    /** Detached sticker/emoji timestamp backgrounds use a different native factory. */
    private final Map<Drawable, Integer> dateSides =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Drawable.ConstantState, Integer> dateStateSides =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private int dateWrapperId;
    private int viewOnceLargeId, viewOnceSmallId;
    private boolean specialViewIdsResolved;

    private void resolveSpecialViewIds(View view) {
        if (specialViewIdsResolved) return;
        String pkg = view.getContext().getPackageName();
        dateWrapperId = view.getResources().getIdentifier("date_wrapper", "id", pkg);
        viewOnceLargeId = view.getResources().getIdentifier("view_once_media_container_large", "id", pkg);
        viewOnceSmallId = view.getResources().getIdentifier("view_once_media_container", "id", pkg);
        specialViewIdsResolved = true;
    }
    private final Set<Method> bubbleDrawHooks = new HashSet<>();
    /** Drawable classes seen while the switch was off; hooked once it is turned on. */
    private final Set<Class<?>> pendingBubbleClasses = new HashSet<>();
    private boolean sharedFactoryAvailable;

    private void installSharedBubbleFactoryHook() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        try {
            Method method=Unobfuscator.loadBubbleDrawableMethod(classLoader);
            // Same factory and direction convention as BubbleColors (first int arg, 3 = outgoing).
            Class<?>[] params=method.getParameterTypes();
            if (!Drawable.class.isAssignableFrom(method.getReturnType())
                    || params.length==0 || params[0]!=int.class) {
                report("shared-bubble-factory","bubble factory signature not verified; factory left native");
                return;
            }
            XposedBridge.hookMethod(method,new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object result=param.getResult();
                    if (!(result instanceof Drawable) || param.args.length==0
                            || !(param.args[0] instanceof Integer)) return;
                    // Identity bookkeeping only: the result WhatsApp receives is unchanged.
                    bubbleSides.put((Drawable) result, (Integer) param.args[0]);
                    Drawable.ConstantState state=((Drawable) result).getConstantState();
                    if (state!=null) bubbleStateSides.put(state, (Integer) param.args[0]);
                    com.waenhancer.theme.BubbleGlassTelemetry.factory((Drawable) result, param.args);
                    Class<?> type=result.getClass();
                    if (bubbleScopeCache != LiquidGlassSettings.BubbleScope.OFF) hookBubbleDraw(type);
                    else synchronized (pendingBubbleClasses) { pendingBubbleClasses.add(type); }
                }
            });
            sharedFactoryAvailable=true;
        } catch (Throwable error) {
            report("shared-bubble-factory","native bubble factory not observable: " + error);
        }
    }

    /** Observe the existing timestamp-background factory without replacing its result. */
    private void installSharedBubbleDateFactoryHook() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        try {
            Method method = Unobfuscator.loadBallonDateDrawable(classLoader);
            Class<?>[] parameters = method.getParameterTypes();
            if (!Drawable.class.isAssignableFrom(method.getReturnType())
                    || parameters.length == 0 || parameters[0] != int.class) {
                report("shared-date-factory", "timestamp background factory signature not verified");
                return;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.getResult() instanceof Drawable) || !(param.args[0] instanceof Integer)) return;
                    Drawable drawable = (Drawable) param.getResult();
                    int direction = (Integer) param.args[0];
                    dateSides.put(drawable, direction);
                    Drawable.ConstantState state = drawable.getConstantState();
                    if (state != null) {
                        // A ConstantState shared by both directions cannot identify a copy.
                        Integer previous = dateStateSides.get(state);
                        dateStateSides.put(state, previous == null ? direction
                                : previous == direction ? direction : -1);
                    }
                    if (bubbleScopeCache != LiquidGlassSettings.BubbleScope.OFF) hookBubbleDraw(drawable.getClass());
                    else synchronized (pendingBubbleClasses) { pendingBubbleClasses.add(drawable.getClass()); }
                }
            });
        } catch (Throwable error) {
            report("shared-date-factory", "timestamp background factory unavailable; date backgrounds remain native: " + error);
        }
    }

    /** Only native date_wrapper backgrounds within message rows, never arbitrary nine-patches. */
    private View detachedDateOwner(Drawable drawable) {
        if (!(drawable.getCallback() instanceof View)) return null;
        View owner = (View) drawable.getCallback();
        resolveSpecialViewIds(owner);
        if (dateWrapperId == 0 || owner.getId() != dateWrapperId || owner.getBackground() != drawable) return null;
        for (View ancestor = owner.getParent() instanceof View ? (View) owner.getParent() : null;
                ancestor != null; ancestor = ancestor.getParent() instanceof View ? (View) ancestor.getParent() : null) {
            if (messageRow(ancestor)) return owner;
        }
        return null;
    }

    /** The native timestamp drawable is drawn directly in its own view's HWUI recording. */
    private boolean drawDetachedDate(Drawable drawable, Canvas canvas) {
        View owner = detachedDateOwner(drawable);
        if (owner == null) return false;
        Integer direction = dateSides.get(drawable);
        if (direction == null && drawable.getConstantState() != null) {
            direction = dateStateSides.get(drawable.getConstantState());
            if (direction != null && direction >= 0) dateSides.put(drawable, direction);
        }
        Session session = sessionFor(owner.getRootView());
        if (session != null) session.trackSpecialBubbleHost(owner);
        if (direction == null || direction < 0 || !sharedFactoryWanted(direction == 3) || session == null) return false;
        GlassMaterialDrawable.DrawHost previous = GlassMaterialDrawable.enterRowDraw(owner, canvas);
        try {
            boolean drawn = session.drawBubble(drawable, canvas, direction == 3);
            if (drawn) com.waenhancer.diagnostics.InspectorTrace.glassEventOnce(
                    "detached-date-glass", "host=date_wrapper direction=" + direction);
            return drawn;
        } finally {
            GlassMaterialDrawable.leaveRowDraw(previous);
        }
    }

    /** View-once backgrounds paint in their own child RenderNode, outside row-draw scopes. */
    private View viewOnceBackgroundOwner(Drawable drawable) {
        if (!(drawable instanceof android.graphics.drawable.NinePatchDrawable)
                || !(drawable.getCallback() instanceof View)) return null;
        View owner = (View) drawable.getCallback();
        resolveSpecialViewIds(owner);
        if (owner.getId() == 0 || owner.getId() == View.NO_ID
                || (owner.getId() != viewOnceLargeId && owner.getId() != viewOnceSmallId)
                || (owner.getBackground() != drawable && owner.getForeground() != drawable)) return null;
        return owner;
    }

    /** The view-once pill is a separate nine-patch, not returned by the normal bubble factory. */
    private boolean drawViewOnceBackground(Drawable drawable, Canvas canvas) {
        View owner = viewOnceBackgroundOwner(drawable);
        if (owner == null) return false;
        // No direction is supplied by the view-once factory. Derive it only when its native
        // main_layout is clearly anchored to one side of its conversation row.
        View main = owner;
        while (main.getParent() instanceof View && !"main_layout".equals(resourceName(main)))
            main = (View) main.getParent();
        if (!"main_layout".equals(resourceName(main)) || !(main.getParent() instanceof View)) return false;
        View row = (View) main.getParent();
        if (!messageRow(row) || row.getWidth() <= 0) return false;
        int leftGap = main.getLeft(), rightGap = row.getWidth() - main.getRight();
        if (Math.abs(leftGap - rightGap) < row.getWidth() / 4) return false;
        boolean outgoing = (leftGap > rightGap) != (row.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
        Session session = sessionFor(owner.getRootView());
        if (session == null) return false;
        session.trackSpecialBubbleHost(owner);
        if (!sharedFactoryWanted(outgoing)) return false;
        // This view records its own display list. Establish a host only after its native
        // callback, resource ID, background identity and message-row placement all agree.
        GlassMaterialDrawable.DrawHost previous = GlassMaterialDrawable.enterRowDraw(owner, canvas);
        try {
            boolean drawn = session.drawBubble(drawable, canvas, outgoing);
            if (drawn) com.waenhancer.diagnostics.InspectorTrace.glassEventOnce(
                    "view-once-glass", "host=" + resourceName(owner) + " outgoing=" + outgoing);
            return drawn;
        } finally {
            GlassMaterialDrawable.leaveRowDraw(previous);
        }
    }

    private void hookPendingBubbleClasses() {
        if (!LiquidGlassSettings.isEnabled(prefs, Surface.BUBBLES)) return;
        ArrayList<Class<?>> types;
        synchronized (pendingBubbleClasses) { types=new ArrayList<>(pendingBubbleClasses); pendingBubbleClasses.clear(); }
        for (Class<?> type : types) hookBubbleDraw(type);
    }

    /** Hooks the concrete draw(Canvas) the native bubble class actually runs. */
    private void hookBubbleDraw(Class<?> type) {
        for (Class<?> t=type; t!=null && t!=Drawable.class && t!=Object.class; t=t.getSuperclass()) {
            Method draw;
            try { draw=t.getDeclaredMethod("draw", Canvas.class); }
            catch (NoSuchMethodException inherited) { continue; }
            if (java.lang.reflect.Modifier.isAbstract(draw.getModifiers())) return;
            synchronized (bubbleDrawHooks) { if (!bubbleDrawHooks.add(draw)) return; }
            try {
                XposedBridge.hookMethod(draw, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        drawNativeBubble(param);
                    }
                });
                // A framework class (e.g. a nine-patch) puts every such draw in WhatsApp through
                // this hook; FREQUENCY telemetry reports calls versus registered bubbles.
                report("shared-bubble-draw", "bubble glass drawn inside " + t.getName() + ".draw"
                        + (t.getName().startsWith("android.") ? " (framework class: app-wide hook, identity-filtered)" : ""));
                com.waenhancer.diagnostics.InspectorTrace.glassEvent(
                        "shared-bubble-draw", "class=" + t.getName());
            } catch (Throwable error) {
                report("shared-bubble-draw", "native bubble draw not observable: " + error);
            }
            return;
        }
    }

    /**
     * A drawable that did not come from the factory but shares a factory result's ConstantState
     * (a cached copy) is the same bubble. Only adopted inside a verified row draw, so a nine-patch
     * from the same resource elsewhere in WhatsApp stays untouched.
     */
    private Integer adoptBubbleCopy(Drawable bubble, Object canvas) {
        if (!(canvas instanceof Canvas) || com.waenhancer.theme.NativeBubbleGlass.hostFor((Canvas) canvas) == null) return null;
        Drawable.ConstantState state=bubble.getConstantState();
        Integer direction=state==null ? null : bubbleStateSides.get(state);
        if (direction!=null) {
            bubbleSides.put(bubble, direction);
            com.waenhancer.theme.BubbleGlassTelemetry.adoptedCopy(bubble, direction);
        } else {
            com.waenhancer.theme.BubbleGlassTelemetry.unregisteredOnRow(bubble,
                    com.waenhancer.theme.NativeBubbleGlass.hostFor((Canvas) canvas));
        }
        return direction;
    }

    /** Glass for a registered native bubble, or nothing (the native draw then proceeds). */
    private void drawNativeBubble(XC_MethodHook.MethodHookParam param) {
        com.waenhancer.theme.BubbleGlassTelemetry.call();
        if (com.waenhancer.theme.NativeBubbleGlass.nativePass()) { com.waenhancer.theme.BubbleGlassTelemetry.stayedNative(com.waenhancer.theme.BubbleGlassTelemetry.Native.NATIVE_PASS); return; }
        Drawable bubble=(Drawable) param.thisObject;
        if (param.args.length > 0 && param.args[0] instanceof Canvas) {
            Canvas canvas = (Canvas) param.args[0];
            if (GlassPane.isCaptureCanvas(canvas)) return;
            try {
                if (drawDetachedDate(bubble, canvas) || drawViewOnceBackground(bubble, canvas)) {
                    param.setResult(null);
                    return;
                }
            } catch (Throwable error) {
                report("special-bubble-draw", "special bubble stayed native: " + error);
            }
        }
        Integer direction=bubbleSides.get(bubble);
        if (direction==null) direction=adoptBubbleCopy(bubble, param.args.length > 0 ? param.args[0] : null);
        Boolean outgoing=direction==null ? null : direction==3;
        // Anything not recognized as a registered native bubble on a canvas is left untouched.
        if (outgoing==null || !(param.args[0] instanceof Canvas)) { com.waenhancer.theme.BubbleGlassTelemetry.stayedNative(com.waenhancer.theme.BubbleGlassTelemetry.Native.NOT_REGISTERED); return; }
        Canvas canvas=(Canvas) param.args[0];
        // Skip only a bubble drawn straight into a pane capture. Rows re-recorded during a capture
        // keep that display list for the real frame: skipping them there left every bubble native
        // (device probe: capture=1212 of 1327 calls, glass=0, with the source ready).
        if (GlassPane.isCaptureCanvas(canvas)) { com.waenhancer.theme.BubbleGlassTelemetry.stayedNative(com.waenhancer.theme.BubbleGlassTelemetry.Native.CAPTURE); return; }
        com.waenhancer.diagnostics.InspectorTrace.glassEventOnce(
                "shared-bubble-draw", "class=" + bubble.getClass().getName());
        View host=com.waenhancer.theme.NativeBubbleGlass.hostFor(canvas);
        if (host==null) { com.waenhancer.theme.NativeBubbleGlass.unhosted(bubble, canvas); return; }
        if (!sharedFactoryWanted(outgoing)) {
            com.waenhancer.theme.BubbleGlassTelemetry.stayedNative(com.waenhancer.theme.BubbleGlassTelemetry.Native.SWITCH_OFF);
            com.waenhancer.theme.BubbleGlassTelemetry.switchOff(direction);
            com.waenhancer.theme.BubbleGlassTelemetry.nativeRow("switch_off", host, direction, bubbleScopeCache.name());
            Session owner=sessionFor(host.getRootView());
            if (owner!=null && owner.bubbleGlass!=null) owner.bubbleGlass.nativeRow(host);
            return;
        }
        Session session=sessionFor(host.getRootView());
        if (session==null) {
            com.waenhancer.theme.BubbleGlassTelemetry.stayedNative(com.waenhancer.theme.BubbleGlassTelemetry.Native.NO_SESSION);
            com.waenhancer.theme.BubbleGlassTelemetry.nativeRow("no_session", host, direction, "-");
            return;
        }
        try {
            if (session.drawBubble(bubble, canvas, outgoing)) param.setResult(null);
        } catch (Throwable error) {
            com.waenhancer.theme.BubbleGlassTelemetry.stayedNative(com.waenhancer.theme.BubbleGlassTelemetry.Native.EXCEPTION);
            report("shared-bubble-draw-error", "bubble left native: " + error);
        }
    }

    /**
     * Bubble scope read once per resume/scan, never per draw. Reading it per draw called
     * {@code prefs.getAll()} on the preference bridge for every bubble (2835 times in one device
     * trace) and any failed read meant OFF: those rows were recorded native, i.e. transparent
     * with a translucent Bubble Colors palette (device trace: switch_off=555 with both sides on).
     */
    private volatile LiquidGlassSettings.BubbleScope bubbleScopeCache = LiquidGlassSettings.BubbleScope.OFF;

    private void refreshBubbleScope() {
        LiquidGlassSettings.BubbleScope read = LiquidGlassSettings.bubbleScopeOrNull(prefs);
        if (read != null) bubbleScopeCache = read; // A failed read keeps the last good value.
    }

    private boolean sharedFactoryWanted(boolean outgoing) {
        return LiquidGlassSettings.scopeIncludes(bubbleScopeCache, outgoing) && GlassOptics.current().oneMate;
    }

    /** Draw-path variant of {@link #bubbleMaterial}: no preference read. */
    private GlassSpec bubbleDrawMaterial(boolean outgoing, GlassSpec base) {
        if (base == null || !LiquidGlassSettings.scopeIncludes(bubbleScopeCache, outgoing)) return null;
        int tint = bubblePalette.forSide(outgoing);
        if (tint == 0 || (tint >>> 24) == 0) return base;
        synchronized (tintedMaterials) {
            return tintedMaterials.computeIfAbsent(base, ignored -> new java.util.HashMap<>())
                    .computeIfAbsent(tint, base::withBubbleTint);
        }
    }

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
                    if (session != null) session.wallpaperChanged(target);
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
                    if (session != null) { session.wallpaperChanged(target); session.rowContentChanged(target); }
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
                if (!GlassPane.isCapturing()) {
                    View view=(View)param.thisObject;
                    conversation.onComposerVisualChanged(view);
                    Session session=sessionFor(view.getRootView());
                    if (session!=null) session.wallpaperChanged(view);
                }
            }
        };
        XposedBridge.hookAllMethods(ImageView.class, "setImageTintList", paletteChanged);
        XposedBridge.hookAllMethods(ImageView.class, "setColorFilter", paletteChanged);
        XposedBridge.hookAllMethods(ImageView.class, "drawableStateChanged", paletteChanged);
        XposedBridge.hookAllMethods(TextView.class, "setTextColor", paletteChanged);
        XposedBridge.hookAllMethods(View.class, "setBackgroundTintList", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                View view = (View) param.thisObject;
                if (!GlassPane.isCapturing()) conversation.onBackgroundChanged(view, "tint");
                Session session = sessionFor(view.getRootView());
                if (session == null || session.installing) return;
                session.wallpaperChanged(view);
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

    /**
     * Message-row content colours on glass are held at their source, like the composer icons
     * (onComposerVisualChanged): WhatsApp resets them on every rebind and playback change, and
     * that invalidates only the child, not the row whose glass recording drove the sync. The
     * native grey then showed for a frame or stayed until the row redrew (owner video
     * 2026-11-03). The reset is answered inside the same setter call, before any frame.
     */
    /**
     * Glass-row content enforced where it is drawn (owner trace 2026-11-04 after #148:
     * contrast_held=0 contrast_restored=0 contrast_fresh=797 per ~1230 glass recordings).
     * WhatsApp gives these rows new reply/voice views; while scrolling every row re-records and
     * the in-draw tint caught them, but at rest a replaced view is recorded only by its own
     * sub-tree, never by the row draw this module hooks, so it kept WhatsApp's grey. Before such
     * a view draws itself inside a row that holds glass colours, the colour is applied in place.
     */
    static final String[] DRAW_WATCH = {"quoted_text", "description", "date", "call_log_subtitle", "control_btn", "call_type_icon",
            "view_once_media_type_large", "view_once_media_type", "view_once_control_icon", "status"};
    private int[] drawWatchIds;
    private int contactStatusId;

    private int drawWatchIndex(View view, int id) {
        int[] ids = drawWatchIds;
        if (ids == null) {
            ids = new int[DRAW_WATCH.length];
            String pkg = view.getContext().getPackageName();
            for (int i = 0; i < ids.length; i++)
                ids[i] = view.getResources().getIdentifier(DRAW_WATCH[i], "id", pkg);
            drawWatchIds = ids;
        }
        for (int i = 0; i < ids.length; i++) if (ids[i] == id && id != 0) return i;
        return -1;
    }

    private void installContrastGuards() {
        // The view-once ColorDrawable is drawn by View.draw *before* onDraw; a posted sync
        // leaves one opaque frame when WhatsApp swaps it during a download or a scroll rebind.
        try {
            XposedBridge.hookMethod(View.class.getDeclaredMethod("draw", Canvas.class), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (applyingContrast || sessions.isEmpty()
                            || android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return;
                    View view = (View) param.thisObject;
                    if (!(view.getBackground() instanceof android.graphics.drawable.ColorDrawable)) return;
                    resolveSpecialViewIds(view);
                    View parent = view.getParent() instanceof View ? (View) view.getParent() : null;
                    int id = view.getId(), parentId = parent == null ? 0 : parent.getId();
                    if ((id != viewOnceLargeId && id != viewOnceSmallId)
                            && (parentId != viewOnceLargeId && parentId != viewOnceSmallId)) return;
                    for (WeakReference<Session> reference : sessions.values()) {
                        Session session = reference == null ? null : reference.get();
                        if (session != null && session.prepareViewOnceFillAtDraw(view)) return;
                    }
                }
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            report("view-once-draw-missing", "view-once fill guard unavailable: " + error);
        }
        XC_MethodHook atDraw = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (applyingContrast || sessions.isEmpty()) return;
                View view = (View) param.thisObject;
                int id = view.getId();
                if (id == View.NO_ID || id == 0) return;
                if (contactStatusId == 0) contactStatusId = view.getResources().getIdentifier(
                        "conversation_contact_status", "id", view.getContext().getPackageName());
                if (id == contactStatusId) { conversation.toneConversationStatusAtDraw(view); return; }
                int which = drawWatchIndex(view, id);
                if (which < 0 || android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return;
                for (WeakReference<Session> reference : sessions.values()) {
                    Session session = reference == null ? null : reference.get();
                    if (session == null) continue;
                    View row = session.glassContrastRow(view);
                    if (row != null) { session.enforceAtDraw(row, view, DRAW_WATCH[which]); return; }
                }
            }
        };
        try {
            XposedBridge.hookAllMethods(TextView.class, "onDraw", atDraw);
            XposedBridge.hookAllMethods(ImageView.class, "onDraw", atDraw);
            report("contrast-at-draw", "glass-row content enforced before draw");
        } catch (RuntimeException | LinkageError error) {
            report("contrast-at-draw-missing", "draw-time contrast unavailable: " + error);
        }
        XC_MethodHook guard = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (applyingContrast || android.os.Looper.myLooper() != android.os.Looper.getMainLooper()
                        || contrastOwners.isEmpty()) return;
                View view = (View) param.thisObject;
                Session owner = contrastOwners.get(view);
                if (owner != null) owner.reassertContrast(view, param.method.getName());
            }
        };
        try {
            XposedBridge.hookAllMethods(TextView.class, "setTextColor", guard);
            XposedBridge.hookAllMethods(ImageView.class, "setImageTintList", guard);
            XposedBridge.hookAllMethods(ImageView.class, "setColorFilter", guard);
            XposedBridge.hookAllMethods(View.class, "setLayerType", guard);
            report("contrast-guards", "glass-row content colour guards installed");
        } catch (RuntimeException | LinkageError error) {
            report("contrast-guard-missing", "glass-row colour guards unavailable: " + error);
        }
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
        /** Only the two or three known search hosts; checked before drawing when native rebinding
         * does not request layout (and therefore never triggers our normal scan). */
        private final WeakHashMap<View, Boolean> searchCandidates = new WeakHashMap<>();
        private com.waenhancer.theme.ConversationBackdropSource bubbleSource;
        private com.waenhancer.theme.NativeBubbleGlass bubbleGlass;
        /**
         * Reply boxes inside glass bubbles: WhatsApp fakes their rounded corners by painting the
         * corner wedges in the (opaque, dark) bubble colour, which shows as black triangles over
         * glass. Clipping the quote frame to a rounded outline cuts exactly those wedges, whatever
         * draws them; the box and its content stay native. Reversible; original values kept here.
         */
        private final WeakHashMap<View, Object[]> clippedQuotes = new WeakHashMap<>();
        private int quoteFrameId = -1;
        static final float QUOTE_CORNER_DP = 8f;
        /** Reply snippet alpha on glass bubbles (composer reply: 86%). */
        static final int REPLY_TEXT_ALPHA = 0xDB;
        /** Waveform colour scale toward white on glass rows (0.5 = halfway). */
        static final float WAVE_LIFT = 0.5f;

        private void clipReplyCorners(View window) {
            if (quoteFrameId == -1) quoteFrameId = window.getResources().getIdentifier(
                    "bubble_quoted_message_frame", "id", window.getContext().getPackageName());
            if (quoteFrameId == 0) return;
            final float radius = QUOTE_CORNER_DP * window.getResources().getDisplayMetrics().density;
            for (View row : new ArrayList<>(nativeDrawRows.keySet())) {
                if (row == null || !row.isAttachedToWindow() || row.getRootView() != window) continue;
                syncReplyText(row); // WhatsApp may have rebound the quote's colour.
                View frame = row.findViewById(quoteFrameId);
                if (frame == null || clippedQuotes.containsKey(frame)) continue;
                clippedQuotes.put(frame, new Object[]{frame.getOutlineProvider(), frame.getClipToOutline()});
                com.waenhancer.theme.BubbleGlassTelemetry.quoteFrame(frame);
                frame.setOutlineProvider(new android.view.ViewOutlineProvider() {
                    @Override public void getOutline(View view, android.graphics.Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                    }
                });
                frame.setClipToOutline(true);
            }
        }

        /** Content colour applied over native colours on glass rows: view -> {native, applied}. */
        private final WeakHashMap<TextView, android.content.res.ColorStateList[]> quoteTexts = new WeakHashMap<>();
        private final WeakHashMap<android.widget.ImageView, android.content.res.ColorStateList[]> glassIcons = new WeakHashMap<>();
        /** Waveforms lifted through a layer colour filter: view -> original layer type. */
        private final WeakHashMap<View, Integer> liftedLayers = new WeakHashMap<>();
        private final Map<String, Integer> contrastIds = new java.util.HashMap<>();
        private final Set<View> contrastPending = java.util.Collections.newSetFromMap(new WeakHashMap<>());
        /**
         * Glass-row contrast with hysteresis (owner 2026-11-03: reply text and play button still
         * flickered white/grey with almost no WhatsApp setter resets in the trace). A row whose
         * latest recording has no glass keeps the glass colours for CONTRAST_HOLD_MS from the
         * first such observation; only a row still without glass after it gets native colours.
         * Single native recordings (texture gaps, re-record passes) and session scans landing in
         * them no longer flip the colours. rowsWithGlassContrast: rows whose colours were last
         * set for glass; nativeSince: first time each of them was seen without glass.
         */
        static final long CONTRAST_HOLD_MS = 500;
        private final Set<View> rowsWithGlassContrast = java.util.Collections.newSetFromMap(new WeakHashMap<>());
        private final WeakHashMap<View, Long> nativeSince = new WeakHashMap<>();
        private final Set<View> holdPending = java.util.Collections.newSetFromMap(new WeakHashMap<>());
        /** True while syncContrast runs inside a row's glass recording: no layer or fill changes. */
        private boolean contrastInDraw;
        private boolean countFresh;
        /**
         * Native fills cleared inside glass bubbles: drawable -> original colour. The full colour,
         * not getAlpha(): ColorDrawable.setAlpha multiplies the base colour's own alpha, so
         * restoring an effective alpha would fade a translucent fill at every transition.
         */
        private final WeakHashMap<Drawable, Integer> clearedFills = new WeakHashMap<>();

        private int contrastId(View row, String name) {
            Integer id = contrastIds.get(name);
            if (id == null) {
                id = row.getResources().getIdentifier(name, "id", row.getContext().getPackageName());
                contrastIds.put(name, id);
            }
            return id;
        }

        private View rowChild(View row, String name) {
            int id = contrastId(row, name);
            return id == 0 ? null : row.findViewById(id);
        }

        /**
         * WhatsApp's secondary colours inside a received bubble (reply snippet, voice-note play
         * button, duration, time, waveform) are made for its opaque bubble and nearly unreadable
         * on glass (owner reports 2026-11-03). On a glass row they follow the material's content
         * colour, like the composer reply; the reply's sender title keeps its participant colour
         * and the seek thumb keeps its accent. Everything is restored when the row has no glass.
         */
        private void syncReplyText(View row) {
            if (row == null || closed) return;
            boolean outer = applyingContrast;
            applyingContrast = true;
            try { syncContrast(row); } finally { applyingContrast = outer; }
        }

        /**
         * WhatsApp reset a held colour (rebind, playback state): its value becomes the native one
         * restored later, and the glass colour is applied again in the same call, so no frame
         * shows the native grey. Any other setter on a held view is left alone.
         */
        void reassertContrast(View view, String method) {
            if (closed) { contrastOwners.remove(view); return; }
            boolean outer = applyingContrast;
            applyingContrast = true;
            try {
                if ("setTextColor".equals(method) && view instanceof TextView) {
                    TextView text = (TextView) view;
                    android.content.res.ColorStateList[] state = quoteTexts.get(text);
                    if (state == null || text.getTextColors() == state[1]) return;
                    state[0] = text.getTextColors();
                    text.setTextColor(state[1]);
                    com.waenhancer.theme.BubbleGlassTelemetry.reasserted(0);
                } else if ("setImageTintList".equals(method) && view instanceof ImageView) {
                    ImageView icon = (ImageView) view;
                    android.content.res.ColorStateList[] state = glassIcons.get(icon);
                    if (state == null || icon.getImageTintList() == state[1]) return;
                    state[0] = icon.getImageTintList();
                    icon.setImageTintList(state[1]);
                    com.waenhancer.theme.BubbleGlassTelemetry.reasserted(1);
                } else if ("setColorFilter".equals(method) && view instanceof ImageView) {
                    // A filter overrides the tint; not overridden blindly, reported as evidence.
                    if (glassIcons.containsKey(view) && ((ImageView) view).getColorFilter() != null)
                        com.waenhancer.theme.BubbleGlassTelemetry.contrastFilter(view);
                } else if ("setLayerType".equals(method) && liftedLayers.containsKey(view)) {
                    liftedLayers.put(view, view.getLayerType()); // WhatsApp's latest, restored later.
                    applyLift(view);
                    com.waenhancer.theme.BubbleGlassTelemetry.reasserted(2);
                }
            } finally { applyingContrast = outer; }
        }

        /**
         * Inside the row's glass recording (the bubble draws before the row's children): text and
         * icon tints set here are recorded by the children in this same recording, so a new or
         * rebound view never shows one native-colour frame. Layer types and fills are left to the
         * posted sync, which also catches anything this pass skips.
         */
        private void syncTintsInDraw(View row) {
            if (row == null || closed) return;
            boolean outer = applyingContrast, outerDraw = contrastInDraw;
            applyingContrast = true; contrastInDraw = true;
            try { syncContrast(row); com.waenhancer.theme.BubbleGlassTelemetry.contrast(3); }
            finally { applyingContrast = outer; contrastInDraw = outerDraw; }
        }

        /** Glass state for contrast, with the hold described at {@link #CONTRAST_HOLD_MS}. */
        private boolean contrastGlass(View row, GlassSpec material) {
            if (bubbleGlass == null || material == null) return false;
            if (bubbleGlass.hasGlass(row)) { nativeSince.remove(row); return true; }
            if (!rowsWithGlassContrast.contains(row)) return false;
            long now = android.os.SystemClock.uptimeMillis();
            Long since = nativeSince.get(row);
            if (since == null) { since = now; nativeSince.put(row, now); }
            long left = CONTRAST_HOLD_MS - (now - since);
            if (left <= 0) {
                nativeSince.remove(row);
                com.waenhancer.theme.BubbleGlassTelemetry.contrast(1);
                return false;
            }
            com.waenhancer.theme.BubbleGlassTelemetry.contrast(0);
            if (holdPending.add(row))
                row.postDelayed(() -> { holdPending.remove(row); if (!closed) syncReplyText(row); }, left + 16);
            return true;
        }

        /** The glass row, with glass colours applied, that contains {@code view}; else null. */
        View glassContrastRow(View view) {
            if (closed || rowsWithGlassContrast.isEmpty()) return null;
            android.view.ViewParent parent = view.getParent();
            for (int i = 0; i < 12 && parent instanceof View; i++, parent = parent.getParent())
                if (rowsWithGlassContrast.contains(parent)) return (View) parent;
            return null;
        }

        /** Answer the downloaded-media fill in the same recording, including a replaced child. */
        boolean prepareViewOnceFillAtDraw(View view) {
            View row = rowsWithGlassContrast.contains(view) ? view : glassContrastRow(view);
            if (row == null || !(row.getId() == viewOnceLargeId || row.getId() == viewOnceSmallId
                    || rowChild(row, "view_once_media_container_large") != null
                    || rowChild(row, "view_once_media_container") != null)) return false;
            boolean previous = applyingContrast;
            applyingContrast = true;
            try { clearFill(view.getBackground(), true); }
            finally { applyingContrast = previous; }
            return true;
        }

        /** See {@link AppLiquidGlass#DRAW_WATCH}: called before the view draws itself (UI thread). */
        void enforceAtDraw(View row, View view, String name) {
            if (view instanceof TextView) {
                android.content.res.ColorStateList[] state = quoteTexts.get(view);
                if (state != null && ((TextView) view).getTextColors() == state[1]) return;
            } else if (view instanceof android.widget.ImageView) {
                android.content.res.ColorStateList[] state = glassIcons.get(view);
                if (state != null && ((android.widget.ImageView) view).getImageTintList() == state[1]) return;
            } else return;
            GlassSpec material = resolvedMaterial();
            if (material == null) return;
            int content = material.contentColor & 0x00FFFFFF;
            int secondary = content | (REPLY_TEXT_ALPHA << 24);
            boolean voice = rowChild(row, "audio_visualizer") != null
                    || rowChild(row, "conversation_row_audio_player_view") != null;
            boolean outer = applyingContrast;
            applyingContrast = true;
            countFresh = false;
            try {
                switch (name) {
                    case "quoted_text": tintText(view, true, secondary); break;
                    case "description": if (!voice) return; tintText(view, true, secondary); break;
                    case "date": if (!voice && row.getId() != dateWrapperId) return;
                        tintText(view, true, secondary); break;
                    case "status": if (row.getId() != dateWrapperId || !(view instanceof ImageView)) return;
                        tintIcon((ImageView) view, true, content | 0xFF000000); break;
                    case "view_once_media_type_large": case "view_once_media_type":
                        if (rowChild(row, "view_once_media_container_large") == null
                                && rowChild(row, "view_once_media_container") == null) return;
                        tintText(view, true, content | 0xFF000000); break;
                    case "view_once_control_icon":
                        if (!(view instanceof ImageView)
                                || rowChild(row, "view_once_media_container_large") == null
                                && rowChild(row, "view_once_media_container") == null) return;
                        tintIcon((ImageView) view, true, content | 0xFF000000); break;
                    case "control_btn": if (!voice || !(view instanceof android.widget.ImageView)) return;
                        tintIcon((android.widget.ImageView) view, true, content | 0xFF000000); break;
                    case "call_type_icon": if (!(view instanceof android.widget.ImageView)) return;
                        tintIcon((android.widget.ImageView) view, true, content | 0xFF000000); break;
                    case "call_log_subtitle": if (rowChild(row, "call_type_icon") == null) return;
                        tintText(view, true, secondary); break;
                    default: return;
                }
                com.waenhancer.theme.BubbleGlassTelemetry.contrast(4);
            } finally { applyingContrast = outer; }
        }

        private void syncContrast(View row) {
            GlassSpec material = resolvedMaterial();
            boolean glass = contrastGlass(row, material);
            // A view tinted for the first time in a row that already had glass colours was
            // replaced by WhatsApp (counted as "fresh"); first tints of a new row are not.
            countFresh = glass && rowsWithGlassContrast.contains(row);
            if (glass) rowsWithGlassContrast.add(row); else rowsWithGlassContrast.remove(row);
            int content = material == null ? 0 : material.contentColor & 0x00FFFFFF;
            int secondary = content | (REPLY_TEXT_ALPHA << 24);
            View quoted = rowChild(row, "quoted_text");
            tintText(quoted, glass, secondary);
            // Detached sticker/emoji timestamp: the timestamp drawable itself is a glass host.
            if (row.getId() == dateWrapperId) {
                tintText(rowChild(row, "date"), glass, secondary);
                View status = rowChild(row, "status");
                if (status instanceof ImageView) tintIcon((ImageView) status, glass, content | 0xFF000000);
            }
            if (glass && !contrastInDraw && quoted instanceof TextView) {
                android.content.res.ColorStateList[] held = quoteTexts.get(quoted);
                com.waenhancer.theme.BubbleGlassTelemetry.quoteState((TextView) quoted,
                        held != null && ((TextView) quoted).getTextColors() == held[1]);
            }
            View visualizer = rowChild(row, "audio_visualizer");
            if (visualizer != null || rowChild(row, "conversation_row_audio_player_view") != null) {
                tintText(rowChild(row, "description"), glass, secondary);
                tintText(rowChild(row, "date"), glass, secondary);
                View play = rowChild(row, "control_btn");
                if (play instanceof android.widget.ImageView) {
                    android.widget.ImageView playIcon = (android.widget.ImageView) play;
                    tintIcon(playIcon, glass, content | 0xFF000000);
                    if (glass && !contrastInDraw) {
                        android.content.res.ColorStateList[] held = glassIcons.get(playIcon);
                        com.waenhancer.theme.BubbleGlassTelemetry.iconState(playIcon,
                                held != null && playIcon.getImageTintList() == held[1]);
                    }
                }
                if (visualizer != null && !contrastInDraw) liftLayer(visualizer, glass);
            }
            // Call rows: the missed/incoming call glyph loses contrast on glass once its native
            // bubble is gone (owner 2026-11-03: "the phone symbol could be white").
            View callIcon = rowChild(row, "call_type_icon");
            if (callIcon instanceof android.widget.ImageView) {
                tintIcon((android.widget.ImageView) callIcon, glass, content | 0xFF000000);
                tintText(rowChild(row, "call_log_subtitle"), glass, secondary);
            }
            // View-once rows: an inner dark rectangle 12px inside the bubble showed its square
            // corners outside the glass's rounded ones, and its label was grey.
            View viewOnce = rowChild(row, "view_once_media_container_large");
            if (viewOnce == null) viewOnce = rowChild(row, "view_once_media_container");
            if (viewOnce instanceof android.view.ViewGroup) {
                android.view.ViewGroup group = (android.view.ViewGroup) viewOnce;
                if (!contrastInDraw) {
                    clearFill(group.getBackground(), glass);
                    for (int i = 0; i < group.getChildCount(); i++) clearFill(group.getChildAt(i).getBackground(), glass);
                }
                tintTree(group, glass, content | 0xFF000000, 0);
            }
        }

        private void clearFill(Drawable fill, boolean glass) {
            if (!(fill instanceof android.graphics.drawable.ColorDrawable)) return;
            android.graphics.drawable.ColorDrawable flat = (android.graphics.drawable.ColorDrawable) fill;
            Integer original = clearedFills.get(fill);
            if (!glass) {
                if (original != null) { flat.setColor(original); clearedFills.remove(fill); }
                return;
            }
            if (original == null) {
                flat.mutate();
                clearedFills.put(fill, flat.getColor());
            } else if (flat.getColor() != 0) {
                // WhatsApp changed this fill while downloading; restore its latest native value.
                clearedFills.put(fill, flat.getColor());
            }
            if (flat.getColor() != 0) flat.setColor(0);
        }

        private void tintTree(View view, boolean glass, int color, int depth) {
            if (depth > 4 || view == null) return;
            if (view instanceof TextView) tintText(view, glass, color);
            else if (view instanceof android.widget.ImageView) tintIcon((android.widget.ImageView) view, glass, color);
            else if (view instanceof android.view.ViewGroup) {
                android.view.ViewGroup group = (android.view.ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) tintTree(group.getChildAt(i), glass, color, depth + 1);
            }
        }

        private void tintText(View found, boolean glass, int color) {
            if (!(found instanceof TextView)) return;
            TextView text = (TextView) found;
            // AntiRevoke's explicit colour owns this label while it is marked. Keep its
            // icon/label intact and use normal glass contrast when no override is present.
            Object revokeColor = de.robv.android.xposed.XposedHelpers.getAdditionalInstanceField(text, "waexRevokeColor");
            if (glass && revokeColor instanceof Integer) color = (Integer) revokeColor;
            android.content.res.ColorStateList[] state = quoteTexts.get(text);
            android.content.res.ColorStateList current = text.getTextColors();
            if (!glass) {
                if (state != null && current == state[1]) {
                    text.setTextColor(state[0]);
                    com.waenhancer.theme.BubbleGlassTelemetry.contrastNative(text, bubbleGlass == null ? "no-glass-session" : "row-without-glass");
                }
                if (quoteTexts.remove(text) != null) contrastOwners.remove(text);
                return;
            }
            if (state != null && current == state[1] && state[1].getDefaultColor() == color) return;
            if (state == null && countFresh) com.waenhancer.theme.BubbleGlassTelemetry.contrast(2);
            android.content.res.ColorStateList original = state != null && current == state[1] ? state[0] : current;
            android.content.res.ColorStateList wanted = android.content.res.ColorStateList.valueOf(color);
            quoteTexts.put(text, new android.content.res.ColorStateList[]{original, wanted});
            contrastOwners.put(text, this);
            text.setTextColor(wanted);
        }

        private void tintIcon(android.widget.ImageView icon, boolean glass, int color) {
            android.content.res.ColorStateList[] state = glassIcons.get(icon);
            android.content.res.ColorStateList current = icon.getImageTintList();
            if (!glass) {
                if (state != null && current == state[1]) icon.setImageTintList(state[0]);
                if (glassIcons.remove(icon) != null) contrastOwners.remove(icon);
                return;
            }
            if (state != null && current == state[1] && state[1].getDefaultColor() == color) return;
            if (state == null && countFresh) com.waenhancer.theme.BubbleGlassTelemetry.contrast(2);
            android.content.res.ColorStateList original = state != null && current == state[1] ? state[0] : current;
            android.content.res.ColorStateList wanted = android.content.res.ColorStateList.valueOf(color);
            glassIcons.put(icon, new android.content.res.ColorStateList[]{original, wanted});
            contrastOwners.put(icon, this);
            icon.setImageTintList(wanted);
        }

        /**
         * The waveform is custom-drawn (played/unplayed colours inside WhatsApp's view), so it is
         * lightened as a whole through its layer paint: every channel moves halfway to white.
         * The played part stays distinguishable by its hue.
         */
        private void liftLayer(View view, boolean glass) {
            Integer original = liftedLayers.get(view);
            if (!glass) {
                if (original != null) {
                    view.setLayerType(original, null);
                    liftedLayers.remove(view);
                    contrastOwners.remove(view);
                }
                return;
            }
            if (original != null && view.getLayerType() == View.LAYER_TYPE_HARDWARE) return;
            if (original == null) liftedLayers.put(view, view.getLayerType());
            contrastOwners.put(view, this);
            applyLift(view);
        }

        private android.graphics.Paint liftPaint;

        private void applyLift(View view) {
            if (liftPaint == null) {
                liftPaint = new android.graphics.Paint();
                liftPaint.setColorFilter(new android.graphics.ColorMatrixColorFilter(new float[]{
                        WAVE_LIFT, 0, 0, 0, 255 * (1 - WAVE_LIFT),
                        0, WAVE_LIFT, 0, 0, 255 * (1 - WAVE_LIFT),
                        0, 0, WAVE_LIFT, 0, 255 * (1 - WAVE_LIFT),
                        0, 0, 0, 1, 0}));
            }
            view.setLayerType(View.LAYER_TYPE_HARDWARE, liftPaint);
        }

        private void restoreReplyTexts() {
            boolean outer = applyingContrast;
            applyingContrast = true;
            try { restoreContrast(); } finally { applyingContrast = outer; }
            contrastOwners.values().removeIf(owner -> owner == this);
            rowsWithGlassContrast.clear();
            nativeSince.clear();
        }

        private void restoreContrast() {
            for (Map.Entry<TextView, android.content.res.ColorStateList[]> entry : new ArrayList<>(quoteTexts.entrySet())) {
                TextView text = entry.getKey();
                if (text != null && text.getTextColors() == entry.getValue()[1]) text.setTextColor(entry.getValue()[0]);
            }
            quoteTexts.clear();
            for (Map.Entry<android.widget.ImageView, android.content.res.ColorStateList[]> entry : new ArrayList<>(glassIcons.entrySet())) {
                android.widget.ImageView icon = entry.getKey();
                if (icon != null && icon.getImageTintList() == entry.getValue()[1]) icon.setImageTintList(entry.getValue()[0]);
            }
            glassIcons.clear();
            for (Map.Entry<View, Integer> entry : new ArrayList<>(liftedLayers.entrySet()))
                if (entry.getKey() != null) entry.getKey().setLayerType(entry.getValue(), null);
            liftedLayers.clear();
            for (Map.Entry<Drawable, Integer> entry : new ArrayList<>(clearedFills.entrySet()))
                if (entry.getKey() instanceof android.graphics.drawable.ColorDrawable)
                    ((android.graphics.drawable.ColorDrawable) entry.getKey()).setColor(entry.getValue());
            clearedFills.clear();
        }

        private void restoreReplyCorners() {
            for (Map.Entry<View, Object[]> entry : new ArrayList<>(clippedQuotes.entrySet())) {
                View frame = entry.getKey();
                if (frame == null) continue;
                frame.setOutlineProvider((android.view.ViewOutlineProvider) entry.getValue()[0]);
                frame.setClipToOutline((Boolean) entry.getValue()[1]);
            }
            clippedQuotes.clear();
        }

        /** One-frame PixelCopy of the bare wallpaper when it cannot be replayed (per wallpaper). */
        private com.waenhancer.theme.WallpaperFallback wallpaperFallback;
        private WeakReference<android.view.Window> activityWindow = new WeakReference<>(null);
        /** Bubble scope + material/optics the observed rows were last recorded with. */
        private String bubbleSettings;
        /** Child RenderNodes (sticker date and view-once) are not dirtied by row.invalidate(). */
        private final WeakHashMap<View, Boolean> specialBubbleHosts = new WeakHashMap<>();
        void trackSpecialBubbleHost(View host) {
            if (!closed && host != null) specialBubbleHosts.put(host, Boolean.TRUE);
        }
        /** Wallpaper identity the current bubble texture was planned from. */
        private String textureIdentity;
        private WeakReference<View> sharedWallpaper = new WeakReference<>(null);
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
                row.invalidate();
            });
        }

        private void watchBubble(View row, Binding binding) {
            if (binding.surface != Surface.BUBBLES) return;
            WeakReference<View> rowRef = new WeakReference<>(row);
            binding.glass.observeCaptureDemand(hardReset -> {
                View target = rowRef.get();
                if (closed || suspended || target == null || bindings.get(target) != binding) return;
                // A text/tick/image update still needs a fresh backdrop, but the previous
                // recording remains valid and may already be visible. Re-arming presentation
                // on every soft update causes a feedback loop: capture -> target.invalidate()
                // -> native setText/image -> soft damage -> presentation rearm -> capture.
                // Only a real release, host/size/mask change or first bind resets retries.
                if (hardReset) {
                    binding.presentation.rearm();
                    binding.recovery.revalidate();
                }
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
                        // Text, ticks and thumbnails do not change what lies behind the bubble:
                        // recapture without presenting transparent frames meanwhile. A real
                        // recycle to another host still releases via owner/offset/bounds checks.
                        binding.glass.damageCapture("row-content");
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
            // The bubble wallpaper texture is not chat content: keep it so returning to the chat
            // does not flash native bubbles while the blur is prepared again.
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
                if (binding.surface == Surface.SEARCH) binding.visible = false;
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
                // Before the per-binding early return: shared bubbles are not bindings.
                realignSharedBubbles();
                reconcileSearch();
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
                    if (binding.surface == Surface.SEARCH && !eligible && binding.visible) {
                        // A tab can leave the viewport without a capture being scheduled.
                        // Remember that visibility transition before it returns.
                        binding.visible = false;
                        binding.glass.releaseLive();
                    }
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
                            || eligible && mayCapture && binding.glass.capturePending()
                            || HomeSurfacePolicy.captureNewSearch(binding.surface == Surface.SEARCH,
                                    eligible, binding.visible, mayCapture);
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
            syncSharedBubbles();
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

        /** Recover search glass on background-only updates that emit no layout notification.
         * A full tree scan here would run on every frame; searchCandidates is a tiny weak set
         * discovered by the existing layout/discovery walk. No extra view hierarchy traversal. */
        private void reconcileSearch() {
            if (!LiquidGlassSettings.isEnabled(prefs, Surface.SEARCH)) return;
            View window = root.get();
            if (window == null) return;
            for (View target : searchCandidates.keySet()) {
                if (target == null || !target.isAttachedToWindow() || target.getRootView() != window
                        || !target.isShown()) continue;
                Binding binding = bindings.get(target);
                if (binding == null) {
                    bind(target, Surface.SEARCH, false);
                } else if (binding.surface == Surface.SEARCH && !binding.nested
                        && target.getBackground() != binding.glass
                        && !(target.getBackground() instanceof GlassMaterialDrawable)
                        && keepGlass(target, binding)) {
                    // The previous recording described the old native background. The next
                    // presentation must be freshly captured against the replacement.
                    binding.visible = false;
                    binding.glass.releaseLive();
                }
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
            if (bubble == null && !bindings.containsKey(view)) bindNativeBubbleRow(view);
            Surface surface = surfaceFor(view);
            if (surface != null) {
                candidates.put(view, surface);
                if (surface == Surface.SEARCH) searchCandidates.put(view, Boolean.TRUE);
                if (LiquidGlassSettings.isEnabled(prefs, surface)) bind(view, surface, false);
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i), depth + 1);
            }
        }

        /**
         * Native message rows draw WhatsApp's bubble drawable on their own canvas. Observing the
         * row's draw identifies that canvas (and the row's position) for the bubble draw hook;
         * the row itself, its background and its children are never modified.
         */
        private void bindNativeBubbleRow(View row) {
            if (closed || nativeDrawRows.containsKey(row)
                    || bubbleScopeCache == LiquidGlassSettings.BubbleScope.OFF
                    || !com.waenhancer.theme.NativeBubbleGlass.supported()) return;
            boolean named = row.getId() != View.NO_ID && resourceName(row).startsWith("conversation_row_");
            // Any other child of a verified message list is a message row too (view-once, calls).
            if (!named && (row.getParent() == null || !rowLists.containsKey(row.getParent()))) return;
            observeNativeRowDraw(row);
            // A row recorded before it was observed painted its bubble natively; record once more.
            row.invalidate();
        }

        boolean drawBubble(Drawable bubble, Canvas canvas, boolean outgoing) {
            if (closed || suspended || bubbleGlass == null || !sharedFactoryAvailable) {
                com.waenhancer.theme.BubbleGlassTelemetry.stayedNative(com.waenhancer.theme.BubbleGlassTelemetry.Native.NO_RENDERER);
                View host=com.waenhancer.theme.NativeBubbleGlass.hostFor(canvas);
                if (host!=null) com.waenhancer.theme.BubbleGlassTelemetry.nativeRow("no_renderer", host, null,
                        closed ? "closed" : suspended ? "suspended" : bubbleGlass == null ? "no-glass" : "no-factory");
                return false;
            }
            // Bubble Colors, when chosen, tints the glass like it tints native bubbles; the wash
            // follows the untinted material, whose content colour the glass rows' text uses.
            GlassSpec base = resolvedMaterial();
            return bubbleGlass.draw(bubble, canvas, bubbleDrawMaterial(outgoing, base), base);
        }

        /** Same-frame wallpaper alignment for every glass bubble (property updates only). */
        private void realignSharedBubbles() {
            if (bubbleGlass != null) bubbleGlass.realign();
        }

        private void releaseSharedBubbles() {
            if (bubbleGlass != null) bubbleGlass.release();
            if (bubbleSource != null) bubbleSource.release();
            if (wallpaperFallback != null) wallpaperFallback.release();
            restoreReplyCorners();
            restoreReplyTexts();
            bubbleGlass = null; bubbleSource = null; wallpaperFallback = null; bubbleSettings = null; sharedWallpaper.clear();
            textureIdentity = null;
        }

        /** Re-records observed rows once; their display lists bake in glass or native bubbles. */
        private void invalidateBubbleRows(View window) {
            for (View row : new ArrayList<>(nativeDrawRows.keySet()))
                if (row != null && row.isAttachedToWindow() && row.getRootView() == window) row.invalidate();
            // A parent invalidation can replay a child's old glass RenderNode unchanged.
            for (View host : new ArrayList<>(specialBubbleHosts.keySet()))
                if (host != null && host.isAttachedToWindow() && host.getRootView() == window) host.invalidate();
        }

        private void syncSharedBubbles() {
            View window=root.get();
            if (window==null) return;
            View wallpaper=findByName(window,"conversation_background",window.getContext().getPackageName());
            if (wallpaper!=sharedWallpaper.get()) {
                releaseSharedBubbles(); sharedWallpaper=new WeakReference<>(wallpaper);
            }
            if (wallpaper==null || bubbleScopeCache == LiquidGlassSettings.BubbleScope.OFF
                    || !com.waenhancer.theme.NativeBubbleGlass.supported() || !GlassOptics.current().oneMate) {
                if (bubbleScopeCache != LiquidGlassSettings.BubbleScope.OFF)
                    com.waenhancer.theme.BubbleGlassTelemetry.source("off", wallpaper==null ? "no-wallpaper-view"
                            : !com.waenhancer.theme.NativeBubbleGlass.supported() ? "api-or-lens" : "classic-renderer");
                // A disabled side must not retain a previously recorded sticker/date glass node.
                if (bubbleGlass != null) invalidateBubbleRows(window);
                releaseSharedBubbles(); return;
            }
            GlassSpec material=resolvedMaterial();
            if (material==null) return;
            if (bubbleSource==null) {
                bubbleSource=new com.waenhancer.theme.ConversationBackdropSource(() -> {
                    if (closed || bubbleGlass == null) return;
                    bubbleGlass.sourceChanged();
                    // Rows recorded before this chat's renderer existed painted natively.
                    invalidateBubbleRows(window);
                });
                bubbleGlass=new com.waenhancer.theme.NativeBubbleGlass(window, bubbleSource);
                // Reply text follows the row's glass. A glass recording applies text and icon
                // tints in place (the bubble draws before the row's children, so this recording
                // already uses them); everything, including layers and fills and the way back to
                // native, is (re)applied by one posted sync per row. Both are idempotent when the
                // colours are already applied, so a re-record they cause ends the cycle.
                bubbleGlass.onGlassChanged(row -> {
                    com.waenhancer.theme.NativeBubbleGlass glassNow = bubbleGlass;
                    if (glassNow != null && glassNow.hasGlass(row)) syncTintsInDraw(row);
                    if (contrastPending.add(row)) row.post(() -> { contrastPending.remove(row); if (!closed) syncReplyText(row); });
                });
            }
            // Scope (incoming/outgoing/both), opacity, rim or optics changed while the wallpaper
            // did not: recorded rows still hold the previous glass or native bubble.
            String settings=bubbleScopeCache.name() + ":"
                    + com.waenhancer.theme.NativeBubbleGlass.settingsKey(material);
            if (bubbleSettings!=null && !bubbleSettings.equals(settings)) invalidateBubbleRows(window);
            bubbleSettings=settings;
            clipReplyCorners(window);
            com.waenhancer.theme.WallpaperCapture plan=com.waenhancer.theme.WallpaperCapture.plan(wallpaper,window);
            String unsupported=plan.unsupportedReason();
            String identity=com.waenhancer.theme.WallpaperCapture.identity(wallpaper);
            // The plan can be unsupported for a moment (mid-layout, a transition, the list laying
            // out again after a tap) while the wallpaper itself is unchanged. Withdrawing the
            // texture then discarded every bubble's glass until a later scan rendered the same
            // wallpaper again; keep it. A changed wallpaper still withdraws (identity differs).
            if (unsupported!=null && bubbleSource.texture()!=null && !bubbleSource.failed()
                    && identity.equals(textureIdentity)
                    && (wallpaperFallback==null || wallpaperFallback.plan(wallpaper,window,identity)==null)) {
                com.waenhancer.theme.ConversationBackdropSource.probe("SOURCE_KEPT", "transient=" + unsupported);
                return;
            }
            // A replay that failed for good stays failed for this wallpaper: once the copy is in
            // use the source no longer reports failure, so remember it per wallpaper identity.
            if (unsupported==null && bubbleSource.failed()) {
                if (wallpaperFallback==null) wallpaperFallback=new com.waenhancer.theme.WallpaperFallback();
                wallpaperFallback.replayFailed(identity);
            }
            if (unsupported!=null || bubbleSource.failed()
                    || (wallpaperFallback!=null && wallpaperFallback.sticky(identity))) {
                // Cannot replay this wallpaper: use (or take, once) a copy of the bare wallpaper.
                if (wallpaperFallback==null) wallpaperFallback=new com.waenhancer.theme.WallpaperFallback();
                com.waenhancer.theme.WallpaperCapture copied=wallpaperFallback.plan(wallpaper,window,identity);
                if (copied!=null) plan=copied;
                else if (unsupported==null || com.waenhancer.theme.WallpaperFallback.eligible(unsupported))
                    wallpaperFallback.request(wallpaper,window,activityWindow.get(),identity,() -> {
                        if (!closed && !suspended) scan();
                    }, () -> !closed && bubbleSource!=null && (bubbleSource.texture()==null || bubbleSource.failed()
                            || com.waenhancer.theme.WallpaperCapture.plan(wallpaper,window).unsupportedReason()!=null));
            }
            if (plan.unsupportedReason()==null) textureIdentity=identity;
            bubbleSource.update(plan,
                    com.waenhancer.theme.ConversationBackdropSource.blurRadius(material,window.getResources().getDisplayMetrics().density));
        }
        void wallpaperChanged(View target) {
            if (installing || closed || suspended) return;
            for (View node=target;node!=null;node=node.getParent() instanceof View?(View)node.getParent():null)
                if ("conversation_background".equals(resourceName(node))) { discoverLater(target); return; }
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
            if (surface == Surface.BUBBLES) return; // Shared consumers never enter LiveBudget/captureBehind.
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
            releaseSharedBubbles();
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
            bindings.clear(); candidates.clear(); searchCandidates.clear(); pendingDiscovery.clear();
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
