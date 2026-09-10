package com.waenhancer;

import android.app.Application;
import android.content.res.XModuleResources;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.AntiUpdater;
import com.waenhancer.xposed.bridge.ScopeHook;
import com.waenhancer.xposed.bridge.client.DeferredProviderSharedPreferences;
import com.waenhancer.xposed.core.FeatureLoader;
import com.waenhancer.xposed.downgrade.Patch;
import com.waenhancer.xposed.utils.XResManager;
import com.waenhancer.xposed.utils.Utils;

import de.robv.android.xposed.IXposedHookInitPackageResources;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class WppXposed implements IXposedHookLoadPackage, IXposedHookInitPackageResources, IXposedHookZygoteInit {

    private static XSharedPreferences pref;
    private String MODULE_PATH;
    public static XC_InitPackageResources.InitPackageResourcesParam ResParam;

    @NonNull
    public static XSharedPreferences getPref() {
        if (pref == null) {
            pref = new XSharedPreferences(BuildConfig.APPLICATION_ID, BuildConfig.APPLICATION_ID + "_preferences");
            // Legacy fallback only. Runtime hooks can fall back to HookProvider/ProviderSharedPreferences
            // when direct XSharedPreferences access is unavailable.
            try {
                pref.makeWorldReadable();
            } catch (Throwable ignored) {
            }
            pref.reload();
        }
        return pref;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (Utils.DEBUG) {
            ;
        }
        var packageName = lpparam.packageName;
        var classLoader = lpparam.classLoader;

        if (packageName.equals(BuildConfig.APPLICATION_ID)) {
            if (Utils.DEBUG) {
                ;
            }

            // Keep self-hooking intentionally narrow. On Android 17 the old manager-side hack
            // that forced PreferenceManager.getDefaultSharedPreferencesMode() to
            // MODE_WORLD_READABLE can crash the companion app during Application.onCreate.
            // ModuleStatus is enough for the self-hook sentinel; preference transport to
            // WhatsApp has a provider-backed fallback and must not mutate ContextImpl semantics.
            XposedHelpers.findAndHookMethod(
                    "com.waenhancer.utils.ModuleStatus",
                    lpparam.classLoader,
                    "isModuleActive",
                    XC_MethodReplacement.returnConstant(true));

            // Save the active Xposed API version without going through PreferenceManager. Using
            // an explicitly MODE_PRIVATE file avoids the deprecated world-readable mode path and
            // keeps the manager process independent from framework-private ContextImpl details.
            try {
                XposedHelpers.findAndHookMethod(
                        "android.app.Application", lpparam.classLoader,
                        "onCreate", new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                android.content.Context context = (android.content.Context) param.thisObject;
                                try {
                                    int apiVersion = XposedBridge.getXposedVersion();
                                    android.content.SharedPreferences localPrefs = context.getSharedPreferences(
                                            BuildConfig.APPLICATION_ID + "_preferences",
                                            android.content.Context.MODE_PRIVATE);
                                    localPrefs.edit()
                                            .putInt("active_xposed_api_version", apiVersion)
                                            .apply();
                                } catch (Throwable t) {
                                    XposedBridge.log("[WAEX] Failed to save active Xposed API version in manager: " + t);
                                }
                            }
                        });
            } catch (Throwable t) {
                XposedBridge.log("[WAEX] Failed to hook Application.onCreate in manager: " + t);
            }

            // Inject Bootloader Spoofer in the manager app itself for live verification.
            if (getPref().getBoolean("bootloader_spoofer", false)) {
                try {
                    com.waenhancer.xposed.spoofer.HookBL.hook(lpparam.classLoader, getPref());
                } catch (Throwable t) {
                    XposedBridge.log("[WAEX] Failed to hook Bootloader Spoofer in settings app: " + t.getMessage());
                }
            }
            return;
        }

        if (Utils.DEBUG) {
            ;
        }

        AntiUpdater.hookSession(lpparam);

        Patch.handleLoadPackage(lpparam, getPref());

        ScopeHook.hook(lpparam);

        //  AndroidPermissions.hook(lpparam); in tests
        boolean isWpp = packageName.equals(FeatureLoader.PACKAGE_WPP);
        boolean isBusiness = packageName.equals(FeatureLoader.PACKAGE_BUSINESS);
        boolean isOriginal = App.isOriginalPackage();

        if (Utils.DEBUG) {
            ;
        }

        if ((isWpp && isOriginal) || isBusiness) {
            if (Utils.DEBUG) {
                ;
            }

            // Initialize module resources early
            XResManager.moduleResources = XModuleResources.createInstance(MODULE_PATH, null);

            // Populate valid IDs immediately for hooks to work
            populateValidIds();

            try {
                // The Application does not exist yet at handleLoadPackage time. Defer provider
                // hydration until FeatureLoader receives callApplicationOnCreate, then make the
                // provider-backed view authoritative before any startup preference is consumed.
                FeatureLoader.start(
                        classLoader,
                        new DeferredProviderSharedPreferences(getPref()),
                        lpparam.appInfo.sourceDir);
                if (Utils.DEBUG) {
                    ;
                }
            } catch (Throwable t) {
                XposedBridge.log("[WAEX] CRITICAL ERROR in FeatureLoader.start: " + t.getMessage());
                XposedBridge.log(t);
            }

            disableSecureFlag();
        }
    }

    @Override
    public void handleInitPackageResources(XC_InitPackageResources.InitPackageResourcesParam resparam) throws Throwable {
        var packageName = resparam.packageName;

        if (!packageName.equals(FeatureLoader.PACKAGE_WPP) && !packageName.equals(FeatureLoader.PACKAGE_BUSINESS))
            return;

        XResManager.moduleResources = XModuleResources.createInstance(MODULE_PATH, resparam.res);
        XResManager.hostResources = resparam.res;
        ResParam = resparam;

        // Populate valid IDs list immediately (fast reflection)
        populateValidIds();

        // Map everything synchronously on the main thread to ensure consistency
        mapAllResources(resparam);
    }

    private void populateValidIds() {
        try {
            Class<?> rClass = com.waenhancer.R.class;
            for (Class<?> subClass : rClass.getDeclaredClasses()) {
                for (java.lang.reflect.Field field : subClass.getFields()) {
                    try {
                        XResManager.validModuleIds.add(field.getInt(null));
                    } catch (Exception ignored) {}
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Error populating valid IDs: " + t.getMessage());
        }
    }

    private void mapAllResources(XC_InitPackageResources.InitPackageResourcesParam resparam) {
        try {
            Class<?> rClass = com.waenhancer.R.class;
            int count = 0;
            for (Class<?> subClass : rClass.getDeclaredClasses()) {
                String type = subClass.getSimpleName();
                if (type.equals("id") || type.equals("styleable") || type.equals("attr")) {
                    continue;
                }
                for (java.lang.reflect.Field field : subClass.getDeclaredFields()) {
                    try {
                        field.setAccessible(true);
                        if (field.getType() == int.class) {
                            int originalId = field.getInt(null);
                            if (originalId >= 0x70000000 && originalId <= 0x7FFFFFFF) {
                                int hostId = XResManager.getHostId(originalId);

                                // Update the R class field directly to the mapped ID
                                field.set(null, hostId);
                                count++;

                                // Also update ResId fields for backward compatibility
                                try {
                                    Class<?> resIdSubClass = Class.forName("com.waenhancer.xposed.utils.ResId$" + type);
                                    java.lang.reflect.Field resIdField = resIdSubClass.getField(field.getName());
                                    resIdField.setAccessible(true);
                                    resIdField.set(null, hostId);
                                } catch (Exception ignored) {}
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Resource mapping error: " + t.getMessage());
        }
    }

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        MODULE_PATH = startupParam.modulePath;

        // Write a system property that persists until reboot.
        // Reading debug.* properties requires NO permission from any app,
        // so WaEnhancer can detect LSPosed even when it's not in module scope.
        // Writing debug.* properties IS allowed from the zygote/root context
        // that initZygote runs under.
        try {
            Class<?> sysProp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method set = sysProp.getMethod("set", String.class, String.class);
            set.invoke(null, "debug.waenhancer.lsposed", "1");
            try {
                int apiVersion = de.robv.android.xposed.XposedBridge.getXposedVersion();
                set.invoke(null, "debug.waenhancer.lsposed.api", String.valueOf(apiVersion));
                /* Log removed */
            } catch (Throwable t2) {
                XposedBridge.log("[WAEX] LSPosed marker written: debug.waenhancer.lsposed=1 (Failed to write API version: " + t2.getMessage() + ")");
            }
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Failed to write LSPosed marker: " + t.getMessage());
        }
    }

    public void disableSecureFlag() {
        XposedHelpers.findAndHookMethod(android.view.Window.class, "setFlags", int.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                param.args[0] = (int) param.args[0] & ~android.view.WindowManager.LayoutParams.FLAG_SECURE;
                param.args[1] = (int) param.args[1] & ~android.view.WindowManager.LayoutParams.FLAG_SECURE;
            }
        });

        XposedHelpers.findAndHookMethod(android.view.Window.class, "addFlags", int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                param.args[0] = (int) param.args[0] & ~android.view.WindowManager.LayoutParams.FLAG_SECURE;
                if ((int) param.args[0] == 0) {
                    param.setResult(null);
                }
            }
        });
    }
}
