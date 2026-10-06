package com.waenhancer.xposed.features.others;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Bundle;

import com.waenhancer.xposed.core.Feature;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Upstream 301a378b/1706457c/4ffb4204, adapted to Community's Xposed runtime. */
public class MinorFixes extends Feature {
    private static final String PICKER = "com.whatsapp.documentpicker.DocumentPickerActivity";
    private static final String PROVIDER = "com.google.mlkit.common.internal.MlKitInitProvider";
    private boolean initialized;

    public MinorFixes(ClassLoader loader, SharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() {
        XposedHelpers.findAndHookMethod(Instrumentation.class, "callActivityOnCreate",
                Activity.class, Bundle.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!(param.args[0] instanceof Activity)) return;
                        Activity activity = (Activity) param.args[0];
                        if (PICKER.equals(activity.getClass().getName())) initialize(activity);
                    }
                });
    }

    private synchronized void initialize(Activity activity) {
        if (initialized) return;
        try {
            // Reuse an initialized ML Kit context instead of attaching a second provider.
            try {
                Class<?> contextClass = Class.forName("com.google.mlkit.common.sdkinternal.MlKitContext",
                        false, activity.getClassLoader());
                if (contextClass.getMethod("getInstance").invoke(null) != null) {
                    initialized = true;
                    return;
                }
            } catch (ReflectiveOperationException ignored) {
                // Missing or not-yet-initialized context: use the upstream provider path.
            }
            ContentProvider provider = (ContentProvider) Class.forName(PROVIDER, true,
                    activity.getClassLoader()).getDeclaredConstructor().newInstance();
            ProviderInfo info;
            try {
                info = activity.getPackageManager().getProviderInfo(
                        new ComponentName(activity.getPackageName(), PROVIDER),
                        PackageManager.GET_META_DATA);
            } catch (PackageManager.NameNotFoundException unavailable) {
                info = new ProviderInfo();
                info.name = PROVIDER;
                info.packageName = activity.getPackageName();
                info.authority = activity.getPackageName() + ".mlkitinitprovider";
                info.applicationInfo = activity.getApplicationInfo();
            }
            provider.attachInfo(activity.getApplicationContext(), info);
            initialized = true;
        } catch (Throwable failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof IllegalStateException && cause.getMessage() != null
                        && cause.getMessage().contains("MlKitContext is already initialized")) {
                    initialized = true;
                    return;
                }
            }
            logDebug("ML Kit document-picker initialization unavailable: "
                    + failure.getClass().getSimpleName());
        }
    }

    @Override
    public String getPluginName() { return "Minor Fixes"; }
}
