package com.waenhancer.xposed.core.db;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.waenhancer.xposed.utils.Utils;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** Only attempted revocations are archived. No conversation history scan or media copy. */
public final class RevocationArchive {
    private final DelMessageStore store;
    private final RevokedSnapshotCrypto crypto = new RevokedSnapshotCrypto();
    private final Map<String, Long> timestamps = new LinkedHashMap<String, Long>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> entry) { return size() > 1024; }
    };
    public static final int MAX_TEXT = 65536;
    public static final int MAX_PAYLOAD = 256 * 1024;

    public RevocationArchive(DelMessageStore store) { this.store = store; }

    /** Called on FeatureLoader's worker, before the first destructive callback. */
    public void prepare() throws Exception {
        store.getWritableDatabase();
        crypto.prepare();
    }

    public static String scope(SQLiteDatabase db) {
        // The physical connection is authoritative, even while the active account is switching.
        return canonical(db.getPath());
    }

    public static String activeScope() {
        return canonical(new File(Utils.getAccountDataDir(), "databases/msgstore.db").getAbsolutePath());
    }

    private static final Map<String, String> paths = new LinkedHashMap<String, String>(8, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> entry) { return size() > 16; }
    };
    private static synchronized String canonical(String path) {
        String result = paths.get(path);
        if (result != null) return result;
        try { result = new File(path).getCanonicalPath(); }
        catch (java.io.IOException unavailable) { result = new File(path).getAbsolutePath(); }
        paths.put(path, result);
        return result;
    }

    private static String identity(String scope, long row, String key) {
        return scope.length() + ":" + scope + ":" + row + ":" + key;
    }

    public synchronized long timestamp(String scope, long row, String key) {
        if (key == null || row <= 0) return 0;
        String identity = identity(scope, row, key);
        Long cached = timestamps.get(identity);
        if (cached != null) return cached;
        long timestamp = store.getRevokedTimestamp(scope, row, key);
        timestamps.put(identity, timestamp);
        return timestamp;
    }

    public long recordedRow(String scope, String key, long chat) {
        return key == null || chat <= 0 ? -1 : store.getRevokedRow(scope, key, chat);
    }

    /** Returns only after the independent archive transaction has ended successfully. */
    public long capture(SQLiteDatabase source, long row) throws Exception {
        return capture(source, row, null);
    }

    public long capture(SQLiteDatabase source, long row, String expectedKey) throws Exception {
        String scope = scope(source);
        try (Cursor cursor = source.rawQuery(
                "SELECT key_id,chat_row_id,message_type,from_me,timestamp,"
                + "CASE WHEN length(text_data)<=65536 THEN text_data END,length(text_data) "
                + "FROM message WHERE _id=?", new String[]{Long.toString(row)})) {
            if (!cursor.moveToFirst()) throw new IllegalStateException("Original row unavailable");
            String key = cursor.getString(0);
            if (key == null || key.isEmpty() || cursor.getInt(2) == 15 || cursor.getInt(3) != 0)
                throw new IllegalStateException("Original incoming content unavailable");
            if (expectedKey != null && !expectedKey.equals(key))
                throw new IllegalStateException("Message account/row mismatch");
            long existing = timestamp(scope, row, key);
            if (existing > 0) return existing;
            if (cursor.getLong(6) > MAX_TEXT) throw new IllegalStateException("Snapshot text limit");
            long chat = cursor.getLong(1);
            JSONObject payload = new JSONObject();
            payload.put("version", 1);
            payload.put("row", row);
            payload.put("key", key);
            payload.put("chat", chat);
            payload.put("type", cursor.getInt(2));
            payload.put("timestamp", cursor.getLong(4));
            payload.put("text", cursor.isNull(5) ? JSONObject.NULL : cursor.getString(5));
            // Media is referenced, never copied or read on this synchronous path. Original
            // message_media rows are protected by the SQL guard once the event is committed.
            try (Cursor media = source.rawQuery("SELECT file_path FROM message_media WHERE message_row_id=? LIMIT 1",
                    new String[]{Long.toString(row)})) {
                if (media.moveToFirst()) payload.put("media_path", media.getString(0));
            } catch (android.database.sqlite.SQLiteException optionalSchema) {
                payload.put("media_reference_unavailable", true);
            }
            String jid = null;
            try (Cursor contact = source.rawQuery("SELECT j.raw_string FROM jid j JOIN chat c ON c.jid_row_id=j._id WHERE c._id=?",
                    new String[]{Long.toString(chat)})) {
                if (contact.moveToFirst()) jid = contact.getString(0);
            } catch (android.database.sqlite.SQLiteException optionalSchema) {
                // Identity uses account + row + key + chat, not a phone-number conversion.
            }
            payload.put("jid", jid == null ? JSONObject.NULL : jid);
            byte[] plain = payload.toString().getBytes(StandardCharsets.UTF_8);
            if (plain.length > MAX_PAYLOAD) throw new IllegalStateException("Snapshot payload limit");
            byte[] encrypted = crypto.encrypt(plain, identity(scope, chat, key));
            long committed = store.commitRevocation(scope, key, chat, row, legacyJid(jid),
                    System.currentTimeMillis(), encrypted);
            synchronized (this) { timestamps.put(identity(scope, row, key), committed); }
            return committed;
        }
    }

    private static String legacyJid(String raw) {
        if (raw == null) return "";
        int at = raw.indexOf('@');
        return at < 0 ? raw : raw.substring(0, at);
    }

    /** Used only for a matched placeholder, never a normal bind/draw. No guessed identity. */
    public String archivedText(String scope, long row, String key) throws Exception {
        DelMessageStore.RevokedData data = store.getRevokedData(scope, row, key);
        if (data == null) return null;
        JSONObject payload = new JSONObject(new String(crypto.decrypt(data.payload(),
                identity(scope, data.chat(), key)), StandardCharsets.UTF_8));
        if (payload.getLong("row") != data.originalRow() || payload.getLong("chat") != data.chat()
                || !key.equals(payload.getString("key")))
            throw new IllegalStateException("Snapshot identity mismatch");
        return payload.isNull("text") ? null : payload.getString("text");
    }
}
