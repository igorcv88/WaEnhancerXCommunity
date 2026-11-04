package com.waenhancer.xposed.features.customization;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.PerfLogger;
import com.waenhancer.xposed.core.WppCore;
import com.waenhancer.xposed.core.devkit.Unobfuscator;
import com.waenhancer.xposed.utils.ReflectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import de.robv.android.xposed.XC_MethodHook;
import android.content.SharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class HideTabs extends Feature {
    private static final int STATUS_TAB_ID = 300;
    private Object mTabPagerInstance;

    /** Native bottom-menu IDs. This snapshot can omit a subsequently injected Groups tab. */
    private final ArrayList<Integer> originalTabs = new ArrayList<>();
    
    /**
     * The actual active tab IDs in the adapter after filtering out hidden ones.
     */
    private final ArrayList<Integer> activeTabs = new ArrayList<>();

    public HideTabs(@NonNull ClassLoader loader, @NonNull SharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Throwable {

        var hidetabs = prefs.getStringSet("hidetabs", null);
        var igstatus = prefs.getBoolean("igstatus", false);
        var disableMetaAI = prefs.getBoolean("metaai", false);

        List<Integer> hideTabsList;
        if (hidetabs != null && !hidetabs.isEmpty()) {
            hideTabsList = hidetabs.stream().map(Integer::valueOf).collect(Collectors.toCollection(ArrayList::new));
        } else {
            hideTabsList = new ArrayList<>();
        }

        if (disableMetaAI && !hideTabsList.contains(1000)) {
            hideTabsList.add(1000);
        }

        if (hideTabsList.contains(1000)) {
            hideTabsList.add(1100);
        }

        if (hideTabsList.isEmpty())
            return;

        // Keep this hook so we can also remove hidden tabs from the adapter's tab list
        // if the method actually returns an ArrayList (some WA versions).
        var onCreateTabList = Unobfuscator.loadTabListMethod(classLoader);
        /* Log removed */

        XposedBridge.hookMethod(onCreateTabList, new XC_MethodHook() {
            @Override
            @SuppressWarnings("unchecked")
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object result = param.getResult();
                if (result instanceof ArrayList) {
                    var tabs = (ArrayList<Integer>) result;
                    // Keep the menu-order snapshot only as a fallback. The tab producer
                    // can insert Groups after this hook, so it must not own pager indices.
                    synchronized (originalTabs) {
                        if (originalTabs.isEmpty()) {
                            originalTabs.addAll(tabs);
                        }
                    }
                    for (var item : hideTabsList) {
                        if (item != STATUS_TAB_ID || !igstatus) {
                            tabs.remove(item);
                        }
                    }
                    synchronized (activeTabs) {
                        activeTabs.clear();
                        activeTabs.addAll(tabs);
                    }
                }
            }
        });

        // This hook fires once per tab at startup, in ViewPager position order.
        // We use it both to hide bottom-nav items AND to build the originalTabs mapping.
        var OnTabItemAddMethod = Unobfuscator.loadOnTabItemAddMethod(classLoader);
        XposedBridge.hookMethod(OnTabItemAddMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                var menuItem = (MenuItem) param.getResult();
                var menuItemId = menuItem.getItemId();

                // Record each tab ID in order — index == ViewPager child position.
                synchronized (originalTabs) {
                    if (!originalTabs.contains(menuItemId)) {
                        originalTabs.add(menuItemId);
                    }
                }

                if (hideTabsList.contains(menuItemId)) {
                    menuItem.setVisible(false);
                }
            }
        });

        // Hide tab-bar item views; hide entire tab bar if only one tab remains.
        var loadTabFrameClass = Unobfuscator.loadTabFrameClass(classLoader);
        /* Log removed */

        XposedHelpers.findAndHookMethod(loadTabFrameClass, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                ArrayList<Integer> origSnapshot;
                synchronized (originalTabs) {
                    origSnapshot = new ArrayList<>(originalTabs);
                }
                if (!origSnapshot.isEmpty()) {
                    var arr = new ArrayList<>(origSnapshot);
                    arr.removeAll(hideTabsList);
                    if (arr.size() == 1) {
                        ((View) param.thisObject).setVisibility(View.GONE);
                    }
                }
                for (var item : hideTabsList) {
                    View view;
                    if ((view = ((View) param.thisObject).findViewById(item)) != null) {
                        view.setVisibility(View.GONE);
                    }
                }
            }
        });

        // Capture the TabsPager instance after HomeActivity.onCreate completes.
        XposedHelpers.findAndHookMethod(WppCore.getHomeActivityClass(classLoader), "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                long perfStart = PerfLogger.start();
                try {
                    Class<?> TabsPagerClass = WppCore.getTabsPagerClass(classLoader);
                    var tabsField = ReflectionUtils.getFieldByType(param.thisObject.getClass(), TabsPagerClass);
                    if (tabsField != null) {
                        tabsField.setAccessible(true);
                        mTabPagerInstance = tabsField.get(param.thisObject);
                    }
                } catch (Exception e) {
                    XposedBridge.log("HideTabs: failed to get TabsPager instance: " + e);
                }
                PerfLogger.end("HideTabs.homeOnCreate", perfStart, 1);
            }
        });

        // Intercepts ViewPager scroll settlement (both swipe AND bottom-nav tap).
        // Only redirect when a current tab list agrees with the real adapter count.
        var onMenuItemSelected = Unobfuscator.loadOnMenuItemSelected(classLoader);
        XposedBridge.hookMethod(onMenuItemSelected, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                long perfStart = PerfLogger.start();
                if (param.thisObject == mTabPagerInstance) {
                    var index = (int) param.args[0];
                    var idxAtual = (int) XposedHelpers.callMethod(param.thisObject, "getCurrentItem");
                    List<Integer> order = pagerOrder(param.thisObject);
                    int newIndex = HomeTabSelection.target(order, hideTabsList, idxAtual, index);
                    param.args[0] = newIndex;
                }
                PerfLogger.end("HideTabs.onMenuItemSelected", perfStart, 1);
            }
        });

        // Hide the fragment view for hidden-tab positions when added to the ViewPager.
        XposedHelpers.findAndHookMethod("androidx.viewpager.widget.ViewPager", classLoader,
                "addView",
                classLoader.loadClass("android.view.View"),
                int.class,
                classLoader.loadClass("android.view.ViewGroup$LayoutParams"),
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.thisObject != mTabPagerInstance) return;
                        int index = (int) param.args[1];
                        int tabId = getTabIdAt(param.thisObject, index);
                        if (tabId != -1 && hideTabsList.contains(tabId)) {
                            ((View) param.args[0]).setVisibility(View.GONE);
                        }
                    }
                });
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Hide Tabs";
    }

    /**
     * Legacy entry point retained for callers that pass the original ordered menu.
     * The ViewPager hook above must use its validated live adapter order instead.
     */
    public int getNewTabIndex(List<?> hidetabs, int indexAtual, int index) {
        ArrayList<Integer> tabsSnapshot;
        synchronized (originalTabs) {
            if (originalTabs.isEmpty()) return index;
            tabsSnapshot = new ArrayList<>(originalTabs);
        }

        return HomeTabSelection.target(tabsSnapshot, hidetabs, indexAtual, index);
    }

    private List<Integer> pagerOrder(Object pager) {
        int count = -1;
        try {
            Object adapter = XposedHelpers.callMethod(pager, "getAdapter");
            if (adapter != null) count = (int) XposedHelpers.callMethod(adapter, "getCount");
        } catch (Throwable ignored) { }
        ArrayList<Integer> adapterSnapshot;
        ArrayList<Integer> menuSnapshot;
        synchronized (activeTabs) { adapterSnapshot = new ArrayList<>(activeTabs); }
        synchronized (originalTabs) {
            menuSnapshot = new ArrayList<>(originalTabs);
        }
        return HomeTabSelection.currentOrder(count, prefs.getBoolean("separategroups", false),
                new ArrayList<>(SeparateGroup.tabs), adapterSnapshot, menuSnapshot);
    }

    private int getTabIdAt(Object pager, int index) {
        List<Integer> order = pagerOrder(pager);
        return index >= 0 && index < order.size() ? order.get(index) : -1;
    }
}
