package com.waenhancer.xposed.core.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;

import java.util.HashSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;

public class DelMessageStore extends SQLiteOpenHelper {
    private static DelMessageStore mInstance;
    private final Map<String, Long> timestampCache = Collections.synchronizedMap(new LinkedHashMap<String, Long>(100, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 1000;
        }
    });

    /**
     * Version 11 adds the owned-media index; version 12 adds the media reference table that
     * lets one deduplicated file belong to several messages.  Never use a destructive fallback
     * here: this database is the only copy of a user's recovered-message history.
     */
    private static final int DATABASE_VERSION = 12;
    public static final String TABLE_DELETED_FOR_ME = "deleted_for_me";
    public static final String TABLE_DELETED_MEDIA = "deleted_media";
    public static final String TABLE_DELETED_MEDIA_REFS = "deleted_media_refs";

    private DelMessageStore(@NonNull Context context) {
        super(context, "delmessages.db", null, DATABASE_VERSION);
    }

    public static DelMessageStore getInstance(Context ctx) {
        if (mInstance == null) {
            synchronized (DelMessageStore.class) {
                if (mInstance == null) {
                    mInstance = new DelMessageStore(ctx.getApplicationContext());
                }
            }
        }
        return mInstance;
    }

    @Override
    public void onUpgrade(SQLiteDatabase sqLiteDatabase, int oldVersion, int newVersion) {
        // MIGRATIONS.md: verify the file before altering it, and compare record counts after.
        // The framework runs this inside a transaction, so throwing rolls the upgrade back and
        // leaves the user's database exactly as it was.
        try (Cursor check = sqLiteDatabase.rawQuery("PRAGMA integrity_check", null)) {
            if (!check.moveToFirst() || !"ok".equalsIgnoreCase(check.getString(0))) {
                throw new SQLiteException("Refusing to migrate a damaged deleted-data database");
            }
        }
        long messagesBefore = countRows(sqLiteDatabase, TABLE_DELETED_FOR_ME);
        if (oldVersion < 4) {
            if (!checkColumnExists(sqLiteDatabase, "delmessages", "timestamp")) {
                sqLiteDatabase.execSQL("ALTER TABLE delmessages ADD COLUMN timestamp INTEGER DEFAULT 0;");
            }
        }
        if (oldVersion < 6) {
            createDeletedForMeTable(sqLiteDatabase);
        }
        if (oldVersion < 7) {
            if (!checkColumnExists(sqLiteDatabase, TABLE_DELETED_FOR_ME, "is_from_me")) {
                try {
                    sqLiteDatabase.execSQL(
                            "ALTER TABLE " + TABLE_DELETED_FOR_ME + " ADD COLUMN is_from_me INTEGER DEFAULT 0;");
                } catch (Exception e) {
                    // Ignore if fails
                }
            }
        }
        if (oldVersion < 8) {
            if (!checkColumnExists(sqLiteDatabase, TABLE_DELETED_FOR_ME, "contact_name")) {
                try {
                    sqLiteDatabase.execSQL("ALTER TABLE " + TABLE_DELETED_FOR_ME + " ADD COLUMN contact_name TEXT;");
                } catch (Exception e) {
                    // Ignore if fails
                }
            }
        }
        if (oldVersion < 9) {
            if (!checkColumnExists(sqLiteDatabase, TABLE_DELETED_FOR_ME, "package_name")) {
                try {
                    sqLiteDatabase.execSQL("ALTER TABLE " + TABLE_DELETED_FOR_ME
                            + " ADD COLUMN package_name TEXT DEFAULT 'com.whatsapp';");
                } catch (Exception e) {
                    // Ignore if fails
                }
            }
        }
        if (oldVersion < 10) {
            if (!checkColumnExists(sqLiteDatabase, TABLE_DELETED_FOR_ME, "original_timestamp")) {
                try {
                    sqLiteDatabase.execSQL("ALTER TABLE " + TABLE_DELETED_FOR_ME
                            + " ADD COLUMN original_timestamp INTEGER DEFAULT 0;");
                } catch (Exception e) {
                    // Ignore if fails
                }
            }
        }
        if (oldVersion < 11) {
            createDeletedMediaTable(sqLiteDatabase);
            createDeletedMediaIndexes(sqLiteDatabase);
        }
        if (oldVersion < 12) {
            createDeletedMediaReferenceTable(sqLiteDatabase);
            // Version 11 stored one owner directly on the media row. Preserve every existing
            // association before future records can share a single deduplicated file.
            sqLiteDatabase.execSQL("INSERT OR IGNORE INTO " + TABLE_DELETED_MEDIA_REFS
                    + " (media_id, message_id) SELECT _id, message_id FROM " + TABLE_DELETED_MEDIA);
            // MIGRATIONS.md requires a record-count comparison. Every version-11 media row must
            // have carried its owner across, or the upgrade rolls back rather than leaving
            // media that no message can reach.
            if (countRows(sqLiteDatabase, TABLE_DELETED_MEDIA_REFS) < countRows(sqLiteDatabase, TABLE_DELETED_MEDIA)) {
                throw new SQLiteException("Media ownership was not fully migrated");
            }
        }
        if (countRows(sqLiteDatabase, TABLE_DELETED_FOR_ME) < messagesBefore) {
            throw new SQLiteException("The upgrade would have lost recovered messages");
        }
    }

    private static long countRows(SQLiteDatabase database, String table) {
        try (Cursor cursor = database.rawQuery("SELECT COUNT(*) FROM " + table, null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        } catch (SQLiteException missingTable) {
            return 0L;
        }
    }

    @Override
    public void onDowngrade(SQLiteDatabase sqLiteDatabase, int oldVersion, int newVersion) {
        // The framework rolls this exception back.  Refusing is safer than manufacturing an
        // older schema by deleting recovered messages or media.
        throw new SQLiteException("Deleted-data downgrade is unsupported; restore a compatible backup instead.");
    }

    private void createDeletedForMeTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_DELETED_FOR_ME + " (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "key_id TEXT, " +
                "chat_jid TEXT, " +
                "sender_jid TEXT, " +
                "timestamp INTEGER, " +
                "original_timestamp INTEGER DEFAULT 0, " +
                "media_type INTEGER, " +
                "text_content TEXT, " +
                "media_path TEXT, " +
                "media_caption TEXT, " +
                "is_from_me INTEGER DEFAULT 0, " +
                "contact_name TEXT, " +
                "package_name TEXT, " +
                "UNIQUE(key_id, chat_jid))");
    }

    private void createDeletedMediaTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_DELETED_MEDIA + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "message_id INTEGER NOT NULL, "
                + "storage_id TEXT NOT NULL UNIQUE, "
                + "sha256 TEXT NOT NULL, "
                + "mime_type TEXT NOT NULL, "
                + "size_bytes INTEGER NOT NULL, "
                + "created_at INTEGER NOT NULL, "
                + "last_accessed_at INTEGER NOT NULL, "
                + "FOREIGN KEY(message_id) REFERENCES " + TABLE_DELETED_FOR_ME + "(_id) ON DELETE CASCADE)");
    }

    private void createDeletedMediaIndexes(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_deleted_media_sha256 ON " + TABLE_DELETED_MEDIA + "(sha256)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_deleted_media_message ON " + TABLE_DELETED_MEDIA + "(message_id)");
    }

    private void createDeletedMediaReferenceTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_DELETED_MEDIA_REFS + " ("
                + "media_id INTEGER NOT NULL, message_id INTEGER NOT NULL, "
                + "PRIMARY KEY(media_id, message_id), "
                + "FOREIGN KEY(media_id) REFERENCES " + TABLE_DELETED_MEDIA + "(_id) ON DELETE CASCADE, "
                + "FOREIGN KEY(message_id) REFERENCES " + TABLE_DELETED_FOR_ME + "(_id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_deleted_media_refs_message ON "
                + TABLE_DELETED_MEDIA_REFS + "(message_id)");
    }

    /** Inserts a media index row only after the private file was written and hashed. */
    public long insertDeletedMedia(long messageId, String storageId, String sha256, String mimeType, long sizeBytes) {
        ContentValues values = new ContentValues();
        long now = System.currentTimeMillis();
        values.put("message_id", messageId);
        values.put("storage_id", storageId);
        values.put("sha256", sha256);
        values.put("mime_type", mimeType);
        values.put("size_bytes", sizeBytes);
        values.put("created_at", now);
        values.put("last_accessed_at", now);
        SQLiteDatabase db = getWritableDatabase();
        long id = db.insertOrThrow(TABLE_DELETED_MEDIA, null, values);
        attachMediaToMessage(db, id, messageId);
        return id;
    }

    public void attachMediaToMessage(long mediaId, long messageId) {
        attachMediaToMessage(getWritableDatabase(), mediaId, messageId);
    }

    private void attachMediaToMessage(SQLiteDatabase db, long mediaId, long messageId) {
        ContentValues reference = new ContentValues();
        reference.put("media_id", mediaId);
        reference.put("message_id", messageId);
        db.insertWithOnConflict(TABLE_DELETED_MEDIA_REFS, null, reference, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public DeletedMediaRecord findMediaByHash(String sha256) {
        try (Cursor cursor = getReadableDatabase().query(TABLE_DELETED_MEDIA, null, "sha256=?",
                new String[] { sha256 }, null, null, "_id ASC", "1")) {
            return cursor.moveToFirst() ? mediaFromCursor(cursor) : null;
        }
    }

    public ArrayList<DeletedMediaRecord> getDeletedMedia() {
        ArrayList<DeletedMediaRecord> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(TABLE_DELETED_MEDIA, null, null, null, null, null,
                "created_at DESC")) {
            while (cursor.moveToNext()) result.add(mediaFromCursor(cursor));
        }
        return result;
    }

    public int deleteDeletedMedia(String storageId) {
        return getWritableDatabase().delete(TABLE_DELETED_MEDIA, "storage_id=?", new String[] { storageId });
    }

    /** The columns {@link #TABLE_DELETED_FOR_ME} accepts from a cross-process caller. */
    private static final java.util.List<String> MESSAGE_COLUMNS = Collections.unmodifiableList(
            java.util.Arrays.asList("key_id", "chat_jid", "sender_jid", "timestamp",
                    "original_timestamp", "media_type", "text_content", "media_path",
                    "media_caption", "is_from_me", "contact_name", "package_name"));

    /**
     * Keeps only real columns. A caller-supplied bundle may carry side-channel keys such as the
     * media MIME type or quota, and handing those to SQLite raises an exception that would
     * travel back across Binder and abort the caller's own work.
     */
    public static ContentValues filterMessageColumns(ContentValues values) {
        ContentValues filtered = new ContentValues();
        for (String column : MESSAGE_COLUMNS) {
            if (values.containsKey(column)) filtered.put(column, values.getAsString(column));
        }
        // Numeric columns must not be stringified.
        for (String column : new String[] { "timestamp", "original_timestamp", "media_type", "is_from_me" }) {
            Long number = values.getAsLong(column);
            if (number != null) filtered.put(column, number);
            else filtered.remove(column);
        }
        return filtered;
    }

    public DeletedMediaRecord findMediaByStorageId(String storageId) {
        try (Cursor cursor = getReadableDatabase().query(TABLE_DELETED_MEDIA, null, "storage_id=?",
                new String[] { storageId }, null, null, null, "1")) {
            return cursor.moveToFirst() ? mediaFromCursor(cursor) : null;
        }
    }

    public int detachMediaFromMessage(long mediaId, long messageId) {
        return getWritableDatabase().delete(TABLE_DELETED_MEDIA_REFS, "media_id=? AND message_id=?",
                new String[] { String.valueOf(mediaId), String.valueOf(messageId) });
    }

    public int countMediaReferences(long mediaId) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE_DELETED_MEDIA_REFS + " WHERE media_id=?",
                new String[] { String.valueOf(mediaId) })) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public boolean hasMediaReferences(long mediaId) {
        try (Cursor cursor = getReadableDatabase().query(TABLE_DELETED_MEDIA_REFS,
                new String[] { "media_id" }, "media_id=?", new String[] { String.valueOf(mediaId) },
                null, null, null, "1")) {
            return cursor.moveToFirst();
        }
    }

    public long findMessageId(String keyId, String chatJid) {
        try (Cursor cursor = getReadableDatabase().query(TABLE_DELETED_FOR_ME, new String[] { "_id" },
                "key_id=? AND chat_jid=?", new String[] { keyId, chatJid }, null, null, null, "1")) {
            return cursor.moveToFirst() ? cursor.getLong(0) : -1;
        }
    }

    private DeletedMediaRecord mediaFromCursor(Cursor cursor) {
        return new DeletedMediaRecord(cursor.getLong(cursor.getColumnIndexOrThrow("_id")),
                cursor.getLong(cursor.getColumnIndexOrThrow("message_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("storage_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("sha256")),
                cursor.getString(cursor.getColumnIndexOrThrow("mime_type")),
                cursor.getLong(cursor.getColumnIndexOrThrow("size_bytes")));
    }

    public void insertMessage(String jid, String msgid, long timestamp) {
        try (SQLiteDatabase dbWrite = this.getWritableDatabase()) {
            ContentValues values = new ContentValues();
            values.put("jid", jid);
            values.put("msgid", msgid);
            values.put("timestamp", timestamp);
            dbWrite.insertWithOnConflict("delmessages", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        }
    }

    public void insertDeletedMessage(DeletedMessage message) {
        try (SQLiteDatabase dbWrite = this.getWritableDatabase()) {
            ContentValues values = new ContentValues();
            values.put("key_id", message.getKeyId());
            values.put("chat_jid", message.getChatJid());
            values.put("sender_jid", message.getSenderJid());
            values.put("timestamp", message.getTimestamp());
            values.put("original_timestamp", message.getOriginalTimestamp());
            values.put("media_type", message.getMediaType());
            values.put("text_content", message.getTextContent());
            values.put("media_path", message.getMediaPath());
            values.put("media_caption", message.getMediaCaption());
            values.put("is_from_me", message.isFromMe() ? 1 : 0);
            values.put("contact_name", message.getContactName());
            values.put("package_name", message.getPackageName());
            dbWrite.insertWithOnConflict(TABLE_DELETED_FOR_ME, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    public java.util.ArrayList<DeletedMessage> getDeletedMessagesByChat(String chatJid, String sortOrder) {
        java.util.ArrayList<DeletedMessage> messages = new java.util.ArrayList<>();
        SQLiteDatabase dbReader = this.getReadableDatabase();
        try (Cursor cursor = dbReader.query(TABLE_DELETED_FOR_ME, null, "chat_jid=?", new String[] { chatJid }, null,
                null, sortOrder)) {
            if (cursor.moveToFirst()) {
                do {
                    long originalTs = 0;
                    if (cursor.getColumnIndex("original_timestamp") != -1) {
                        originalTs = cursor.getLong(cursor.getColumnIndexOrThrow("original_timestamp"));
                    }

                    messages.add(new DeletedMessage(
                            cursor.getLong(cursor.getColumnIndexOrThrow("_id")),
                            cursor.getString(cursor.getColumnIndexOrThrow("key_id")),
                            cursor.getString(cursor.getColumnIndexOrThrow("chat_jid")),
                            cursor.getString(cursor.getColumnIndexOrThrow("sender_jid")),
                            cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                            originalTs,
                            cursor.getInt(cursor.getColumnIndexOrThrow("media_type")),
                            cursor.getString(cursor.getColumnIndexOrThrow("text_content")),
                            cursor.getString(cursor.getColumnIndexOrThrow("media_path")),
                            cursor.getString(cursor.getColumnIndexOrThrow("media_caption")),
                            cursor.getInt(cursor.getColumnIndexOrThrow("is_from_me")) == 1,
                            cursor.getString(cursor.getColumnIndexOrThrow("contact_name")),
                            cursor.getString(cursor.getColumnIndexOrThrow("package_name"))));
                } while (cursor.moveToNext());
            }
        }
        return messages;
    }

    public java.util.ArrayList<DeletedMessage> getAllDeletedMessages() {
        return getDeletedMessages(false);
    }

    public java.util.ArrayList<DeletedMessage> getDeletedMessages(boolean isGroup) {
        java.util.ArrayList<DeletedMessage> messages = new java.util.ArrayList<>();
        SQLiteDatabase dbReader = this.getReadableDatabase();
        String selection = isGroup ? "chat_jid LIKE '%@g.us'" : "chat_jid NOT LIKE '%@g.us'";

        try (Cursor cursor = dbReader.query(TABLE_DELETED_FOR_ME, null, selection, null, null, null,
                "timestamp DESC")) {
            if (cursor.moveToFirst()) {
                do {
                    long originalTs = 0;
                    if (cursor.getColumnIndex("original_timestamp") != -1) {
                        originalTs = cursor.getLong(cursor.getColumnIndexOrThrow("original_timestamp"));
                    }
                    messages.add(new DeletedMessage(
                            cursor.getLong(cursor.getColumnIndexOrThrow("_id")),
                            cursor.getString(cursor.getColumnIndexOrThrow("key_id")),
                            cursor.getString(cursor.getColumnIndexOrThrow("chat_jid")),
                            cursor.getString(cursor.getColumnIndexOrThrow("sender_jid")),
                            cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                            originalTs,
                            cursor.getInt(cursor.getColumnIndexOrThrow("media_type")),
                            cursor.getString(cursor.getColumnIndexOrThrow("text_content")),
                            cursor.getString(cursor.getColumnIndexOrThrow("media_path")),
                            cursor.getString(cursor.getColumnIndexOrThrow("media_caption")),
                            cursor.getInt(cursor.getColumnIndexOrThrow("is_from_me")) == 1,
                            cursor.getString(cursor.getColumnIndexOrThrow("contact_name")),
                            cursor.getString(cursor.getColumnIndexOrThrow("package_name"))));
                } while (cursor.moveToNext());
            }
        }
        return messages;
    }

    public java.util.ArrayList<DeletedMessage> getAllDeletedMessagesInternal() {
        java.util.ArrayList<DeletedMessage> messages = new java.util.ArrayList<>();
        SQLiteDatabase dbReader = this.getReadableDatabase();
        try (dbReader;
                Cursor cursor = dbReader.query(TABLE_DELETED_FOR_ME, null, null, null, null, null, "timestamp DESC")) {
            if (cursor.moveToFirst()) {
                do {
                    long originalTs = 0;
                    if (cursor.getColumnIndex("original_timestamp") != -1) {
                        originalTs = cursor.getLong(cursor.getColumnIndexOrThrow("original_timestamp"));
                    }
                    messages.add(new DeletedMessage(
                            cursor.getLong(cursor.getColumnIndexOrThrow("_id")),
                            cursor.getString(cursor.getColumnIndexOrThrow("key_id")),
                            cursor.getString(cursor.getColumnIndexOrThrow("chat_jid")),
                            cursor.getString(cursor.getColumnIndexOrThrow("sender_jid")),
                            cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                            originalTs,
                            cursor.getInt(cursor.getColumnIndexOrThrow("media_type")),
                            cursor.getString(cursor.getColumnIndexOrThrow("text_content")),
                            cursor.getString(cursor.getColumnIndexOrThrow("media_path")),
                            cursor.getString(cursor.getColumnIndexOrThrow("media_caption")),
                            cursor.getInt(cursor.getColumnIndexOrThrow("is_from_me")) == 1,
                            cursor.getString(cursor.getColumnIndexOrThrow("contact_name")),
                            cursor.getString(cursor.getColumnIndexOrThrow("package_name"))));
                } while (cursor.moveToNext());
            }
        }
        return messages;
    }

    public void deleteMessage(String keyId) {
        try (SQLiteDatabase dbWrite = this.getWritableDatabase()) {
            dbWrite.delete(TABLE_DELETED_FOR_ME, "key_id=?", new String[] { keyId });
        }
    }

    public void deleteMessages(java.util.List<String> keyIds) {
        if (keyIds == null || keyIds.isEmpty())
            return;
        try (SQLiteDatabase dbWrite = this.getWritableDatabase()) {
            StringBuilder args = new StringBuilder();
            for (int i = 0; i < keyIds.size(); i++) {
                args.append("?,");
            }
            if (args.length() > 0)
                args.setLength(args.length() - 1); // remove last comma
            dbWrite.delete(TABLE_DELETED_FOR_ME, "key_id IN (" + args.toString() + ")", keyIds.toArray(new String[0]));
        }
    }

    public void deleteMessagesByChat(String chatJid) {
        try (SQLiteDatabase dbWrite = this.getWritableDatabase()) {
            dbWrite.delete(TABLE_DELETED_FOR_ME, "chat_jid=?", new String[] { chatJid });
        }
    }

    public HashSet<String> getMessagesByJid(String jid) {
        HashSet<String> messages = new HashSet<>();
        if (jid == null)
            return messages;
        SQLiteDatabase dbReader = this.getReadableDatabase();
        try (dbReader;
                Cursor query = dbReader.query("delmessages", new String[] { "_id", "jid", "msgid" }, "jid=?",
                        new String[] { jid }, null, null, null)) {
            if (query.moveToFirst()) {
                do {
                    messages.add(query.getString(query.getColumnIndexOrThrow("msgid")));
                } while (query.moveToNext());
            }
        }
        return messages;
    }

    @Override
    public void onCreate(SQLiteDatabase sqLiteDatabase) {
        sqLiteDatabase.execSQL(
                "CREATE TABLE IF NOT EXISTS delmessages (_id INTEGER PRIMARY KEY AUTOINCREMENT, jid TEXT, msgid TEXT, timestamp INTEGER DEFAULT 0, UNIQUE(jid, msgid))");
        createDeletedForMeTable(sqLiteDatabase);
        createDeletedMediaTable(sqLiteDatabase);
        createDeletedMediaIndexes(sqLiteDatabase);
        createDeletedMediaReferenceTable(sqLiteDatabase);
    }

    @Override
    public void onConfigure(SQLiteDatabase database) {
        super.onConfigure(database);
        database.setForeignKeyConstraintsEnabled(true);
    }

    /** Set once the process has verified this database; the check is not worth repeating. */
    private static volatile boolean integrityVerified;
    /** Recorded rather than thrown, so a damaged file never makes the app unusable. */
    private static volatile boolean integrityFailed;

    /** True when the last check found structural damage. The UI warns; nothing is auto-deleted. */
    public static boolean isIntegrityCompromised() {
        return integrityFailed;
    }

    @Override
    public void onOpen(SQLiteDatabase database) {
        super.onOpen(database);
        if (database.isReadOnly() || integrityVerified) return;
        integrityVerified = true;
        // quick_check finds the corruption that matters here without the full-scan cost of
        // integrity_check, which runs on the UI path every time the helper reopens.
        try (Cursor result = database.rawQuery("PRAGMA quick_check(1)", null)) {
            integrityFailed = !result.moveToFirst() || !"ok".equalsIgnoreCase(result.getString(0));
        } catch (SQLiteException exception) {
            integrityFailed = true;
        }
        // Deliberately not thrown: this database is the only copy of the user's recovered
        // history, and a helper that throws from onOpen makes every read fail for good. The
        // damaged file is left untouched so it can still be salvaged or backed up.
    }

    public long getTimestampByMessageId(String msgid) {
        if (msgid == null) return 0;
        Long cached = timestampCache.get(msgid);
        if (cached != null) return cached;
        SQLiteDatabase dbReader = this.getReadableDatabase();
        try (Cursor query = dbReader.query("delmessages", new String[] { "timestamp" }, "msgid=?",
                        new String[] { msgid }, null, null, null)) {
            if (query.moveToFirst()) {
                long ts = query.getLong(query.getColumnIndexOrThrow("timestamp"));
                timestampCache.put(msgid, ts);
                return ts;
            }
            timestampCache.put(msgid, -1L);
            return 0;
        }
    }

    private boolean checkColumnExists(SQLiteDatabase db, String tableName, String columnName) {
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + tableName + ")", null)) {
            if (cursor != null) {
                int nameIndex = cursor.getColumnIndex("name");
                while (cursor.moveToNext()) {
                    String currentColumnName = cursor.getString(nameIndex);
                    if (columnName.equals(currentColumnName)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
