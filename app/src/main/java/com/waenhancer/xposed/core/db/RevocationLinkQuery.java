package com.waenhancer.xposed.core.db;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only, bounded message_add_on compatibility; invoked on the marker worker only. */
public final class RevocationLinkQuery {
    public static final String SQL = "SELECT key_id FROM message_add_on WHERE parent_message_row_id=? LIMIT 9";
    private final Map<String, Boolean> indexed = new LinkedHashMap<String, Boolean>(4, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> entry) { return size() > 8; }
    };

    public synchronized List<String> read(SQLiteDatabase source, String expectedScope, long row, String key) {
        if (source == null || !source.isOpen() || !expectedScope.equals(RevocationArchive.scope(source)))
            return List.of();
        // Prevent a stale binding or a reused row ID from consulting another message's links.
        try (Cursor original = source.rawQuery("SELECT key_id,from_me FROM message WHERE _id=?",
                new String[]{Long.toString(row)})) {
            if (!original.moveToFirst() || original.getInt(1) != 0 || !key.equals(original.getString(0)))
                return List.of();
        }
        Boolean supported = indexed.get(expectedScope);
        if (supported == null) {
            supported = false;
            try (Cursor plan = source.rawQuery("EXPLAIN QUERY PLAN " + SQL, new String[]{Long.toString(row)})) {
                while (plan.moveToNext()) {
                    String detail = plan.getString(plan.getColumnCount() - 1).toUpperCase(java.util.Locale.ROOT);
                    if (detail.contains("SEARCH") && (detail.contains("INDEX") || detail.contains("PRIMARY KEY")))
                        supported = true;
                }
            }
            indexed.put(expectedScope, supported);
        }
        if (!supported) return List.of(); // Never scan the add-on history for a bound row.
        List<String> result = new ArrayList<>();
        try (Cursor links = source.rawQuery(SQL, new String[]{Long.toString(row)})) {
            while (links.moveToNext()) {
                if (result.size() == 8) return List.of(); // Unknown remainder: no guessed association.
                result.add(links.getString(0));
            }
        }
        return result;
    }
}
