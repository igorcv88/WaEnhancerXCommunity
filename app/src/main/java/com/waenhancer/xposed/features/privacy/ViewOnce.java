package com.waenhancer.xposed.features.privacy;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.components.FMessageWpp;
import com.waenhancer.xposed.core.devkit.Unobfuscator;

import de.robv.android.xposed.XC_MethodHook;
import android.content.SharedPreferences;
import de.robv.android.xposed.XposedBridge;

public class ViewOnce extends Feature {

    public ViewOnce(ClassLoader loader, SharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Exception {
        if (!prefs.getBoolean("viewonce", false)) return;

        var methods = Unobfuscator.loadViewOnceMethod(classLoader);

        for (var method : methods) {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    // WhatsApp 2.26.33+ can reach matching methods through call shapes where
                    // the old int slot is temporarily null/non-numeric. Never auto-unbox an
                    // unvalidated Object: LSPosed otherwise reports Integer.intValue() NPEs for
                    // every invocation of the stale shape.
                    if (param.args == null || param.args.length == 0
                            || !(param.args[0] instanceof Number)) {
                        return;
                    }
                    if (param.thisObject == null || FMessageWpp.TYPE == null
                            || !FMessageWpp.TYPE.isInstance(param.thisObject)) {
                        return;
                    }

                    int returnValue = ((Number) param.args[0]).intValue();
                    var fMessage = new FMessageWpp(param.thisObject);
                    var key = fMessage.getKey();
                    if (key != null && returnValue == 1 && !key.isFromMe) {
                        param.args[0] = 0;
                    }
                }
            });
        }
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "View Once";
    }
}
