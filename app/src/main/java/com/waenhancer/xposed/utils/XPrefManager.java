package com.waenhancer.xposed.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.waenhancer.BuildConfig;
import com.waenhancer.config.PreferenceStores;
import com.waenhancer.xposed.bridge.client.ProviderSharedPreferences;

/**
 * Process-local preference access point.
 *
 * <p>Hooked WhatsApp processes use the UID-validated HookProvider bridge. The companion app reads
 * its own normal default preference store. This class no longer constructs XSharedPreferences, so
 * it remains valid after LSPosed removes New XSharedPreferences support.</p>
 */
public final class XPrefManager {

    private static volatile SharedPreferences pref;

    private XPrefManager() {
    }

    public static void setPref(SharedPreferences preferences) {
        pref = preferences;
    }

    public static SharedPreferences getPref() {
        SharedPreferences current = pref;
        if (current != null) return current;
        current = Utils.xprefs;
        if (current != null) {
            pref = current;
        }
        return current;
    }

    public static void reload() {
        SharedPreferences current = getPref();
        if (current == null) return;
        try {
            current.getClass().getMethod("reload").invoke(current);
        } catch (NoSuchMethodException ignored) {
            // Ordinary app-local SharedPreferences are already live in-process.
        } catch (Throwable ignored) {
        }
    }

    public static SharedPreferences getPref(Context context) {
        SharedPreferences current = getPref();
        if (current != null) return current;
        if (context == null) return null;

        Context appContext = context.getApplicationContext();
        if (appContext == null) appContext = context;

        if (BuildConfig.APPLICATION_ID.equals(appContext.getPackageName())) {
            current = PreferenceStores.publicStore(appContext);
        } else {
            SharedPreferences localPrefs = appContext.getSharedPreferences(
                    "wae_embedded_prefs", Context.MODE_PRIVATE);
            current = new ProviderSharedPreferences(appContext, localPrefs, null);
        }
        pref = current;
        return current;
    }
}
