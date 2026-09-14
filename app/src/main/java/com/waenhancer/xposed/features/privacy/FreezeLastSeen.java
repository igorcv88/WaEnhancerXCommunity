package com.waenhancer.xposed.features.privacy;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.devkit.Unobfuscator;

import android.content.SharedPreferences;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;

public class FreezeLastSeen extends Feature {
    public FreezeLastSeen(ClassLoader loader, SharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Exception {
        boolean freezeLastSeen = prefs.getBoolean("freezelastseen", false);
        boolean ghostmode = prefs.getBoolean("ghostmode_actual", false);

        if (freezeLastSeen || ghostmode) {
            var method = Unobfuscator.loadFreezeSeenMethod(classLoader);
            /* Log removed */
            XposedBridge.hookMethod(method, new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    diagnosticTriggered();
                    // Same behavior as XC_MethodReplacement.DO_NOTHING, with the diagnostic
                    // callback recorded before the original host method is suppressed.
                    return null;
                }
            });
        }
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Freeze Last Seen";
    }

}
