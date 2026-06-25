package com.waenhancer.xposed.core.plugins;

public class IsolatedParentClassLoader extends ClassLoader {
    private final ClassLoader hostClassLoader;
    private final ClassLoader moduleClassLoader;

    public IsolatedParentClassLoader(ClassLoader hostClassLoader) {
        super(ClassLoader.getSystemClassLoader());
        this.hostClassLoader = hostClassLoader;
        this.moduleClassLoader = IsolatedParentClassLoader.class.getClassLoader();
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // Enforce boundary rules:
        // 1. Allow com.waex.api.* (API layer) and com.waenhancer.* to be resolved from moduleClassLoader
        if (name.startsWith("com.waex.api.") || name.startsWith("com.waenhancer.")) {
            if (moduleClassLoader != null) {
                return moduleClassLoader.loadClass(name);
            }
        }

        // 2. Allow de.robv.android.xposed.* (Xposed Framework) to be resolved robustly
        if (name.startsWith("de.robv.android.xposed.")) {
            if (name.equals("de.robv.android.xposed.XposedBridge")) {
                return de.robv.android.xposed.XposedBridge.class;
            }
            if (name.equals("de.robv.android.xposed.XC_MethodHook")) {
                return de.robv.android.xposed.XC_MethodHook.class;
            }
            if (name.equals("de.robv.android.xposed.XposedHelpers")) {
                return de.robv.android.xposed.XposedHelpers.class;
            }
            if (name.equals("de.robv.android.xposed.XC_MethodHook$MethodHookParam")) {
                return de.robv.android.xposed.XC_MethodHook.MethodHookParam.class;
            }
            if (name.equals("de.robv.android.xposed.XC_MethodHook$Unhook")) {
                return de.robv.android.xposed.XC_MethodHook.Unhook.class;
            }
            if (name.equals("de.robv.android.xposed.XSharedPreferences")) {
                return de.robv.android.xposed.XSharedPreferences.class;
            }

            android.util.Log.d("WAEX-Loader", "Attempting to load other Xposed class: " + name);
            try {
                Class<?> clazz = Class.forName(name, false, null);
                return clazz;
            } catch (Throwable ignored) {}
            if (moduleClassLoader != null) {
                try {
                    return moduleClassLoader.loadClass(name);
                } catch (Throwable ignored) {}
                try {
                    ClassLoader parent = moduleClassLoader.getParent();
                    if (parent != null) {
                        return parent.loadClass(name);
                    }
                } catch (Throwable ignored) {}
            }
            try {
                ClassLoader xposedLoader = de.robv.android.xposed.XposedBridge.class.getClassLoader();
                if (xposedLoader != null) {
                    return xposedLoader.loadClass(name);
                }
            } catch (Throwable ignored) {}
            try {
                ClassLoader threadLoader = Thread.currentThread().getContextClassLoader();
                if (threadLoader != null) {
                    return threadLoader.loadClass(name);
                }
            } catch (Throwable ignored) {}
            try {
                return Class.forName(name);
            } catch (Throwable ignored) {}
            android.util.Log.e("WAEX-Loader", "Failed all attempts to load other Xposed class: " + name);
        }

        // 3. Block com.waex.helper.* from parent delegation
        if (name.startsWith("com.waex.helper.")) {
            throw new ClassNotFoundException("Blocked delegation of plugin class to parent: " + name);
        }
        
        // 4. Reject any other host developer classes (com.waex.host.*)
        if (name.startsWith("com.waex.host.")) {
            throw new ClassNotFoundException("Blocked access to host class: " + name);
        }

        // 5. Delegate to the platform boot classloader for java.*, android.*, etc.
        try {
            return super.loadClass(name, resolve);
        } catch (ClassNotFoundException e) {
            // Fallback for other system/support libraries (like android/androidx platform classes loaded by the host)
            // strictly excluding any host developer packages
            if (!name.startsWith("com.waenhancer.") && !name.startsWith("com.waex.host.")) {
                return hostClassLoader.loadClass(name);
            }
            throw e;
        }
    }
}
