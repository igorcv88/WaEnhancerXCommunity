package com.waenhancer.diagnostics;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;

import com.waenhancer.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Host-side recorder for an explicitly started diagnostic session.
 *
 * <p>{@link #begin} is called once by the feature loader before any feature is constructed, on
 * the loader thread. Events recorded before the session is bound (lazy features, retries) are
 * buffered and replayed, so an early failure is never dropped. When no session is active the
 * recorder switches off and every later call returns immediately.</p>
 *
 * <p>Events, per feature: {@code loaded} (constructed), {@code installed} (doHook returned),
 * {@code error} (doHook threw), {@code degraded} (installed without an optional part),
 * {@code reached} (a hooked callback ran), {@code acted:<path>} (the feature changed the host's
 * behaviour on that path) and {@code resolverFailed} (a descriptor lookup failed while the feature
 * was loading).</p>
 */
public final class RuntimeDiagnostics {
    public static final String METHOD_BEGIN = "validation_begin";
    public static final String METHOD_WRITE = "validation_write";
    static final int MAX_SNAPSHOT_BYTES = 64 * 1024;
    static final long MAX_SESSION_MS = 30 * 60 * 1000L;
    static final int MAX_PENDING = 512;
    static final int MAX_BEGIN_ATTEMPTS = 2;
    static final int MAX_RESOLVER_KEYS = 16;
    static final int MAX_FEATURES = 160;
    static final Set<String> FIRST_EVENTS = Set.of("loaded", "installed", "error", "degraded", "registered", "inert");
    private static final Object LOCK = new Object();
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private static final ScheduledExecutorService WRITER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "WaEnhancerDiagnostics");
        thread.setDaemon(true);
        return thread;
    });

    enum State { NEW, BOUND, OFF }

    private static State state = State.NEW;
    private static int attempts;
    private static volatile Run run;
    private static boolean writerScheduled;
    private static final List<Event> pending = new ArrayList<>();
    private static int dropped;

    private RuntimeDiagnostics() {}

    /**
     * Binds this host process to the active session, if there is one. Returns true when bound.
     * A provider failure (exception) can be retried once; "no session" switches recording off.
     */
    public static boolean begin(Context context, SharedPreferences prefs) {
        synchronized (LOCK) {
            if (state != State.NEW) return state == State.BOUND;
            if (context == null || attempts >= MAX_BEGIN_ATTEMPTS) {
                switchOff();
                return false;
            }
            attempts++;
        }
        Bundle response;
        String actualConfig;
        try {
            Bundle request = new Bundle();
            request.putLong("processStart", android.os.Process.getStartElapsedRealtime());
            request.putInt("moduleVersionCode", BuildConfig.VERSION_CODE);
            response = call(context, METHOD_BEGIN, request);
            actualConfig = ValidationIdentity.configuration(prefs);
        } catch (Exception | LinkageError transientFailure) {
            return false; // state stays NEW; buffered events are kept for a retry
        }
        synchronized (LOCK) {
            if (state != State.NEW) return state == State.BOUND;
            try {
                if (response == null || !actualConfig.equals(response.getString("configuration"))) {
                    switchOff();
                    return false;
                }
                JSONObject root = new JSONObject()
                        .put("schema", 3)
                        .put("session", response.getString("session"))
                        .put("process", response.getString("process"))
                        .put("configuration", actualConfig)
                        .put("xposedApi", de.robv.android.xposed.XposedBridge.getXposedVersion())
                        .put("source", "OPT_IN_RUNTIME")
                        .put("features", new JSONObject());
                Run bound = new Run(context, prefs, response.getLong("deadline"), actualConfig, root);
                drainPending(root);
                run = bound;
                state = State.BOUND;
                schedule(bound);
                return true;
            } catch (Exception | LinkageError e) {
                switchOff();
                return false;
            }
        }
    }

    public static boolean bound() {
        return run != null;
    }

    /** Attributes resolver failures on this thread to {@code feature} until {@link #leave}. */
    public static void enter(String feature) {
        CURRENT.set(feature);
    }

    public static void leave() {
        CURRENT.remove();
    }

    public static void feature(String name, String event) {
        if (FIRST_EVENTS.contains(event) || "reached".equals(event)) record(new Event(name, event, null));
    }

    public static void acted(String name, String path) {
        if (path != null && path.matches("[a-z][a-z0-9-]{0,47}")) record(new Event(name, "acted", path));
    }

    /** Called by the descriptor cache for every failed lookup; attributed to the loading feature. */
    public static void resolverFailed(String key) {
        String feature = CURRENT.get();
        if (feature != null && key != null) record(new Event(feature, "resolverFailed", key));
    }

    public static void loaderFinished(int submitted, boolean timedOut) {
        update(root -> root.put("loader", new JSONObject()
                .put("submitted", submitted).put("state", timedOut ? "TIMED_OUT" : "COMPLETE")));
    }

    public static void probe(boolean core, Collection<JSONObject> contracts) {
        update(root -> root.put("corePassed", core)
                .put("contracts", new JSONArray(contracts))
                .put("probeDefinitive", true));
    }

    public static void probeFailure() {
        update(root -> root.put("probeError", true));
    }

    /** The loader's features never finished, so the probe did not run beside their resolvers. */
    public static void probeSkipped() {
        update(root -> root.put("probeSkipped", true));
    }

    private static void record(Event event) {
        Run current = run;
        if (current != null) {
            update(root -> apply(root, event));
            return;
        }
        synchronized (LOCK) {
            if (run != null) {
                update(root -> apply(root, event));
            } else if (state == State.NEW) {
                if (pending.size() < MAX_PENDING) pending.add(event);
                else dropped++;
            }
        }
    }

    /** Replays buffered events into a freshly bound report. Caller holds LOCK (or is a test). */
    static void drainPending(JSONObject root) throws Exception {
        for (Event event : pending) apply(root, event);
        if (dropped > 0) root.put("droppedEvents", dropped);
        pending.clear();
        dropped = 0;
    }

    /** Test seam: back to the state of a fresh host process. */
    static void resetForTest() {
        synchronized (LOCK) {
            state = State.NEW;
            attempts = 0;
            run = null;
            pending.clear();
            dropped = 0;
        }
    }

    static void switchOffForTest() {
        synchronized (LOCK) { switchOff(); }
    }

    /** Pure merge of one event into the report; visible for tests. */
    static void apply(JSONObject root, Event event) throws Exception {
        if (!event.feature.matches("[A-Za-z][A-Za-z0-9]{0,79}")) return;
        JSONObject features = root.getJSONObject("features");
        JSONObject f = features.optJSONObject(event.feature);
        if (f == null) {
            if (features.length() >= MAX_FEATURES) return;
            f = new JSONObject();
            features.put(event.feature, f);
        }
        switch (event.name) {
            case "reached":
                f.put("reached", event.at); // latest: an exercise is compared with the arm time
                break;
            case "acted": {
                JSONObject paths = f.optJSONObject("acted");
                if (paths == null) f.put("acted", paths = new JSONObject());
                if (paths.has(event.detail) || paths.length() < 16) paths.put(event.detail, event.at);
                break;
            }
            case "resolverFailed": {
                if (!f.has("resolverFailed")) f.put("resolverFailed", event.at);
                JSONArray keys = f.optJSONArray("resolverKeys");
                if (keys == null) f.put("resolverKeys", keys = new JSONArray());
                if (keys.length() < MAX_RESOLVER_KEYS) keys.put(event.detail.length() > 120
                        ? event.detail.substring(0, 120) : event.detail);
                break;
            }
            default:
                if (!f.has(event.name)) f.put(event.name, event.at);
        }
    }

    private interface Change { void apply(JSONObject root) throws Exception; }

    private static void update(Change change) {
        Run current = run;
        if (current == null || SystemClock.elapsedRealtime() > current.deadline) return;
        synchronized (LOCK) {
            try {
                change.apply(current.report);
                schedule(current);
            } catch (Exception ignored) { /* diagnostics cannot break a hook */ }
        }
    }

    /** Caller holds LOCK. */
    private static void schedule(Run current) {
        if (writerScheduled) return;
        writerScheduled = true;
        WRITER.schedule(() -> persist(current), 500L, TimeUnit.MILLISECONDS);
    }

    /** Caller holds LOCK. */
    private static void switchOff() {
        state = State.OFF;
        pending.clear();
        run = null;
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
                    || !current.configuration.equals(ValidationIdentity.configuration(current.prefs))) {
                synchronized (LOCK) { switchOff(); }
                return;
            }
            Bundle request = new Bundle();
            request.putString("report", value);
            if (call(current.context, METHOD_WRITE, request) == null) {
                synchronized (LOCK) { switchOff(); }
            }
        } catch (Exception ignored) {
            synchronized (LOCK) { switchOff(); }
        }
    }

    private static Bundle call(Context context, String method, Bundle request) {
        return context.getContentResolver().call(
                Uri.parse("content://" + BuildConfig.APPLICATION_ID + ".hookprovider"),
                method, null, request);
    }

    static final class Event {
        final String feature;
        final String name;
        final String detail;
        final long at;

        Event(String feature, String name, String detail) {
            this(feature, name, detail, System.currentTimeMillis());
        }

        Event(String feature, String name, String detail, long at) {
            this.feature = feature == null ? "" : feature;
            this.name = name;
            this.detail = detail;
            this.at = at;
        }
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
