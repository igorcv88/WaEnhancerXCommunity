package com.waenhancer;

import android.app.Application;
import android.content.res.XModuleResources;

import com.waenhancer.xposed.bridge.client.DeferredProviderSharedPreferences;
import com.waenhancer.xposed.core.FeatureLoader;
import com.waenhancer.xposed.utils.XResManager;
import com.waenhancer.xposed.utils.Utils;

import de.robv.android.xposed.IXposedHookInitPackageResources;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class WppXposed implements IXposedHookLoadPackage, IXposedHookInitPackageResources, IXposedHookZygoteInit {

    private String MODULE_PATH;
    public static XC_InitPackageResources.InitPackageResourcesParam ResParam;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // Reject stale/extra LSPosed scopes before initializing app helpers or any hooks.
        String packageName = lpparam.packageName;
        if (!AppOnlyScope.accepts(packageName, BuildConfig.APPLICATION_ID)) return;
        var classLoader = lpparam.classLoader;
        if (packageName.equals(BuildConfig.APPLICATION_ID)) {
            if (Utils.DEBUG) {
                ;
            }

            // Keep self-hooking intentionally narrow. Runtime configuration no longer depends on
            // XSharedPreferences: the manager reads its own private/default store and WhatsApp uses
            // the UID-validated HookProvider bridge.
            XposedHelpers.findAndHookMethod(
                    "com.waenhancer.utils.ModuleStatus",
                    lpparam.classLoader,
                    "isModuleActive",
                    XC_MethodReplacement.returnConstant(true));

            try {
                XposedHelpers.findAndHookMethod(
                        "android.app.Application", lpparam.classLoader,
                        "onCreate", new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                android.content.Context context = (android.content.Context) param.thisObject;
                                try {
                                    // Phase 2 will remove NSP metadata. Restoring here as well as in
                                    // App.onCreate makes the self-hook path safe on the very first
                                    // post-migration process start, before any setting is consumed.
                                    com.waenhancer.config.NspPreferenceMigration.restoreIfNeeded(context);

                                    android.content.SharedPreferences localPrefs = context.getSharedPreferences(
                                            BuildConfig.APPLICATION_ID + "_preferences",
                                            android.content.Context.MODE_PRIVATE);

                                    int apiVersion = XposedBridge.getXposedVersion();
                                    localPrefs.edit()
                                            .putInt("active_xposed_api_version", apiVersion)
                                            .apply();

                                } catch (Throwable t) {
                                    XposedBridge.log("[WAEX] Failed to initialize manager self-hook preferences: " + t);
                                }
                            }
                        });
            } catch (Throwable t) {
                XposedBridge.log("[WAEX] Failed to hook Application.onCreate in manager: " + t);
            }
            return;
        }

        if (Utils.DEBUG) {
            ;
        }


        // App-local feature initialization.
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
                // The Application does not exist yet at handleLoadPackage time. The deferred
                // wrapper intentionally has no XSharedPreferences fallback: as soon as the host
                // Application exists it hydrates from the module's UID-validated HookProvider.
                FeatureLoader.start(
                        classLoader,
                        new DeferredProviderSharedPreferences(),
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

        // No global property writes, runtime hooks or app helpers at zygote initialization.
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
