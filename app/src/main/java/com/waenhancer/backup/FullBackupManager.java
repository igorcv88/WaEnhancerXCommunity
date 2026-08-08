package com.waenhancer.backup;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.util.Base64;

import com.waenhancer.config.PreferenceSchema;
import com.waenhancer.config.PreferenceStores;
import com.waenhancer.xposed.core.db.DelMessageStore;
import com.waenhancer.xposed.core.db.DeletedMediaRecord;
import com.waenhancer.xposed.core.db.DeletedMediaVault;
import com.waenhancer.xposed.core.db.DeletedMessage;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Portable, password-encrypted backup of deleted data and the schema-defined private secrets. */
public final class FullBackupManager {
    public static final int FORMAT_VERSION = 1;
    private static final int MAX_MESSAGE_RECORDS = 100_000;
    /**
     * The manifest is built, encrypted and validated wholly in memory, and media travels as
     * Base64 (about 4/3 of its size) inside a JSON string. The cap is set by what a phone can
     * actually hold at once, not by what the vault can store; anything larger must be exported
     * as a media-free backup.
     */
    private static final long MAX_MEDIA_BYTES = 96L * 1024L * 1024L;

    private FullBackupManager() { }

    public static byte[] export(Context context, char[] password, boolean includeMedia) throws BackupCodec.BackupException {
        try {
            DelMessageStore store = DelMessageStore.getInstance(context);
            JSONObject root = new JSONObject();
            root.put("formatVersion", FORMAT_VERSION);
            root.put("createdAt", System.currentTimeMillis());
            root.put("messages", messages(store));
            root.put("secrets", secrets(PreferenceStores.privateStore(context)));
            root.put("mediaIncluded", includeMedia);
            root.put("media", media(context, store, includeMedia));
            // The manifest holds the user's secrets in the clear. Overwrite the buffer as soon
            // as it is sealed rather than leaving it for the collector.
            byte[] plaintext = root.toString().getBytes(StandardCharsets.UTF_8);
            try {
                return FullBackupCrypto.encrypt(plaintext, password);
            } finally {
                java.util.Arrays.fill(plaintext, (byte) 0);
            }
        } catch (JSONException | IOException exception) {
            throw new BackupCodec.BackupException("Could not create full backup manifest.", exception);
        }
    }

    /** Validates the authenticated payload before changing preferences, rows, or private files. */
    public static RestoreReport restore(Context context, byte[] encrypted, char[] password) throws BackupCodec.BackupException {
        final JSONObject root;
        try {
            root = new JSONObject(new String(FullBackupCrypto.decrypt(encrypted, password), StandardCharsets.UTF_8));
            if (root.optInt("formatVersion", -1) != FORMAT_VERSION) {
                throw new BackupCodec.BackupException("Unsupported full backup version.");
            }
            validate(root);
        } catch (JSONException exception) {
            throw new BackupCodec.BackupException("Full backup manifest is invalid.", exception);
        }

        DelMessageStore store = DelMessageStore.getInstance(context);
        DeletedMediaVault vault = new DeletedMediaVault(context, store);
        SharedPreferences privateStore = PreferenceStores.privateStore(context);
        Map<String, ?> preferencesBefore = privateStore.getAll();
        ArrayList<String> createdMedia = new ArrayList<>();
        SQLiteDatabase database = store.getWritableDatabase();
        RestoreReport report = null;
        BackupCodec.BackupException failure = null;
        database.beginTransaction();
        try {
            HashMap<Long, Long> messageIds = restoreMessages(store, database, root.getJSONArray("messages"));
            int restoredMedia = restoreMedia(store, vault, root.getJSONArray("media"), messageIds, createdMedia);
            int restoredSecrets = restoreSecrets(privateStore, root.getJSONObject("secrets"));
            database.setTransactionSuccessful();
            report = new RestoreReport(messageIds.size(), restoredSecrets, restoredMedia);
        } catch (JSONException | IOException | RuntimeException | BackupCodec.BackupException exception) {
            restorePreferences(privateStore, preferencesBefore);
            failure = new BackupCodec.BackupException("Could not restore full backup; no source data was removed.", exception);
        } finally {
            database.endTransaction();
        }
        if (failure != null) {
            for (String storageId : createdMedia) {
                try { vault.discardUnindexed(storageId); } catch (IOException ignored) { }
            }
            throw failure;
        }
        return report;
    }

