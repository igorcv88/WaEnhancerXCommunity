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
        for (int i = 0; i < InspectorTrace.MAX_ENTRIES + 10; i++) {
            JSONArray batch = new JSONArray();
            batch.put(new JSONObject().put("kind", "activity").put("index", i));
            assertTrue(InspectorTrace.appendPrivate(prefs, "com.whatsapp.w4b", batch.toString()));
        }
        JSONArray entries = new JSONArray(prefs.getString(InspectorTrace.KEY_BUSINESS, ""));
        assertEquals(InspectorTrace.MAX_ENTRIES, entries.length());
        assertEquals(10, entries.getJSONObject(0).getInt("index"));
        assertFalse(prefs.contains(InspectorTrace.KEY_WPP));
    }
}
