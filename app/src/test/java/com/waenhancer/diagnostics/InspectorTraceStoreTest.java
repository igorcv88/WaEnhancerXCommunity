package com.waenhancer.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.waenhancer.testing.FakeSharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class InspectorTraceStoreTest {
    @Test public void combinesBatchesInCompanionPrivateHistory() throws Exception {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        String first = "[{\"kind\":\"activity\",\"activity\":\"HomeActivity\"}]";
        String second = "[{\"kind\":\"selection\",\"id\":\"mini_player\"},"
                + "{\"kind\":\"glass\",\"reason\":\"SOURCE_READY\"}]";
        assertTrue(InspectorTrace.appendPrivate(prefs, "com.whatsapp", first));
        assertTrue(InspectorTrace.appendPrivate(prefs, "com.whatsapp", second));
        JSONArray entries = new JSONArray(prefs.getString(InspectorTrace.KEY_WPP, ""));
        assertEquals(3, entries.length());
        assertEquals("mini_player", entries.getJSONObject(1).getString("id"));
        assertFalse(prefs.contains(InspectorTrace.KEY_BUSINESS));
    }

    @Test public void rejectsUnknownTargetsKindsAndOversizedBatches() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        assertFalse(InspectorTrace.appendPrivate(prefs, "com.random.app", "[]"));
        assertFalse(InspectorTrace.appendPrivate(prefs, "com.whatsapp", "[{\"kind\":\"message\"}]"));
        assertFalse(InspectorTrace.appendPrivate(prefs, "com.whatsapp", "x".repeat(InspectorTrace.MAX_BATCH_CHARS + 1)));
        assertFalse(prefs.contains(InspectorTrace.KEY_WPP));
    }

    @Test public void historyIsBoundedAndBusinessIsSeparate() throws Exception {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        int quota = TraceHistory.QUOTAS.get("activity").entries;
        for (int i = 0; i < quota + 10; i++) {
            JSONArray batch = new JSONArray();
            batch.put(new JSONObject().put("kind", "activity").put("index", i));
            assertTrue(InspectorTrace.appendPrivate(prefs, "com.whatsapp.w4b", batch.toString()));
        }
        JSONArray entries = new JSONArray(prefs.getString(InspectorTrace.KEY_BUSINESS, ""));
        assertEquals(quota, entries.length());
        assertEquals(10, entries.getJSONObject(0).getInt("index"));
        assertFalse(prefs.contains(InspectorTrace.KEY_WPP));
    }

    /** Repetitive glass telemetry cannot evict the snapshots and selections a report is read for. */
    @Test public void glassFloodKeepsSnapshotsAndSelections() throws Exception {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        assertTrue(InspectorTrace.appendPrivate(prefs, "com.whatsapp",
                "[{\"kind\":\"activity\",\"activity\":\"Conversation\"},{\"kind\":\"selection\",\"id\":\"bubble\"}]"));
        for (int i = 0; i < 400; i++) {
            JSONArray batch = new JSONArray();
            batch.put(new JSONObject().put("kind", "glass").put("reason", "BUBBLE_DRAW").put("detail", "n=" + i).put("at", i));
            assertTrue(InspectorTrace.appendPrivate(prefs, "com.whatsapp", batch.toString()));
        }
        JSONArray entries = new JSONArray(prefs.getString(InspectorTrace.KEY_WPP, ""));
        assertEquals(1, TraceHistory.count(entries, "activity"));
        assertEquals(1, TraceHistory.count(entries, "selection"));
        assertEquals(TraceHistory.QUOTAS.get("glass").entries, TraceHistory.count(entries, "glass"));
        // The oldest glass events went first, and the order is still chronological.
        assertEquals("activity", entries.getJSONObject(0).getString("kind"));
        assertEquals("n=399", entries.getJSONObject(entries.length() - 1).getString("detail"));
    }

    /** An identical glass event repeated is counted on the stored one, not stored again. */
    @Test public void repeatedGlassEventsAreAggregated() throws Exception {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        for (int i = 0; i < 50; i++) {
            JSONArray batch = new JSONArray();
            batch.put(new JSONObject().put("kind", "glass").put("reason", "SOURCE_READY").put("detail", "same").put("at", 1000 + i));
            assertTrue(InspectorTrace.appendPrivate(prefs, "com.whatsapp", batch.toString()));
        }
        JSONArray entries = new JSONArray(prefs.getString(InspectorTrace.KEY_WPP, ""));
        assertEquals(1, entries.length());
        assertEquals(50, entries.getJSONObject(0).getInt("repeat"));
        assertEquals(1000, entries.getJSONObject(0).getLong("at"));
        assertEquals(1049, entries.getJSONObject(0).getLong("last_at"));
    }

    /** A different event in between ends the run: order is kept, nothing is merged across it. */
    @Test public void aDifferentEventEndsTheRepeatRun() throws Exception {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        String a = "[{\"kind\":\"glass\",\"reason\":\"SOURCE_READY\",\"detail\":\"a\"}]";
        String b = "[{\"kind\":\"glass\",\"reason\":\"SOURCE_READY\",\"detail\":\"b\"}]";
        assertTrue(InspectorTrace.appendPrivate(prefs, "com.whatsapp", a));
        assertTrue(InspectorTrace.appendPrivate(prefs, "com.whatsapp", b));
        assertTrue(InspectorTrace.appendPrivate(prefs, "com.whatsapp", a));
        assertEquals(3, new JSONArray(prefs.getString(InspectorTrace.KEY_WPP, "")).length());
    }

    @Test public void subtreeExportsHaveTheirOwnQuota() throws Exception {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        assertTrue(InspectorTrace.appendPrivate(prefs, "com.whatsapp", "[{\"kind\":\"selection\",\"id\":\"x\"}]"));
        int quota = TraceHistory.QUOTAS.get("subtree").entries;
        for (int i = 0; i < quota + 3; i++) {
            assertTrue(InspectorTrace.appendPrivate(prefs, "com.whatsapp",
                    "[{\"kind\":\"subtree\",\"root\":\"r" + i + "\",\"nodes\":[]}]"));
        }
        JSONArray entries = new JSONArray(prefs.getString(InspectorTrace.KEY_WPP, ""));
        assertEquals(quota, TraceHistory.count(entries, "subtree"));
        assertEquals(1, TraceHistory.count(entries, "selection"));
    }
}
