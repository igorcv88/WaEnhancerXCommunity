package com.waenhancer.backup;

import android.content.Context;
import android.content.SharedPreferences;

import com.waenhancer.config.BottomBarPreferenceSchema;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Versioned, allowlisted settings backup. Database/media backup belongs to Block D. */
public final class BackupCodec {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    private static final Set<String> SAFE_KEYS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList((
                    "thememode,wae_color_mode,wae_color_preset,force_english,update_check," +
                    "disable_expiration,lite_mode,show_hook_toast,bypass_version_check," +
                    "customize_supported_versions,ampm,segundos,secondstotime," +
                    "antirevoke,antirevokestatus,viewonce,downloadviewonce,download_video_note," +
                    "hideread,hidereceipt,hidestatusview,ghostmode,ghostmode_r,freezelastseen," +
                    "call_blocker,call_type,hide_archived_chat,hide_once_view_seen,blueonreply," +
                    "removeforwardlimit,customforwardlimit,disable_pinned_limit," +
                    "delete_for_everyone_all_messages,show_edited_message_history," +
                    "show_message_device_source,show_button_to_send_blue_tick," +
                    "imagequality,videoquality,increase_video_size_limit,send_video_in_real_resolution," +
                    "send_video_in_60fps,statusdowload,statuscomposer,oldstatus,igstatus,channels," +
                    "removechannel_rec,status_style,autonext_status,copystatus,toast_viewed_status," +
                    "enable_spy,novaconfig,novofiltro,menuwicon,showname,showbio,show_dnd_button," +
                    "separate_groups,separate_groups_counter,enable_filter_chats,disable_channels," +
                    "admin_grp,admin_emoji,floatingmenu,animation_emojis,bubble_color,bubble_left," +
                    "bubble_right,changecolor,changecolor_mode,primary_color,background_color," +
                    "text_color,wallpaper,wallpaper_file,wallpaper_alpha,wallpaper_alpha_toolbar," +
                    "wallpaper_alpha_navigation,hidetabs,custom_filters,filter_items,css_theme," +
                    "change_dpi,folder_theme,animation_list,custom_toolbar,custom_view," +
                    "floating_bottom_bar,floating_bottom_bar_glass,floating_bottom_bar_fill_color," +
                    "floating_bottom_bar_fully_rounded,floating_bottom_bar_radius," +
                    "floating_bottom_bar_bottom_margin,floating_bottom_bar_horizontal_margin," +
                    "floating_bottom_bar_glass_opacity,floating_bottom_bar_icon_size," +
                    "floating_bottom_bar_text_size,floating_bottom_bar_padding_vertical," +
                    "floating_bottom_bar_icon_label_spacing,floating_bottom_bar_height_mode," +
                    "floating_bottom_bar_manual_height,floating_bottom_bar_scroll_hide_mode," +
                    "floating_bottom_bar_fab_mode,floating_bottom_bar_fab_offset," +
                    "floating_bottom_bar_minimal_fab_size,floating_bottom_bar_minimal_fab_radius," +
                    "floating_bottom_bar_minimal_fab_opacity,floating_bottom_bar_minimal_fab_margin," +
                    "floating_bottom_bar_minimal_fab_color,floating_bottom_bar_minimal_fab_icon_color," +
                    "floating_bottom_bar_indicator_visible,floating_bottom_bar_indicator_width_mode," +
                    "floating_bottom_bar_indicator_height_mode,floating_bottom_bar_indicator_width," +
                    "floating_bottom_bar_indicator_height,floating_bottom_bar_indicator_radius," +
                    "floating_bottom_bar_indicator_padding_horizontal," +
                    "floating_bottom_bar_indicator_padding_vertical,floating_bottom_bar_indicator_offset," +
                    "floating_bottom_bar_indicator_opacity,floating_bottom_bar_indicator_color," +
                    "tasker_enabled,tasker_confirm_send,tasker_allowed_packages," +
                    "call_recording,call_recording_format,call_recording_source," +
                    "auto_status_forward,status_forward_rules,google_translate,audio_transcript," +
                    "toast_viewer,contact_online_notification,locked_chats_enhancer,hide_chat," +
                    "tag_message,download_profile,media_preview,file_size_spoofer," +
                    "force_restore_backup_feature,lazy_feature_loading"
            ).split(","))));

    private static final Set<String> BLOCKED_KEYS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "keybox", "keybox_xml", "private_key", "certificate", "certificates",
                    "license_key", "license_token", "tg_username", "encrypted_config",
                    "is_pro_verified", "expires_at", "pro_plugin_path", "pro_plugin_lib_path",
                    "github_token", "gh_public_token", "tasker_secret", "installation_id",
                    "module_heartbeat", "last_crash", "crash_stacktrace")));

    private static final Map<String, String> LEGACY_ALIASES;

    static {
        Map<String, String> aliases = new HashMap<>();
        aliases.put("floating_bottom_bar_scroll_hide", "floating_bottom_bar_scroll_hide_mode");
        aliases.put("colors_customization", "changecolor");
        aliases.put("custom_primary_color", "primary_color");
        LEGACY_ALIASES = Collections.unmodifiableMap(aliases);
    }

    private BackupCodec() {
    }

    public static String exportSettings(SharedPreferences preferences, String appVersion)
            throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("appVersion", appVersion == null ? "unknown" : appVersion);
        root.put("createdAt", OffsetDateTime.now().toString());

        JSONObject settings = new JSONObject();
        for (String key : SAFE_KEYS) {
            if (!preferences.contains(key) || isSensitive(key)) continue;
            Object value = preferences.getAll().get(key);
            if (!isSupportedValue(value)) continue;
            settings.put(key, encode(value));
        }
        root.put("settings", settings);
        return root.toString(2);
    }

    public static ImportPlan parseAndValidate(byte[] data) throws BackupException {
        if (data == null || data.length == 0) throw new BackupException("Backup is empty.");
        if (data.length > MAX_BYTES) throw new BackupException("Backup exceeds the 2 MB limit.");

        final JSONObject root;
        try {
            root = new JSONObject(new String(data, StandardCharsets.UTF_8));
        } catch (JSONException exception) {
            throw new BackupException("Backup is not valid JSON.", exception);
        }

        boolean legacy = !root.has("schemaVersion");
        JSONObject source;
        int schemaVersion;
        if (legacy) {
            source = root;
            schemaVersion = 0;
        } else {
            schemaVersion = root.optInt("schemaVersion", -1);
            if (schemaVersion != SCHEMA_VERSION) {
                throw new BackupException("Unsupported backup schema: " + schemaVersion);
            }
            source = root.optJSONObject("settings");
            if (source == null) throw new BackupException("Backup does not contain settings.");
        }

        Map<String, Object> values = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        List<String> sensitive = new ArrayList<>();
        List<String> normalized = new ArrayList<>();

        for (String originalKey : iterable(source.keys())) {
            String key = LEGACY_ALIASES.getOrDefault(originalKey, originalKey);
            if (isSensitive(key)) {
                sensitive.add(originalKey);
                continue;
            }
            if (!SAFE_KEYS.contains(key)) {
                unknown.add(originalKey);
                continue;
            }

            try {
                Object decoded = decode(source.get(originalKey), legacy);
                Object safe = normalizeValue(key, decoded, normalized);
                if (safe != null) values.put(key, safe);
            } catch (JSONException | IllegalArgumentException exception) {
                throw new BackupException("Invalid value for setting: " + originalKey, exception);
            }
        }

        return new ImportPlan(schemaVersion, legacy, values, unknown, sensitive, normalized);
    }

    public static ImportReport apply(Context context, SharedPreferences preferences, ImportPlan plan)
            throws BackupException {
        if (plan == null) throw new BackupException("Import plan is missing.");
        writeSnapshot(context, preferences);

        Map<String, Object> before = new LinkedHashMap<>(preferences.getAll());
        SharedPreferences.Editor editor = preferences.edit();
        for (Map.Entry<String, Object> entry : plan.values.entrySet()) {
            put(editor, entry.getKey(), entry.getValue());
        }

        if (!editor.commit()) {
            restore(preferences, before);
            throw new BackupException("Android rejected the settings transaction.");
        }
        return new ImportReport(plan.values.size(), plan.unknownKeys,
                plan.sensitiveKeys, plan.normalizedKeys, plan.legacy);
    }

    public static Set<String> safeKeys() {
        return SAFE_KEYS;
    }

    public static boolean isSensitive(String key) {
        if (key == null) return true;
        String normalized = key.toLowerCase(Locale.ROOT);
        if (BLOCKED_KEYS.contains(normalized)) return true;
        return normalized.contains("private_key")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("certificate")
                || normalized.contains("keybox")
                || normalized.contains("license")
                || normalized.contains("plugin_path")
                || normalized.contains("classloader")
                || normalized.contains("heartbeat")
                || normalized.contains("crash_stack");
    }

    private static JSONObject encode(Object value) throws JSONException {
        JSONObject encoded = new JSONObject();
        if (value instanceof Boolean) {
            encoded.put("type", "boolean");
            encoded.put("value", value);
        } else if (value instanceof Integer) {
            encoded.put("type", "int");
            encoded.put("value", value);
        } else if (value instanceof Long) {
            encoded.put("type", "long");
            encoded.put("value", value);
        } else if (value instanceof Float || value instanceof Double) {
            encoded.put("type", "float");
            encoded.put("value", ((Number) value).doubleValue());
        } else if (value instanceof Set<?>) {
            encoded.put("type", "string_set");
            encoded.put("value", new JSONArray(value));
        } else {
            encoded.put("type", "string");
            encoded.put("value", String.valueOf(value));
        }
        return encoded;
    }

    private static Object decode(Object raw, boolean legacy) throws JSONException {
        if (!(raw instanceof JSONObject)) {
            if (!legacy) throw new JSONException("Versioned setting must be an object.");
            return raw;
        }
        JSONObject encoded = (JSONObject) raw;
        String type = encoded.optString("type", "");
        Object value = encoded.get("value");
        switch (type.toLowerCase(Locale.ROOT)) {
            case "boolean":
                if (!(value instanceof Boolean)) throw new JSONException("Expected boolean");
                return value;
            case "integer":
            case "int":
                return asNumber(value).intValue();
            case "long":
                return asNumber(value).longValue();
            case "double":
            case "float":
                return asNumber(value).floatValue();
            case "hashset":
            case "string_set":
            case "jsonarray":
                return toStringSet(value);
            case "string":
                return String.valueOf(value);
            default:
                if (legacy) return value;
                throw new JSONException("Unsupported type: " + type);
        }
    }

    private static Object normalizeValue(String key, Object value, List<String> normalized) {
        if (BottomBarPreferenceSchema.all().containsKey(key)) {
            float safe = BottomBarPreferenceSchema.normalize(key, value);
            if (!(value instanceof Float) || Float.compare(((Number) value).floatValue(), safe) != 0) {
                normalized.add(key);
            }
            return safe;
        }
        if ("floating_bottom_bar_scroll_hide_mode".equals(key) && value instanceof Boolean) {
            normalized.add(key);
            return (Boolean) value ? "tabs" : "off";
        }
        if (!isSupportedValue(value)) {
            throw new IllegalArgumentException("Unsupported value type");
        }
        return value;
    }

    private static boolean isSupportedValue(Object value) {
        if (value instanceof String || value instanceof Boolean || value instanceof Integer
                || value instanceof Long || value instanceof Float || value instanceof Double) {
            return true;
        }
        if (value instanceof Set<?>) {
            for (Object item : (Set<?>) value) if (!(item instanceof String)) return false;
            return true;
        }
        return false;
    }

    private static Number asNumber(Object value) throws JSONException {
        if (value instanceof Number) return (Number) value;
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw new JSONException("Expected number");
        }
    }

    private static Set<String> toStringSet(Object value) throws JSONException {
        JSONArray array;
        if (value instanceof JSONArray) array = (JSONArray) value;
        else throw new JSONException("Expected array");
        Set<String> result = new LinkedHashSet<>();
        for (int i = 0; i < array.length(); i++) result.add(array.getString(i));
        return result;
    }

    private static void put(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        else if (value instanceof Double) editor.putFloat(key, ((Double) value).floatValue());
        else if (value instanceof Set<?>) {
            @SuppressWarnings("unchecked") Set<String> set = (Set<String>) value;
            editor.putStringSet(key, new LinkedHashSet<>(set));
        } else editor.putString(key, String.valueOf(value));
    }

    private static void restore(SharedPreferences preferences, Map<String, Object> snapshot) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, Object> entry : snapshot.entrySet()) {
            put(editor, entry.getKey(), entry.getValue());
        }
        editor.commit();
    }

    private static void writeSnapshot(Context context, SharedPreferences preferences)
            throws BackupException {
        if (context == null) return;
        try {
            File directory = new File(context.getFilesDir(), "migration_snapshots");
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IllegalStateException("Unable to create snapshot directory");
            }
            File snapshot = new File(directory,
                    "settings-before-import-" + System.currentTimeMillis() + ".json");
            try (FileOutputStream output = new FileOutputStream(snapshot)) {
                output.write(exportSettings(preferences, "pre-import-snapshot")
                        .getBytes(StandardCharsets.UTF_8));
            }
            pruneSnapshots(directory, 5);
        } catch (Exception exception) {
            throw new BackupException("Unable to create the pre-import snapshot.", exception);
        }
    }

    private static void pruneSnapshots(File directory, int keep) {
        File[] files = directory.listFiles();
        if (files == null || files.length <= keep) return;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = keep; i < files.length; i++) files[i].delete();
    }

    private static <T> Iterable<T> iterable(final java.util.Iterator<T> iterator) {
        return () -> iterator;
    }

    public static final class ImportPlan {
        public final int schemaVersion;
        public final boolean legacy;
        public final Map<String, Object> values;
        public final List<String> unknownKeys;
        public final List<String> sensitiveKeys;
        public final List<String> normalizedKeys;

        private ImportPlan(int schemaVersion, boolean legacy, Map<String, Object> values,
                           List<String> unknownKeys, List<String> sensitiveKeys,
                           List<String> normalizedKeys) {
            this.schemaVersion = schemaVersion;
            this.legacy = legacy;
            this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            this.unknownKeys = Collections.unmodifiableList(new ArrayList<>(unknownKeys));
            this.sensitiveKeys = Collections.unmodifiableList(new ArrayList<>(sensitiveKeys));
            this.normalizedKeys = Collections.unmodifiableList(new ArrayList<>(normalizedKeys));
        }
    }

    public static final class ImportReport {
        public final int applied;
        public final List<String> skippedUnknown;
        public final List<String> skippedSensitive;
        public final List<String> normalized;
        public final boolean legacy;

        private ImportReport(int applied, List<String> skippedUnknown,
                             List<String> skippedSensitive, List<String> normalized,
                             boolean legacy) {
            this.applied = applied;
            this.skippedUnknown = skippedUnknown;
            this.skippedSensitive = skippedSensitive;
            this.normalized = normalized;
            this.legacy = legacy;
        }

        public String summary() {
            return "Applied " + applied + " settings"
                    + (legacy ? " from a legacy backup" : "")
                    + ". Unknown skipped: " + skippedUnknown.size()
                    + "; sensitive skipped: " + skippedSensitive.size()
                    + "; normalized: " + normalized.size() + ".";
        }
    }

    public static class BackupException extends Exception {
        public BackupException(String message) {
            super(message);
        }

        public BackupException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
