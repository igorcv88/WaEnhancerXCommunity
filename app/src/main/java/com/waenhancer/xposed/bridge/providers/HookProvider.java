package com.waenhancer.xposed.bridge.providers;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.waenhancer.config.PreferenceSchema;
import com.waenhancer.config.PreferenceStores;
import com.waenhancer.security.CallerAuthority;
import com.waenhancer.xposed.bridge.service.HookBinder;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * The configuration bridge between the module and the hooked WhatsApp process.
 *
 * <p>Phase C3 replaces the generic preference operations this provider used to expose. It was
 * {@code exported="true"} with no permission and no caller check, opened
 * {@code Binder.clearCallingIdentity()} immediately, and served {@code get_all_preferences},
 * {@code put_preference}, {@code remove_preference} and {@code clear_preferences} to any
 * application installed on the device — enough for any third-party app to read every module
 * setting, rewrite them, or wipe them.</p>
 *
 * <p>What changed:</p>
 * <ul>
 *   <li>every call validates the Binder calling UID against {@link CallerAuthority};</li>
 *   <li>the calling identity is captured <em>before</em> it is cleared, never after;</li>
 *   <li>reads are limited to keys the schema marks as public, so the private store is not
 *       reachable across the boundary at all;</li>
 *   <li>writes are limited to keys the schema knows, and rejected for secrets;</li>
 *   <li>{@code clear_preferences} is gone: no caller has a legitimate reason to wipe the
 *       configuration through IPC;</li>
 *   <li>{@code get_secret} serves a single named secret, and only to a trusted UID, so a hook
 *       can use an API key without that key living in the world-readable file.</li>
 * </ul>
 */
public class HookProvider extends ContentProvider {

    private static final String METHOD_GET = "get_preference";
    private static final String METHOD_GET_ALL = "get_all_preferences";
    private static final String METHOD_PUT = "put_preference";
    private static final String METHOD_REMOVE = "remove_preference";
    private static final String METHOD_GET_SECRET = "get_secret";
    private static final String METHOD_BINDER = "getHookBinder";

    @Override
    public boolean onCreate() {
        return getContext() != null;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        Context context = getContext();
        if (context == null) return null;

        // Resolve the caller before clearing the identity: after clearCallingIdentity() the
        // Binder UID is this process, so any check made later would trivially pass.
        if (!CallerAuthority.isTrustedCaller(context)) return null;

        long token = android.os.Binder.clearCallingIdentity();
        try {
            SharedPreferences publicStore = PreferenceStores.publicStore(context);

            if (METHOD_BINDER.equals(method)) {
                Bundle result = new Bundle();
                result.putBinder("binder", HookBinder.getInstance());
                return result;
            }

            // Telemetry was removed. These remain local no-ops for one compatibility release so
            // an injected process built against the previous bridge does not crash.
            if ("record_event".equals(method) || "record_crash".equals(method)) {
                return Bundle.EMPTY;
            }

            if (METHOD_GET.equals(method) && extras != null) {
                String key = extras.getString("key");
                Bundle result = new Bundle();
                if (isReadablePublicKey(key)) {
                    putTyped(result, publicStore.getAll().get(key));
                }
                return result;
            }

            if (METHOD_GET_ALL.equals(method)) {
                Bundle result = new Bundle();
                HashMap<String, Object> visible = new HashMap<>();
                for (Map.Entry<String, ?> entry : publicStore.getAll().entrySet()) {
                    if (isReadablePublicKey(entry.getKey())) {
                        visible.put(entry.getKey(), entry.getValue());
                    }
                }
                result.putSerializable("prefs", visible);
                return result;
            }

            if (METHOD_GET_SECRET.equals(method) && extras != null) {
                String key = extras.getString("key");
                if (key == null || !PreferenceSchema.isSecret(key)) return null;
                Bundle result = new Bundle();
                Object value = PreferenceStores.privateStore(context).getAll().get(key);
                if (value == null) value = publicStore.getAll().get(key);
                if (value instanceof String) result.putString("value", (String) value);
                return result;
            }

            if (METHOD_PUT.equals(method) && extras != null) {
                return write(context, extras);
            }

            if (METHOD_REMOVE.equals(method) && extras != null) {
                String key = extras.getString("key");
                if (!isWritableKey(key)) return null;
                PreferenceStores.storeFor(context, key).edit().remove(key).commit();
                notifyPreferencesChanged(context);
                return Bundle.EMPTY;
            }

            // clear_preferences is deliberately not implemented.
            return null;
        } finally {
            android.os.Binder.restoreCallingIdentity(token);
        }
    }

    /** Public, schema-known keys only: the private store is never served across the boundary. */
    private static boolean isReadablePublicKey(String key) {
        if (key == null) return false;
        PreferenceSchema.Entry entry = PreferenceSchema.entry(key);
        return entry != null && entry.store == PreferenceSchema.Store.PUBLIC;
    }

    /** A write is accepted for a key the schema knows and that is not a secret. */
    private static boolean isWritableKey(String key) {
        if (key == null) return false;
        PreferenceSchema.Entry entry = PreferenceSchema.entry(key);
        return entry != null && entry.sensitivity != PreferenceSchema.Sensitivity.SECRET;
    }

    private Bundle write(Context context, Bundle extras) {
        String key = extras.getString("key");
        String type = extras.getString("type");
        if (!isWritableKey(key) || type == null) return null;

        SharedPreferences.Editor editor = PreferenceStores.storeFor(context, key).edit();
        switch (type) {
            case "string":
                editor.putString(key, extras.getString("value"));
                break;
            case "string_set":
                var values = extras.getStringArrayList("value");
                editor.putStringSet(key, values == null ? null : new HashSet<>(values));
                break;
            case "boolean":
                editor.putBoolean(key, extras.getBoolean("value"));
                break;
            case "int":
                editor.putInt(key, extras.getInt("value"));
                break;
            case "long":
                editor.putLong(key, extras.getLong("value"));
                break;
            case "float":
                editor.putFloat(key, extras.getFloat("value"));
                break;
            default:
                return null;
        }
        editor.commit();
        notifyPreferencesChanged(context);
        return Bundle.EMPTY;
    }

    private static void putTyped(Bundle result, Object value) {
        if (value instanceof Boolean) result.putBoolean("value", (Boolean) value);
        else if (value instanceof String) result.putString("value", (String) value);
        else if (value instanceof Integer) result.putInt("value", (Integer) value);
        else if (value instanceof Long) result.putLong("value", (Long) value);
        else if (value instanceof Float) result.putFloat("value", (Float) value);
    }

    private static void notifyPreferencesChanged(Context context) {
        context.getContentResolver().notifyChange(
                Uri.parse("content://" + com.waenhancer.BuildConfig.APPLICATION_ID
                        + ".hookprovider/preferences"),
                null);
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection,
                        @Nullable String selection, @Nullable String[] selectionArgs,
                        @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return "";
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values,
                      @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }
}
