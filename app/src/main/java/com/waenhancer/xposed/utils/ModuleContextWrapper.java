package com.waenhancer.xposed.utils;

import android.content.Context;
import android.content.pm.PackageManager;
import android.view.ContextThemeWrapper;

import com.waenhancer.BuildConfig;
import com.waenhancer.R;

/**
 * A themed context backed by the module APK rather than WhatsApp's resources.
 */
public class ModuleContextWrapper extends ContextThemeWrapper {

    public ModuleContextWrapper(Context base) {
        super(createModuleContext(base), R.style.AppTheme);
    }

    private static Context createModuleContext(Context base) {
        if (base == null) {
            throw new IllegalArgumentException("Base context must not be null");
        }
        try {
            return base.createPackageContext(
                    BuildConfig.APPLICATION_ID,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY
            );
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("WaEnhancer module package not found", e);
        }
    }
}
