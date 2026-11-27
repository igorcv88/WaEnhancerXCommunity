package com.waenhancer.diagnostics;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;

import com.waenhancer.BuildConfig;

import org.json.JSONObject;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Local recorder for an explicitly started diagnostic session. */
public final class RuntimeDiagnostics {
    public static final String METHOD_BEGIN = "validation_begin";
    public static final String METHOD_WRITE = "validation_write";
    static final int MAX_SNAPSHOT_BYTES = 64 * 1024;
    static final long MAX_SESSION_MS = 30 * 60 * 1000L;
    private static final Set<String> EVENTS = Set.of("loaded", "resolverFailed", "installed", "triggered", "error");
    private static final Object LOCK = new Object();
    private static final ScheduledExecutorService WRITER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "WaEnhancerDiagnostics");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile Run run;
    private static boolean attempted;
    private static boolean writerScheduled;

    private RuntimeDiagnostics() {}

    /** Called once on a feature worker after the host DexKit bridge has opened. */
    public static boolean initializeOnce(Context context, SharedPreferences prefs) {
        synchronized (LOCK) {
            if (attempted || context == null) return false;
            attempted = true;
        }
        try {
            Bundle request = new Bundle();
            request.putLong("processStart", android.os.Process.getStartElapsedRealtime());
            request.putInt("moduleVersionCode", BuildConfig.VERSION_CODE);
            Bundle response = call(context, METHOD_BEGIN, request);
            if (response == null) return false;

            String actualConfig = ValidationIdentity.configuration(prefs);
            if (!actualConfig.equals(response.getString("configuration"))) return false;

            JSONObject root = new JSONObject()
                    .put("schema", 2)
                    .put("session", response.getString("session"))
                    .put("process", response.getString("process"))
                    .put("configuration", actualConfig)
                    .put("xposedApi", de.robv.android.xposed.XposedBridge.getXposedVersion())
                    .put("source", "OPT_IN_RUNTIME")
                    .put("features", new JSONObject());
            run = new Run(context, prefs, response.getLong("deadline"), actualConfig, root);
            update(r -> {});
            return true;
        } catch (Exception | LinkageError ignored) {
            return false;
        }
    }

    public static void probe(boolean core, Collection<JSONObject> contracts) {
        update(root -> root.put("corePassed", core)
                .put("contracts", new org.json.JSONArray(contracts))
                .put("probeDefinitive", true));
    }

    public static void probeFailure() {
        update(root -> root.put("probeError", true));
    }

    public static void feature(String name, String event) {
        if (!EVENTS.contains(event)) return;
        update(root -> {
            JSONObject features = root.getJSONObject("features");
            JSONObject f = features.optJSONObject(name);
            if (f == null) {
                if (features.length() >= 128) return;
                f = new JSONObject();
                features.put(name, f);
            }
            long now = System.currentTimeMillis();
            if (!f.has(event) || ("triggered".equals(event) && now - f.optLong(event) >= 500L)) {
                f.put(event, now);
            }
        });
    }

    private interface Change { void apply(JSONObject root) throws Exception; }

    private static void update(Change change) {
        Run current = run;
        if (current == null || SystemClock.elapsedRealtime() > current.deadline) return;
        synchronized (LOCK) {
            try {
                change.apply(current.report);
                if (!writerScheduled) {
                    writerScheduled = true;
                    WRITER.schedule(() -> persist(current), 500L, TimeUnit.MILLISECONDS);
                }
            } catch (Exception ignored) { /* diagnostics cannot break a hook */ }
        }
    }

    private static void persist(Run current) {
        try {
            String value;
            synchronized (LOCK) {
                writerScheduled = false;
                current.report.put("updatedAt", System.currentTimeMillis());
                value = current.report.toString();
            }
            if (SystemClock.elapsedRealtime() > current.deadline
                    || !current.configuration.equals(
                    ValidationIdentity.configuration(current.prefs))) {
                run = null;
                return;
            }
            Bundle request = new Bundle();
            request.putString("report", value);
            if (call(current.context, METHOD_WRITE, request) == null) run = null;
        } catch (Exception ignored) {
            run = null;
        }
    }

    private static Bundle call(Context context, String method, Bundle request) {
        return context.getContentResolver().call(
                Uri.parse("content://" + BuildConfig.APPLICATION_ID + ".hookprovider"),
                method, null, request);
    }

    private static final class Run {
        final Context context;
        final SharedPreferences prefs;
        final long deadline;
        final String configuration;
        final JSONObject report;

        Run(Context context, SharedPreferences prefs, long deadline, String configuration, JSONObject report) {
            this.context = context;
            this.prefs = prefs;
            this.deadline = deadline;
            this.configuration = configuration;
            this.report = report;
        }
    }

    static String snapshotKey(String packageName) {
        return "com.whatsapp.w4b".equals(packageName)
                ? "validation_runtime_snapshot_business" : "validation_runtime_snapshot_wpp";
    }
}
