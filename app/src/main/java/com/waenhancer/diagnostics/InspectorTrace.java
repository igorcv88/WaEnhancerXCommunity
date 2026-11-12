package com.waenhancer.diagnostics;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import com.waenhancer.BuildConfig;
import com.waenhancer.config.PreferenceStores;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Explicitly armed, content-free UI investigation shared by Element Inspector and glass probes.
 * No text, accessibility descriptions, image pixels, phone numbers or message identifiers are read.
 * The hooked process sends bounded metadata batches to the companion's private preference store.
 */
public final class InspectorTrace {
    public static final String KEY_WPP = "inspector_trace_wpp";
    public static final String KEY_BUSINESS = "inspector_trace_business";
    public static final String METHOD_APPEND = "append_inspector_trace";
    public static final int MAX_ENTRIES = 120;
    public static final int MAX_STORED_CHARS = 90_000;
    public static final int MAX_BATCH_CHARS = 28_000;

    // Sample only when explicitly armed; 350 cut off long conversations before the transient footer.
    private static final int MAX_VIEW_VISITS = 650;
    private static final int MAX_VIEW_RECORDS = 68;
    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> QUEUE = new ArrayDeque<>();
    private static final Set<String> ONCE_PER_ARM = new HashSet<>();
    private static final ScheduledExecutorService WRITER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "WaEXInspectorTrace");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile boolean armed;
    private static volatile Context hostContext;
    private static boolean scheduled;

    private InspectorTrace() {}

    public static void arm(Context context) {
        hostContext = context.getApplicationContext();
        if (!armed) synchronized (LOCK) { ONCE_PER_ARM.clear(); }
        armed = true;
    }

    public static void disarm() { armed = false; }

    public static boolean active() { return armed; }

    public static String keyFor(String pkg) {
        return "com.whatsapp.w4b".equals(pkg) ? KEY_BUSINESS : KEY_WPP;
    }

    /** Called only after a user explicitly picks a view in the inspector. */
    public static void selection(Activity activity, String className, String resourceName,
                                 String parentChain, int left, int top, int right, int bottom) {
        if (!armed) return;
        try {
            JSONObject event = base("selection", activity);
            event.put("class", shortValue(className, 180));
            event.put("id", shortValue(resourceName, 140));
            event.put("parents", shortValue(parentChain, 350));
            event.put("bounds", left + "," + top + "," + right + "," + bottom);
            enqueue(event);
        } catch (Exception ignored) { /* Telemetry must not affect touch handling. */ }
    }

    /**
     * Samples structural metadata around the top of the screen, plus views whose resource names
     * suggest a player. No View#getText(), getContentDescription() or bitmap capture is performed.
     */
    public static void activitySnapshot(Activity activity, String stage) {
        if (!armed || activity.isFinishing() || activity.isDestroyed()) return;
        try {
            View decor = activity.getWindow().getDecorView();
            int height = decor.getResources().getDisplayMetrics().heightPixels;
            JSONArray playerNodes = new JSONArray();
            JSONArray ordinaryNodes = new JSONArray();
            // The transient in-chat typing/recording bubble is near the bottom, while the
            // original inspector only selected the top 43% and player-named views.
            JSONArray presenceNamed = new JSONArray();
            JSONArray presenceOther = new JSONArray();
            boolean presenceOverflow = false;
            ArrayDeque<Node> stack = new ArrayDeque<>();
            stack.push(new Node(decor, 0));
            int visited = 0;
            while (!stack.isEmpty() && visited++ < MAX_VIEW_VISITS) {
                Node item = stack.pop();
                View view = item.view;
                if (view.getVisibility() != View.VISIBLE) continue;
                int[] location = new int[2];
                view.getLocationOnScreen(location);
                int top = location[1], bottom = top + view.getHeight();
                String id = resourceName(view);
                String role = id.toLowerCase(java.util.Locale.ROOT);
                boolean player = role.contains("player") || role.contains("voice")
                        || role.contains("audio") || role.contains("playback");
                int width = view.getWidth(), viewHeight = view.getHeight();
                boolean namedPresence = role.contains("typing") || role.contains("recording")
                        || role.contains("composing") || role.contains("presence")
                        || role.contains("indicator") || role.contains("bubble")
                        || role.contains("microphone");
                boolean smallBottom = top >= height * 0.42f && top <= height * 0.99f
                        && width > 0 && width <= decor.getWidth() * 0.55f
                        && viewHeight > 0 && viewHeight <= height * 0.17f;
                boolean nativeBubble = view.getBackground() instanceof android.graphics.drawable.NinePatchDrawable
                        || view.getForeground() instanceof android.graphics.drawable.NinePatchDrawable;
                boolean presence = smallBottom && (namedPresence || nativeBubble
                        || top >= height * 0.70f);
                boolean ordinary = (top <= height * 0.43f && bottom >= 0) || player;
                if (width > 0 && viewHeight > 0 && (ordinary || presence)) {
                    JSONObject node = new JSONObject();
                    node.put("depth", item.depth);
                    node.put("class", shortValue(view.getClass().getName(), 160));
                    node.put("id", shortValue(id, 130));
                    node.put("parent", view.getParent() instanceof View
                            ? shortValue(resourceName((View) view.getParent()), 130) : "(window)");
                    node.put("bounds", location[0] + "," + top + ","
                            + view.getWidth() + "," + view.getHeight());
                    node.put("alpha", Math.round(view.getAlpha() * 100f));
                    node.put("bg", view.getBackground() == null ? "none"
                            : shortValue(view.getBackground().getClass().getName(), 140));
                    // Player candidates get priority even if a long chat list fills the quota.
                    if (ordinary) {
                        if (player && playerNodes.length() < 24) playerNodes.put(node);
                        else if (ordinaryNodes.length() < MAX_VIEW_RECORDS) ordinaryNodes.put(node);
                    }
                    if (presence) {
                        node.put("fg", view.getForeground() == null ? "none"
                                : shortValue(view.getForeground().getClass().getName(), 140));
                        if ((namedPresence || nativeBubble) && presenceNamed.length() < 18)
                            presenceNamed.put(node);
                        else {
                            // Keep the lowest/latest anonymous candidates: the indicator can
                            // sit immediately above the composer after older messages.
                            if (presenceOther.length() == 24) {
                                presenceOther.remove(0);
                                presenceOverflow = true;
                            }
                            presenceOther.put(node);
                        }
                    }
                }
                if (view instanceof ViewGroup && item.depth < 22) {
                    ViewGroup group = (ViewGroup) view;
                    for (int i = group.getChildCount() - 1; i >= 0; i--)
                        stack.push(new Node(group.getChildAt(i), item.depth + 1));
                }
            }
            JSONObject event = base("activity", activity);
            JSONArray nodes = new JSONArray();
            for (int i = 0; i < playerNodes.length(); i++) nodes.put(playerNodes.get(i));
            for (int i = 0; i < ordinaryNodes.length() && nodes.length() < MAX_VIEW_RECORDS; i++)
                nodes.put(ordinaryNodes.get(i));
            event.put("stage", shortValue(stage, 32));
            event.put("visited", visited);
            event.put("truncated", !stack.isEmpty() || ordinaryNodes.length() >= MAX_VIEW_RECORDS);
            event.put("nodes", nodes);
            enqueue(event);
            if (presenceNamed.length() + presenceOther.length() > 0
                    && activity.getClass().getName().contains("Conversation")) {
                // A separate bounded payload preserves the old top/player snapshots exactly.
                JSONObject bottomEvent = base("activity", activity);
                bottomEvent.put("stage", shortValue("presence-" + stage, 32));
                bottomEvent.put("visited", visited);
                bottomEvent.put("truncated", !stack.isEmpty() || presenceOverflow
                        || presenceNamed.length() + presenceOther.length() > 32);
                JSONArray bottomNodes = new JSONArray();
                for (int i = 0; i < presenceNamed.length(); i++)
                    bottomNodes.put(presenceNamed.get(i));
                // Preserve the latest (bottommost) anonymous elements if named candidates
                // consumed slots. Earlier messages must not evict the transient indicator.
                int firstOther = Math.max(0, presenceOther.length() - (32 - bottomNodes.length()));
                for (int i = firstOther; i < presenceOther.length(); i++)
                    bottomNodes.put(presenceOther.get(i));
                bottomEvent.put("nodes", bottomNodes);
                enqueue(bottomEvent);
            }
        } catch (Exception | LinkageError ignored) { /* Never break WhatsApp. */ }
    }

    /** Only pre-defined lifecycle event names and structural details are retained. */
    public static void glassEvent(String reason, String detail) {
        if (!armed || !allowedGlassReason(reason)) return;
        try {
            JSONObject event = new JSONObject();
            event.put("kind", "glass");
            event.put("at", System.currentTimeMillis());
            event.put("reason", shortValue(reason, 64));
            // Details are produced by our probes, not supplied by the WhatsApp UI.
            event.put("detail", shortValue(detail, 850));
            enqueue(event);
        } catch (Exception ignored) { }
    }

    public static void glassEventOnce(String reason, String detail) {
        if (!armed) return;
        synchronized (LOCK) {
            if (!ONCE_PER_ARM.add(reason + ":" + detail)) return;
        }
        glassEvent(reason, detail);
    }

    private static boolean allowedGlassReason(String reason) {
        if (reason == null) return false;
        return "shared-bubble-draw".equals(reason) || "shared-bubble-draw-error".equals(reason)
                || reason.startsWith("SOURCE_") || reason.startsWith("BUBBLE_")
                || reason.startsWith("INSPECTOR_");
    }

    private static JSONObject base(String kind, Activity activity) throws Exception {
        return new JSONObject().put("kind", kind).put("at", System.currentTimeMillis())
                .put("activity", shortValue(activity.getClass().getName(), 180));
    }

    private static String resourceName(View view) {
        if (view.getId() == View.NO_ID || view.getId() == 0) return "(no-id)";
        try { return view.getResources().getResourceEntryName(view.getId()); }
        catch (Exception ignored) { return "0x" + Integer.toHexString(view.getId()); }
    }

    private static String shortValue(String value, int max) {
        if (value == null) return "";
        String clean = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        return clean.length() > max ? clean.substring(0, max) : clean;
    }

    private static void enqueue(JSONObject event) {
        String encoded = event.toString();
        if (encoded.length() > MAX_BATCH_CHARS) return;
        synchronized (LOCK) {
            if (QUEUE.size() >= 72) QUEUE.removeFirst();
            QUEUE.addLast(encoded);
            if (scheduled) return;
            scheduled = true;
        }
        WRITER.schedule(InspectorTrace::flush, 350L, TimeUnit.MILLISECONDS);
    }

    private static void flush() {
        Context context = hostContext;
        JSONArray batch = new JSONArray();
        synchronized (LOCK) {
            int chars = 2;
            while (!QUEUE.isEmpty() && batch.length() < 24) {
                String next = QUEUE.peekFirst();
                if (batch.length() > 0 && chars + next.length() > MAX_BATCH_CHARS) break;
                chars += next.length() + 1;
                try { batch.put(new JSONObject(QUEUE.removeFirst())); }
                catch (Exception invalid) { QUEUE.removeFirst(); }
            }
            scheduled = false;
        }
        if (context != null && batch.length() > 0) {
            try {
                Bundle extras = new Bundle();
                extras.putString("target", context.getPackageName());
                extras.putString("batch", batch.toString());
                context.getContentResolver().call(Uri.parse("content://"
                        + BuildConfig.APPLICATION_ID + ".hookprovider"), METHOD_APPEND, null, extras);
            } catch (Throwable ignored) { }
        }
        synchronized (LOCK) {
            if (!QUEUE.isEmpty() && !scheduled) {
                scheduled = true;
                WRITER.schedule(InspectorTrace::flush, 350L, TimeUnit.MILLISECONDS);
            }
        }
    }

    /** The authenticated bridge merges bounded batches into a companion-private local history. */
    public static synchronized boolean appendPrivate(SharedPreferences privateStore, String target,
                                                     String batchString) {
        if (!("com.whatsapp".equals(target) || "com.whatsapp.w4b".equals(target))
                || batchString == null || batchString.length() > MAX_BATCH_CHARS) return false;
        try {
            JSONArray batch = new JSONArray(batchString);
            if (batch.length() == 0 || batch.length() > 24) return false;
            String key = keyFor(target);
            JSONArray history;
            try { history = new JSONArray(privateStore.getString(key, "[]")); }
            catch (Exception invalid) { history = new JSONArray(); }
            for (int i = 0; i < batch.length(); i++) {
                JSONObject entry = batch.optJSONObject(i);
                if (entry == null) return false;
                String kind = entry.optString("kind");
                if (!("activity".equals(kind) || "selection".equals(kind)
                        || "glass".equals(kind))) return false;
                history.put(entry);
            }
            while (history.length() > MAX_ENTRIES || history.toString().length() > MAX_STORED_CHARS)
                history.remove(0);
            return privateStore.edit().putString(key, history.toString()).commit();
        } catch (Exception ignored) { return false; }
    }

    public static String report(Context context) {
        SharedPreferences prefs = PreferenceStores.privateStore(context);
        StringBuilder report = new StringBuilder("\n\nELEMENT INSPECTOR / PLAYER SCOPE\n");
        report.append("Metadata only. No messages, contacts or audio contents.\n");
        for (String target : new String[]{"com.whatsapp", "com.whatsapp.w4b"}) {
            String data = prefs.getString(keyFor(target), "[]");
            report.append("\n").append(target).append(":\n");
            try {
                JSONArray entries = new JSONArray(data);
                if (entries.length() == 0) report.append("(no captures yet)\n");
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.optJSONObject(i);
                    if (entry != null) report.append(entry.toString(2)).append('\n');
                }
            } catch (Exception ignored) { report.append("(invalid capture history)\n"); }
        }
        return report.toString();
    }

    public static void clear(Context context) {
        PreferenceStores.privateStore(context).edit().remove(KEY_WPP).remove(KEY_BUSINESS).apply();
    }

    private static final class Node {
        final View view;
        final int depth;
        Node(View view, int depth) { this.view = view; this.depth = depth; }
    }
}
