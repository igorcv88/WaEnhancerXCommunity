package com.waenhancer.diagnostics;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import com.waenhancer.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Fail-open, local-only recorder used from the hooked host process. */
public final class RuntimeDiagnostics {
    public static final String PREF_SNAPSHOT_WPP = "validation_runtime_snapshot_wpp";
    public static final String PREF_SNAPSHOT_BUSINESS = "validation_runtime_snapshot_business";
    private static final long HOT_PATH_WRITE_DELAY_MS = 500L;
    private static final Object LOCK = new Object();
    private static final ScheduledExecutorService WRITER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "WaEnhancerDiagnostics");
        thread.setDaemon(true);
        return thread;
    });

    private static JSONObject snapshot = new JSONObject();
    private static volatile Context appContext;
    private static boolean dirty;
    private static boolean writerScheduled;

    private RuntimeDiagnostics() {}

    public static void initialize(Context context, String hostPackage, String hostVersion, int xposedApi) {
        Context safeContext = safeContext(context);
        if (safeContext == null) return;
        appContext = safeContext;
        mutate(safeContext, root -> {
            root.put("hostPackage", hostPackage).put("hostVersion", hostVersion)
                    .put("moduleVersion", BuildConfig.VERSION_NAME).put("xposedApi", xposedApi)
                    .put("updatedAt", System.currentTimeMillis())
                    .put("probeDefinitive", false);
            root.remove("corePassed");
            root.remove("requiredFailures");
            root.remove("optionalFailures");
            if (!root.has("features")) root.put("features", new JSONObject());
        });
    }

    /** Records the early diagnostic probe. It is intentionally not considered definitive. */
    public static void probe(Context context, boolean core, List<String> required, List<String> optional) {
        recordProbe(context, core, required, optional, false);
    }

    /** Records the authoritative probe performed after DexKit has been initialized. */
    public static void probe(boolean core, List<String> required, List<String> optional) {
        recordProbe(appContext, core, required, optional, true);
    }

    private static void recordProbe(Context context, boolean core, List<String> required,
            List<String> optional, boolean definitive) {
        mutate(context, root -> root.put("corePassed", core)
                .put("requiredFailures", new JSONArray(required))
                .put("optionalFailures", new JSONArray(optional))
                .put("probeDefinitive", definitive)
                .put("updatedAt", System.currentTimeMillis()));
    }

    public static void feature(Context context, String name, String event, Throwable error) {
        mutate(context, root -> {
            JSONObject features = root.optJSONObject("features");
            if (features == null) { features = new JSONObject(); root.put("features", features); }
            JSONObject f = features.optJSONObject(name);
            if (f == null) { f = new JSONObject(); features.put(name, f); }
            long now = System.currentTimeMillis();
            f.put(event, true).put(event + "At", now).put("updatedAt", now);
            if (error != null) f.put("errorType", error.getClass().getSimpleName());
        }, false);
        if (error != null || "triggered".equals(event)) {
            requestPersist(context, HOT_PATH_WRITE_DELAY_MS);
        }
    }

    public static void opportunity(Context context, String surface) {
        mutate(context, root -> {
            JSONObject o = root.optJSONObject("opportunities");
            if (o == null) { o = new JSONObject(); root.put("opportunities", o); }
            o.put(surface, System.currentTimeMillis());
        }, false);
        requestPersist(context, HOT_PATH_WRITE_DELAY_MS);
    }

    private interface Change { void apply(JSONObject root) throws Exception; }

    private static void mutate(Context context, Change change) {
        mutate(context, change, true);
    }

    private static void mutate(Context context, Change change, boolean flush) {
        Context safeContext = safeContext(context);
        if (safeContext == null) return;
        try {
            synchronized (LOCK) {
                change.apply(snapshot);
                dirty = true;
            }
            if (flush) requestPersist(safeContext, 0L);
        } catch (Throwable ignored) { /* diagnostics must never break a hook */ }
    }

    public static void flush(Context context) {
        requestPersist(context, 0L);
    }

    private static void requestPersist(Context context, long delayMs) {
        Context safeContext = safeContext(context);
        if (safeContext == null) return;
        synchronized (LOCK) {
            if (!dirty || writerScheduled) return;
            writerScheduled = true;
        }
        scheduleWriter(safeContext, delayMs);
    }

    private static void scheduleWriter(Context context, long delayMs) {
        try {
            WRITER.schedule(() -> persistPending(context), Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
            synchronized (LOCK) { writerScheduled = false; }
        }
    }

    private static void persistPending(Context context) {
        final String key;
        final String value;
        synchronized (LOCK) {
            key = snapshotKey(context.getPackageName());
            value = snapshot.toString();
            dirty = false;
        }

        persist(context, key, value);

        boolean writeAgain;
        synchronized (LOCK) {
            writeAgain = dirty;
            if (!writeAgain) writerScheduled = false;
        }
        if (writeAgain) scheduleWriter(context, HOT_PATH_WRITE_DELAY_MS);
    }

    private static void persist(Context context, String key, String value) {
        try {
            Bundle extras = new Bundle();
            extras.putString("key", key);
            extras.putString("type", "string");
            extras.putString("value", value);
            context.getContentResolver().call(
                    Uri.parse("content://" + BuildConfig.APPLICATION_ID + ".hookprovider"),
                    "put_preference", null, extras);
        } catch (Throwable ignored) { }
    }

    private static Context safeContext(Context context) {
        if (context == null) return null;
        Context application = context.getApplicationContext();
        return application == null ? context : application;
    }

    public static String snapshotKey(String packageName) {
        return "com.whatsapp.w4b".equals(packageName) ? PREF_SNAPSHOT_BUSINESS : PREF_SNAPSHOT_WPP;
    }
}
