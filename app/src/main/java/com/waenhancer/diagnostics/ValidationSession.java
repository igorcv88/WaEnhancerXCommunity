package com.waenhancer.diagnostics;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;

import com.waenhancer.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Companion-side view of one explicitly started, entirely local validation session. */
public final class ValidationSession {
    private static final String ACTIVE = "validation_session_active";
    private static final String TARGET = "validation_session_target";
    private static final String STARTED = "validation_session_started";
    private static final String MANUAL_PREFIX = "validation_manual_";
    private ValidationSession() {}

    public static void start(Context context, SharedPreferences prefs, String targetPackage) {
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) if (key.startsWith(MANUAL_PREFIX)) editor.remove(key);
        editor.putBoolean(ACTIVE, true).putString(TARGET, targetPackage)
                .putLong(STARTED, System.currentTimeMillis()).apply();
    }

    public static void reset(SharedPreferences prefs) {
        SharedPreferences.Editor editor = prefs.edit().remove(ACTIVE).remove(TARGET).remove(STARTED);
        for (String key : prefs.getAll().keySet()) if (key.startsWith(MANUAL_PREFIX)) editor.remove(key);
        editor.apply();
    }

    public static boolean active(SharedPreferences prefs) { return prefs.getBoolean(ACTIVE, false); }
    public static String target(SharedPreferences prefs) { return prefs.getString(TARGET, ""); }
    public static void setManual(SharedPreferences prefs, String feature, boolean confirmed) {
        if (!active(prefs)) return;
        prefs.edit().putBoolean(MANUAL_PREFIX + feature, confirmed).apply();
    }
    public static boolean manual(SharedPreferences prefs, String feature) {
        return prefs.getBoolean(MANUAL_PREFIX + feature, false);
    }

    public static Map<String, ValidationModel.FeatureEvidence> evidence(SharedPreferences prefs) {
        return evidence(prefs, target(prefs));
    }

    private static Map<String, ValidationModel.FeatureEvidence> evidence(SharedPreferences prefs, String pkg) {
        return evidence(prefs, pkg, snapshot(prefs, pkg));
    }

    private static Map<String, ValidationModel.FeatureEvidence> evidence(SharedPreferences prefs, String pkg,
            JSONObject root) {
        LinkedHashMap<String, ValidationModel.FeatureEvidence> result = new LinkedHashMap<>();
        JSONObject runtime = root.optJSONObject("features");
        JSONObject opportunities = root.optJSONObject("opportunities");
        boolean targetSession = active(prefs) && pkg.equals(target(prefs));
        long started = targetSession ? prefs.getLong(STARTED, Long.MAX_VALUE) : Long.MAX_VALUE;
        for (Map.Entry<String, FeatureCatalog.Entry> item : FeatureCatalog.entries().entrySet()) {
            ValidationModel.FeatureEvidence e = new ValidationModel.FeatureEvidence();
            FeatureCatalog.Entry catalog = item.getValue();
            JSONObject f = runtime == null ? null : runtime.optJSONObject(item.getKey());
            e.required = catalog.required; e.manualRequired = catalog.manual;
            e.manualConfirmed = targetSession && manual(prefs, item.getKey());
            e.loaded = f != null && f.optBoolean("loaded");
            e.resolverPassed = f != null && f.optBoolean("resolverPassed");
            e.installed = f != null && f.optBoolean("installed");
            e.triggered = f != null && f.optBoolean("triggered")
                    && ValidationModel.occurredDuringSession(f.optLong("triggeredAt", 0L), started);
            e.error = f != null && f.optBoolean("error");
            e.opportunity = opportunities != null && ValidationModel.occurredDuringSession(
                    opportunities.optLong(catalog.surface, 0L), started);
            result.put(item.getKey(), e);
        }
        return result;
    }

    public static String buildReport(Context context, SharedPreferences prefs) {
        String pkg = target(prefs);
        JSONObject runtime = snapshot(prefs, pkg);
        String version = packageVersion(context, pkg);
        boolean currentSnapshot = isCurrentSnapshot(runtime, pkg, version);
        boolean core = currentSnapshot && runtime.optBoolean("corePassed", false);
        JSONArray optional = currentSnapshot ? runtime.optJSONArray("optionalFailures") : null;
        boolean optionalFailed = optional != null && optional.length() > 0;
        Map<String, ValidationModel.FeatureEvidence> evidence = currentSnapshot
                ? evidence(prefs, pkg, runtime)
                : evidence(prefs, pkg, new JSONObject());
        ValidationModel.Compatibility state = currentSnapshot
                ? ValidationModel.aggregate(core, optionalFailed,
                        isOfficiallyValidated(context, pkg, version), active(prefs), evidence.values())
                : ValidationModel.Compatibility.RUNTIME_COMPATIBLE;

        StringBuilder out = new StringBuilder("WaEnhancer Community functional validation\n");
        out.append("Target: ").append(pkg).append(" ").append(version).append('\n');
        out.append("Module: ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n");
        out.append("Xposed API: ").append(currentSnapshot ? runtime.optInt("xposedApi", 0) : "not observed").append('\n');
        out.append("Session: ").append(active(prefs) ? "ACTIVE" : "NOT STARTED").append('\n');
        out.append("Overall: ").append(label(state)).append('\n');
        out.append("Core probe: ").append(currentSnapshot ? (core ? "PASS" : "FAIL") : "NOT RUN").append('\n');
        if (currentSnapshot) {
            appendArray(out, "Required contract failures", runtime.optJSONArray("requiredFailures"));
            appendArray(out, "Optional contract failures", optional);
        } else {
            out.append("Runtime snapshot: not available for this installed build/module\n");
        }

        String currentSurface = "";
        List<String> pendingManual = new ArrayList<>();
        for (Map.Entry<String, ValidationModel.FeatureEvidence> item : evidence.entrySet()) {
            FeatureCatalog.Entry catalog = FeatureCatalog.entries().get(item.getKey());
            if (!catalog.surface.equals(currentSurface)) {
                currentSurface = catalog.surface; out.append("\n[").append(currentSurface).append("]\n");
            }
            ValidationModel.FeatureEvidence e = item.getValue();
            out.append("- ").append(item.getKey()).append(": ").append(e.state());
            if (e.installed && !e.triggered && e.opportunity) out.append(" (hook never triggered after opportunity)");
            if (e.manualRequired) {
                out.append(" · behavior ").append(e.manualConfirmed ? "CONFIRMED" : "NOT VERIFIED");
                if (!e.manualConfirmed && e.enabled) pendingManual.add(item.getKey());
            }
            out.append('\n');
        }
        out.append("\nManual tests pending: ").append(pendingManual.isEmpty() ? "none" : pendingManual).append('\n');
        out.append("Privacy: no message content, contacts, phone numbers, JIDs or private identifiers are collected.\n");
        return out.toString();
    }

    public static ValidationModel.Compatibility compatibility(Context context, SharedPreferences prefs,
            String pkg, String version) {
        JSONObject runtime = snapshot(prefs, pkg);
        if (!isCurrentSnapshot(runtime, pkg, version)) {
            return ValidationModel.Compatibility.RUNTIME_COMPATIBLE; // no current evidence: never claim incompatible
        }
        JSONArray optional = runtime.optJSONArray("optionalFailures");
        return ValidationModel.aggregate(runtime.optBoolean("corePassed", false),
                optional != null && optional.length() > 0, isOfficiallyValidated(context, pkg, version),
                active(prefs) && pkg.equals(target(prefs)), evidence(prefs, pkg, runtime).values());
    }

    public static String label(ValidationModel.Compatibility state) {
        switch (state) {
            case VALIDATED: return "Validated";
            case DEGRADED: return "Runtime Compatible · Degraded";
            case INCOMPATIBLE: return "Incompatible";
            default: return "Runtime Compatible · Not validated";
        }
    }

    private static boolean isCurrentSnapshot(JSONObject runtime, String pkg, String version) {
        if (pkg == null || pkg.isEmpty() || version == null || version.isEmpty()
                || "unknown".equals(version) || "not installed".equals(version)) {
            return false;
        }
        return pkg.equals(runtime.optString("hostPackage"))
                && version.equals(runtime.optString("hostVersion"))
                && BuildConfig.VERSION_NAME.equals(runtime.optString("moduleVersion"))
                && runtime.optBoolean("probeDefinitive", false);
    }

    private static boolean isOfficiallyValidated(Context context, String pkg, String version) {
        try {
            String raw = new String(context.getAssets().open("validated_builds.json").readAllBytes(), StandardCharsets.UTF_8);
            JSONArray builds = new JSONObject(raw).getJSONArray("builds");
            for (int i = 0; i < builds.length(); i++) {
                JSONObject b = builds.getJSONObject(i);
                if (pkg.equals(b.optString("package")) && version.equals(b.optString("version"))) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }
    private static void appendArray(StringBuilder out, String title, JSONArray values) {
        if (values == null || values.length() == 0) out.append(title).append(": none\n");
        else out.append(title).append(": ").append(LocalDiagnostics.sanitize(values.toString())).append('\n');
    }
    private static JSONObject json(String value) { try { return new JSONObject(value); } catch (Exception e) { return new JSONObject(); } }
    private static JSONObject snapshot(SharedPreferences prefs, String pkg) {
        return json(prefs.getString(RuntimeDiagnostics.snapshotKey(pkg), "{}"));
    }
    private static String packageVersion(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) return "unknown";
        try {
            PackageInfo p = context.getPackageManager().getPackageInfo(pkg, 0);
            return p.versionName == null ? "unknown" : p.versionName;
        } catch (Exception ignored) { return "not installed"; }
    }
}
