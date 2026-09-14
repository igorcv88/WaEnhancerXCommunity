package com.waenhancer.xposed.core;

import android.util.Log;

import androidx.annotation.NonNull;

import android.content.SharedPreferences;
import de.robv.android.xposed.XposedBridge;

public abstract class Feature {

    public final ClassLoader classLoader;
    public final SharedPreferences prefs;
    public static boolean DEBUG = false;

    // Global tracking to prevent hook leaks if doHook is called multiple times
    private static final java.util.Set<java.lang.reflect.Member> hookedMethods = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    protected boolean markHooked(java.lang.reflect.Member member) {
        if (member == null) return false;
        return hookedMethods.add(member);
    }

    public Feature(@NonNull ClassLoader classLoader, @NonNull SharedPreferences preferences) {
        this.classLoader = classLoader;
        this.prefs = preferences;

        // The old diagnostics code had an initialize() method and a definitive HostCompatibility
        // probe, but neither was ever invoked. Feature construction happens after FeatureLoader has
        // initialized DexKit, so the first Feature is a safe one-shot point to bind the snapshot to
        // the real host/module build and execute the authoritative semantic resolver probe.
        boolean diagnosticsInitialized = com.waenhancer.diagnostics.RuntimeDiagnostics.initializeOnce(
                FeatureLoader.mApp);
        if (diagnosticsInitialized) {
            try {
                HostCompatibility.probe(classLoader);
            } catch (Throwable t) {
                com.waenhancer.diagnostics.RuntimeDiagnostics.probeFailure(FeatureLoader.mApp, t);
            }
        }

        com.waenhancer.diagnostics.RuntimeDiagnostics.feature(
                FeatureLoader.mApp, getClass().getSimpleName(), "loaded", null);
    }

    /** Call from a hook callback, never from installation, so Installed is not confused with Working. */
    protected final void diagnosticTriggered() {
        com.waenhancer.diagnostics.RuntimeDiagnostics.feature(
                FeatureLoader.mApp, getClass().getSimpleName(), "triggered", null);
    }

    public abstract void doHook() throws Throwable;

    @NonNull
    public abstract String getPluginName();

    public void logDebug(Object object) {
    }

    public void logDebug(String title, Object object) {
    }


    public void log(Object object) {
    }

    public void logError(Object object) {
        if (object instanceof Throwable) {
            // XposedBridge.log(String.format("[%s] CRITICAL ERROR:", this.getPluginName()));
            // XposedBridge.log((Throwable) object);
        } else {
            // XposedBridge.log(String.format("[%s] CRITICAL ERROR: %s", this.getPluginName(), object));
        }
    }

    protected void reloadPrefs() {
        try {
            java.lang.reflect.Method reload = prefs.getClass().getMethod("reload");
            reload.invoke(prefs);
        } catch (NoSuchMethodException ignored) {
            // Ordinary SharedPreferences are live in-process and do not need an explicit reload.
        } catch (Throwable ignored) {
        }
    }

    protected String getSafeString(String key, String defaultValue) {
        try {
            reloadPrefs();
            Object val = prefs.getAll().get(key);
            if (val == null) return defaultValue;
            if (val instanceof String) return (String) val;
            if (val instanceof Boolean) return (Boolean) val ? "1" : "0";
            return String.valueOf(val);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    protected float getSafeFloat(String key, float defaultValue) {
        try {
            Object val = prefs.getAll().get(key);
            if (val == null) return defaultValue;
            if (val instanceof Float) return (Float) val;
            if (val instanceof Integer) return ((Integer) val).floatValue();
            if (val instanceof String) return Float.parseFloat((String) val);
            if (val instanceof Double) return ((Double) val).floatValue();
            return defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
