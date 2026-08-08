package com.waenhancer.provider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.waenhancer.xposed.core.db.DelMessageStore;

public class DeletedMessagesProvider extends ContentProvider {

    public static final String AUTHORITY = com.waenhancer.BuildConfig.APPLICATION_ID + ".provider";
    public static final String PATH_DELETED_MESSAGES = "deleted_messages";
    public static final String PATH_PREFERENCES = "preferences";
    public static final String PATH_MEDIA = "media";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/" + PATH_DELETED_MESSAGES);
    public static final Uri PREF_URI = Uri.parse("content://" + AUTHORITY + "/" + PATH_PREFERENCES);

    private static final int DELETED_MESSAGES = 1;
    private static final int PREFERENCES = 2;
    private static final int MEDIA_ITEM = 3;
    private static final UriMatcher uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        uriMatcher.addURI(AUTHORITY, PATH_DELETED_MESSAGES, DELETED_MESSAGES);
        uriMatcher.addURI(AUTHORITY, PATH_PREFERENCES, PREFERENCES);
        uriMatcher.addURI(AUTHORITY, PATH_MEDIA + "/*", MEDIA_ITEM);
    }

    private DelMessageStore dbHelper;

    @Override
    public boolean onCreate() {
        dbHelper = DelMessageStore.getInstance(getContext());
        return true;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        if (!com.waenhancer.security.CallerAuthority.isTrustedCaller(getContext())) return null;
        if (uriMatcher.match(uri) != MEDIA_ITEM) return null;
        // No caller-controlled selection or sort order is accepted.
        return dbHelper.getReadableDatabase().query(DelMessageStore.TABLE_DELETED_MEDIA,
                new String[] { "_id", "message_id", "storage_id", "sha256", "mime_type", "size_bytes", "created_at" },
                "storage_id=?", new String[] { uri.getLastPathSegment() }, null, null, null);
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        // Exported so the hooked process can record a deletion. Without this check any
        // application could write arbitrary rows into the user's message history.
        if (!com.waenhancer.security.CallerAuthority.isTrustedCaller(getContext())) return null;
        if (uriMatcher.match(uri) == DELETED_MESSAGES && values != null) {
            SQLiteDatabase db = dbHelper.getWritableDatabase();

            // Only schema columns are persisted. The caller controls this bundle, and an unknown
            // key would otherwise reach SQLite and throw back across Binder into the hook.
            ContentValues row = DelMessageStore.filterMessageColumns(values);
            String chatJid = row.getAsString("chat_jid");
            String keyId = row.getAsString("key_id");
            if (keyId == null || chatJid == null) return null;

            String contactName = row.getAsString("contact_name");
            if (contactName != null && !contactName.isEmpty()) {
                ContentValues updateValues = new ContentValues();
                updateValues.put("contact_name", contactName);
                db.update(DelMessageStore.TABLE_DELETED_FOR_ME, updateValues, "chat_jid = ?", new String[]{chatJid});
            }

            // Never CONFLICT_REPLACE: REPLACE deletes the conflicting row, and the media
            // foreign key would cascade the preserved media of a message that is merely being
            // re-recorded. Update in place so the row _id, and its media, survive.
            long id = db.insertWithOnConflict(DelMessageStore.TABLE_DELETED_FOR_ME, null, row,
                    SQLiteDatabase.CONFLICT_IGNORE);
            if (id <= 0) {
                db.update(DelMessageStore.TABLE_DELETED_FOR_ME, row, "key_id=? AND chat_jid=?",
                        new String[]{keyId, chatJid});
                id = dbHelper.findMessageId(keyId, chatJid);
            }
            if (id > 0) {
                // The hook sends this while the source is still reachable. A failed media copy
                // must never discard the recovered message, so media is deliberately optional.
                String sourcePath = values.getAsString("media_path");
                if (sourcePath != null && !sourcePath.isEmpty()) {
                    try {
                        new com.waenhancer.xposed.core.db.DeletedMediaVault(getContext(), dbHelper)
                                .preserve(id, new java.io.File(sourcePath), values.getAsString("media_mime_type"),
                                        values.getAsLong("media_quota_bytes") == null ? 0L
                                                : values.getAsLong("media_quota_bytes"));
                    } catch (java.io.IOException ignored) {
                        // The message remains available; the UI can show it without media.
                    }
                }
                return Uri.withAppendedPath(CONTENT_URI, String.valueOf(id));
            }
        }
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        if (!com.waenhancer.security.CallerAuthority.isSelf() || uriMatcher.match(uri) != MEDIA_ITEM) return 0;
        try {
            // Deduplicated media can be shared by several messages. Detach this owner and only
            // erase the bytes once nothing references them any more.
            String owner = uri.getQueryParameter("message_id");
            com.waenhancer.xposed.core.db.DeletedMediaVault vault =
                    new com.waenhancer.xposed.core.db.DeletedMediaVault(getContext(), dbHelper);
            if (owner != null) {
                try {
                    return vault.releaseReference(uri.getLastPathSegment(), Long.parseLong(owner)) ? 1 : 0;
                } catch (NumberFormatException invalid) {
                    return 0;
                }
            }
            return vault.permanentlyDelete(uri.getLastPathSegment()) ? 1 : 0;
        } catch (java.io.IOException ignored) {
            return 0;
        }
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws java.io.FileNotFoundException {
        if (!com.waenhancer.security.CallerAuthority.isTrustedCaller(getContext()) || !"r".equals(mode)
                || uriMatcher.match(uri) != MEDIA_ITEM) throw new java.io.FileNotFoundException();
        try {
            java.io.File media = new com.waenhancer.xposed.core.db.DeletedMediaVault(getContext(), dbHelper)
                    .resolve(uri.getLastPathSegment());
            return ParcelFileDescriptor.open(media, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (java.io.IOException exception) {
            throw new java.io.FileNotFoundException();
        }
    }

    @Nullable
    @Override
    public android.os.Bundle call(@NonNull String method, @Nullable String arg, @Nullable android.os.Bundle extras) {
        var context = getContext();
        if (context == null) {
            return super.call(method, arg, extras);
        }

        // This provider is exported. Every operation below touches the user's message history
        // or their automation log, so an untrusted caller gets nothing at all.
        if (!com.waenhancer.security.CallerAuthority.isTrustedCaller(context)) {
            return null;
        }

        // get_preference and put_preference are deliberately gone. A provider that owns the
        // deleted-message database has no business being a second, unvalidated door onto the
        // configuration; HookProvider is the one configuration bridge and it checks the schema.

        if ("log_tasker_event".equals(method) && extras != null) {
            String type = extras.getString("type");
            String targetNumber = extras.getString("targetNumber");
            String messagePreview = extras.getString("messagePreview");
            if (type != null && targetNumber != null) {
                try {
                    com.waenhancer.utils.TaskerHistoryManager.getInstance(context)
                            .logEvent(type, targetNumber, messagePreview != null ? messagePreview : "");
                } catch (Exception e) {
                    android.util.Log.e("DeletedMessagesProvider", "Failed to log tasker event", e);
                }
            }
            return android.os.Bundle.EMPTY;
        }

        return super.call(method, arg, extras);
    }
}
