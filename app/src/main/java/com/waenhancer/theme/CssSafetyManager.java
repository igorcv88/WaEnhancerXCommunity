package com.waenhancer.theme;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Local CSS validation, temporary testing, rollback and safe-mode state. */
public final class CssSafetyManager {

    public static final int MAX_CSS_BYTES = 256 * 1024;
    public static final int MAX_IMAGE_REFERENCES = 24;
    public static final long DEFAULT_TEST_DURATION_MS = 120_000L;
    public static final String KEY_LAST_VALID = "css_last_valid";
    public static final String KEY_PREVIOUS_VALID = "css_previous_valid";
    public static final String KEY_SAFE_MODE = "css_safe_mode";
    public static final String KEY_FAILURE_COUNT = "css_failure_count";
    public static final String KEY_TEST_CSS = "css_test_value";
    public static final String KEY_TEST_EXPIRES_AT = "css_test_expires_at";

    private CssSafetyManager() {
    }

    public static ValidationResult validate(String css) {
        String source = css == null ? "" : css;
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_CSS_BYTES) {
            errors.add("CSS exceeds 256 KB.");
        }

        String lower = source.toLowerCase(Locale.ROOT);
        if (lower.contains("javascript:")) errors.add("javascript: URLs are not allowed.");
        if (lower.contains("expression(")) errors.add("CSS expressions are not allowed.");
        if (lower.matches("(?s).*@import\\s+(url\\()?['\"]?https?://.*")) {
            errors.add("Remote @import rules are not allowed.");
        }

        int braces = 0;
        boolean inString = false;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (inString) {
                if (c == quote) inString = false;
                continue;
            }
            if (c == '\'' || c == '"') {
                inString = true;
                quote = c;
            } else if (c == '{') {
                braces++;
            } else if (c == '}') {
                braces--;
                if (braces < 0) {
                    errors.add("A closing brace has no matching opening brace.");
                    break;
                }
            }
        }
        if (inString) errors.add("A quoted CSS value is not closed.");
        if (braces != 0) errors.add("CSS braces are unbalanced.");

        int images = occurrences(lower, "url(");
        if (images > MAX_IMAGE_REFERENCES) {
            errors.add("CSS contains more than " + MAX_IMAGE_REFERENCES + " image references.");
        }
        if (lower.contains("position:fixed") || lower.contains("position: fixed")) {
            warnings.add("Fixed-position elements can cover WhatsApp controls.");
        }
        if (lower.contains("*{") || lower.contains("* {")) {
            warnings.add("A universal selector can increase rendering cost.");
        }
        return new ValidationResult(errors.isEmpty(), errors, warnings);
    }

    public static SaveResult save(SharedPreferences preferences, String css) {
        ValidationResult validation = validate(css);
        if (!validation.valid) return new SaveResult(false, validation, null);

        String previous = preferences.getString(KEY_LAST_VALID, "");
        String safeCss = css == null ? "" : css;
        boolean committed = preferences.edit()
                .putString(KEY_PREVIOUS_VALID, previous)
                .putString(KEY_LAST_VALID, safeCss)
                .putString("custom_css", safeCss)
                .remove(KEY_TEST_CSS)
                .remove(KEY_TEST_EXPIRES_AT)
                .putBoolean(KEY_SAFE_MODE, false)
                .putInt(KEY_FAILURE_COUNT, 0)
                .commit();
        return new SaveResult(committed, validation, committed ? safeCss : null);
    }

    public static SaveResult beginTest(SharedPreferences preferences, String css, long durationMs) {
        ValidationResult validation = validate(css);
        if (!validation.valid) return new SaveResult(false, validation, null);
        long duration = Math.max(15_000L, Math.min(10 * 60_000L, durationMs));
        String testCss = css == null ? "" : css;
        boolean committed = preferences.edit()
                .putString(KEY_TEST_CSS, testCss)
                .putLong(KEY_TEST_EXPIRES_AT, System.currentTimeMillis() + duration)
                .putBoolean(KEY_SAFE_MODE, false)
                .commit();
        return new SaveResult(committed, validation, committed ? testCss : null);
    }

    public static String effectiveCss(SharedPreferences preferences) {
        if (preferences.getBoolean(KEY_SAFE_MODE, false)) return "";

        long expiresAt = readLong(preferences, KEY_TEST_EXPIRES_AT, 0L);
        String testCss = preferences.getString(KEY_TEST_CSS, "");
        if (expiresAt > System.currentTimeMillis() && validate(testCss).valid) {
            return testCss;
        }

        String candidate = preferences.getString("custom_css", "");
        if (validate(candidate).valid) return candidate;
        return preferences.getString(KEY_LAST_VALID, "");
    }

    public static boolean rollback(SharedPreferences preferences) {
        String previous = preferences.getString(KEY_PREVIOUS_VALID, "");
        if (!validate(previous).valid) return false;
        return preferences.edit()
                .putString("custom_css", previous)
                .putString(KEY_LAST_VALID, previous)
                .remove(KEY_TEST_CSS)
                .remove(KEY_TEST_EXPIRES_AT)
                .putBoolean(KEY_SAFE_MODE, false)
                .putInt(KEY_FAILURE_COUNT, 0)
                .commit();
    }

    public static boolean enableSafeMode(SharedPreferences preferences) {
        return preferences.edit()
                .putBoolean(KEY_SAFE_MODE, true)
                .remove(KEY_TEST_CSS)
                .remove(KEY_TEST_EXPIRES_AT)
                .commit();
    }

    public static boolean disableSafeMode(SharedPreferences preferences) {
        return preferences.edit()
                .putBoolean(KEY_SAFE_MODE, false)
                .putInt(KEY_FAILURE_COUNT, 0)
                .commit();
    }

    public static void recordThemeFailure(SharedPreferences preferences) {
        int failures;
        try {
            failures = preferences.getInt(KEY_FAILURE_COUNT, 0) + 1;
        } catch (ClassCastException ignored) {
            failures = 1;
        }
        SharedPreferences.Editor editor = preferences.edit().putInt(KEY_FAILURE_COUNT, failures);
        if (failures >= 3) editor.putBoolean(KEY_SAFE_MODE, true);
        editor.apply();
    }

    public static void clearFailureState(SharedPreferences preferences) {
        preferences.edit().putInt(KEY_FAILURE_COUNT, 0).apply();
    }

    private static long readLong(SharedPreferences preferences, String key, long fallback) {
        try {
            Object value = preferences.getAll().get(key);
            if (value instanceof Number) return ((Number) value).longValue();
            if (value instanceof String) return Long.parseLong((String) value);
        } catch (RuntimeException ignored) {
        }
        return fallback;
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    public static final class ValidationResult {
        public final boolean valid;
        public final List<String> errors;
        public final List<String> warnings;

        private ValidationResult(boolean valid, List<String> errors, List<String> warnings) {
            this.valid = valid;
            this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
            this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
        }

        public String message() {
            if (valid && warnings.isEmpty()) return "CSS is valid.";
            List<String> messages = new ArrayList<>();
            messages.addAll(errors);
            messages.addAll(warnings);
            return String.join("\n", messages);
        }
    }

    public static final class SaveResult {
        public final boolean saved;
        public final ValidationResult validation;
        public final String css;

        private SaveResult(boolean saved, ValidationResult validation, String css) {
            this.saved = saved;
            this.validation = validation;
            this.css = css;
        }
    }
}
