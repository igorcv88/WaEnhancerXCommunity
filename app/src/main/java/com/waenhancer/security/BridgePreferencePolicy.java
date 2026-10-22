package com.waenhancer.security;

import com.waenhancer.config.PreferenceSchema;
import java.util.List;
import java.util.Locale;

/** The preference IPC may mutate only public, non-secret keys with schema-exact types. */
public final class BridgePreferencePolicy {
    private BridgePreferencePolicy() {}

    public static boolean writable(String key) {
        PreferenceSchema.Entry entry = key == null ? null : PreferenceSchema.entry(key);
        return entry != null && entry.store == PreferenceSchema.Store.PUBLIC
                && entry.sensitivity != PreferenceSchema.Sensitivity.SECRET;
    }

    public static boolean requiresCompanion(String key) {
        PreferenceSchema.Entry entry = key == null ? null : PreferenceSchema.entry(key);
        return entry != null && entry.store == PreferenceSchema.Store.PRIVATE;
    }

    public static boolean accepts(String key, String type, Object value) {
        if (!writable(key) || type == null) return false;
        PreferenceSchema.Type expected = PreferenceSchema.entry(key).type;
        if (!expected.name().toLowerCase(Locale.ROOT).equals(type)) return false;
        switch (expected) {
            case BOOLEAN: return value instanceof Boolean;
            case INT: return value instanceof Integer;
            case LONG: return value instanceof Long;
            case FLOAT: return value instanceof Float;
            case STRING: return value == null || value instanceof String;
            case STRING_SET:
                if (value == null) return true;
                if (!(value instanceof List<?>)) return false;
                for (Object item : (List<?>) value) if (!(item instanceof String)) return false;
                return true;
            default: return false;
        }
    }
}
