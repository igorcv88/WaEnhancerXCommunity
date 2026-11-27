package com.waenhancer.diagnostics;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;

import com.waenhancer.BuildConfig;
import com.waenhancer.config.PreferenceStores;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Companion-side state for a local diagnostic session, scoped to one host process. */
public final class ValidationSession {
    private static final String ACTIVE = "validation_session_active";
    private static final String TARGET = "validation_session_target";
    private static final String STARTED = "validation_session_started";
    private static final String ID = "validation_session_id";
    private static final String STARTED_ELAPSED = "validation_session_started_elapsed";
    private static final String DEADLINE = "validation_session_deadline";
    private static final String CONFIGURATION = "validation_session_configuration";
    private static final String PROCESS = "validation_session_process";
    private static final String EXERCISE_PREFIX = "validation_exercise_";
    private static final String MANUAL_PREFIX = "validation_manual_";
    private ValidationSession() {}

    public static synchronized boolean start(Context context, SharedPreferences prefs, String targetPackage) {
        if (!validTarget(targetPackage)) return false;
        try {
            String config = configuration(context);
            long startedElapsed = SystemClock.elapsedRealtime();
            SharedPreferences.Editor editor = clear(prefs);
            editor.putBoolean(ACTIVE, true).putString(TARGET, targetPackage)
                    .putString(ID, UUID.randomUUID().toString()).putString(CONFIGURATION, config)
                    .putLong(STARTED, System.currentTimeMillis()).putLong(STARTED_ELAPSED, startedElapsed)
                    .putLong(DEADLINE, startedElapsed + RuntimeDiagnostics.MAX_SESSION_MS).apply();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static synchronized void reset(SharedPreferences prefs) {
        clear(prefs).apply();
    }

    private static SharedPreferences.Editor clear(SharedPreferences prefs) {
        SharedPreferences.Editor editor = prefs.edit()
                .remove(ACTIVE).remove(TARGET).remove(STARTED).remove(ID).remove(PROCESS)
                .remove(STARTED_ELAPSED).remove(DEADLINE).remove(CONFIGURATION)
                .remove(RuntimeDiagnostics.snapshotKey("com.whatsapp"))
                .remove(RuntimeDiagnostics.snapshotKey("com.whatsapp.w4b"));
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(MANUAL_PREFIX) || key.startsWith(EXERCISE_PREFIX)) editor.remove(key);
        }
        return editor;
    }

    public static boolean active(SharedPreferences prefs) {
        long now = SystemClock.elapsedRealtime();
        long since = System.currentTimeMillis() - prefs.getLong(STARTED, 0L);
        return prefs.getBoolean(ACTIVE, false) && !prefs.getString(ID, "").isEmpty()
                && now >= prefs.getLong(STARTED_ELAPSED, Long.MAX_VALUE)
                && now <= prefs.getLong(DEADLINE, 0L) && since >= 0L
                && since <= RuntimeDiagnostics.MAX_SESSION_MS;
    }

    private static String target(SharedPreferences prefs) { return prefs.getString(TARGET, ""); }

