package com.waenhancer.diagnostics;

import android.content.Context;
import android.os.Bundle;
import android.net.Uri;

import com.waenhancer.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

/** Fail-open, local-only recorder used from the hooked host process. */
public final class RuntimeDiagnostics {
    public static final String PREF_SNAPSHOT = "validation_runtime_snapshot";
    private static final Object LOCK = new Object();
    private static JSONObject snapshot = new JSONObject();
    private RuntimeDiagnostics() {}

    public static void initialize(Context context, String hostPackage, String hostVersion, int xposedApi) {
        mutate(context, root -> {
            root.put("hostPackage", hostPackage).put("hostVersion", hostVersion)
                    .put("moduleVersion", BuildConfig.VERSION_NAME).put("xposedApi", xposedApi)
                    .put("updatedAt", System.currentTimeMillis());
            if (!root.has("features")) root.put("features", new JSONObject());
        });
    }

    public static void probe(Context context, boolean core, java.util.List<String> required, java.util.List<String> optional) {
        mutate(context, root -> root.put("corePassed", core)
                .put("requiredFailures", new JSONArray(required)).put("optionalFailures", new JSONArray(optional)));
    }

    public static void feature(Context context, String name, String event, Throwable error) {
        mutate(context, root -> {
            JSONObject features = root.optJSONObject("features");
            if (features == null) { features = new JSONObject(); root.put("features", features); }
            JSONObject f = features.optJSONObject(name);
            if (f == null) { f = new JSONObject(); features.put(name, f); }
            f.put(event, true).put("updatedAt", System.currentTimeMillis());
            if (error != null) f.put("errorType", error.getClass().getSimpleName());
        }, error != null || "triggered".equals(event));
    }

    public static void opportunity(Context context, String surface) {
        mutate(context, root -> {
            JSONObject o = root.optJSONObject("opportunities");
            if (o == null) { o = new JSONObject(); root.put("opportunities", o); }
            o.put(surface, true);
        });
    }

    private interface Change { void apply(JSONObject root) throws Exception; }
    private static void mutate(Context context, Change change) {
        mutate(context, change, true);
    }
    private static void mutate(Context context, Change change, boolean flush) {
        if (context == null) return;
        try {
            synchronized (LOCK) {
                change.apply(snapshot);
                if (!flush) return;
                persist(context);
            }
        } catch (Throwable ignored) { /* diagnostics must never break a hook */ }
    }

    public static void flush(Context context) {
        if (context == null) return;
        try {
            synchronized (LOCK) { persist(context); }
        } catch (Throwable ignored) { }
    }

    private static void persist(Context context) {
        try {
                Bundle extras = new Bundle();
                extras.putString("key", PREF_SNAPSHOT); extras.putString("type", "string");
                extras.putString("value", snapshot.toString());
                context.getContentResolver().call(Uri.parse("content://" + BuildConfig.APPLICATION_ID + ".hookprovider"),
                        "put_preference", null, extras);
        } catch (Throwable ignored) { }
    }
}
