package com.waenhancer.xposed.bridge.client;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.waenhancer.xposed.core.FeatureLoader;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Defers construction of the provider-backed preference bridge until the hooked host has an
 * {@link Application}. Before that point a small empty preference view returns caller defaults;
 * from the first host startup preference read onward, the UID-validated HookProvider is
 * authoritative.
 *
 * <p>This class deliberately has no XSharedPreferences fallback. FeatureLoader installs it before
 * the host Application exists but does not consume startup settings until
 * {@code callApplicationOnCreate}, after {@link FeatureLoader#mApp} has been assigned.</p>
 */
public final class DeferredProviderSharedPreferences implements SharedPreferences {

    private static final SharedPreferences EMPTY = new EmptySharedPreferences();
    @Nullable
    private final SharedPreferences fallbackPrefs;
    private volatile SharedPreferences delegate;

    public DeferredProviderSharedPreferences() {
        this(null);
    }

    /** Generic fallback retained only for tests/compatibility; runtime does not pass XSP here. */
    public DeferredProviderSharedPreferences(@Nullable SharedPreferences fallbackPrefs) {
        this.fallbackPrefs = fallbackPrefs;
    }

    private SharedPreferences current() {
        SharedPreferences resolved = delegate;
        if (resolved != null) return resolved;

        Application app = FeatureLoader.mApp;
        if (app == null) {
            return fallbackPrefs != null ? fallbackPrefs : EMPTY;
        }

        synchronized (this) {
            resolved = delegate;
            if (resolved == null) {
                SharedPreferences localPrefs = app.getSharedPreferences(
                        "wae_embedded_prefs", Context.MODE_PRIVATE);
                resolved = new ProviderSharedPreferences(app, localPrefs, fallbackPrefs);
                delegate = resolved;
            }
        }
        return resolved;
    }

    /** Used by Feature.reloadPrefs() and XPrefManager.reload(). */
    public void reload() {
        SharedPreferences resolved = current();
        if (resolved instanceof ProviderSharedPreferences) {
            ((ProviderSharedPreferences) resolved).reload();
            return;
        }
        try {
            resolved.getClass().getMethod("reload").invoke(resolved);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public Map<String, ?> getAll() {
        return current().getAll();
    }

    @Nullable
    @Override
    public String getString(String key, @Nullable String defValue) {
        return current().getString(key, defValue);
    }

    @Nullable
    @Override
    public Set<String> getStringSet(String key, @Nullable Set<String> defValues) {
        return current().getStringSet(key, defValues);
    }

    @Override
    public int getInt(String key, int defValue) {
        return current().getInt(key, defValue);
    }

    @Override
    public long getLong(String key, long defValue) {
        return current().getLong(key, defValue);
    }

    @Override
    public float getFloat(String key, float defValue) {
        return current().getFloat(key, defValue);
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        return current().getBoolean(key, defValue);
    }

    @Override
    public boolean contains(String key) {
        return current().contains(key);
    }

    @Override
    public Editor edit() {
        return current().edit();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        current().registerOnSharedPreferenceChangeListener(listener);
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        current().unregisterOnSharedPreferenceChangeListener(listener);
    }

    private static final class EmptySharedPreferences implements SharedPreferences {
        private static final Editor EMPTY_EDITOR = new EmptyEditor();

        @Override
        public Map<String, ?> getAll() {
            return Collections.emptyMap();
        }

        @Nullable
        @Override
        public String getString(String key, @Nullable String defValue) {
            return defValue;
        }

        @Nullable
        @Override
        public Set<String> getStringSet(String key, @Nullable Set<String> defValues) {
            return defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            return defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            return defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            return defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            return defValue;
        }

        @Override
        public boolean contains(String key) {
            return false;
        }

        @Override
        public Editor edit() {
            return EMPTY_EDITOR;
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }
    }

    private static final class EmptyEditor implements Editor {
        @Override public Editor putString(String key, @Nullable String value) { return this; }
        @Override public Editor putStringSet(String key, @Nullable Set<String> values) { return this; }
        @Override public Editor putInt(String key, int value) { return this; }
        @Override public Editor putLong(String key, long value) { return this; }
        @Override public Editor putFloat(String key, float value) { return this; }
        @Override public Editor putBoolean(String key, boolean value) { return this; }
        @Override public Editor remove(String key) { return this; }
        @Override public Editor clear() { return this; }
        @Override public boolean commit() { return true; }
        @Override public void apply() { }
    }
}
