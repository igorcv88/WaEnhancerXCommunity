package com.waenhancer.xposed.features.listeners;

import android.graphics.drawable.Drawable;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;

import androidx.annotation.NonNull;

import com.waenhancer.R;
import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.WppCore;
import com.waenhancer.xposed.core.components.FMessageWpp;
import com.waenhancer.xposed.core.components.StatusItemWaex;
import com.waenhancer.xposed.core.devkit.Unobfuscator;
import com.waenhancer.xposed.features.media.StatusDownload;
import com.waenhancer.xposed.utils.DesignUtils;
import com.waenhancer.xposed.utils.ReflectionUtils;

import org.luckypray.dexkit.query.enums.StringMatchType;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import android.content.SharedPreferences;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class MenuStatusListener extends Feature {

    public static final LinkedHashSet<OnMenuItemStatusListener> menuStatuses = new LinkedHashSet<>();
    public static final ArrayList<FMessageWpp> currentStatusList = new ArrayList<>();
    public static int currentIndex = -1;

    private static Field currentIndexField = null;

    public static LinkedHashSet<OnMenuItemStatusListener> getMenuStatuses() {
        return menuStatuses;
    }

    public static synchronized void registerStatusListener(OnMenuItemStatusListener listener) {
        menuStatuses.removeIf(l -> l.getClass().getName().equals(listener.getClass().getName()));
        menuStatuses.add(listener);
    }

    public static FMessageWpp getFMessageFromStatusData(Object obj) {
        if (obj == null) return null;

        Field fMessageField = ReflectionUtils.findFieldUsingFilterIfExists(obj.getClass(),
                f -> FMessageWpp.TYPE != null && FMessageWpp.TYPE.isAssignableFrom(f.getType()));
        if (fMessageField != null) {
            Object fMessageObj = ReflectionUtils.getObjectField(fMessageField, obj);
            if (fMessageObj != null) {
                return new FMessageWpp(fMessageObj);
            }
        }

        try {
            java.lang.reflect.Method mapMethod = Unobfuscator.loadFStatusToFMessage(obj.getClass().getClassLoader());
            Class<?> fStatusClass = mapMethod.getParameterTypes()[0];
            Field fStatusField = ReflectionUtils.findFieldUsingFilterIfExists(obj.getClass(),
                    f -> fStatusClass.isAssignableFrom(f.getType()));
            if (fStatusField != null) {
                Object fStatusObj = fStatusField.get(obj);
                Object fMessageObj = WppCore.getFMessageFromFStatus(fStatusObj);
                if (fMessageObj != null) {
                    return new FMessageWpp(fMessageObj);
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    public MenuStatusListener(@NonNull ClassLoader classLoader, @NonNull SharedPreferences preferences) {
        super(classLoader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        var menuStatusMethod = Unobfuscator.loadMenuStatusMethod(classLoader);
        var menuManagerClass = Unobfuscator.loadMenuManagerClass(classLoader);

        try {
            currentIndexField = Unobfuscator.loadStatusPlaybackCurrentIndexField(classLoader);
            if (currentIndexField != null) {
                currentIndexField.setAccessible(true);
            }
        } catch (Throwable ignored) {
            currentIndexField = null;
        }

        Class<?> statusPlaybackBaseFragmentClass;
        Class<?> statusPlaybackContactFragmentClass;

        try {
            statusPlaybackBaseFragmentClass = Unobfuscator.findFirstClassUsingName(
                    classLoader, StringMatchType.EndsWith, "StatusPlaybackBaseFragment");
        } catch (Throwable t) {
            statusPlaybackBaseFragmentClass = classLoader.loadClass(
                    "com.whatsapp.status.playback.fragment.StatusPlaybackBaseFragment");
        }

        try {
            statusPlaybackContactFragmentClass = Unobfuscator.findFirstClassUsingName(
                    classLoader, StringMatchType.EndsWith, "StatusPlaybackContactFragment");
        } catch (Throwable t) {
            statusPlaybackContactFragmentClass = classLoader.loadClass(
                    "com.whatsapp.status.playback.fragment.StatusPlaybackContactFragment");
        }

        final Class<?> baseFragClass = statusPlaybackBaseFragmentClass;
        final Class<?> contactFragClass = statusPlaybackContactFragmentClass;

        Field listStatusField = ReflectionUtils.getFieldByExtendType(contactFragClass, List.class);

        XposedBridge.hookMethod(menuStatusMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    var fieldObjects = new ArrayList<>();
                    for (Field field : menuStatusMethod.getDeclaringClass().getDeclaredFields()) {
                        Object value = ReflectionUtils.getObjectField(field, param.thisObject);
                        if (value != null) {
                            fieldObjects.add(value);
                        }
                    }

                    Object fragmentInstance;
                    if (param.thisObject != null && contactFragClass.isInstance(param.thisObject)) {
                        fragmentInstance = param.thisObject;
                    } else {
                        fragmentInstance = fieldObjects.stream()
                                .filter(obj -> baseFragClass != null && baseFragClass.isInstance(obj))
                                .findFirst()
                                .orElse(null);
                    }
                    if (fragmentInstance == null) return;

                    Menu menu;
                    if (param.args.length > 0 && param.args[0] instanceof Menu) {
                        menu = (Menu) param.args[0];
                    } else {
                        var menuManager = fieldObjects.stream()
                                .filter(menuManagerClass::isInstance)
                                .findFirst()
                                .orElse(null);
                        var menuField = ReflectionUtils.getFieldByExtendType(menuManagerClass, Menu.class);
                        menu = menuField == null ? null
                                : (Menu) ReflectionUtils.getObjectField(menuField, menuManager);
                    }
                    if (menu == null) return;

                    Integer index = resolveCurrentIndex(fragmentInstance);
                    if (index == null) {
                        XposedBridge.log("[WAEX] MenuStatusListener: unable to resolve current status index");
                        return;
                    }

                    @SuppressWarnings("unchecked")
                    List<?> listStatus = listStatusField != null
                            ? (List<?>) listStatusField.get(fragmentInstance)
                            : null;
                    if (listStatus == null || listStatus.isEmpty()) return;
                    if (index < 0 || index >= listStatus.size()) {
                        XposedBridge.log("[WAEX] MenuStatusListener: index " + index
                                + " out of bounds for raw status list size " + listStatus.size());
                        return;
                    }

                    Object activeRawStatus = listStatus.get(index);
                    StatusItemWaex currentStatusItem = StatusItemWaex.from(activeRawStatus);
                    if (currentStatusItem == null) {
                        XposedBridge.log("[WAEX] MenuStatusListener: current raw status could not be parsed");
                        return;
                    }
                    FMessageWpp currentFMessage = currentStatusItem.getFMessage();
                    if (currentFMessage == null) {
                        XposedBridge.log("[WAEX] MenuStatusListener: current status has no FMessage");
                        return;
                    }

                    // Keep this list index-aligned with WhatsApp's raw status list because legacy
                    // menu listeners use currentIndex directly with fMessageList.get(currentIndex).
                    List<FMessageWpp> fMessageList = new ArrayList<>(listStatus.size());
                    for (Object obj : listStatus) {
                        StatusItemWaex statusItem = StatusItemWaex.from(obj);
                        fMessageList.add(statusItem != null ? statusItem.getFMessage() : null);
                    }

                    currentStatusList.clear();
                    currentStatusList.addAll(fMessageList);
                    currentIndex = index;
                    StatusDownload.activeStatusObj = activeRawStatus;

                    SubMenu waeSubMenu = null;
                    for (OnMenuItemStatusListener menuStatus : menuStatuses) {
                        if (waeSubMenu == null) {
                            String waeTitle = "WaEnhancerX";
                            try {
                                String moduleTitle = com.waenhancer.xposed.core.FeatureLoader.getModuleString(
                                        com.waenhancer.xposed.utils.Utils.getApplication(),
                                        R.string.app_name,
                                        "WaEnhancerX");
                                if (moduleTitle != null && !moduleTitle.isEmpty()) {
                                    waeTitle = moduleTitle;
                                }
                            } catch (Exception ignored) {
                            }

                            waeSubMenu = menu.addSubMenu(0, 0x7EAD0012, 0, waeTitle);
                            Drawable waeIcon = DesignUtils.getDrawableByName("ic_settings");
                            if (waeIcon != null) {
                                waeIcon.setTint(0xff8696a0);
                                waeSubMenu.getItem().setIcon(waeIcon);
                            }
                        }

                        final int finalIndex = index;
                        var menuItem = menuStatus.addMenu(
                                waeSubMenu, currentStatusItem, fMessageList, finalIndex);
                        if (menuItem == null) {
                            continue;
                        }

                        menuItem.setOnMenuItemClickListener(item -> {
                            menuStatus.onClick(
                                    item, fragmentInstance, currentStatusItem, fMessageList, finalIndex);
                            return true;
                        });
                    }

                    if (waeSubMenu != null && !waeSubMenu.hasVisibleItems()) {
                        menu.removeItem(0x7EAD0012);
                    }
                } catch (Throwable t) {
                    XposedBridge.log("[WAEX] MenuStatusListener error in hook: " + t);
                }
            }
        });
    }

    private static Integer resolveCurrentIndex(Object fragmentInstance) {
        if (fragmentInstance == null) return null;

        if (currentIndexField != null) {
            try {
                return currentIndexField.getInt(fragmentInstance);
            } catch (Throwable ignored) {
            }
        }

        try {
            Object value = XposedHelpers.getObjectField(fragmentInstance, "A02");
            if (value instanceof Integer) return (Integer) value;
        } catch (Throwable ignored) {
        }

        try {
            Object value = XposedHelpers.getObjectField(fragmentInstance, "A00");
            if (value instanceof Integer) return (Integer) value;
        } catch (Throwable ignored) {
        }

        return null;
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Menu Status";
    }

    public interface OnMenuItemStatusListener {
        MenuItem addMenu(Menu menu, List<FMessageWpp> fMessageList, int currentIndex);

        void onClick(MenuItem item, Object fragmentInstance, List<FMessageWpp> fMessageList,
                     int currentIndex);

        default MenuItem addMenu(Menu menu, StatusItemWaex currentItem,
                                 List<FMessageWpp> fMessageList, int currentIndex) {
            return addMenu(menu, fMessageList, currentIndex);
        }

        default void onClick(MenuItem item, Object fragmentInstance, StatusItemWaex currentItem,
                             List<FMessageWpp> fMessageList, int currentIndex) {
            onClick(item, fragmentInstance, fMessageList, currentIndex);
        }
    }
}