    private static HashMap<Long, Long> restoreMessages(DelMessageStore store, SQLiteDatabase database,
                                                       JSONArray messages) throws JSONException {
        HashMap<Long, Long> ids = new HashMap<>();
        for (int index = 0; index < messages.length(); index++) {
            JSONObject value = messages.getJSONObject(index);
            ContentValues row = new ContentValues();
            row.put("key_id", value.getString("keyId"));
            row.put("chat_jid", value.getString("chatJid"));
            row.put("sender_jid", value.optString("senderJid", null));
            row.put("timestamp", value.getLong("timestamp"));
            row.put("original_timestamp", value.optLong("originalTimestamp", 0));
            row.put("media_type", value.optInt("mediaType", 0));
            row.put("text_content", value.optString("text", null));
            row.put("media_caption", value.optString("caption", null));
            row.put("is_from_me", value.optBoolean("fromMe") ? 1 : 0);
            row.put("contact_name", value.optString("contact", null));
            row.put("package_name", value.optString("package", null));
            database.insertWithOnConflict(DelMessageStore.TABLE_DELETED_FOR_ME, null, row, SQLiteDatabase.CONFLICT_IGNORE);
            long destinationId = store.findMessageId(value.getString("keyId"), value.getString("chatJid"));
            if (destinationId <= 0) throw new JSONException("Could not resolve restored message");
            ids.put(value.getLong("id"), destinationId);
        }
        return ids;
    }

    private static int restoreMedia(DelMessageStore store, DeletedMediaVault vault, JSONArray media, Map<Long, Long> messageIds,
                                    ArrayList<String> createdMedia) throws JSONException, IOException {
        int restored = 0;
        for (int index = 0; index < media.length(); index++) {
            JSONObject item = media.getJSONObject(index);
            Long messageId = messageIds.get(item.getLong("messageId"));
            if (messageId == null) throw new IOException("Media record has no restored message");
            byte[] bytes = Base64.decode(item.getString("data"), Base64.NO_WRAP);
            boolean existed = store.findMediaByHash(item.getString("sha256")) != null;
            // The vault's default quota is smaller than the backup limit, so a legitimate
            // backup would be rejected halfway through. A restore of already-owned data is
            // allowed to fill up to the size the manifest was capped at.
            DeletedMediaRecord record = vault.restore(messageId, item.getString("sha256"),
                    item.getString("mimeType"), bytes,
                    Math.max(DeletedMediaVault.DEFAULT_QUOTA_BYTES, MAX_MEDIA_BYTES));
            if (!existed) createdMedia.add(record.storageId);
            restored++;
        }
        return restored;
    }

