package com.waenhancer.xposed.features.customization;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.waenhancer.theme.BackdropSampler;
import com.waenhancer.theme.GlassRenderer;
import com.waenhancer.theme.GlassSpec;
import com.waenhancer.theme.LiquidLens;
import com.waenhancer.theme.LiquidMorph;
import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.devkit.Unobfuscator;
import com.waenhancer.xposed.utils.DesignUtils;
import com.waenhancer.xposed.utils.Utils;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.util.WeakHashMap;

import eightbitlab.com.blurview.BlurAlgorithm;
import eightbitlab.com.blurview.BlurView;
import eightbitlab.com.blurview.RenderEffectBlur;
import eightbitlab.com.blurview.RenderScriptBlur;

public class FloatingBottomBar extends Feature {

    private static final int PILL_SIDE_MARGIN_DP = 16;
    private static final int PILL_BOTTOM_MARGIN_DP = 22;
    private static final int SCROLL_BOTTOM_PADDING_DP = 126;
    private static final int FAB_VISIBLE_OFFSET_DP = 80;
    private static final float PILL_ELEVATION_DP = 12f;
    /** How far the morphing blob is pulled in from a tab's own edges. */
    private static final int MORPH_HORIZONTAL_INSET_DP = 8;
    private static final float PILL_TRANSLATION_Z_DP = 8f;

    /** The same two, for a lensed surface. See {@link #applyPillShadow}. */
    private static final float LENSED_ELEVATION_DP = 5f;
    private static final float LENSED_TRANSLATION_Z_DP = 2f;
    private static final WeakHashMap<View, Boolean> styledBottomBars = new WeakHashMap<>();
    private static final java.util.Set<Class<?>> hookedItemClasses = new java.util.HashSet<>();
    private static final WeakHashMap<View, Boolean> registeredScrollListeners = new WeakHashMap<>();
    private static final WeakHashMap<View, Float> targetTranslations = new WeakHashMap<>();
    private static final WeakHashMap<View, Integer> originalBottomPaddings = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> fabListeners = new WeakHashMap<>();
    /** Known homes of WhatsApp's FAB across builds; all are tried, none is required. */
    private static final String[] FAB_CLASS_CANDIDATES = {
            "com.whatsapp.wds.components.fab.WDSFab",
            "com.whatsapp.ui.wds.components.fab.WDSFab",
            "com.whatsapp.WaFab",
    };
    /**
     * How far above the pill the FAB is lifted in the z axis. The pill itself already sits at
     * {@code PILL_ELEVATION_DP + PILL_TRANSLATION_Z_DP}; without clearing that the FAB is drawn
     * underneath the bar and simply disappears behind it.
     */
    private static final float FAB_Z_CLEARANCE_DP = 8f;
    private static final WeakHashMap<View, Boolean> styledFabs = new WeakHashMap<>();
    private static final WeakHashMap<View, FrameLayout> glassHosts = new WeakHashMap<>();
    private static final WeakHashMap<View, BlurView> glassBlurViews = new WeakHashMap<>();
    /** The morphing blob under the selected tab, for the variants that have one. */
    private static final WeakHashMap<View, LiquidMorph> liquidMorphs = new WeakHashMap<>();
    /** One backdrop sampler per bar, so its throttle is per-surface rather than global. */
    private static final WeakHashMap<View, BackdropSampler> backdropSamplers = new WeakHashMap<>();
    /** Last colour sampled from behind each bar; 0 until the first sample lands. */
    private static final WeakHashMap<View, Integer> backdropColors = new WeakHashMap<>();
    private static boolean scrollHideEnabled = true;
    private static String scrollHideMode = "tabs";
    private static boolean glassEnabled = false;
    private static float glassOpacity = 35f;
    private static int glassFillColor = 0;
    private static int pillRadiusDp = 28;
    private static int pillSideMarginDp = PILL_SIDE_MARGIN_DP;
    private static int pillBottomMarginDp = PILL_BOTTOM_MARGIN_DP;
    private static int pillManualHeightDp = 64;
    private static boolean pillManualHeight = false;
    private static int fabVisibleOffsetDp = FAB_VISIBLE_OFFSET_DP;
    private static String fabMode = "default";
    private static SharedPreferences activePrefs;

