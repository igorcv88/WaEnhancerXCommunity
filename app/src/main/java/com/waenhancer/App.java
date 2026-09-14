package com.waenhancer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.preference.PreferenceManager;

import com.waenhancer.diagnostics.LocalDiagnostics;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.material.app.LocaleDelegate;

public class App extends Application {

    private static App instance;
    private static final ExecutorService executorService = Executors.newCachedThreadPool();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private android.content.SharedPreferences.OnSharedPreferenceChangeListener nspSnapshotListener;

    public static void showRequestStoragePermission(Activity activity) {
        com.waenhancer.ui.helpers.BottomSheetHelper.showConfirmation(
                activity,
                activity.getString(R.string.storage_permission),
                activity.getString(R.string.permission_storage),
                activity.getString(R.string.allow),
                false,
                () -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Intent intent = new Intent(
                                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        intent.setData(Uri.fromParts("package", activity.getPackageName(), null));
                        activity.startActivity(intent);
                    } else {
                        ActivityCompat.requestPermissions(activity,
                                new String[]{
                                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                        android.Manifest.permission.READ_EXTERNAL_STORAGE
                                },
                                0);
                    }
                });
    }

    @SuppressLint("ApplySharedPref")
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        LocalDiagnostics.record(this, "lifecycle", "Manager process started");

        var sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        // Phase 1 of removing LSPosed New XSharedPreferences: while NSP is still declared, the
        // helper only captures a typed copy in filesDir. Once a later release removes the NSP
        // metadata, the same helper restores that copy into the normal app-private stores before
        // any setting is consumed.
        try {
            com.waenhancer.config.NspPreferenceMigration.restoreIfNeeded(this);
            sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        } catch (RuntimeException ignored) {
        }

        if (sharedPreferences.getBoolean("verify_blocked_contact", false)) {
            sharedPreferences.edit().putBoolean("verify_blocked_contact", false).apply();
        }

        // Keep the legacy preference bridge operational until the staged NSP migration has been
        // validated in a released build. Phase 2 will remove the NSP manifest metadata only after
        // the provider-backed runtime path and snapshot have both been exercised on-device.
        File prefFile = new File(
                getApplicationInfo().dataDir,
                "shared_prefs/" + getPackageName() + "_preferences.xml");
        if (!prefFile.exists()) {
            sharedPreferences.edit().putBoolean("init_prefs_creation", true).commit();
        }

        // Phase C2/C3 storage work, in this order: copy anything the hooked process must not be
        // able to read into the private store, then mint the automation token if it is missing,
        // then remove the secrets from the world-readable file now that a UID-validated reader
        // exists. The removal step verifies the private copy itself and refuses otherwise, so a
        // failure here leaves the old location intact rather than losing the value.
        try {
            com.waenhancer.config.PreferenceMigration.copyPrivateValues(this);

            android.content.SharedPreferences privateStore =
                    com.waenhancer.config.PreferenceStores.privateStore(this);
            if (privateStore.getString(
                    com.waenhancer.security.TaskerGuard.KEY_SECRET, null) == null) {
                privateStore.edit()
                        .putString(com.waenhancer.security.TaskerGuard.KEY_SECRET,
                                com.waenhancer.security.TaskerGuard.newSecret())
                        .commit();
            }

            com.waenhancer.config.PreferenceMigration.removeMigratedSecrets(this, true);
        } catch (RuntimeException ignored) {
            // Storage migration must never stop the app from starting.
        }

        try {
            com.waenhancer.config.NspPreferenceMigration.captureIfNeeded(this);
        } catch (RuntimeException ignored) {
        }

        int mode;
        try {
            mode = Integer.parseInt(sharedPreferences.getString("thememode", "0"));
        } catch (RuntimeException ignored) {
            mode = 0;
        }
        setThemeMode(mode);
        changeLanguage(this);

        sharedPreferences.registerOnSharedPreferenceChangeListener((prefs, key) -> {
            try {
                getContentResolver().notifyChange(
                        Uri.parse("content://" + BuildConfig.APPLICATION_ID + ".hookprovider/preferences"),
                        null);
            } catch (RuntimeException ignored) {
            }
        });

        // Hold a strong reference because SharedPreferences listeners are weakly referenced by the
        // framework. Capture both stores after any change so the final NSP-removal release has a
        // current migration snapshot even if the user upgrades without restarting the manager.
        nspSnapshotListener = (prefs, key) -> executorService.execute(() -> {
            try {
                com.waenhancer.config.NspPreferenceMigration.captureIfNeeded(this);
            } catch (RuntimeException ignored) {
            }
        });
        sharedPreferences.registerOnSharedPreferenceChangeListener(nspSnapshotListener);
        com.waenhancer.config.PreferenceStores.privateStore(this)
                .registerOnSharedPreferenceChangeListener(nspSnapshotListener);

        final Thread.UncaughtExceptionHandler originalHandler =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Throwable current = throwable;
            while (current != null) {
                String className = current.getClass().getName();
                if ("android.os.DeadSystemRuntimeException".equals(className)
                        || "android.os.DeadSystemException".equals(className)
                        || "android.os.DeadObjectException".equals(className)) {
                    System.exit(0);
                    return;
                }
                current = current.getCause();
            }

            if (originalHandler != null) {
                originalHandler.uncaughtException(thread, throwable);
            }
        });
    }

    public static void setThemeMode(int mode) {
        switch (mode) {
            case 1:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case 2:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case 0:
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }

    public static App getInstance() {
        return instance;
    }

    public static ExecutorService getExecutorService() {
        return executorService;
    }

    public static Handler getMainHandler() {
        return mainHandler;
    }

    public void restartApp(String packageWpp) {
        Intent intent = new Intent(BuildConfig.APPLICATION_ID + ".WHATSAPP.RESTART");
        intent.putExtra("PKG", packageWpp);
        sendBroadcast(intent);
    }

    public static void changeLanguage(Context context) {
        boolean forceEnglish = PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("force_english", false);
        LocaleDelegate.setDefaultLocale(forceEnglish ? Locale.ENGLISH : Locale.getDefault());
        var resources = context.getResources();
        var configuration = resources.getConfiguration();
        configuration.setLocale(LocaleDelegate.getDefaultLocale());
        //noinspection deprecation
        resources.updateConfiguration(configuration, resources.getDisplayMetrics());
    }

    public static File getWaEnhancerFolder() {
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File folder = new File(downloads, "WaEnhancerCommunity");
        if (!folder.exists()) {
            folder.mkdirs();
        }
        return folder;
    }

    public static boolean isOriginalPackage() {
        return BuildConfig.APPLICATION_ID.equals("com.waenhancer.community") || BuildConfig.DEBUG;
    }
}
