package com.waex.api.plugin;

import android.content.SharedPreferences;

public class PluginSupport {
    private final ClassLoader classLoader;
    private final SharedPreferences prefs;

    public PluginSupport(ClassLoader classLoader, SharedPreferences prefs) {
        this.classLoader = classLoader;
        this.prefs = prefs;
    }

    public ClassLoader getClassLoader() {
        return classLoader;
    }

    public SharedPreferences getPrefs() {
        return prefs;
    }
}
