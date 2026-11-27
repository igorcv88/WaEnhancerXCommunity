package com.waenhancer.xposed.core;

import com.waenhancer.diagnostics.RuntimeDiagnostics;

import androidx.annotation.NonNull;

import android.content.SharedPreferences;

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

        // Diagnostic events (loaded, installed, error) are recorded by the loader around
        // construction and doHook, so a feature cannot report itself installed before it is.
    }

    /**
     * A hooked callback of this feature ran. It says the hook point is live, not that the feature
     * did anything: callbacks also run for traffic the feature ignores. Call it at callback entry.
     */
    protected final void diagnosticTriggered() {
        RuntimeDiagnostics.feature(getClass().getSimpleName(), "reached");
    }

    /**
     * The feature changed the host's behaviour on {@code path} (for example it suppressed a read
     * receipt in a direct chat). Call it after the decision, and only when the feature acted.
     */
    protected final void diagnosticActed(String path) {
        RuntimeDiagnostics.acted(getClass().getSimpleName(), path);
    }

    /** The feature installed, but an optional part of it did not (reported, not fatal). */
    protected final void diagnosticDegraded() {
        RuntimeDiagnostics.feature(getClass().getSimpleName(), "degraded");
    }

    /**
     * Installs the shared AB-override hook this feature relies on. Without it the feature keeps
     * its other hooks and reports a degraded install instead of assuming another feature did it.
     */
    protected final boolean hostPropsOrDegraded() {
        try {
            HostProps.requireBooleanOverrides(classLoader);
            return true;
        } catch (Throwable t) {
            de.robv.android.xposed.XposedBridge.log("[WAEX] " + getClass().getSimpleName()
                    + ": AB boolean overrides unavailable: " + t.getMessage());
            diagnosticDegraded();
            return false;
        }
    }

    /** As {@link #hostPropsOrDegraded}, for features that force integer properties. */
    protected final boolean hostIntegerPropsOrDegraded() {
        try {
            HostProps.requireIntegerOverrides(classLoader);
            return true;
        } catch (Throwable t) {
            de.robv.android.xposed.XposedBridge.log("[WAEX] " + getClass().getSimpleName()
                    + ": AB integer overrides unavailable: " + t.getMessage());
            diagnosticDegraded();
            return false;
        }
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