    /** Called by the provider only after authenticating the exact target caller. */
    public static synchronized Bundle bindProcess(Context context, String caller, Bundle request) {
        SharedPreferences prefs = PreferenceStores.privateStore(context);
        if (!active(prefs) || !validTarget(caller) || !caller.equals(target(prefs))
                || request.getLong("processStart", 0L) < prefs.getLong(STARTED_ELAPSED, Long.MAX_VALUE)
                || request.getLong("processStart", 0L) > SystemClock.elapsedRealtime()
                || request.getInt("moduleVersionCode", -1) != BuildConfig.VERSION_CODE) return null;
        try {
            String config = configuration(context);
            if (!config.equals(prefs.getString(CONFIGURATION, ""))) return null;
            String process = UUID.randomUUID().toString();
            var editor = prefs.edit().putString(PROCESS, process)
                    .remove(RuntimeDiagnostics.snapshotKey(caller));
            for (String key : prefs.getAll().keySet()) {
                if (key.startsWith(EXERCISE_PREFIX)) editor.remove(key);
            }
            editor.apply();
            Bundle response = new Bundle();
            response.putString("session", prefs.getString(ID, ""));
            response.putString("process", process);
            response.putString("configuration", config);
            response.putLong("deadline", prefs.getLong(DEADLINE, 0L));
            return response;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** APK hashing runs on the incoming Binder worker. Re-check the lease after hashing. */
    public static Bundle storeSnapshot(Context context, String caller, String raw) {
        if (!validTarget(caller) || raw == null || raw.length() > RuntimeDiagnostics.MAX_SNAPSHOT_BYTES
                || raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    > RuntimeDiagnostics.MAX_SNAPSHOT_BYTES) return null;
        SharedPreferences prefs = PreferenceStores.privateStore(context);
        try {
            JSONObject report = new JSONObject(raw);
            if (!currentLease(context, prefs, caller, report) || report.optInt("xposedApi") <= 0) return null;
            JSONObject identity = ValidationIdentity.read(context, caller);
            synchronized (ValidationSession.class) {
                if (!currentLease(context, prefs, caller, report)
                        || !identity.getString("installation").equals(
                        ValidationIdentity.installation(context, caller))) return null;
                report.put("identity", identity);
                prefs.edit().putString(RuntimeDiagnostics.snapshotKey(caller), report.toString()).apply();
                return Bundle.EMPTY;
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean currentLease(Context context, SharedPreferences prefs, String pkg, JSONObject report)
            throws Exception {
        return validTarget(pkg) && active(prefs) && pkg.equals(target(prefs))
                && report.optInt("schema") == 2
                && ValidationModel.sameRun(prefs.getString(ID, ""), prefs.getString(PROCESS, ""),
                    prefs.getString(CONFIGURATION, ""), report.optString("session"),
                    report.optString("process"), report.optString("configuration"))
                && report.optString("configuration").equals(configuration(context));
    }

    public static synchronized void armExercise(Context context, SharedPreferences prefs, String feature) {
        FeatureCatalog.Entry entry = FeatureCatalog.entries().get(feature);
        if (entry == null || !entry.required || !entry.manual
                || currentSnapshot(context, prefs, target(prefs)).length() == 0) return;
        prefs.edit().putLong(EXERCISE_PREFIX + feature, System.currentTimeMillis())
                .remove(MANUAL_PREFIX + feature).apply();
    }

    public static boolean canExercise(Context context, SharedPreferences prefs) {
        return currentSnapshot(context, prefs, target(prefs)).length() != 0;
    }

    public static synchronized void setManual(Context context, SharedPreferences prefs,
            String feature, boolean confirmed) {
        FeatureCatalog.Entry entry = FeatureCatalog.entries().get(feature);
        if (entry == null || !entry.required || !entry.manual) return;
        JSONObject report = currentSnapshot(context, prefs, target(prefs));
        ValidationModel.FeatureEvidence observed = evidence(context, prefs, report).get(feature);
        if (!confirmed || observed.state() != ValidationModel.Status.TRIGGERED_UNVERIFIED) {
            prefs.edit().remove(MANUAL_PREFIX + feature).apply();
            return;
        }
        prefs.edit().putString(MANUAL_PREFIX + feature, report.optString("process")).apply();
    }

    public static boolean canConfirm(Context context, SharedPreferences prefs, String feature) {
        var entry = evidence(context, prefs, currentSnapshot(context, prefs, target(prefs))).get(feature);
        return entry != null && entry.state() == ValidationModel.Status.TRIGGERED_UNVERIFIED;
    }

    public static boolean manual(SharedPreferences prefs, String feature) {
        String process = prefs.getString(PROCESS, "");
        Object stored = prefs.getAll().get(MANUAL_PREFIX + feature);
        return active(prefs) && !process.isEmpty() && process.equals(stored);
    }

    private static Map<String, ValidationModel.FeatureEvidence> evidence(Context context,
            SharedPreferences prefs, JSONObject report) {
        LinkedHashMap<String, ValidationModel.FeatureEvidence> result = new LinkedHashMap<>();
        JSONObject runtime = report.optJSONObject("features");
        long started = prefs.getLong(STARTED, Long.MAX_VALUE);
        long captured = report.optLong("updatedAt", 0L);
        SharedPreferences publicPrefs = PreferenceStores.publicStore(context);
        for (Map.Entry<String, FeatureCatalog.Entry> item : FeatureCatalog.entries().entrySet()) {
            String name = item.getKey();
            ValidationModel.FeatureEvidence e = new ValidationModel.FeatureEvidence();
            JSONObject f = runtime == null ? null : runtime.optJSONObject(name);
            e.required = item.getValue().required;
            e.instrumented = FeatureCatalog.hasRuntimeProbe(name);
            e.enabled = FeatureCatalog.enabled(publicPrefs, name);
            e.manualConfirmed = runtime != null && manual(prefs, name);
            e.loaded = observed(f, "loaded", started, captured);
            e.installed = observed(f, "installed", started, captured);
            long exercisedAt = prefs.getLong(EXERCISE_PREFIX + name, Long.MAX_VALUE);
            e.triggered = observed(f, "triggered", e.required ? Math.max(started, exercisedAt) : started, captured);
            e.error = observed(f, "error", started, captured);
            e.resolverFailed = observed(f, "resolverFailed", started, captured);
            result.put(name, e);
        }
        return result;
    }

    private static boolean observed(JSONObject feature, String event, long started, long captured) {
        return feature != null && ValidationModel.occurredDuringSession(
                feature.optLong(event, 0L), started, captured);
    }

    public static String buildReport(Context context, SharedPreferences prefs) {
        String pkg = target(prefs);
        JSONObject report = currentSnapshot(context, prefs, pkg);
        Map<String, ValidationModel.FeatureEvidence> evidence = evidence(context, prefs, report);
        StringBuilder out = new StringBuilder("WaEnhancer Community compatibility evidence\n");
        out.append("Target: ").append(pkg).append('\n');
        out.append("Session: ").append(active(prefs) ? "ACTIVE" : "NOT STARTED OR EXPIRED").append('\n');
        out.append("Core resolver probe: ").append(label(aggregate(report, evidence))).append('\n');
        JSONObject identity = report.optJSONObject("identity");
        if (identity != null) {
            identity.remove("installation");
            out.append("Identity: ").append(identity).append('\n');
            out.append("Session ID: ").append(report.optString("session")).append('\n');
            out.append("Process ID: ").append(report.optString("process")).append('\n');
            out.append("Configuration (schema-approved scalar settings): ")
                    .append(report.optString("configuration")).append('\n');
            out.append("Xposed API: ").append(report.optInt("xposedApi")).append('\n');
            out.append("Source: OPT_IN_RUNTIME; timestamp: ").append(report.optLong("updatedAt")).append('\n');
        }
        if (report.optBoolean("probeError")) out.append("Core probe execution failed\n");
        JSONArray contracts = report.optJSONArray("contracts");
        if (contracts != null) for (int i = 0; i < contracts.length(); i++) {
            out.append(contracts.optJSONObject(i)).append('\n');
        }
        out.append("\nCallback probes (L3): ").append(FeatureCatalog.runtimeProbeCount()).append('\n');
        for (var item : evidence.entrySet()) {
            var e = item.getValue();
            out.append("- ").append(item.getKey()).append(": ").append(e.state());
            if (e.installed) out.append(" · L2");
            if (e.triggered && e.required) {
                out.append(" · L3 at ").append(report.optJSONObject("features")
                        .optJSONObject(item.getKey()).optLong("triggered"));
            }
            if (e.required && e.manualConfirmed && e.state() == ValidationModel.Status.TRIGGERED_UNVERIFIED)
                out.append(" · user observation recorded");
            out.append('\n');
        }
        var runtime = report.optJSONObject("features");
        if (runtime != null) {
            var names = runtime.keys();
            while (names.hasNext()) {
                String name = names.next();
                if (evidence.containsKey(name) || !name.matches("[A-Za-z][A-Za-z0-9]{0,79}")) continue;
                var item = runtime.optJSONObject(name);
                if (item == null) continue;
                out.append("- ").append(name).append(": ").append(item.has("error")
                        ? ValidationModel.Status.INSTALL_FAILED : ValidationModel.Status.NOT_INSTRUMENTED);
                if (item.has("installed")) out.append(" · L2 install marker");
                out.append('\n');
            }
        }
        out.append("\nDiagnostic smoke cases: ").append(
                ValidationModel.smokeConfirmed(evidence.values()) ? "OBSERVED (instrumented session)" : "INCOMPLETE");
        out.append("\nPhysical compatibility and two-endpoint cases are recorded separately.\n");
        return out.toString();
    }

    public static ValidationModel.Compatibility compatibility(Context context, SharedPreferences prefs, String pkg) {
        JSONObject report = currentSnapshot(context, prefs, pkg);
        return aggregate(report, evidence(context, prefs, report));
    }

    private static ValidationModel.Compatibility aggregate(JSONObject report,
            Map<String, ValidationModel.FeatureEvidence> evidence) {
        boolean optionalFailed = false;
        JSONArray contracts = report.optJSONArray("contracts");
        if (contracts != null) for (int i = 0; i < contracts.length(); i++) {
            JSONObject item = contracts.optJSONObject(i);
            optionalFailed |= item != null && !item.optBoolean("required")
                    && !item.optBoolean("returned");
        }
        return ValidationModel.aggregate(report.optBoolean("probeDefinitive"),
                report.optBoolean("corePassed"), optionalFailed, evidence.values());
    }

    public static String label(ValidationModel.Compatibility state) {
        switch (state) {
            case DEGRADED: return "Core resolved · optional failures";
            case INCOMPATIBLE: return "Core resolution failed";
            case RUNTIME_COMPATIBLE: return "Core resolved · behavior unverified";
            default: return "Unknown · diagnostic evidence unavailable";
        }
    }

    private static JSONObject currentSnapshot(Context context, SharedPreferences prefs, String pkg) {
        if (!validTarget(pkg)) return new JSONObject();
        try {
            JSONObject report = new JSONObject(prefs.getString(RuntimeDiagnostics.snapshotKey(pkg), "{}"));
            JSONObject identity = report.optJSONObject("identity");
            if (identity != null && currentLease(context, prefs, pkg, report)
                    && pkg.equals(identity.optString("hostPackage"))
                    && BuildConfig.VERSION_CODE == identity.optLong("moduleVersionCode")
                    && BuildConfig.VERSION_NAME.equals(identity.optString("moduleVersion"))
                    && identity.optString("installation").equals(ValidationIdentity.installation(context, pkg))
                    && ValidationModel.occurredDuringSession(report.optLong("updatedAt"),
                        prefs.getLong(STARTED, Long.MAX_VALUE), System.currentTimeMillis())) return report;
        } catch (Exception ignored) { /* Old or incomplete snapshots are unknown. */ }
        return new JSONObject();
    }

    private static String configuration(Context context) throws Exception {
        return ValidationIdentity.configuration(PreferenceStores.publicStore(context));
    }

    private static boolean validTarget(String target) {
        return "com.whatsapp".equals(target) || "com.whatsapp.w4b".equals(target);
    }
}
