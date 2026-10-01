package com.waenhancer.xposed.features.customization;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.PopupWindow;

import androidx.annotation.NonNull;

import com.waenhancer.config.GlassSurfaceCatalog;
import com.waenhancer.config.LiquidGlassSettings;
import com.waenhancer.config.LiquidGlassSettings.Surface;
import com.waenhancer.theme.GlassMaterialDrawable;
import com.waenhancer.theme.GlassRenderer;
import com.waenhancer.theme.GlassSpec;
import com.waenhancer.theme.GlassSurface;
import com.waenhancer.theme.SharedGlassBackdrop;
import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.WppCore;
import com.waenhancer.xposed.core.devkit.Unobfuscator;
import com.waenhancer.xposed.utils.DesignUtils;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
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
    private final Set<Method> drawHooks = new HashSet<>();
    private final Set<String> reported = new HashSet<>();
    private WeakReference<Session> foreground = new WeakReference<>(null);

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
                if (session != null) session.close();
            } else if (state == WppCore.ActivityChangeState.ChangeType.RESUMED) {
                reloadPrefs();
                Session session = watch(decor, null);
                foreground = new WeakReference<>(session);
                if (session != null) {
                    session.cachedMaterial = null;
                    decor.post(session::scan);
                }
            }
        });
        installBubbleHook();
        XposedBridge.hookAllMethods(Dialog.class, "show", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Dialog dialog = (Dialog) param.thisObject;
                if (!LiquidGlassSettings.isEnabled(prefs, Surface.PANELS) || dialog.getWindow() == null) return;
                View decor = dialog.getWindow().getDecorView();
                decor.post(() -> {
                    Session session = watch(decor, foreground.get());
                    if (session == null) return;
                    // Platform alert panel, not the entire full-screen dialog decor.
                    View panel = findByName(decor, "parentPanel", "android");
                    if (panel != null) session.bind(panel, Surface.PANELS, false);
                    session.scan();
                });
            }
        });
        XC_MethodHook popupHook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!LiquidGlassSettings.isEnabled(prefs, Surface.PANELS)) return;
                PopupWindow popup = (PopupWindow) param.thisObject;
                View content = popup.getContentView();
                if (content == null || !popup.isShowing()) return;
                content.post(() -> {
                    if (!popup.isShowing()) return;
                    View decor = content.getRootView();
                    Session session = watch(decor, foreground.get());
                    if (session == null) return;
                    View target = content;
                    // PopupBackgroundView draws the native shell; content is not reparented.
                    if (content.getParent() instanceof View && ((View) content.getParent()).getBackground() != null) {
                        target = (View) content.getParent();
                    }
                    session.bind(target, Surface.PANELS, false);
                    session.scan();
                });
            }
        };
        XposedBridge.hookAllMethods(PopupWindow.class, "showAtLocation", popupHook);
        XposedBridge.hookAllMethods(PopupWindow.class, "showAsDropDown", popupHook);
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
                            () -> {
                                Session session = foreground.get();
                                return session == null ? null : session.backdrop();
                            }, () -> material(Surface.BUBBLES), 16f, true));
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

    private Session watch(View root, Session source) {
        Session existing = sessionFor(root);
        if (existing != null && !existing.closed) return existing;
        if (root == null) return null;
        Session session = new Session(root, source);
        sessions.put(root, new WeakReference<>(session));
        session.attach();
        return session;
    }

    private void ensureDrawHook(View view) {
        try {
            Method method = view.getClass().getMethod("draw", Canvas.class);
            if (!drawHooks.add(method)) return;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (SharedGlassBackdrop.shouldSkip((View) param.thisObject)) param.setResult(null);
                }
            });
        } catch (Throwable error) {
            report("draw-hook", "capture exclusion unavailable: " + error);
        }
    }

    private void report(String key, String message) {
        if (reported.add(key)) XposedBridge.log("[LiquidGlass/App] " + message);
    }

    private static View findByName(View root, String name, String pkg) {
        int id = root.getResources().getIdentifier(name, "id", pkg);
        return id == 0 ? null : root.findViewById(id);
    }

    private final class Session implements ViewTreeObserver.OnGlobalLayoutListener,
            ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        private final WeakReference<View> root;
        private final WeakReference<Session> source;
        private final SharedGlassBackdrop recording;
        private final WeakHashMap<View, Binding> bindings = new WeakHashMap<>();
        private long lastScan;
        private boolean closed;
        private GlassSpec cachedMaterial;
        private long materialAt;

        Session(View root, Session source) {
            this.root = new WeakReference<>(root);
            this.source = new WeakReference<>(source);
            this.recording = new SharedGlassBackdrop(root);
        }

        SharedGlassBackdrop backdrop() {
            Session parent = source.get();
            return parent != null && !parent.closed ? parent.recording : recording;
        }

        GlassSpec resolvedMaterial() {
            View view = root.get();
            if (view == null) return null;
            long now = SystemClock.uptimeMillis();
            if (cachedMaterial == null || now - materialAt >= 1000) {
                cachedMaterial = GlassRenderer.resolveFor(view.getContext(),
                        LiquidGlassSettings.MATERIAL.key(), DesignUtils.isNightMode(view.getContext()),
                        0, DesignUtils.getPrimaryColor(), LiquidGlassSettings.opacityPercent(prefs));
                materialAt = now;
            }
            return cachedMaterial;
        }

        void attach() {
            View view = root.get();
            if (view == null) return;
            view.getViewTreeObserver().addOnGlobalLayoutListener(this);
            view.getViewTreeObserver().addOnPreDrawListener(this);
            view.addOnAttachStateChangeListener(this);
        }

        @Override public void onGlobalLayout() {
            if (SystemClock.uptimeMillis() - lastScan >= 300) scan();
        }

        @Override public boolean onPreDraw() {
            if (!bindings.isEmpty() && !closed && !SharedGlassBackdrop.isCapturing()) {
                backdrop().beginFrame();
                backdrop().request();
            }
            return true;
        }

        void scan() {
            if (closed || SharedGlassBackdrop.isCapturing()) return;
            View view = root.get();
            if (view == null) return;
            lastScan = SystemClock.uptimeMillis();
            try {
                for (Map.Entry<View, Binding> entry : new ArrayList<>(bindings.entrySet())) {
                    View target = entry.getKey();
                    if (target == null) continue;
                    Binding binding = entry.getValue();
                    if (!LiquidGlassSettings.isEnabled(prefs, binding.surface) || !target.isAttachedToWindow()) {
                        binding.restore(target);
                        bindings.remove(target);
                    } else if (target.getBackground() != binding.glass) {
                        // A native rebind wins. Capture its new background rather than restoring
                        // an old drawable over whatever WhatsApp just changed.
                        binding.restoreChildren();
                        bindings.remove(target);
                        bind(target, binding.surface, binding.nativeMask);
                    }
                }
                if (!LiquidGlassSettings.hasAppSurfaces(prefs)) return;
                walk(view, 0);
                if (!bindings.isEmpty()) recording.request();
            } catch (Throwable error) {
                report("scan-error", "surface scan skipped: " + error);
            }
        }

        private void walk(View view, int depth) {
            if (depth > 24 || view == null || GlassSurface.isGlassHost(view)) return;
            if (view.getBackground() instanceof GlassMaterialDrawable && !bindings.containsKey(view)) {
                GlassMaterialDrawable glass = (GlassMaterialDrawable) view.getBackground();
                if (LiquidGlassSettings.isEnabled(prefs, Surface.BUBBLES)) {
                    Binding binding = new Binding(view, glass.original(), Surface.BUBBLES, true);
                    binding.glass = glass;
                    bindings.put(view, binding);
                    ensureDrawHook(view);
                    SharedGlassBackdrop.exclude(view, true);
                } else {
                    glass.restoreCallback();
                    view.setBackground(glass.original());
                    SharedGlassBackdrop.exclude(view, false);
                }
            }
            String name = null;
            if (view.getId() != View.NO_ID && view.getId() != 0) {
                try { name = view.getResources().getResourceEntryName(view.getId()); }
                catch (android.content.res.Resources.NotFoundException ignored) { }
            }
            Surface surface = GlassSurfaceCatalog.surface(name);
            if (surface != null && LiquidGlassSettings.isEnabled(prefs, surface)) bind(view, surface, false);
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i), depth + 1);
            }
        }

        void bind(View view, Surface surface, boolean nativeMask) {
            if (view == null || bindings.containsKey(view) || view.getWidth() < 1 || view.getHeight() < 1
                    || view == root.get() || GlassSurface.isGlassHost(view)) return;
            try {
                Binding binding = new Binding(view, view.getBackground(), surface, nativeMask);
                binding.glass = new GlassMaterialDrawable(view, binding.original, this::backdrop,
                        () -> material(surface), GlassSurfaceCatalog.radiusDp(surface), nativeMask);
                // Ensure exclusions exist before making the surface eligible for capture.
                ensureDrawHook(view);
                bindings.put(view, binding);
                SharedGlassBackdrop.exclude(view, true);
                view.setBackgroundTintList(null);
                view.setBackground(binding.glass);
                view.setPadding(binding.left, binding.top, binding.right, binding.bottom);
                binding.clearFullBleed(view, 0);
                report("surface-" + surface, surface + " background installed; native layout retained");
                diagnosticTriggered();
            } catch (Throwable error) {
                Binding binding = bindings.remove(view);
                if (binding != null) binding.restore(view);
                report("bind-" + surface, surface + " unchanged: " + error);
            }
        }

        void close() {
            if (closed) return;
            closed = true;
            View view = root.get();
            if (view != null) {
                ViewTreeObserver observer = view.getViewTreeObserver();
                if (observer.isAlive()) {
                    observer.removeOnGlobalLayoutListener(this);
                    observer.removeOnPreDrawListener(this);
                }
                view.removeOnAttachStateChangeListener(this);
                sessions.remove(view);
            }
            for (Map.Entry<View, Binding> entry : new ArrayList<>(bindings.entrySet())) {
                if (entry.getKey() != null) entry.getValue().restore(entry.getKey());
            }
            bindings.clear();
            recording.release();
        }
        @Override public void onViewAttachedToWindow(View view) { }
        @Override public void onViewDetachedFromWindow(View view) { close(); }
    }

    private static final class BackgroundState {
        final Drawable background;
        final ColorStateList tint;
        final android.graphics.PorterDuff.Mode tintMode;
        BackgroundState(View view) {
            background = view.getBackground();
            tint = view.getBackgroundTintList();
            tintMode = view.getBackgroundTintMode();
        }
    }

    private static final class Binding {
        final Drawable original;
        final Surface surface;
        final boolean nativeMask;
        final BackgroundState state;
        final int left, top, right, bottom;
        final WeakHashMap<View, BackgroundState> cleared = new WeakHashMap<>();
        GlassMaterialDrawable glass;
        Binding(View view, Drawable original, Surface surface, boolean nativeMask) {
            this.original = original;
            this.surface = surface;
            this.nativeMask = nativeMask;
            state = new BackgroundState(view);
            left = view.getPaddingLeft(); top = view.getPaddingTop();
            right = view.getPaddingRight(); bottom = view.getPaddingBottom();
        }
        void clearFullBleed(View view, int depth) {
            if (!(view instanceof ViewGroup) || depth > 1) return;
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                // Small controls, badges, avatars and other glass surfaces keep their background.
                if (child.getBackground() != null && !(child.getBackground() instanceof GlassMaterialDrawable)
                        && child.getWidth() >= view.getWidth() * 0.90f
                        && child.getHeight() >= view.getHeight() * 0.90f) {
                    cleared.put(child, new BackgroundState(child));
                    child.setBackgroundTintList(null);
                    int l = child.getPaddingLeft(), t = child.getPaddingTop();
                    int r = child.getPaddingRight(), b = child.getPaddingBottom();
                    child.setBackground(null);
                    child.setPadding(l, t, r, b);
                    clearFullBleed(child, depth + 1);
                }
            }
        }
        void restoreChildren() {
            for (Map.Entry<View, BackgroundState> entry : cleared.entrySet()) {
                View child = entry.getKey();
                if (child == null || child.getBackground() != null) continue;
                int l = child.getPaddingLeft(), t = child.getPaddingTop();
                int r = child.getPaddingRight(), b = child.getPaddingBottom();
                child.setBackground(entry.getValue().background);
                child.setBackgroundTintMode(entry.getValue().tintMode);
                child.setBackgroundTintList(entry.getValue().tint);
                child.setPadding(l, t, r, b);
            }
            cleared.clear();
        }
        void restore(View view) {
            SharedGlassBackdrop.exclude(view, false);
            if (view.getBackground() == glass) {
                glass.restoreCallback();
                view.setBackground(original);
                view.setBackgroundTintMode(state.tintMode);
                view.setBackgroundTintList(state.tint);
                view.setPadding(left, top, right, bottom);
            }
            restoreChildren();
        }
    }

    @NonNull @Override public String getPluginName() { return "Liquid Glass: app surfaces"; }
}
