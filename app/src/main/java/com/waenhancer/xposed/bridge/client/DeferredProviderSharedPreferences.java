package com.waenhancer.xposed.bridge.client;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.waenhancer.xposed.core.FeatureLoader;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

import de.robv.android.xposed.XSharedPreferences;

/**
 * Defers construction of the provider-backed preference bridge until the hooked host has an
 * {@link Application}. Before that point the legacy XSharedPreferences instance remains a
 * read-only fallback; from the first host startup preference read onward, the UID-validated
 * HookProvider becomes authoritative and the legacy store is retained only as fallback data.
 *
 * <p>This is intentionally lazy because {@code handleLoadPackage()} runs before an application
 * context exists. It also makes the provider available before FeatureLoader evaluates startup
 * settings such as supported-version customization and bypass_version_check, rather than only
 * after those decisions have already been made.</p>
 */
public final class DeferredProviderSharedPreferences implements SharedPreferences {

    private final SharedPreferences fallbackPrefs;
    private volatile SharedPreferences delegate;

    public DeferredProviderSharedPreferences(SharedPreferences fallbackPrefs) {
        this.fallbackPrefs = Objects.requireNonNull(fallbackPrefs, "fallbackPrefs");
    }

    private SharedPreferences current() {
        SharedPreferences resolved = delegate;
        if (resolved != null) return resolved;

        Application app = FeatureLoader.mApp;
        if (app == null) {
            return fallbackPrefs;
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

    /** Used by Feature.reloadPrefs(), whose provider bridge is deliberately reflection-friendly. */
    public void reload() {
        SharedPreferences resolved = current();
        if (resolved instanceof ProviderSharedPreferences) {
            ((ProviderSharedPreferences) resolved).reload();
        } else if (resolved instanceof XSharedPreferences) {
            ((XSharedPreferences) resolved).reload();
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
}
