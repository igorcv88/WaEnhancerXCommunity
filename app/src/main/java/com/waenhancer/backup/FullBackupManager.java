package com.waenhancer.backup;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;

import com.waenhancer.config.PreferenceSchema;
import com.waenhancer.config.PreferenceStores;
import com.waenhancer.xposed.core.db.DelMessageStore;
import com.waenhancer.xposed.core.db.DeletedMediaRecord;
import com.waenhancer.xposed.core.db.DeletedMessage;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** Full portable backup. All payload fields, including secrets, are inside AES-GCM encryption. */
public final class FullBackupManager {
    public static final int FORMAT_VERSION = 1;
    private FullBackupManager() { }

    public static byte[] export(Context context, char[] password, boolean includeMedia) throws BackupCodec.BackupException {
        try {
            DelMessageStore store = DelMessageStore.getInstance(context);
            JSONObject root = new JSONObject();
            root.put("formatVersion", FORMAT_VERSION);
            root.put("createdAt", System.currentTimeMillis());
            root.put("messages", messages(store));
            root.put("secrets", secrets(PreferenceStores.privateStore(context)));
            root.put("media", media(store, includeMedia));
            byte[] body = root.toString().getBytes(StandardCharsets.UTF_8);
            return FullBackupCrypto.encrypt(body, password);
        } catch (JSONException exception) { throw new BackupCodec.BackupException("Could not create full backup manifest.", exception); }
    }

    /** Validates every record before any preference or database write, then restores atomically. */
    public static RestoreReport restore(Context context, byte[] encrypted, char[] password) throws BackupCodec.BackupException {
        final JSONObject root;
        try {
            root = new JSONObject(new String(FullBackupCrypto.decrypt(encrypted, password), StandardCharsets.UTF_8));
            if (root.optInt("formatVersion", -1) != FORMAT_VERSION) throw new BackupCodec.BackupException("Unsupported full backup version.");
            validate(root);
        } catch (JSONException exception) { throw new BackupCodec.BackupException("Full backup manifest is invalid.", exception); }
        DelMessageStore store = DelMessageStore.getInstance(context);
        SQLiteDatabase db = store.getWritableDatabase();
        SharedPreferences privateStore = PreferenceStores.privateStore(context);
        Map<String, ?> before = privateStore.getAll();
        boolean preferencesChanged = false;
        db.beginTransaction();
        try {
            JSONArray messages = root.getJSONArray("messages"); int inserted = 0;
            for (int index = 0; index < messages.length(); index++) {
                JSONObject value = messages.getJSONObject(index);
                android.content.ContentValues row = new android.content.ContentValues();
                row.put("key_id", value.getString("keyId")); row.put("chat_jid", value.getString("chatJid"));
                row.put("sender_jid", value.optString("senderJid", null)); row.put("timestamp", value.getLong("timestamp"));
                row.put("original_timestamp", value.optLong("originalTimestamp", 0)); row.put("media_type", value.optInt("mediaType", 0));
                row.put("text_content", value.optString("text", null)); row.put("media_caption", value.optString("caption", null));
                row.put("is_from_me", value.optBoolean("fromMe") ? 1 : 0); row.put("contact_name", value.optString("contact", null));
                row.put("package_name", value.optString("package", null));
                if (db.insertWithOnConflict(DelMessageStore.TABLE_DELETED_FOR_ME, null, row, SQLiteDatabase.CONFLICT_IGNORE) != -1) inserted++;
            }
            SharedPreferences.Editor editor = privateStore.edit();
            JSONObject secretValues = root.getJSONObject("secrets");
            java.util.Iterator<String> keys = secretValues.keys(); int secrets = 0;
            while (keys.hasNext()) { String key = keys.next(); editor.putString(key, secretValues.getString(key)); secrets++; }
            if (!editor.commit()) throw new BackupCodec.BackupException("Could not persist restored secrets.");
            preferencesChanged = true;
            db.setTransactionSuccessful();
            return new RestoreReport(inserted, secrets, root.getJSONArray("media").length());
        } catch (JSONException | RuntimeException exception) {
            if (preferencesChanged) restorePreferences(privateStore, before);
            throw new BackupCodec.BackupException("Could not restore full backup.", exception);
        } finally { db.endTransaction(); }
    }

    private static JSONArray messages(DelMessageStore store) throws JSONException {
        JSONArray result = new JSONArray(); for (DeletedMessage m : store.getAllDeletedMessagesInternal()) { JSONObject o = new JSONObject();
            o.put("keyId", m.getKeyId()); o.put("chatJid", m.getChatJid()); o.put("senderJid", m.getSenderJid()); o.put("timestamp", m.getTimestamp()); o.put("originalTimestamp", m.getOriginalTimestamp()); o.put("mediaType", m.getMediaType()); o.put("text", m.getTextContent()); o.put("caption", m.getMediaCaption()); o.put("fromMe", m.isFromMe()); o.put("contact", m.getContactName()); o.put("package", m.getPackageName()); result.put(o); } return result;
    }
    private static JSONObject secrets(SharedPreferences prefs) throws JSONException { JSONObject result = new JSONObject(); for (String key : PreferenceSchema.secretKeys()) { Object value = prefs.getAll().get(key); if (value instanceof String && !((String) value).isEmpty()) result.put(key, value); } return result; }
    private static JSONArray media(DelMessageStore store, boolean include) throws JSONException { JSONArray result = new JSONArray(); if (!include) return result; for (DeletedMediaRecord m : store.getDeletedMedia()) { JSONObject o = new JSONObject(); o.put("storageId", m.storageId); o.put("sha256", m.sha256); o.put("mimeType", m.mimeType); o.put("size", m.sizeBytes); result.put(o); } return result; }
    private static void validate(JSONObject root) throws BackupCodec.BackupException, JSONException { JSONArray messages = root.getJSONArray("messages"); if (messages.length() > 100000) throw new BackupCodec.BackupException("Too many message records."); for (int i=0;i<messages.length();i++) { JSONObject m=messages.getJSONObject(i); if (m.optString("keyId").isEmpty() || m.optString("chatJid").isEmpty() || m.optLong("timestamp", -1)<0) throw new BackupCodec.BackupException("Invalid message record."); } JSONObject s=root.getJSONObject("secrets"); java.util.Iterator<String> it=s.keys(); while(it.hasNext()) if(!PreferenceSchema.isSecret(it.next())) throw new BackupCodec.BackupException("Unexpected private value."); }
    private static void restorePreferences(SharedPreferences preferences, Map<String, ?> before) {
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : PreferenceSchema.secretKeys()) {
            if (!before.containsKey(key)) editor.remove(key);
            else editor.putString(key, String.valueOf(before.get(key)));
        }
        editor.commit();
    }
    public static final class RestoreReport { public final int messages, secrets, mediaMetadata; RestoreReport(int messages, int secrets, int mediaMetadata) { this.messages=messages; this.secrets=secrets; this.mediaMetadata=mediaMetadata; } }
}