    private static int restoreSecrets(SharedPreferences preferences, JSONObject values) throws JSONException, BackupCodec.BackupException {
        SharedPreferences.Editor editor = preferences.edit();
        int restored = 0;
        java.util.Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!PreferenceSchema.isSecret(key)) throw new BackupCodec.BackupException("Unexpected private value.");
            editor.putString(key, values.getString(key));
            restored++;
        }
        if (!editor.commit()) throw new BackupCodec.BackupException("Could not persist restored secrets.");
        return restored;
    }

    private static JSONArray messages(DelMessageStore store) throws JSONException {
        JSONArray result = new JSONArray();
        for (DeletedMessage message : store.getAllDeletedMessagesInternal()) {
            JSONObject item = new JSONObject();
            item.put("id", message.getId());
            item.put("keyId", message.getKeyId());
            item.put("chatJid", message.getChatJid());
            item.put("senderJid", message.getSenderJid());
            item.put("timestamp", message.getTimestamp());
            item.put("originalTimestamp", message.getOriginalTimestamp());
            item.put("mediaType", message.getMediaType());
            item.put("text", message.getTextContent());
            item.put("caption", message.getMediaCaption());
            item.put("fromMe", message.isFromMe());
            item.put("contact", message.getContactName());
            item.put("package", message.getPackageName());
            result.put(item);
        }
        return result;
    }

    private static JSONObject secrets(SharedPreferences preferences) throws JSONException {
        JSONObject result = new JSONObject();
        Map<String, ?> values = preferences.getAll();
        for (String key : PreferenceSchema.secretKeys()) {
            Object value = values.get(key);
            if (value instanceof String && !((String) value).isEmpty()) result.put(key, value);
        }
        return result;
    }

    private static JSONArray media(Context context, DelMessageStore store, boolean include) throws JSONException, IOException {
        JSONArray result = new JSONArray();
        if (!include) return result;
        DeletedMediaVault vault = new DeletedMediaVault(context, store);
        long totalBytes = 0;
        for (DeletedMediaRecord record : store.getDeletedMedia()) {
            totalBytes += record.sizeBytes;
            if (totalBytes > MAX_MEDIA_BYTES) throw new IOException("Selected media exceeds the full-backup limit");
            byte[] bytes = vault.readVerified(record.storageId, record.sha256, record.sizeBytes);
            JSONObject item = new JSONObject();
            item.put("messageId", record.messageId);
            item.put("sha256", record.sha256);
            item.put("mimeType", record.mimeType);
            item.put("size", record.sizeBytes);
            item.put("data", Base64.encodeToString(bytes, Base64.NO_WRAP));
            result.put(item);
        }
        return result;
    }

    private static void validate(JSONObject root) throws BackupCodec.BackupException, JSONException {
        JSONArray messages = root.getJSONArray("messages");
        if (messages.length() > MAX_MESSAGE_RECORDS) throw new BackupCodec.BackupException("Too many message records.");
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            if (message.optLong("id", -1) < 0 || message.optString("keyId").isEmpty()
                    || message.optString("chatJid").isEmpty() || message.optLong("timestamp", -1) < 0) {
                throw new BackupCodec.BackupException("Invalid message record.");
            }
        }
        JSONObject secrets = root.getJSONObject("secrets");
        java.util.Iterator<String> keys = secrets.keys();
        while (keys.hasNext()) if (!PreferenceSchema.isSecret(keys.next())) {
            throw new BackupCodec.BackupException("Unexpected private value.");
        }
        long totalBytes = 0;
        for (int i = 0; i < root.getJSONArray("media").length(); i++) {
            JSONObject media = root.getJSONArray("media").getJSONObject(i);
            long size = media.optLong("size", -1);
            if (size < 0 || size > MAX_MEDIA_BYTES || !media.optString("sha256").matches("[0-9a-f]{64}")) {
                throw new BackupCodec.BackupException("Invalid media manifest.");
            }
            byte[] bytes = Base64.decode(media.optString("data"), Base64.NO_WRAP);
            if (bytes.length != size || !sha256(bytes).equals(media.getString("sha256"))) {
                throw new BackupCodec.BackupException("Media checksum mismatch.");
            }
            totalBytes += size;
            if (totalBytes > MAX_MEDIA_BYTES) throw new BackupCodec.BackupException("Full backup media is too large.");
        }
    }

    private static void restorePreferences(SharedPreferences preferences, Map<String, ?> before) {
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : PreferenceSchema.secretKeys()) {
            Object value = before.get(key);
            // Only a genuinely absent key is removed. Coercing a non-string back with
            // String.valueOf would silently rewrite the user's value into another type.
            if (value == null) editor.remove(key);
            else if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
        }
        editor.commit();
    }

    private static String sha256(byte[] bytes) throws BackupCodec.BackupException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest(bytes)) result.append(String.format(java.util.Locale.ROOT, "%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new BackupCodec.BackupException("SHA-256 is unavailable.", exception);
        }
    }

    public static final class RestoreReport {
        public final int messages;
        public final int secrets;
        public final int media;
        RestoreReport(int messages, int secrets, int media) {
            this.messages = messages;
            this.secrets = secrets;
            this.media = media;
        }
    }
}
