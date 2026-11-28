package com.waenhancer.diagnostics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Element Inspector's stored history, bounded per purpose.
 *
 * <p>One shared bound let repetitive glass telemetry evict the activity snapshots and the
 * user's own selections, which are what a report is read for. Each kind now has its own entry
 * and size quota, and an entry only ever evicts the oldest entry of its own kind. A glass event
 * identical to the previous glass event (same reason and detail) is not stored again: the stored
 * one counts the repetition ({@code repeat}) and keeps the time of the last one ({@code last_at}).
 * The overall order stays chronological.</p>
 */
final class TraceHistory {
    static final class Quota {
        final int entries;
        final int chars;

        Quota(int entries, int chars) {
            this.entries = entries;
            this.chars = chars;
        }
    }

    /** Purposes and their bounds; the sums stay within the former 120 entries / 90 000 chars. */
    static final Map<String, Quota> QUOTAS = new LinkedHashMap<>();

    static {
        QUOTAS.put("selection", new Quota(30, 12_000));
        QUOTAS.put("subtree", new Quota(6, 30_000));
        QUOTAS.put("activity", new Quota(54, 34_000));
        QUOTAS.put("glass", new Quota(30, 14_000));
    }

    private TraceHistory() {}

    static boolean knownKind(String kind) {
        return QUOTAS.containsKey(kind);
    }

    /** Appends {@code entry} to {@code history} in place, then enforces the quota of its kind. */
    static void append(JSONArray history, JSONObject entry) throws Exception {
        String kind = entry.optString("kind");
        if ("glass".equals(kind) && mergeRepeat(history, entry)) return;
        history.put(entry);
        enforce(history, kind);
    }

    private static boolean mergeRepeat(JSONArray history, JSONObject entry) throws Exception {
        for (int i = history.length() - 1; i >= 0; i--) {
            JSONObject previous = history.optJSONObject(i);
            if (previous == null || !"glass".equals(previous.optString("kind"))) continue;
            if (!previous.optString("reason").equals(entry.optString("reason"))
                    || !previous.optString("detail").equals(entry.optString("detail"))) {
                return false;
            }
            previous.put("repeat", previous.optInt("repeat", 1) + 1);
            previous.put("last_at", entry.optLong("at", previous.optLong("at")));
            return true;
        }
        return false;
    }

    private static void enforce(JSONArray history, String kind) {
        Quota quota = QUOTAS.get(kind);
        if (quota == null) return;
        while (true) {
            int count = 0;
            int chars = 0;
            int oldest = -1;
            for (int i = 0; i < history.length(); i++) {
                JSONObject item = history.optJSONObject(i);
                if (item == null || !kind.equals(item.optString("kind"))) continue;
                if (oldest < 0) oldest = i;
                count++;
                chars += item.toString().length();
            }
            if (oldest < 0 || count <= 1 || count <= quota.entries && chars <= quota.chars) return;
            history.remove(oldest);
        }
    }

    static int count(JSONArray history, String kind) {
        int count = 0;
        for (int i = 0; i < history.length(); i++) {
            JSONObject item = history.optJSONObject(i);
            if (item != null && kind.equals(item.optString("kind"))) count++;
        }
        return count;
    }
}
