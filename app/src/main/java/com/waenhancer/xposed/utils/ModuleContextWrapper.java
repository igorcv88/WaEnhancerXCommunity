package com.waenhancer.xposed.utils;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.view.ContextThemeWrapper;
import com.waenhancer.BuildConfig;
import com.waenhancer.R;

public class ModuleContextWrapper extends ContextThemeWrapper {
    private final Context moduleContext;

    public ModuleContextWrapper(Context base) {
        super(base, R.style.AppTheme);
        try {
            moduleContext = base.createPackageContext(
                    BuildConfig.APPLICATION_ID,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY
            );
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException("Module package not found", e);
        }
    }

    @Override
    public Context getApplicationContext() {
        return moduleContext.getApplicationContext();
    }

    @Override
    public ClassLoader getClassLoader() {
        ClassLoader cl = ModuleContextWrapper.class.getClassLoader();
        return cl != null ? cl : super.getClassLoader();
    }

    @Override
    public Resources getResources() {
        return moduleContext.getResources();
    }

    @Override
    public AssetManager getAssets() {
        return moduleContext.getAssets();
    }
}