    public FloatingBottomBar(@NonNull ClassLoader loader, @NonNull SharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        activePrefs = prefs;
        if (!prefs.getBoolean("floating_bottom_bar", false)) return;

        scrollHideMode = getPrefString(activePrefs, "floating_bottom_bar_scroll_hide_mode",
                prefs.getBoolean("floating_bottom_bar_scroll_hide", true) ? "tabs" : "off");
        scrollHideEnabled = !"off".equals(scrollHideMode);
        glassEnabled = prefs.getBoolean("floating_bottom_bar_glass", true);
        glassOpacity = normalized("floating_bottom_bar_glass_opacity");
        glassFillColor = getPrefColor(activePrefs, "floating_bottom_bar_fill_color", 0);
        pillRadiusDp = prefs.getBoolean("floating_bottom_bar_fully_rounded", false)
                ? 1000 : Math.round(normalized("floating_bottom_bar_radius"));
        pillSideMarginDp = Math.round(normalized("floating_bottom_bar_horizontal_margin"));
        pillBottomMarginDp = Math.round(normalized("floating_bottom_bar_bottom_margin"));
        pillManualHeight = "manual".equals(getPrefString(
                prefs, "floating_bottom_bar_height_mode", "automatic"));
        pillManualHeightDp = Math.round(normalized("floating_bottom_bar_manual_height"));
        fabVisibleOffsetDp = Math.round(normalized("floating_bottom_bar_fab_offset"));
        fabMode = getPrefString(activePrefs, "floating_bottom_bar_fab_mode", "default");

        // Emitted before anything can go wrong, so its absence is itself a result: no line means
        // this module never ran in this process, which is a different problem from any decision
        // the glass engine could make afterwards.
        XposedBridge.log("glass boot: glassEnabled=" + glassEnabled
                + " variant=" + getPrefString(activePrefs, "floating_bottom_bar_glass_variant",
                        GlassSpec.Variant.STABLE.key())
                + " opacity=" + glassOpacity
                + " lensSupported=" + LiquidLens.isSupported());


        // Hook the tab frame container
        Class<?> loadTabFrameClass = Unobfuscator.loadTabFrameClass(classLoader);
        XposedBridge.hookAllMethods(loadTabFrameClass, "setVisibility", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    View view = (View) param.thisObject;
                    if (view == null) return;
                    int visibility = (int) param.args[0];
                    View animTarget = getBarAnimationTarget(view);
                    if (animTarget != view && animTarget.getVisibility() != visibility) {
                        animTarget.setVisibility(visibility);
                    }
                } catch (Throwable ignored) {}
            }
        });

        XposedBridge.hookAllMethods(loadTabFrameClass, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                final View view = (View) param.thisObject;
                if (view == null) return;

                view.post(() -> {
                    try {
                        // Prevent duplicate initializations
                        if (styledBottomBars.containsKey(view)) return;
                        styledBottomBars.put(view, true);

                        // Hook the click listener on the BottomNavigationItemView class dynamically
                        ViewGroup menuView = findMenuView(view);
                        if (menuView != null && menuView.getChildCount() > 0) {
                            View child = menuView.getChildAt(0);
                            Class<?> itemViewClass = child.getClass();
                            
                            // Wrap existing listeners immediately
                            for (int i = 0; i < menuView.getChildCount(); i++) {
                                wrapOnClickListener(view, menuView.getChildAt(i));
                            }

                            synchronized (hookedItemClasses) {
                                if (!hookedItemClasses.contains(itemViewClass)) {
                                    hookedItemClasses.add(itemViewClass);
                                    XposedBridge.hookAllMethods(itemViewClass, "setOnClickListener", new XC_MethodHook() {
                                        @Override
                                        protected void beforeHookedMethod(MethodHookParam param2) throws Throwable {
                                            try {
                                                final View.OnClickListener originalListener = (View.OnClickListener) param2.args[0];
                                                if (originalListener == null) return;
                                                if (originalListener instanceof TabClickProxy) return;

                                                param2.args[0] = new TabClickProxy(null, originalListener);
                                            } catch (Throwable t) {
                                                XposedBridge.log("[WAEX-FBB] Error wrapping setOnClickListener: " + t);
                                            }
                                        }
                                    });
                                }
                            }
                        }

                        applyTabMetrics(view);

                        float density = view.getContext().getResources().getDisplayMetrics().density;

                        // Create rounded shape background matching the theme surface/card color
                        GradientDrawable shape = new GradientDrawable();
                        shape.setShape(GradientDrawable.RECTANGLE);
                        shape.setCornerRadius(pillRadiusDp * density);

                        boolean isNight = DesignUtils.isNightMode(view.getContext());
                        int bgColor = isNight ? 0xff1f2c34 : 0xffffffff;
                        if (glassFillColor != 0) {
                            bgColor = glassFillColor;
                        } else if (prefs.getBoolean("changecolor", false)) {
                            int customBg = DesignUtils.getPrimarySurfaceColor();
                            if (customBg != 0 && customBg != -1) {
                                bgColor = customBg;
                            }
                        }
                        
                        shape.setColor(bgColor);
                        // Subtle premium stroke matching host app's design
                        shape.setStroke(Math.max(1, (int) (0.6f * density)), isNight ? 0x18FFFFFF : 0x22000000);

                        // Prevent system tints from overriding our custom background shape
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                            view.setBackgroundTintList(null);
                        }

                        if (view instanceof ViewGroup) {
                            ((ViewGroup) view).setClipChildren(false);
                            ((ViewGroup) view).setClipToPadding(false);
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                            view.setClipToOutline(false);
                        }

                        if (glassEnabled) {
                            applyGlassmorphism(view, density);
                        } else {
                            view.setBackground(shape);
                            applyPillShadow(view, density);
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                                view.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
                            }
                        }

                        // Clear backgrounds of immediate children to prevent solid white rectangular overlays
                        makeChildrenTransparent(view);

                        applyBarVerticalMetrics(view, density);

                        // Attach LayoutChangeListener to enforce margins, overriding parent-forced layout passes
                        view.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                            private boolean isUpdating = false;

                            @Override
                            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                                if (isUpdating) return;
                                isUpdating = true;
                                try {
                                    // With glass on, the margins belong to the host that wraps
                                    // this bar; only the height is still the bar's own.
                                    ViewGroup.LayoutParams lp = v.getLayoutParams();
                                    if (!glassHosts.containsKey(v)
                                            && lp instanceof ViewGroup.MarginLayoutParams) {
                                        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                                        int marginSide = dp(density, pillSideMarginDp);
                                        int marginBottom = dp(density, pillBottomMarginDp);
                                        if (mlp.leftMargin != marginSide || mlp.rightMargin != marginSide || mlp.bottomMargin != marginBottom) {
                                            mlp.leftMargin = marginSide;
                                            mlp.rightMargin = marginSide;
                                            mlp.bottomMargin = marginBottom;
                                            v.setLayoutParams(mlp);
                                        }
                                    }
                                    applyBarHeight(v, density);
                                    refreshLiquid(v, density);
                                } finally {
                                    isUpdating = false;
                                }
                            }
                        });

                        // Initial layout params update to force immediate layout
                        ViewGroup.LayoutParams lp = view.getLayoutParams();
                        if (lp instanceof ViewGroup.MarginLayoutParams) {
                            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                            int marginSide = dp(density, pillSideMarginDp);
                            int marginBottom = dp(density, pillBottomMarginDp);
                            mlp.leftMargin = marginSide;
                            mlp.rightMargin = marginSide;
                            mlp.bottomMargin = marginBottom;
                            view.setLayoutParams(mlp);
                        }

                        // Make layouts overlap and hide adjacent divider lines
                        adjustLayoutOverlap(view, density);

                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                });
            }
        });

        // Hook RecyclerView's onAttachedToWindow to dynamically hook scroll events/padding on pages
        try {
            Class<?> recyclerViewClass = XposedHelpers.findClass("androidx.recyclerview.widget.RecyclerView", classLoader);
            
            XposedBridge.hookAllMethods(recyclerViewClass, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    final View rv = (View) param.thisObject;
                    if (rv == null) return;
                    rv.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                        private boolean hasSetPadding = false;
                        @Override
                        public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                                   int oldLeft, int oldTop, int oldRight, int oldBottom) {
                            try {
                                if (hasSetPadding) return;
                                if (v.getHeight() > 0 && v.getWidth() > 0) {
                                    if ("all".equals(scrollHideMode) || isMainTabScrollable(v)) {
                                        View bottomNav = findBottomNavForScrollable(v);
                                        if (bottomNav != null) {
                                            float density = v.getContext().getResources().getDisplayMetrics().density;
                                            int paddingBottom = dp(density, SCROLL_BOTTOM_PADDING_DP);
                                            prepareScrollableBottomPadding(v, paddingBottom);
                                            hasSetPadding = true;
                                        }
                                    } else {
                                        restoreOriginalBottomPadding(v);
                                        hasSetPadding = true;
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
                }
            });

            // Hook dispatchOnScrolled candidate methods in RecyclerView
            java.util.List<java.lang.reflect.Method> candidateMethods = new java.util.ArrayList<>();
            for (java.lang.reflect.Method m : recyclerViewClass.getDeclaredMethods()) {
                Class<?>[] paramTypes = m.getParameterTypes();
                if (paramTypes.length == 2 && paramTypes[0] == int.class && paramTypes[1] == int.class && m.getReturnType() == void.class) {
                    String name = m.getName();
                    if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                    if (name.equals("scrollBy") || name.equals("scrollTo") || name.equals("onMeasure") || name.equals("onSizeChanged") || name.equals("onLayout") || name.equals("setMeasuredDimension")) {
                        continue;
                    }
                    candidateMethods.add(m);
                }
            }

            for (java.lang.reflect.Method m : candidateMethods) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args == null || param.args.length < 2) return;
                        View rv = (View) param.thisObject;
                        int dy = (int) param.args[1];
                        handleRecyclerViewScrolled(rv, dy);
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        // Keep every FAB above the pill and in whatever mode the user picked.
        //
        // This used to hang off a single hard-coded class name. WhatsApp moved WDSFab to a
        // different package, findClass threw, and the whole block - Hidden, Minimal, the
        // elevation that keeps the button above the bar - was skipped, which is why none of the
        // FAB controls did anything and the button sat behind the pill. The constructor hook is
        // now best-effort across the known names, and the styling itself no longer depends on it:
        // the same work is redone from the view-tree sweep, which finds the button by its class
        // hierarchy rather than by one exact name.
        for (String candidate : FAB_CLASS_CANDIDATES) {
            try {
                Class<?> fabClass = XposedHelpers.findClass(candidate, classLoader);
                XposedBridge.hookAllConstructors(fabClass, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        final View fab = (View) param.thisObject;
                        if (fab == null) return;
                        fab.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                            @Override
                            public void onViewAttachedToWindow(View v) {
                                if (fabListeners.containsKey(v)) return;
                                fabListeners.put(v, true);
                                v.post(() -> applyFabStyle(v));
                                v.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                                    @Override
                                    public void onLayoutChange(View view, int l, int t, int r,
                                                               int b, int ol, int ot, int or,
                                                               int ob) {
                                        applyFabStyle(view);
                                    }
                                });
                            }

                            @Override
                            public void onViewDetachedFromWindow(View v) {}
                        });
                    }
                });
            } catch (Throwable ignored) {
                // Expected for every name this build of WhatsApp does not use.
            }
        }

        // Hook ViewPager page dispatch events directly to intercept page changes
        try {
            Class<?> viewPagerClass = XposedHelpers.findClass("androidx.viewpager.widget.ViewPager", classLoader);
            XposedBridge.hookAllMethods(viewPagerClass, "dispatchOnPageSelected", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        int position = (int) param.args[0];
                        View viewPager = (View) param.thisObject;
                        ViewGroup root = getRootLayout(viewPager);
                        View bottomNav = findBottomNavInRoot(root);
                        if (bottomNav != null) {
                            int tabId = getTabIdForIndex(bottomNav, position);
                            handleTabSelectionChanged(bottomNav, tabId);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("[WAEX-FBB] Error in dispatchOnPageSelected: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[WAEX-FBB] Error hooking dispatchOnPageSelected: " + t);
        }

        // Hook tab selection calls on BottomNavigationView directly to intercept clicks and manual selections
        try {
            XposedBridge.hookAllMethods(loadTabFrameClass, "setSelectedItemId", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        int tabId = (int) param.args[0];
                        View bottomNav = (View) param.thisObject;
                        handleTabSelectionChanged(bottomNav, tabId);
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[WAEX-FBB] Error hooking setSelectedItemId: " + t);
        }

        // Hook the tab item selection listener of BottomNavigationView to capture tab taps/clicks
        try {
            java.lang.reflect.Method targetSetListenerMethod = null;
            for (java.lang.reflect.Method m : loadTabFrameClass.getMethods()) {
                String name = m.getName();
                if ((name.equals("setOnItemSelectedListener") || name.equals("setOnNavigationItemSelectedListener")) 
                    && m.getParameterCount() == 1) {
                    targetSetListenerMethod = m;
                    break;
                }
            }

            if (targetSetListenerMethod != null) {
                final Class<?> listenerInterface = targetSetListenerMethod.getParameterTypes()[0];
                
                XposedBridge.hookMethod(targetSetListenerMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            final Object originalListener = param.args[0];
                            if (originalListener == null) return;
                            if (java.lang.reflect.Proxy.isProxyClass(originalListener.getClass())) return;
                            
                            final View bottomNav = (View) param.thisObject;
                            
                            Object listenerProxy = java.lang.reflect.Proxy.newProxyInstance(
                                listenerInterface.getClassLoader(),
                                new Class<?>[]{listenerInterface},
                                new java.lang.reflect.InvocationHandler() {
                                    @Override
                                    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
                                        String methodName = method.getName();
                                        if ("equals".equals(methodName)) {
                                            return proxy == args[0];
                                        }
                                        if ("hashCode".equals(methodName)) {
                                            return System.identityHashCode(proxy);
                                        }
                                        if ("toString".equals(methodName)) {
                                            return "NavigationBarOnItemSelectedListenerProxy@" + Integer.toHexString(System.identityHashCode(proxy));
                                        }
                                        
                                        if (args != null && args.length == 1 && args[0] instanceof android.view.MenuItem) {
                                            android.view.MenuItem item = (android.view.MenuItem) args[0];
                                            int tabId = item.getItemId();
                                            handleTabSelectionChanged(bottomNav, tabId);
                                        }
                                        
                                        return method.invoke(originalListener, args);
                                    }
                                }
                            );
                            
                            param.args[0] = listenerProxy;
                        } catch (Throwable t) {
                            XposedBridge.log("[WAEX-FBB] Error wrapping tab selection listener: " + t);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log("[WAEX-FBB] Error hooking item selection listener: " + t);
        }
    }

    /**
     * Applies the vertical padding and, when asked for, the manual pill height.
     *
     * <p>Padding alone never reached zero. A Material navigation bar carries a minimum height of
     * its own (and so does each tab item), so the pill bottomed out at that minimum no matter how
     * far the slider came down, leaving a band of dead space above and below the tabs. Clearing
     * those minimums is what lets the pill actually shrink to its content.</p>
     */
    private static void applyBarVerticalMetrics(View bar, float density) {
        com.waenhancer.config.BottomBarGeometry geometry = geometry(bar);
        int paddingVertical = geometry.verticalPaddingPx;

        bar.setMinimumHeight(0);
        bar.setPadding(bar.getPaddingLeft(), paddingVertical,
                bar.getPaddingRight(), paddingVertical);

        ViewGroup menu = findMenuView(bar);
        if (menu != null) {
            menu.setMinimumHeight(0);
            menu.setPadding(menu.getPaddingLeft(), 0, menu.getPaddingRight(), 0);
            for (int i = 0; i < menu.getChildCount(); i++) {
                View item = menu.getChildAt(i);
                item.setMinimumHeight(0);
                item.setPadding(item.getPaddingLeft(), 0, item.getPaddingRight(), 0);
                // A definite box for the item's own layout to work in. Left to itself it sizes
                // to a Material minimum the pill may be shorter than.
                ViewGroup.LayoutParams itemParams = item.getLayoutParams();
                if (itemParams != null && itemParams.height != geometry.contentHeightPx()) {
                    itemParams.height = geometry.contentHeightPx();
                    item.setLayoutParams(itemParams);
                }
            }
        }

        applyBarHeight(bar, density);
    }

    /**
     * Resolves the bar's vertical geometry from the current preferences.
     *
     * <p>The same call the editor's preview makes, so the two cannot disagree about how tall the
     * pill is or where its content sits.</p>
     */
    private static com.waenhancer.config.BottomBarGeometry geometry(View bar) {
        android.util.DisplayMetrics metrics =
                bar.getContext().getResources().getDisplayMetrics();
        float fontScale = bar.getContext().getResources().getConfiguration().fontScale;
        return com.waenhancer.config.BottomBarGeometry.resolve(
                pillManualHeight,
                pillManualHeightDp,
                Math.round(normalized("floating_bottom_bar_icon_size")),
                Math.round(normalized("floating_bottom_bar_icon_label_spacing")),
                normalized("floating_bottom_bar_text_size"),
                Math.round(normalized("floating_bottom_bar_padding_vertical")),
                metrics.density,
                fontScale <= 0f ? 1f : fontScale,
                measuredLabelHeight(bar));
    }

    /**
     * Height WhatsApp's own label actually occupies, or {@code 0} if it cannot be measured.
     *
     * <p>The geometry can estimate a line box from the text size, and the preview has to. Here
     * the real view is available, and it is worth asking: its font, its line spacing and its font
     * padding are all WhatsApp's, and an estimate a few pixels short leaves the label placed
     * below the room reserved for it and clipped away entirely.</p>
     */
    private static int measuredLabelHeight(View bar) {
        try {
            ViewGroup menu = findMenuView(bar);
            if (menu == null) return 0;
            for (int i = 0; i < menu.getChildCount(); i++) {
                TextView label = findFirstLabel(menu.getChildAt(i));
                if (label == null) continue;
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                        normalized("floating_bottom_bar_text_size"));
                int unspecified = View.MeasureSpec.makeMeasureSpec(
                        0, View.MeasureSpec.UNSPECIFIED);
                label.measure(unspecified, unspecified);
                int measured = label.getMeasuredHeight();
                if (measured > 0) return measured;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /**
     * Gives the bar an exact pixel height, in both height modes.
     *
     * <p>Automatic mode used to hand the bar {@code WRAP_CONTENT}. WhatsApp's tab frame is a
     * custom {@code ViewGroup} that does not implement {@code wrap_content}: its measure pass
     * falls through to {@code View.getDefaultSize}, which answers an {@code AT_MOST} spec with
     * the entire available height. The glass host then wrapped that, and the pill covered the
     * window. The height is computed by {@link com.waenhancer.config.BottomBarGeometry} instead,
     * and the bar is never asked to measure itself.</p>
     *
     * <p>Called again after the bar is re-parented into the overlay root: the move installs fresh
     * layout params, which is what silently discarded the height set at attach time.</p>
     */
    private static void applyBarHeight(View bar, float density) {
        ViewGroup.LayoutParams params = bar.getLayoutParams();
        if (params == null) return;
        int target = geometry(bar).pillHeightPx;
        if (params.height != target) {
            params.height = target;
            bar.setLayoutParams(params);
        }
    }

    private void makeChildrenTransparent(View view) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                child.setBackground(null);
                child.setBackgroundColor(0);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    child.setBackgroundTintList(null);
                }
                
                // Clear background drawables recursively for intermediate layouts/wrappers
                String className = child.getClass().getName();
                if (child instanceof ViewGroup && (className.contains("Frame") || className.contains("Linear") || className.contains("Relative") || className.contains("Menu"))) {
                    makeChildrenTransparent(child);
                }
            }
        }
    }

    private void adjustLayoutOverlap(final View bottomNav, final float density) {
        try {
            final ViewParent parent = bottomNav.getParent();
            if (!(parent instanceof ViewGroup)) return;
            final ViewGroup parentGroup = (ViewGroup) parent;

            makeContainerTransparent(parentGroup);

            // Walk up the hierarchy to find the root layout container (preferring the nested FrameLayout)
            ViewGroup root = null;
            if (parentGroup != null) {
                ViewParent grandparent = parentGroup.getParent();
                if (grandparent instanceof ViewGroup) {
                    ViewGroup conversationListViewHost = (ViewGroup) grandparent;
                    View nestedContent = conversationListViewHost.findViewById(android.R.id.content);
                    if (nestedContent instanceof ViewGroup) {
                        root = (ViewGroup) nestedContent;
                    }
                }
            }
            if (root == null) {
                root = getRootLayout(bottomNav);
            }
            if (root == null) {
                root = parentGroup; // Fallback
            }
            final ViewGroup rootLayout = root;

            // Find the ViewPager or ViewPager2 sibling/ancestor in rootLayout
            final View viewPager = findViewPager(rootLayout);
            if (viewPager == null) {
                return;
            }

            // 1. Hide dividers in the original parent before we move bottomNav
            hideAdjacentDividers(bottomNav, parentGroup, density);

            // 2. Ensure viewPager bottom margin is 0
            viewPager.post(() -> {
                try {
                    ViewGroup.LayoutParams lp = viewPager.getLayoutParams();
                    if (lp instanceof ViewGroup.MarginLayoutParams) {
                        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                        if (mlp.bottomMargin != 0) {
                            mlp.bottomMargin = 0;
                            viewPager.setLayoutParams(mlp);
                        }
                    }
                } catch (Throwable ignored) {}
            });

            // 3. Move bottomNav out of WhatsApp's full-width bottom_nav_container.
            // That container owns the solid rectangle; the pill itself must be the overlay.
            parentGroup.post(() -> {
                try {
                    ViewGroup targetRoot = findBottomOverlayRoot(bottomNav, parentGroup, rootLayout);
                    if (targetRoot == null) targetRoot = rootLayout;
                    if (targetRoot == null) return;
                    ViewParent currentParent = bottomNav.getParent();
                    if (currentParent instanceof ViewGroup) {
                        ((ViewGroup) currentParent).removeView(bottomNav);
                        if (currentParent != targetRoot) {
                            collapseOldBottomNavContainer((ViewGroup) currentParent);
                        }
                    }

                    int marginSide = dp(density, pillSideMarginDp);
                    int marginBottom = dp(density, pillBottomMarginDp);
                        ViewGroup.LayoutParams newLp = null;

                        if (targetRoot instanceof android.widget.FrameLayout) {
                            android.widget.FrameLayout.LayoutParams flp = new android.widget.FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            );
                            flp.gravity = android.view.Gravity.BOTTOM;
                            newLp = flp;
                        } else if (targetRoot.getClass().getName().contains("CoordinatorLayout")) {
                            try {
                                Class<?> clLpClass = Class.forName("androidx.coordinatorlayout.widget.CoordinatorLayout$LayoutParams");
                                java.lang.reflect.Constructor<?> ctor = clLpClass.getConstructor(int.class, int.class);
                                Object clLp = ctor.newInstance(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                                java.lang.reflect.Field gravityField = clLpClass.getField("gravity");
                                gravityField.set(clLp, android.view.Gravity.BOTTOM);
                                newLp = (ViewGroup.LayoutParams) clLp;
                            } catch (Throwable ignored) {}
                        } else if (targetRoot instanceof android.widget.RelativeLayout) {
                            android.widget.RelativeLayout.LayoutParams rlp = new android.widget.RelativeLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            );
                            rlp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM);
                            newLp = rlp;
                        } else if (targetRoot.getClass().getName().contains("ConstraintLayout")) {
                            try {
                                Class<?> clLpClass = Class.forName("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams");
                                java.lang.reflect.Constructor<?> ctor = clLpClass.getConstructor(int.class, int.class);
                                Object clLp = ctor.newInstance(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                                java.lang.reflect.Field bottomToBottomField = clLpClass.getField("bottomToBottom");
                                bottomToBottomField.set(clLp, 0); // 0 is PARENT_ID
                                java.lang.reflect.Field leftToLeftField = clLpClass.getField("leftToLeft");
                                leftToLeftField.set(clLp, 0);
                                java.lang.reflect.Field rightToRightField = clLpClass.getField("rightToRight");
                                rightToRightField.set(clLp, 0);
                                newLp = (ViewGroup.LayoutParams) clLp;
                            } catch (Throwable ignored) {}
                        }

                        if (newLp == null) {
                            newLp = new ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        }

                        if (newLp instanceof ViewGroup.MarginLayoutParams) {
                            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) newLp;
                            mlp.leftMargin = marginSide;
                            mlp.rightMargin = marginSide;
                            mlp.bottomMargin = marginBottom;
                        }

                    View barOverlay;
                    if (glassEnabled) {
                        barOverlay = installGlassHost(targetRoot, rootLayout, bottomNav, newLp, density);
                    } else {
                        targetRoot.addView(bottomNav, newLp);
                        barOverlay = bottomNav;
                    }

                    targetRoot.setClipChildren(false);
                    targetRoot.setClipToPadding(false);
                    ViewParent gp = targetRoot.getParent();
                    if (gp instanceof ViewGroup) {
                        ((ViewGroup) gp).setClipChildren(false);
                        ((ViewGroup) gp).setClipToPadding(false);
                    }

                    barOverlay.bringToFront();
                    applyPillShadow(barOverlay, density);
                    bottomNav.bringToFront();

                    final View animTarget = getBarAnimationTarget(bottomNav);
                    final ViewGroup finalParentGroup = parentGroup;
                    bottomNav.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            try {
                                int targetVisibility = View.GONE;
                                if (bottomNav.getVisibility() == View.VISIBLE && finalParentGroup.isShown()) {
                                    targetVisibility = View.VISIBLE;
                                    View convHost = findConversationViewHost(bottomNav);
                                    if (convHost != null && convHost.isShown()) {
                                        targetVisibility = View.GONE;
                                    }
                                }
                                if (animTarget.getVisibility() != targetVisibility) {
                                    animTarget.setVisibility(targetVisibility);
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            });

            // 4. Update inner scroll paddings and add scroll-to-hide listeners
            viewPager.post(() -> {
                try {
                    int paddingBottom = dp(density, SCROLL_BOTTOM_PADDING_DP);
                    setBottomPaddingAndScrollListeners(viewPager, paddingBottom, bottomNav);
                    
                    int currentItem = (int) XposedHelpers.callMethod(viewPager, "getCurrentItem");
                    int tabId = getTabIdForIndex(bottomNav, currentItem);
                    if (tabId == 1000 || tabId == 1100) {
                        handleTabSelectionChanged(bottomNav, tabId);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            });

            // 5. Style the FABs so they float above the pill in the mode the user picked.
            rootLayout.postDelayed(() -> {
                try {
                    for (View fab : findFabs(rootLayout)) {
                        applyFabStyle(fab);
                    }
                } catch (Throwable ignored) {}
            }, 500);

        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static View findViewPager(ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            String name = child.getClass().getName();
            // Match custom TabsPager or traditional ViewPager
            if (name.toLowerCase().contains("pager") || name.equals("androidx.viewpager.widget.ViewPager")) {
                return child;
            }
            if (child instanceof ViewGroup) {
                View vp = findViewPager((ViewGroup) child);
                if (vp != null) return vp;
            }
        }
        return null;
    }

    private void setBottomPaddingAndScrollListeners(View view, int paddingBottom, final View bottomNav) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (isScrollableClass(child.getClass())) {
                    final View scrollView = child;
                    scrollView.post(() -> {
                        try {
                            if (!"all".equals(scrollHideMode)
                                    && !isMainTabScrollable(scrollView)) {
                                restoreOriginalBottomPadding(scrollView);
                                return;
                            }
                            prepareScrollableBottomPadding(scrollView, paddingBottom);

                            String scrollClassName = scrollView.getClass().getName();
                            if (scrollClassName.contains("ScrollView") && scrollView instanceof ViewGroup) {
                                ViewGroup vg = (ViewGroup) scrollView;
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                    if (!registeredScrollListeners.containsKey(vg)) {
                                        vg.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                                            onViewScrolled(bottomNav, scrollY - oldScrollY);
                                        });
                                        registeredScrollListeners.put(vg, true);
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    });
                } else if (child instanceof ViewGroup) {
                    setBottomPaddingAndScrollListeners(child, paddingBottom, bottomNav);
                }
            }
        }
    }

    private static void prepareScrollableBottomPadding(View scrollView, int paddingBottom) {
        if (!originalBottomPaddings.containsKey(scrollView)) {
            originalBottomPaddings.put(scrollView, scrollView.getPaddingBottom());
        }
        scrollView.setPadding(
            scrollView.getPaddingLeft(),
            scrollView.getPaddingTop(),
            scrollView.getPaddingRight(),
            paddingBottom
        );
        if (scrollView instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) scrollView;
            vg.setClipToPadding(false);
        }
    }

    private static void restoreOriginalBottomPadding(View scrollView) {
        Integer originalBottom = originalBottomPaddings.get(scrollView);
        if (originalBottom == null) return;
        if (scrollView.getPaddingBottom() == originalBottom) return;
        scrollView.setPadding(
            scrollView.getPaddingLeft(),
            scrollView.getPaddingTop(),
            scrollView.getPaddingRight(),
            originalBottom
        );
    }

    private static void handleRecyclerViewScrolled(View rv, int dy) {
        try {
            if (Math.abs(dy) > 50000) return; // Ignore layout measureSpec triggers (e.g. 1073743008)
            if (Math.abs(dy) < 5) return;
            if (!rv.isShown()) return; // Ignore background scrolls
            if (!"all".equals(scrollHideMode) && !isMainTabScrollable(rv)) return;

            View bottomNav = findBottomNavForScrollable(rv);
            if (bottomNav == null) return;
            onViewScrolled(bottomNav, dy);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static void onViewScrolled(View bottomNav, int dy) {
        if (bottomNav == null) return;
        if (isMetaAiTabActive(bottomNav)) return;
        
        View barTarget = getBarAnimationTarget(bottomNav);
        if (!barTarget.isShown()) return; // Do not animate if bottom bar is hidden on screen
        
        float density = bottomNav.getContext().getResources().getDisplayMetrics().density;
        
        // Use cached preference to prevent disk I/O in hot scroll path
        if (!scrollHideEnabled) {
            Float lastTarget = targetTranslations.get(barTarget);
            if (lastTarget == null || lastTarget != 0f) {
                targetTranslations.put(barTarget, 0f);
                barTarget.animate().translationY(0).setDuration(200).start();
                animateFabs(bottomNav, false, density);
            }
            return;
        }

        if (Math.abs(dy) < 5) return; // Skip minor/jitter scroll actions

        float targetTranslationY;
        int height = barTarget.getHeight();
        if (height <= 0) {
            height = bottomNav.getHeight();
        }
        if (height <= 0) {
            height = (int) (80 * density);
        }

        if (dy > 0) {
            targetTranslationY = height + (24 * density);
        } else {
            targetTranslationY = 0;
        }

        Float lastTarget = targetTranslations.get(barTarget);
        if (lastTarget != null && lastTarget == targetTranslationY) {
            // Already animating to this target, avoid thrashing
            return;
        }
        targetTranslations.put(barTarget, targetTranslationY);

        barTarget.animate()
            .translationY(targetTranslationY)
            .setDuration(250)
            .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
            .start();
        // Animate FABs in sync
        animateFabs(bottomNav, dy > 0, density);
    }

    private static void animateFabs(View bottomNav, boolean hide, float density) {
        try {
            ViewGroup root = getRootLayout(bottomNav);
            if (root == null) return;
            
            java.util.List<View> fabs = findFabs(root);
            for (View fab : fabs) {
                // A FAB created after the initial sweep reaches us here first; decorate it now so
                // it is already in the right mode and above the pill when it animates.
                if (!decorateFab(fab)) continue;
                float targetTranslationY = hide ? 0f : getFabVisibleTranslation(density);
                Float lastFabTarget = targetTranslations.get(fab);
                if (lastFabTarget != null && lastFabTarget == targetTranslationY) {
                    continue;
                }
                targetTranslations.put(fab, targetTranslationY);
                fab.animate()
                    .translationY(targetTranslationY)
                    .setDuration(250)
                    .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                    .start();
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Applies the FAB mode, the z clearance that keeps the button above the pill, and the
     * vertical offset - everything the FAB controls promise.
     *
     * <p>One routine, called from both the constructor hook and the view-tree sweep, so a build
     * of WhatsApp whose FAB class we cannot name still gets the full treatment.</p>
     */
    private static void applyFabStyle(View fab) {
        if (!decorateFab(fab)) return;
        try {
            float density = fab.getResources().getDisplayMetrics().density;
            Float pending = targetTranslations.get(fab);
            float target = pending != null ? pending : getFabVisibleTranslation(density);
            if (fab.getTranslationY() != target) fab.setTranslationY(target);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Applies the FAB mode and the z clearance, leaving the vertical position alone.
     *
     * <p>Split out so the scroll animation can guarantee a newly-created button is in the right
     * mode and above the pill without fighting the translation it is about to animate.</p>
     *
     * @return false when this FAB is hidden and needs no further work
     */
    private static boolean decorateFab(View fab) {
        if (fab == null) return false;
        try {
            if ("hidden".equals(fabMode)) {
                if (fab.getVisibility() != View.GONE) fab.setVisibility(View.GONE);
                return false;
            }
            if (fab.getVisibility() == View.GONE) fab.setVisibility(View.VISIBLE);

            float density = fab.getResources().getDisplayMetrics().density;
            // Rebuilding the minimal background on every layout pass would allocate a drawable
            // per frame while the list scrolls.
            if ("minimal".equals(fabMode) && !styledFabs.containsKey(fab)) {
                styledFabs.put(fab, true);
                applyMinimalFab(fab);
            }

            fab.setElevation((PILL_ELEVATION_DP + FAB_Z_CLEARANCE_DP) * density);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                fab.setTranslationZ((PILL_TRANSLATION_Z_DP + FAB_Z_CLEARANCE_DP) * density);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static java.util.List<View> findFabs(ViewGroup root) {
        java.util.List<View> fabs = new java.util.ArrayList<>();
        findFabsRecursive(root, fabs);
        return fabs;
    }

    private static void findFabsRecursive(View view, java.util.List<View> fabs) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (isFab(child)) {
                    fabs.add(child);
                } else if (child instanceof ViewGroup) {
                    findFabsRecursive(child, fabs);
                }
            }
        }
    }

    /**
     * Recognises a FAB by its class hierarchy rather than by its own class name, so a subclass
     * WhatsApp renamed still matches through the superclass it extends.
     */
    private static boolean isFab(View view) {
        for (Class<?> clazz = view.getClass(); clazz != null && clazz != View.class;
                clazz = clazz.getSuperclass()) {
            String name = clazz.getName();
            if (name.contains("WDSFab") || name.contains("FloatingActionButton")
                    || name.endsWith("Fab")) {
                return true;
            }
        }
        return false;
    }

    private void hideAdjacentDividers(View bottomNav, ViewGroup originalParent, float density) {
        try {
            // Hide dividers inside the tab frame container itself
            if (bottomNav instanceof ViewGroup) {
                ViewGroup container = (ViewGroup) bottomNav;
                for (int i = 0; i < container.getChildCount(); i++) {
                    View child = container.getChildAt(i);
                    int height = child.getHeight() > 0 ? child.getHeight() : (child.getLayoutParams() != null ? child.getLayoutParams().height : 0);
                    if (height > 0 && height <= (int) (4 * density)) {
                        child.setVisibility(View.GONE);
                    }
                }
            }
            
            // Hide dividers inside the original parent layout
            if (originalParent != null) {
                originalParent.setBackground(null);
                originalParent.setBackgroundColor(0);
                
                for (int i = 0; i < originalParent.getChildCount(); i++) {
                    View child = originalParent.getChildAt(i);
                    if (child != bottomNav) {
                        int height = child.getHeight() > 0 ? child.getHeight() : (child.getLayoutParams() != null ? child.getLayoutParams().height : 0);
                        if (height > 0 && height <= (int) (4 * density)) {
                            child.setVisibility(View.GONE);
                        }
                    }
                }
                
                // Hide dividers inside the original grandparent layout
                ViewParent grandparent = originalParent.getParent();
                if (grandparent instanceof ViewGroup) {
                    ViewGroup grandGroup = (ViewGroup) grandparent;
                    for (int i = 0; i < grandGroup.getChildCount(); i++) {
                        View child = grandGroup.getChildAt(i);
                        if (child != originalParent) {
                            int height = child.getHeight() > 0 ? child.getHeight() : (child.getLayoutParams() != null ? child.getLayoutParams().height : 0);
                            if (height > 0 && height <= (int) (4 * density)) {
                                child.setVisibility(View.GONE);
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static boolean isDescendantOfTabsPager(View view) {
        ViewParent parent = view.getParent();
        while (parent != null) {
            String name = parent.getClass().getName();
            if (name.contains("TabsPager") || name.toLowerCase().contains("pager") || name.equals("androidx.viewpager.widget.ViewPager")) {
                return true;
            }
            if (parent instanceof View) {
                parent = ((View) parent).getParent();
            } else {
                break;
            }
        }
        return false;
    }

    private static ViewGroup getRootLayout(View view) {
        View current = view;
        ViewGroup root = null;
        while (current != null) {
            if (current instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) current;
                String name = vg.getClass().getName();
                if (name.contains("CoordinatorLayout") || 
                    name.contains("ConstraintLayout") || 
                    name.contains("RelativeLayout") ||
                    vg.getId() == android.R.id.content ||
                    name.endsWith("DecorView")) {
                    root = vg;
                    if (vg.getId() == android.R.id.content || name.endsWith("DecorView")) {
                        break;
                    }
                }
            }
            ViewParent next = current.getParent();
            if (next instanceof View) {
                current = (View) next;
            } else {
                break;
            }
        }
        return root;
    }

    private static View findBottomNavInRoot(ViewGroup root) {
        if (root == null) return null;
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (styledBottomBars.containsKey(child)) {
                return child;
            }
            if (child instanceof ViewGroup) {
                View nested = findBottomNavInRoot((ViewGroup) child);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static View findBottomNavForScrollable(View scrollable) {
        ViewGroup rootLayout = getRootLayout(scrollable);
        View bottomNav = findBottomNavInRoot(rootLayout);
        if (bottomNav != null) return bottomNav;

        View rootView = scrollable != null ? scrollable.getRootView() : null;
        if (rootView instanceof ViewGroup) {
            bottomNav = findBottomNavInRoot((ViewGroup) rootView);
            if (bottomNav != null) return bottomNav;
        }

        return null;
    }

    private static boolean isScrollableClass(Class<?> clazz) {
        while (clazz != null && clazz != Object.class) {
            String name = clazz.getName();
            if (name.equals("androidx.recyclerview.widget.RecyclerView") || 
                name.equals("android.widget.ScrollView") ||
                name.contains("RecyclerView") || 
                name.contains("ScrollView") ||
                name.equals("android.widget.AbsListView") ||
                name.contains("ListView")) {
                return true;
            }
            clazz = clazz.getSuperclass();
        }
        return false;
    }

    private static boolean isInsideConversation(View view) {
        ViewParent parent = view.getParent();
        while (parent != null) {
            if (parent instanceof View) {
                View v = (View) parent;
                if (v.getId() != View.NO_ID) {
                    try {
                        String entryName = v.getResources().getResourceEntryName(v.getId());
                        if ("conversation_view_host".equals(entryName)) {
                            return true;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            if (parent instanceof View) {
                parent = ((View) parent).getParent();
            } else {
                break;
            }
        }
        return false;
    }

    private static boolean isMainTabScrollable(View view) {
        if (view == null) return false;

        if (!isScrollableClass(view.getClass())) return false;
        if (isInsideConversation(view)) return false;

        boolean isDescendant = isDescendantOfTabsPager(view);
        boolean isLarge = isLargeVerticalScrollable(view);
        
        // If it's a descendant of TabsPager and it is large vertical scrollable, it is a main tab list!
        if (isDescendant && isLarge) {
            return true;
        }

        // Fallback checks
        try {
            if (view.getId() != View.NO_ID) {
                String entryName = view.getResources().getResourceEntryName(view.getId());
                if ("list".equalsIgnoreCase(entryName) && isLarge) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        String className = view.getClass().getName();
        if ((className.contains("WDSList") || className.contains("ObservableRecyclerView") || className.contains("CallsHistory")) && isLarge) {
            return true;
        }

        return false;
    }

    private static boolean isLargeVerticalScrollable(View view) {
        int height = view.getHeight();
        int width = view.getWidth();
        float density = view.getContext().getResources().getDisplayMetrics().density;

        // If view is already measured, use its measured size
        if (height > 0 && width > 0) {
            return height >= dp(density, 280) && width >= dp(density, 240);
        }

        // If not measured yet (e.g. during onAttachedToWindow), check layout parameters
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null) {
            boolean heightOk = (lp.height == ViewGroup.LayoutParams.MATCH_PARENT || 
                               lp.height >= dp(density, 280));
            boolean widthOk = (lp.width == ViewGroup.LayoutParams.MATCH_PARENT || 
                              lp.width >= dp(density, 240));
            return heightOk && widthOk;
        }

        return false;
    }

    private static int dp(float density, int value) {
        return (int) (value * density + 0.5f);
    }

    private static float getFabVisibleTranslation(float density) {
        return -dp(density, fabVisibleOffsetDp);
    }

    private static View getBarAnimationTarget(View bottomNav) {
        FrameLayout host = glassHosts.get(bottomNav);
        return host != null ? host : bottomNav;
    }

    private static void makeContainerTransparent(ViewGroup group) {
        if (group == null) return;
        group.setBackground(null);
        group.setBackgroundColor(0);
        group.setClipChildren(false);
        group.setClipToPadding(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            group.setBackgroundTintList(null);
        }
    }

    private static void collapseOldBottomNavContainer(ViewGroup group) {
        makeContainerTransparent(group);
        group.setPadding(0, 0, 0, 0);
        group.setMinimumHeight(0);
        ViewGroup.LayoutParams lp = group.getLayoutParams();
        if (lp != null) {
            lp.height = 0;
            group.setLayoutParams(lp);
        }
    }

    private static ViewGroup findBottomOverlayRoot(View bottomNav, ViewGroup parentGroup, ViewGroup fallbackRoot) {
        ViewParent parent = parentGroup.getParent();
        if (parent instanceof ViewGroup) {
            ViewGroup directParent = (ViewGroup) parent;
            if (!(directParent instanceof android.widget.LinearLayout)) {
                return directParent;
            }
        }
        ViewGroup root = getRootLayout(bottomNav);
        return root != null ? root : fallbackRoot;
    }

    private View installGlassHost(ViewGroup targetRoot, ViewGroup blurRoot, View bottomNav,
                                  ViewGroup.LayoutParams hostLp, float density) {
        try {
            removeGlassHost(bottomNav);

            android.content.Context ctx = bottomNav.getContext();
            FrameLayout host = new FrameLayout(ctx);
            host.setClipChildren(false);
            host.setClipToPadding(false);
            host.setBackground(createGlassOutlineShape(ctx, density));
            applyPillShadow(host, density);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                host.setClipToOutline(false);
                host.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
            }

            // With the lens active the shader owns the fill, the rim and the shape's own
            // antialiased coverage, so nothing else may paint them. A background drawable here
            // would be a flat wash with no detail in it, and the lens would be refracting that
            // instead of the backdrop — which is exactly why the previous build read as tinted
            // grey. An outline clip would hard-cut the edge the shader just feathered.
            GlassSpec hostSpec = glassSpec(ctx);
            boolean lensed = LiquidLens.isActiveFor(hostSpec);
            BlurView blurView = new BlurView(ctx);
            if (!lensed) {
                blurView.setBackground(createGlassShape(ctx, density, true));
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                blurView.setClipToOutline(!lensed);
                blurView.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
            }

            FrameLayout.LayoutParams blurLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
            host.addView(blurView, blurLp);

            // Between the blurred pane and the tabs: the blob has to be lit by the glass above
            // it and sit behind the icons, or it stops reading as something inside the surface.
            // Not under the lens. The blob is flat paint on a surface built entirely from
            // refraction, so it has no glass character at any opacity — dimming it only made it
            // a fainter sticker. WhatsApp's own capsule already marks the selected tab, so the
            // surface loses nothing by dropping it. Bringing it back means making it a second
            // shape in the lens's distance field so it refracts too, not painting it on top.
            if (hostSpec.morphing && !lensed) {
                LiquidMorph morph = new LiquidMorph(ctx);
                morph.applySpec(hostSpec);
                liquidMorphs.put(bottomNav, morph);
                host.addView(morph, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            } else {
                liquidMorphs.remove(bottomNav);
            }

            bottomNav.setBackground(createGlassShape(ctx, density, false));
            if (bottomNav instanceof ViewGroup) {
                ((ViewGroup) bottomNav).setClipChildren(false);
                ((ViewGroup) bottomNav).setClipToPadding(false);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                bottomNav.setBackgroundTintList(null);
                bottomNav.setClipToOutline(false);
                bottomNav.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
            }

            // An exact height in both modes: a wrap_content here is what the host wrapped into a
            // window-tall pill, because WhatsApp's tab frame answers a wrap_content measure with
            // all the space it is offered. See BottomBarGeometry.
            FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    geometry(bottomNav).pillHeightPx,
                    android.view.Gravity.BOTTOM
            );
            glassHosts.put(bottomNav, host);
            glassBlurViews.put(bottomNav, blurView);
            host.addView(bottomNav, navLp);

            setupBlurView(blurView, blurRoot != null ? blurRoot : targetRoot, bottomNav);
            targetRoot.addView(host, hostLp);
            XposedBridge.log("glass host installed: lensed=" + lensed
                    + " variant=" + getPrefString(activePrefs,
                            "floating_bottom_bar_glass_variant", GlassSpec.Variant.STABLE.key())
                    + " fill=#" + Integer.toHexString(hostSpec.fillColor)
                    + " blur=" + hostSpec.blurRadius);
            return host;
        } catch (Throwable t) {
            XposedBridge.log(t);
            glassHosts.remove(bottomNav);
            glassBlurViews.remove(bottomNav);
            targetRoot.addView(bottomNav, hostLp);
            bottomNav.setBackground(createGlassShape(bottomNav.getContext(), density, true));
            return bottomNav;
        }
    }

    private void setupBlurView(BlurView blurView, ViewGroup blurRoot, View bottomNav) {
        try {
            android.content.Context ctx = bottomNav.getContext();
            BlurAlgorithm algorithm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? new RenderEffectBlur()
                    : new RenderScriptBlur(ctx);
            android.graphics.drawable.Drawable windowBg = null;
            View rootView = bottomNav.getRootView();
            if (rootView != null) {
                windowBg = rootView.getBackground();
            }
            // Radius and overlay both come from the resolved spec: a device that fell back to
            // no-blur asks for radius 0 and pays for it with the higher fill opacity the spec
            // already worked out, instead of blurring at a radius it cannot afford.
            GlassSpec spec = glassSpec(ctx);
            float radius = GlassRenderer.blurRadius(spec);
            // The overlay is a flat colour composited over the blur before the lens ever runs.
            // Under a lens it halves the backdrop's contrast and leaves nothing to refract, so
            // the tint is handed to the shader instead and applied after the displacement.
            int overlay = LiquidLens.isActiveFor(spec) ? android.graphics.Color.TRANSPARENT
                    : spec.fillColor;
            blurView.setupWith(blurRoot, algorithm)
                    .setFrameClearDrawable(windowBg)
                    .setBlurRadius(Math.max(1f, radius))
                    .setOverlayColor(overlay);
            blurView.setBlurEnabled(radius > 0f);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static void removeGlassHost(View bottomNav) {
        try {
            FrameLayout host = glassHosts.remove(bottomNav);
            BlurView blurView = glassBlurViews.remove(bottomNav);
            LiquidLens.clear(blurView);
            liquidMorphs.remove(bottomNav);
            backdropSamplers.remove(bottomNav);
            backdropColors.remove(bottomNav);
            if (host != null) {
                ViewParent parent = host.getParent();
                if (parent instanceof ViewGroup) {
                    ((ViewGroup) parent).removeView(host);
                }
                host.removeView(bottomNav);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Applies a glassmorphism effect to the floating bottom bar.
     *
     * Uses a translucent theme-aware fill. In WhatsApp's hooked hierarchy a separate blur layer
     * is too easy to desync during tab/page transitions, which causes transparent or resizing pills.
     */
    private void applyGlassmorphism(final View bottomNav, final float density) {
        try {
            final android.content.Context ctx = bottomNav.getContext();
            if (ctx == null) return;

            bottomNav.setBackground(createGlassShape(ctx, density, true));
            if (bottomNav instanceof ViewGroup) {
                ((ViewGroup) bottomNav).setClipChildren(false);
                ((ViewGroup) bottomNav).setClipToPadding(false);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                bottomNav.setBackgroundTintList(null);
                bottomNav.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
                bottomNav.setClipToOutline(false);
            }
            applyPillShadow(bottomNav, density);

        } catch (Throwable e) {
            XposedBridge.log(e);
        }
    }

    /**
     * Raises the pill off the content behind it.
     *
     * <p>A lensed surface gets much less of this. 20dp of combined Z casts the wide, dark drop
     * shadow that belongs under an opaque floating object, and under a pane you are meant to be
     * looking <em>through</em> it reads as a solid slab sitting on the list — the "moulded bump"
     * effect. Glass separates itself from its backdrop by refracting it, so it needs only enough
     * shadow to keep its lower edge from merging into a dark list.</p>
     */
    private static void applyPillShadow(View view, float density) {
        boolean lensed = LiquidLens.isActiveFor(glassSpec(view.getContext()));
        float elevation = lensed ? LENSED_ELEVATION_DP : PILL_ELEVATION_DP;
        float translation = lensed ? LENSED_TRANSLATION_Z_DP : PILL_TRANSLATION_Z_DP;
        view.setElevation(elevation * density);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.setTranslationZ(translation * density);
        }
    }

    private static View findConversationViewHost(View bottomNav) {
        try {
            ViewGroup root = getRootLayout(bottomNav);
            if (root != null) {
                int resId = bottomNav.getContext().getResources().getIdentifier("conversation_view_host", "id", bottomNav.getContext().getPackageName());
                if (resId != 0 && resId != View.NO_ID) {
                    return root.findViewById(resId);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static float normalized(String key) {
        try {
            if (activePrefs != null) {
                return com.waenhancer.config.BottomBarPreferenceSchema.read(activePrefs, key);
            }
        } catch (Throwable ignored) {
        }
        // Never fall back to 0: for sizes such as manual_height (48-96) or minimal_fab_size
        // (32-72) that would collapse the element instead of degrading to a sane default.
        try {
            return com.waenhancer.config.BottomBarPreferenceSchema.spec(key).defaultValue;
        } catch (Throwable ignored) {
            return 0f;
        }
    }

    private static String getPrefString(SharedPreferences preferences,
                                        String key, String defaultValue) {
        try {
            Object raw = preferences.getAll().get(key);
            return raw == null ? defaultValue : String.valueOf(raw);
        } catch (Throwable ignored) {
            return defaultValue;
        }
    }

    private static float getPrefFloat(SharedPreferences prefs, String key, float defaultValue) {
        try {
            return prefs.getFloat(key, defaultValue);
        } catch (Throwable ignored) {
            try {
                return (float) prefs.getInt(key, (int) defaultValue);
            } catch (Throwable ignoredToo) {
                return defaultValue;
            }
        }
    }

    private static int getPrefColor(SharedPreferences prefs, String key, int defaultValue) {
        try {
            if (!prefs.contains(key)) return defaultValue;
            return prefs.getInt(key, defaultValue);
        } catch (Throwable ignored) {
            try {
                String value = prefs.getString(key, null);
                return value != null ? android.graphics.Color.parseColor(value) : defaultValue;
            } catch (Throwable ignoredToo) {
                return defaultValue;
            }
        }
    }

    /**
     * Resolves the Advanced Glass description for this bar.
     *
     * <p>The bar does not decide what glass looks like — {@link GlassSpec} does, and the settings
     * preview resolves the same spec from the same values. Anything computed here instead would
     * be a second definition of glass that drifts from the one the user previewed.</p>
     */
    private static GlassSpec glassSpec(android.content.Context ctx) {
        return GlassRenderer.resolveFor(ctx,
                getPrefString(activePrefs, "floating_bottom_bar_glass_variant",
                        GlassSpec.Variant.STABLE.key()),
                com.waenhancer.xposed.utils.DesignUtils.isNightMode(ctx),
                glassFillColor,
                com.waenhancer.xposed.utils.DesignUtils.getPrimaryColor(),
                glassOpacity);
    }

    /**
     * The glass description for one bar, as it looks over the backdrop actually behind it.
     *
     * <p>{@link #glassSpec} answers what the variant is; this answers what it currently looks
     * like. For the adaptive variants those differ, and they have to: a fill and an edge computed
     * once from the theme cannot react to the content scrolling under them, which is most of what
     * separates a surface that reads as glass from one that reads as paint.</p>
     */
    private static GlassSpec glassSpecFor(View bar) {
        GlassSpec spec = glassSpec(bar.getContext());
        Integer backdrop = backdropColors.get(bar);
        if (backdrop == null || backdrop == 0) return spec;
        return spec.adaptTo(backdrop,
                com.waenhancer.xposed.utils.DesignUtils.isNightMode(bar.getContext()));
    }

    private static android.graphics.drawable.Drawable createGlassShape(android.content.Context ctx, float density, boolean includeFill) {
        return createGlassShape(ctx, density, includeFill, glassSpec(ctx));
    }

    private static android.graphics.drawable.Drawable createGlassShape(
            android.content.Context ctx, float density, boolean includeFill, GlassSpec spec) {
        if (!includeFill) {
            if (LiquidLens.isActiveFor(spec)) {
                // The lens derives its rim from the same distance field it refracts with, so it
                // already traces the true outline. A stroke drawn over that is a second edge at
                // a slightly different radius, and reads as the drawn border it is.
                return new android.graphics.drawable.ColorDrawable(0x00000000);
            }
            // The nav itself only contributes the edge; the fill belongs to the blurred layer
            // beneath it, or the two stack and the surface reads twice as opaque as configured.
            android.graphics.drawable.GradientDrawable edge =
                    new android.graphics.drawable.GradientDrawable();
            edge.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            edge.setCornerRadius(pillRadiusDp * density);
            edge.setColor(0x00000000);
            edge.setStroke(Math.max(1, Math.round(spec.strokeWidthDp * density)),
                    spec.strokeColor);
            return edge;
        }
        return GlassRenderer.background(spec, pillRadiusDp * density, density);
    }

    /**
     * Brings the liquid layers up to date: what is behind the bar, what that does to its colours,
     * the refracting rim, and where the blob sits.
     *
     * <p>Cheap to call on every layout pass. The backdrop sample is throttled by
     * {@link BackdropSampler} itself, and the colours are re-derived only when that sample
     * actually changed.</p>
     */
    private static void refreshLiquid(View bottomNav, float density) {
        try {
            FrameLayout host = glassHosts.get(bottomNav);
            if (host == null) return;
            GlassSpec base = glassSpec(bottomNav.getContext());

            // Sampling draws part of the host's view tree, so it is posted rather than run
            // from inside the layout callback that got us here: reading a hierarchy while it is
            // still settling is how a sampler ends up recording a half-laid-out frame.
            if (base.adaptive) {
                host.post(() -> sampleBackdrop(bottomNav, host, density));
            }

            GlassSpec spec = glassSpecFor(bottomNav);

            BlurView blurView = glassBlurViews.get(bottomNav);
            if (blurView != null) {
                LiquidLens.apply(blurView, spec, pillCornerRadiusPx(blurView, density), density);
                logGlassState(spec);
            }

            LiquidMorph morph = liquidMorphs.get(bottomNav);
            if (morph != null) {
                morph.applySpec(spec);
                moveMorphToSelectedTab(bottomNav, morph, density);
            }
        } catch (Throwable ignored) {
        }
    }

    /** The last line {@link #logGlassState} emitted, so a per-layout call logs once per change. */
    private static String lastGlassLog;

    /**
     * Reports what the glass engine actually decided, once per change.
     *
     * <p>Every way the lens can decline produces the same thing on screen — the surface the
     * fallback already painted. "It looks the same as before" therefore does not distinguish a
     * variant with no lens from a shader the driver rejected from an engine that never ran, and
     * those have entirely different fixes. This is the only place that difference is visible.</p>
     */
    private static void logGlassState(GlassSpec spec) {
        try {
            String variant = getPrefString(activePrefs, "floating_bottom_bar_glass_variant",
                    GlassSpec.Variant.STABLE.key());
            String line = "glass: variant=" + variant
                    + " blur=" + spec.blurRadius
                    + " fallback=" + spec.usingFallback
                    + " lens=" + LiquidLens.status();
            if (!line.equals(lastGlassLog)) {
                lastGlassLog = line;
                XposedBridge.log(line);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Takes one throttled reading of what is behind the bar and repaints if it moved. */
    private static void sampleBackdrop(View bottomNav, FrameLayout host, float density) {
        try {
            if (glassHosts.get(bottomNav) != host) return;
            BackdropSampler sampler = backdropSamplers.get(bottomNav);
            if (sampler == null) {
                sampler = new BackdropSampler();
                backdropSamplers.put(bottomNav, sampler);
            }
            View root = getRootLayout(bottomNav);
            int sampled = sampler.sample(root != null ? root : host.getRootView(),
                    host, android.os.SystemClock.uptimeMillis());
            Integer previous = backdropColors.get(bottomNav);
            if (sampled != 0 && (previous == null || previous != sampled)) {
                backdropColors.put(bottomNav, sampled);
                applyAdaptedColors(bottomNav, host, density);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Repaints the surfaces whose colours depend on the backdrop that just changed. */
    private static void applyAdaptedColors(View bottomNav, FrameLayout host, float density) {
        GlassSpec spec = glassSpecFor(bottomNav);
        BlurView blurView = glassBlurViews.get(bottomNav);
        if (blurView != null) {
            if (LiquidLens.isActiveFor(spec)) {
                // The adapted tint reaches the surface through the shader's uniform, which
                // refreshLiquid rebuilds because fillColor is part of the lens cache key.
                blurView.setOverlayColor(android.graphics.Color.TRANSPARENT);
                LiquidLens.apply(blurView, spec, pillCornerRadiusPx(blurView, density), density);
            } else {
                blurView.setOverlayColor(spec.fillColor);
                blurView.setBackground(
                        createGlassShape(bottomNav.getContext(), density, true, spec));
            }
        }
        bottomNav.setBackground(createGlassShape(bottomNav.getContext(), density, false, spec));
        host.invalidate();
    }

    /**
     * The pill's corner radius in pixels, never larger than the pill can actually be.
     *
     * <p>"Fully rounded" is stored as 1000dp, which stands in for "half the height" rather than
     * being a real measurement. Handing that figure to a lens that solves for the distance to the
     * edge would put the whole surface outside its own shape.</p>
     */
    private static float pillCornerRadiusPx(View surface, float density) {
        float requested = pillRadiusDp * density;
        int height = surface.getHeight();
        if (height <= 0) return requested;
        return Math.min(requested, height / 2f);
    }

    /** Springs the blob to whichever tab is currently selected. */
    private static void moveMorphToSelectedTab(View bottomNav, LiquidMorph morph, float density) {
        ViewGroup menu = findMenuView(bottomNav);
        if (menu == null || menu.getChildCount() == 0) return;

        View selected = null;
        for (int i = 0; i < menu.getChildCount(); i++) {
            View item = menu.getChildAt(i);
            if (item.isSelected()) {
                selected = item;
                break;
            }
        }
        if (selected == null || selected.getWidth() <= 0 || morph.getHeight() <= 0) return;

        // Item coordinates are relative to the menu; the blob lives in the host alongside it.
        float left = selected.getLeft() + menu.getLeft() + bottomNav.getLeft();
        android.graphics.RectF bounds = new android.graphics.RectF(
                left, 0f, left + selected.getWidth(), morph.getHeight());
        bounds.inset(dp(density, MORPH_HORIZONTAL_INSET_DP), 0f);
        morph.moveTo(bounds, bounds.height() / 2f);
    }

    private static android.graphics.drawable.GradientDrawable createGlassOutlineShape(android.content.Context ctx, float density) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        shape.setCornerRadius(pillRadiusDp * density);
        shape.setColor(0x00000000);
        return shape;
    }

    /*
     * getGlassOverlayColor(), resolveGlassFillColor() and getGlassStrokeColor() used to live
     * here. They were a second, flatter definition of glass that knew nothing about variants,
     * highlights, refraction, the no-blur fallback or contrast. GlassSpec is now the only place
     * those colours are decided, and the settings preview resolves the same spec.
     */

    private static ViewGroup findMenuView(View tabFrame) {
        if (!(tabFrame instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) tabFrame;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ViewGroup) {
                ViewGroup childGroup = (ViewGroup) child;
                if (childGroup.getChildCount() > 0) {
                    return childGroup;
                }
            }
        }
        return null;
    }


    private static void handleTabSelectionChanged(View bottomNav, int tabId) {
        try {
            View barTarget = getBarAnimationTarget(bottomNav);
            float density = bottomNav.getContext().getResources().getDisplayMetrics().density;
            int height = barTarget.getHeight();
            if (height <= 0) height = bottomNav.getHeight();
            if (height <= 0) height = (int) (80 * density);

            boolean isMetaAiActive = isMetaAiTabActive(bottomNav);
            // Re-assert the metrics: WhatsApp re-lays out the items on selection, which restores
            // its own label sizes and minimum heights.
            applyTabMetrics(bottomNav);
            applyBarVerticalMetrics(bottomNav, density);
            refreshLiquid(bottomNav, density);

            if (tabId == 1000 || tabId == 1100 || isMetaAiActive) {
                float targetTranslationY = height + (24 * density);
                targetTranslations.put(barTarget, targetTranslationY);
                barTarget.animate()
                    .translationY(targetTranslationY)
                    .setDuration(250)
                    .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                    .start();
                animateFabs(bottomNav, true, density);
            } else {
                targetTranslations.put(barTarget, 0f);
                barTarget.animate()
                    .translationY(0)
                    .setDuration(250)
                    .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                    .start();
                animateFabs(bottomNav, false, density);
            }
        } catch (Throwable t) {
            XposedBridge.log("[WAEX-FBB] Error handling tab selection: " + t);
        }
    }

    /*
     * The selected-tab indicator used to live here. Section 10.6 of the execution plan asked it to
     * resolve Material Navigation's internal active-indicator view and restyle that; no such view
     * is reachable in the hooked layout, so the fallback path ran instead and set a background on
     * the tab item itself. That drew a *second* indicator on top of WhatsApp's own, which is the
     * opposite of what the feature promised. Removed in full — see the removal manifest in
     * HANDOFF_WaEnhancer_Community_v2_ExecutionPlan.md.
     */

    /**
     * Applies icon size, label text size and icon-to-label spacing to the real tabs.
     *
     * <p>Every metric is applied unconditionally. These used to be applied only while the value
     * differed from the schema default, and restored to WhatsApp's own metrics otherwise — which
     * turned each slider into a step function with a cliff at its default: the icon slider grew
     * smoothly from 16 to 23dp and then jumped to WhatsApp's intrinsic icon size at 24, because
     * 24 is the default and the default meant "hands off".</p>
     */
    private static void applyTabMetrics(View bottomNav) {
        try {
            ViewGroup menu = findMenuView(bottomNav);
            if (menu == null) return;
            com.waenhancer.config.BottomBarGeometry geometry = geometry(bottomNav);
            int iconSize = geometry.iconSizePx;
            int spacing = geometry.spacingPx;
            float textSizeSp = normalized("floating_bottom_bar_text_size");

            for (int i = 0; i < menu.getChildCount(); i++) {
                View item = menu.getChildAt(i);
                ImageView icon = findFirstIcon(item);

                if (icon != null) {
                    ViewGroup.LayoutParams params = icon.getLayoutParams();
                    if (params != null
                            && (params.width != iconSize || params.height != iconSize)) {
                        params.width = iconSize;
                        params.height = iconSize;
                        icon.setLayoutParams(params);
                    }
                }

                // Spacing moves the *label*, not the icon. WhatsApp's active-tab indicator is a
                // separate view positioned from the icon's own placement, and it does not follow
                // a margin change — so growing the icon's bottom margin slid the icon up out of
                // its own indicator. Moving the label down opens the same gap and leaves the icon
                // sitting where its indicator expects it.
                View labelAnchor = labelSpacingAnchor(item, icon);
                if (labelAnchor != null) {
                    placeLabelAnchor(labelAnchor, geometry);
                }

                // Every label, not just the first one. A Material navigation item carries two
                // TextViews — a small label for the unselected state and a large one for the
                // selected state — so resizing only the first left the active tab at WhatsApp's
                // own size while its neighbours resized around it.
                for (TextView label : findLabels(item)) {
                    label.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp);
                    label.setVisibility(geometry.labelVisible ? View.VISIBLE : View.GONE);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Positions the label group below the icon, whatever kind of parent the item turns out to be.
     *
     * <p>A top margin alone only opens a gap when the parent stacks its children vertically. A
     * Material navigation item is a {@code FrameLayout} whose children are placed by gravity, so
     * the margin barely moved the label and it stayed centred — drawn over the icon, and over the
     * unread badge, exactly as reported on a 51dp bar. Where the parent overlays its children the
     * label is pinned to the top and offset past the icon by hand; where it stacks them the gap
     * is all that is needed.</p>
     */
    private static void placeLabelAnchor(View labelAnchor,
                                         com.waenhancer.config.BottomBarGeometry geometry) {
        ViewGroup.LayoutParams params = labelAnchor.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;

        int topMargin;
        if (stacksChildrenVertically(labelAnchor.getParent())) {
            topMargin = geometry.spacingPx;
        } else {
            topMargin = geometry.contentTopOffsetPx() + geometry.iconSizePx + geometry.spacingPx;
            if (margins instanceof FrameLayout.LayoutParams) {
                ((FrameLayout.LayoutParams) margins).gravity =
                        android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
            } else if (margins instanceof android.widget.LinearLayout.LayoutParams) {
                ((android.widget.LinearLayout.LayoutParams) margins).gravity =
                        android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
            }
        }

        if (margins.topMargin != topMargin) {
            margins.topMargin = topMargin;
            labelAnchor.setLayoutParams(margins);
        }
    }

    /** True when this parent lays its children out one below the next. */
    private static boolean stacksChildrenVertically(ViewParent parent) {
        return parent instanceof android.widget.LinearLayout
                && ((android.widget.LinearLayout) parent).getOrientation()
                        == android.widget.LinearLayout.VERTICAL;
    }

    /**
      * The view whose top margin opens the icon-to-label gap: the label group when the item has
      * one, otherwise the label itself.
      *
      * <p>It walks up from the label but stops short of any ancestor that also holds the icon.
      * Climbing all the way to the item's direct child assumed that child was a label group, and
      * on a build where the item is {@code item > content > (icon, label)} it is not: offsetting
      * it moved the icon down by the same amount, pushing the whole group out of the bottom of
      * the item, which is how the labels disappeared instead of moving.</p>
      */
    private static View labelSpacingAnchor(View item, ImageView icon) {
        TextView label = findFirstLabel(item);
        if (label == null) return null;
        View candidate = label;
        ViewParent parent = label.getParent();
        while (parent instanceof ViewGroup && parent != item) {
            ViewGroup group = (ViewGroup) parent;
            if (icon != null && isAncestorOf(group, icon)) break;
            candidate = group;
            parent = group.getParent();
        }
        return candidate;
    }

    private static boolean isAncestorOf(ViewGroup group, View view) {
        for (ViewParent parent = view.getParent(); parent instanceof ViewGroup;
                parent = parent.getParent()) {
            if (parent == group) return true;
        }
        return false;
    }

    private static ImageView findFirstIcon(View view) {
        if (view instanceof ImageView) return (ImageView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ImageView found = findFirstIcon(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Every label in a tab item, in tree order. */
    private static java.util.List<TextView> findLabels(View view) {
        java.util.List<TextView> labels = new java.util.ArrayList<>(2);
        collectLabels(view, labels);
        return labels;
    }

    private static void collectLabels(View view, java.util.List<TextView> out) {
        if (view instanceof TextView) {
            out.add((TextView) view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectLabels(group.getChildAt(i), out);
            }
        }
    }

    private static TextView findFirstLabel(View view) {
        if (view instanceof TextView) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findFirstLabel(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void applyMinimalFab(View fab) {
        try {
            float density = fab.getResources().getDisplayMetrics().density;
            int size = Math.round(normalized("floating_bottom_bar_minimal_fab_size") * density);
            ViewGroup.LayoutParams params = fab.getLayoutParams();
            if (params != null) {
                params.width = size;
                params.height = size;
                if (params instanceof ViewGroup.MarginLayoutParams) {
                    int margin = Math.round(
                            normalized("floating_bottom_bar_minimal_fab_margin") * density);
                    ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                    margins.leftMargin = margin;
                    margins.rightMargin = margin;
                    margins.setMarginStart(margin);
                    margins.setMarginEnd(margin);
                }
                fab.setLayoutParams(params);
            }
            GradientDrawable background = new GradientDrawable();
            int color = getPrefColor(activePrefs, "floating_bottom_bar_minimal_fab_color", 0);
            if (color == 0) color = DesignUtils.getPrimaryColor();
            int opacity = Math.round(normalized("floating_bottom_bar_minimal_fab_opacity"));
            background.setColor((Math.max(0, Math.min(255, Math.round(opacity * 2.55f))) << 24)
                    | (color & 0x00FFFFFF));
            background.setCornerRadius(normalized(
                    "floating_bottom_bar_minimal_fab_radius") * density);
            fab.setBackground(background);
            if (fab instanceof android.widget.ImageView) {
                ((android.widget.ImageView) fab).setImageTintList(ColorStateList.valueOf(
                        getPrefColor(activePrefs,
                                "floating_bottom_bar_minimal_fab_icon_color", 0xffffffff)));
            }
        } catch (Throwable ignored) {
        }
    }

    private static int getTabIdForIndex(View bottomNav, int index) {
        try {
            Object menu = XposedHelpers.callMethod(bottomNav, "getMenu");
            if (menu != null) {
                int size = (int) XposedHelpers.callMethod(menu, "size");
                java.util.List<Integer> visibleIds = new java.util.ArrayList<>();
                for (int i = 0; i < size; i++) {
                    Object menuItem = XposedHelpers.callMethod(menu, "getItem", i);
                    if (menuItem != null && (boolean) XposedHelpers.callMethod(menuItem, "isVisible")) {
                        visibleIds.add((int) XposedHelpers.callMethod(menuItem, "getItemId"));
                    }
                }
                int resolvedId = -1;
                if (index >= 0 && index < visibleIds.size()) {
                    resolvedId = visibleIds.get(index);
                }
                return resolvedId;
            }
        } catch (Throwable t) {
            XposedBridge.log("[WAEX-FBB] Error in getTabIdForIndex: " + t);
        }
        return -1;
    }

    private static boolean isMetaAiTabSelected(View bottomNav) {
        try {
            int selectedId = (int) XposedHelpers.callMethod(bottomNav, "getSelectedItemId");
            return selectedId == 1000 || selectedId == 1100;
        } catch (Throwable ignored) {}
        return false;
    }

    private static View findBottomNavForView(View view) {
        ViewParent parent = view.getParent();
        while (parent != null) {
            if (parent instanceof View) {
                View parentView = (View) parent;
                if (styledBottomBars.containsKey(parentView)) {
                    return parentView;
                }
                parent = parentView.getParent();
            } else {
                break;
            }
        }
        return null;
    }

    private static View.OnClickListener getOnClickListener(View view) {
        try {
            java.lang.reflect.Field listenerInfoField = View.class.getDeclaredField("mListenerInfo");
            listenerInfoField.setAccessible(true);
            Object listenerInfo = listenerInfoField.get(view);
            if (listenerInfo != null) {
                java.lang.reflect.Field onClickListenerField = listenerInfo.getClass().getDeclaredField("mOnClickListener");
                onClickListenerField.setAccessible(true);
                return (View.OnClickListener) onClickListenerField.get(listenerInfo);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Click proxy that reports the tab selection before delegating to WhatsApp's own listener.
     *
     * <p>It is a named class on purpose. The re-entrancy guard used to be
     * {@code getClass().getName().contains("FloatingBottomBar")}, but R8 renames anonymous inner
     * classes ({@code FloatingBottomBar$5$1 -> Z.pd0}), so that test was always false in release
     * builds and a fresh proxy was stacked on top of the previous one on every
     * {@code setOnClickListener} call. {@code instanceof} does not depend on the name.
     */
    private static final class TabClickProxy implements View.OnClickListener {
        private final View bottomNav;
        private final View.OnClickListener delegate;

        /** @param bottomNav the owning bar, or {@code null} to resolve it from the clicked view */
        TabClickProxy(View bottomNav, View.OnClickListener delegate) {
            this.bottomNav = bottomNav;
            this.delegate = delegate;
        }

        @Override
        public void onClick(View v) {
            try {
                View nav = bottomNav != null ? bottomNav : findBottomNavForView(v);
                if (nav != null) {
                    handleTabSelectionChanged(nav, v.getId());
                }
            } catch (Throwable t) {
                XposedBridge.log("[WAEX-FBB] Error in tab click proxy: " + t);
            }
            delegate.onClick(v);
        }
    }

    private static void wrapOnClickListener(final View bottomNav, final View itemView) {
        try {
            final View.OnClickListener originalListener = getOnClickListener(itemView);
            if (originalListener != null && !(originalListener instanceof TabClickProxy)) {
                itemView.setOnClickListener(new TabClickProxy(bottomNav, originalListener));
            }
        } catch (Throwable t) {
            XposedBridge.log("[WAEX-FBB] Error wrapping listener: " + t);
        }
    }

    private static boolean isMetaAiTabActive(View bottomNav) {
        try {
            if (isMetaAiTabSelected(bottomNav)) {
                return true;
            }
            ViewGroup root = getRootLayout(bottomNav);
            View viewPager = findViewPager(root);
            if (viewPager != null) {
                int currentItem = (int) XposedHelpers.callMethod(viewPager, "getCurrentItem");
                int tabId = getTabIdForIndex(bottomNav, currentItem);
                return tabId == 1000 || tabId == 1100;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Floating Bottom Bar";
    }
}
