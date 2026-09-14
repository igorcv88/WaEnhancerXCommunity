package com.waenhancer.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Two-stage migration helper for LSPosed's deprecated New XSharedPreferences (NSP) transport.
 *
 * <p>While the APK still declares NSP support, LSPosed redirects the module's SharedPreferences
 * directory into its shared preference area. Removing that declaration immediately would move
 * both the public and private stores back to the application's normal data directory and make the
 * existing values appear to disappear. To avoid that, this class snapshots both stores into
 * {@code filesDir}, which is not affected by the SharedPreferences directory hook.</p>
 *
 * <p>The transition is intentionally staged:</p>
 * <ol>
 *   <li>Phase 1 keeps {@code xposedminversion=93} / {@code xposedsharedprefs} and captures a
 *       typed snapshot while runtime hooks switch to the provider bridge.</li>
 *   <li>Phase 2 lowers the legacy minimum to 82 and removes {@code xposedsharedprefs}. On first
 *       manager start, the snapshot is merged into the normal app-private stores exactly once.</li>
 * </ol>
 */
public final class NspPreferenceMigration {

    private static final int SNAPSHOT_VERSION = 1;
    private static final String SNAPSHOT_FILE = "nsp_preferences_v1.json";
    private static final String RESTORED_MARKER = "nsp_preferences_restored_v1";
    private static final Object LOCK = new Object();

    private NspPreferenceMigration() {
    }

    /** True when this APK still asks LSPosed for New XSharedPreferences semantics. */
    public static boolean declaresNewXSharedPreferences(Context context) {
        if (context == null) return false;
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(
                    context.getPackageName(), PackageManager.GET_META_DATA);
            Bundle meta = info.metaData;
            if (meta == null) return false;
            if (meta.containsKey("xposedsharedprefs")) return true;

            Object raw = meta.get("xposedminversion");
            int minVersion = 0;
            if (raw instanceof Integer) {
                minVersion = (Integer) raw;
            } else if (raw instanceof String) {
                String text = ((String) raw).trim();
                StringBuilder digits = new StringBuilder();
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (!Character.isDigit(c)) break;
                    digits.append(c);
                }
                if (digits.length() > 0) minVersion = Integer.parseInt(digits.toString());
            }
            return minVersion > 92;
        } catch (PackageManager.NameNotFoundException | NumberFormatException | RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Capture the current NSP-backed stores. This is a no-op after NSP metadata has been removed.
     */
    public static void captureIfNeeded(Context context) {
        if (context == null || !declaresNewXSharedPreferences(context)) return;
        synchronized (LOCK) {
            try {
                JSONObject root = new JSONObject();
                root.put("version", SNAPSHOT_VERSION);
                root.put("public", encodeStore(PreferenceStores.publicStore(context)));
                root.put("private", encodeStore(PreferenceStores.privateStore(context)));
                writeSnapshot(context, root.toString());
            } catch (Throwable ignored) {
                // Migration safety must never stop the settings app from starting.
            }
        }
    }

    /**
     * Restore the staged snapshot after the APK stops declaring NSP. The restore is merge-only so
     * values already written to the normal stores are not cleared merely because an older snapshot
     * lacks them. Snapshot values win on this one first migration because they are the user's last
     * known configuration from the NSP-backed release.
     */
    public static void restoreIfNeeded(Context context) {
        if (context == null || declaresNewXSharedPreferences(context)) return;

        File marker = new File(context.getFilesDir(), RESTORED_MARKER);
        if (marker.exists()) return;

        File snapshot = new File(context.getFilesDir(), SNAPSHOT_FILE);
        if (!snapshot.isFile()) return;

        synchronized (LOCK) {
            if (marker.exists()) return;
            try {
                JSONObject root = new JSONObject(readSnapshot(snapshot));
                if (root.optInt("version", -1) != SNAPSHOT_VERSION) return;

                boolean publicOk = restoreStore(
                        root.optJSONObject("public"), PreferenceStores.publicStore(context));
                boolean privateOk = restoreStore(
                        root.optJSONObject("private"), PreferenceStores.privateStore(context));
                if (!publicOk || !privateOk) return;

                try (FileOutputStream out = new FileOutputStream(marker)) {
                    out.write("1".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }

                // The snapshot can contain secrets from private_config. Once restored, remove the
                // duplicate copy from filesDir rather than keeping sensitive stale data around.
                //noinspection ResultOfMethodCallIgnored
                snapshot.delete();
            } catch (Throwable ignored) {
                // Leave the snapshot and marker untouched so the next start can retry.
            }
        }
    }

    private static JSONObject encodeStore(SharedPreferences prefs) throws Exception {
        JSONObject encoded = new JSONObject();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || value == null) continue;

            JSONObject item = new JSONObject();
            if (value instanceof String) {
                item.put("type", "string");
                item.put("value", value);
            } else if (value instanceof Boolean) {
                item.put("type", "boolean");
                item.put("value", value);
            } else if (value instanceof Integer) {
                item.put("type", "int");
                item.put("value", value);
            } else if (value instanceof Long) {
                item.put("type", "long");
                item.put("value", value);
            } else if (value instanceof Float) {
                item.put("type", "float");
                item.put("value", ((Float) value).doubleValue());
            } else if (value instanceof Set<?>) {
                JSONArray array = new JSONArray();
                boolean supported = true;
                for (Object member : (Set<?>) value) {
                    if (!(member instanceof String)) {
                        supported = false;
                        break;
                    }
                    array.put(member);
                }
                if (!supported) continue;
                item.put("type", "string_set");
                item.put("value", array);
            } else {
                continue;
            }
            encoded.put(key, item);
        }
        return encoded;
    }

    private static boolean restoreStore(JSONObject encoded, SharedPreferences prefs) {
        if (encoded == null) return true;
        try {
            SharedPreferences.Editor editor = prefs.edit();
            JSONArray names = encoded.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String key = names.optString(i, null);
                    if (key == null) continue;
                    JSONObject item = encoded.optJSONObject(key);
                    if (item == null) continue;

                    String type = item.optString("type", "");
                    switch (type) {
                        case "string":
                            editor.putString(key, item.optString("value", null));
                            break;
                        case "boolean":
                            editor.putBoolean(key, item.optBoolean("value"));
                            break;
                        case "int":
                            editor.putInt(key, item.optInt("value"));
                            break;
                        case "long":
                            editor.putLong(key, item.optLong("value"));
                            break;
                        case "float":
                            editor.putFloat(key, (float) item.optDouble("value"));
                            break;
                        case "string_set":
                            JSONArray values = item.optJSONArray("value");
                            if (values == null) break;
                            Set<String> strings = new HashSet<>();
                            for (int j = 0; j < values.length(); j++) {
                                String value = values.optString(j, null);
                                if (value != null) strings.add(value);
                            }
                            editor.putStringSet(key, strings);
                            break;
                        default:
                            break;
                    }
                }
            }
            return editor.commit();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void writeSnapshot(Context context, String json) throws Exception {
        AtomicFile atomic = new AtomicFile(new File(context.getFilesDir(), SNAPSHOT_FILE));
        FileOutputStream stream = null;
        try {
            stream = atomic.startWrite();
            stream.write(json.getBytes(StandardCharsets.UTF_8));
            stream.flush();
            atomic.finishWrite(stream);
        } catch (Throwable t) {
            if (stream != null) atomic.failWrite(stream);
            if (t instanceof Exception) throw (Exception) t;
            throw new RuntimeException(t);
        }
    }

    private static String readSnapshot(File file) throws Exception {
        AtomicFile atomic = new AtomicFile(file);
        try (FileInputStream in = atomic.openRead(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
