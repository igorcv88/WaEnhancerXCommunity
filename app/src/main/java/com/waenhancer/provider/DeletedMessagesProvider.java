package com.waenhancer.provider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.waenhancer.xposed.core.db.DelMessageStore;

public class DeletedMessagesProvider extends ContentProvider {

    public static final String AUTHORITY = com.waenhancer.BuildConfig.APPLICATION_ID + ".provider";
    public static final String PATH_DELETED_MESSAGES = "deleted_messages";
    public static final String PATH_PREFERENCES = "preferences";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/" + PATH_DELETED_MESSAGES);
    public static final Uri PREF_URI = Uri.parse("content://" + AUTHORITY + "/" + PATH_PREFERENCES);

    private static final int DELETED_MESSAGES = 1;
    private static final int PREFERENCES = 2;
    private static final UriMatcher uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        uriMatcher.addURI(AUTHORITY, PATH_DELETED_MESSAGES, DELETED_MESSAGES);
        uriMatcher.addURI(AUTHORITY, PATH_PREFERENCES, PREFERENCES);
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
        // Not needed for now, but good practice to implement basic query if UI needs it later
        // or just return null if we only use it for insertion from Xposed
        return null; 
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
            
            // --- NEW: Propagate Contact Name to all messages in this chat ---
            String chatJid = values.getAsString("chat_jid");
            String contactName = values.getAsString("contact_name");
            
            if (chatJid != null && contactName != null && !contactName.isEmpty()) {
                ContentValues updateValues = new ContentValues();
                updateValues.put("contact_name", contactName);
                db.update(DelMessageStore.TABLE_DELETED_FOR_ME, updateValues, "chat_jid = ?", new String[]{chatJid});
            }
            // ----------------------------------------------------------------
            
            long id = db.insertWithOnConflict(DelMessageStore.TABLE_DELETED_FOR_ME, null, values, SQLiteDatabase.CONFLICT_REPLACE);
            if (id > 0) {
                return Uri.withAppendedPath(CONTENT_URI, String.valueOf(id));
            }
        }
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
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
