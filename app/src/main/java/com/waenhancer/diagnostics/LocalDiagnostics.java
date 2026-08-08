package com.waenhancer.diagnostics;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import com.waenhancer.BuildConfig;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

/** Local-only, size-bounded diagnostics with redaction before persistence. */
public final class LocalDiagnostics {

    private static final Object LOCK = new Object();
    private static final int MAX_EVENTS = 200;
    private static final int MAX_FILE_BYTES = 128 * 1024;
    private static final String FILE_NAME = "waenhancer-diagnostics.log";

    private static final Pattern JID = Pattern.compile("(?i)\\b[0-9]{5,20}@(s\\.whatsapp\\.net|g\\.us|lid)\\b");
    private static final Pattern PHONE = Pattern.compile("(?<![A-Za-z0-9])\\+?[0-9][0-9 .()_-]{6,}[0-9](?![A-Za-z0-9])");
    private static final Pattern PRIVATE_PATH = Pattern.compile("(?i)(/data/(user|user_de|data)/[^\\s,;]+|/storage/emulated/[0-9]+/[^\\s,;]+)");
    private static final Pattern MESSAGE_FIELD = Pattern.compile("(?i)(message|text|body|jid|number|contact|name)=([^,;\\n]+)");
    private static final Pattern TOKEN = Pattern.compile(
            "(?i)(gh[pousr]_[A-Za-z0-9]{16,}|AIza[0-9A-Za-z_-]{20,}|xox[abprs]-[A-Za-z0-9-]{10,}"
                    + "|eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,})");
    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{8,}");
    // The separator is required so the trigger word alone does not swallow the following token,
    // and the replacement markers below deliberately avoid every trigger word so a later pattern
    // cannot redact the redaction.
    private static final Pattern SECRET_FIELD = Pattern.compile(
            "(?i)\\b(authorization|token|secret|password|passwd|api[_-]?key|keybox|private[_-]?key)"
                    + "\\b\\s*[:=]\\s*(?:bearer\\s+)?[^\\s,;\\n]+");
    private static final Pattern PEM_BLOCK = Pattern.compile("(?s)-----BEGIN[^-]*-----.*?-----END[^-]*-----");
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");

    private LocalDiagnostics() {
    }

    public static void record(Context context, String category, String message) {
        if (context == null) return;
        String safeCategory = sanitize(category == null ? "general" : category);
        String safeMessage = sanitize(message == null ? "" : message);
        String line = utcTimestamp() + " [" + safeCategory + "] " + safeMessage;

        synchronized (LOCK) {
            try {
                File file = file(context);
                try (java.io.FileOutputStream output =
                             new java.io.FileOutputStream(file, true)) {
                    output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                }
                trim(file);
            } catch (IOException ignored) {
                // Diagnostics must never affect the module's normal execution.
            }
        }
    }

    public static String buildReport(Context context, SharedPreferences preferences) {
        StringBuilder report = new StringBuilder();
        report.append("WaEnhancer Community local diagnostic report\n");
        report.append("Generated: ").append(utcTimestamp()).append('\n');
        report.append("App: ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")\n");
        report.append("Application ID: ").append(BuildConfig.APPLICATION_ID).append('\n');
        report.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" / API ").append(Build.VERSION.SDK_INT).append('\n');
        report.append("Device: ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append('\n');
        report.append("WhatsApp: ").append(packageVersion(context, "com.whatsapp")).append('\n');
        report.append("WhatsApp Business: ")
                .append(packageVersion(context, "com.whatsapp.w4b")).append('\n');

        if (preferences != null) {
            report.append("CSS safe mode: ")
                    .append(preferences.getBoolean("css_safe_mode", false)).append('\n');
            report.append("CSS failure count: ")
                    .append(readInt(preferences, "css_failure_count", 0)).append('\n');
            report.append("Floating bottom bar: ")
                    .append(preferences.getBoolean("floating_bottom_bar", false)).append('\n');
            report.append("Theme preset: ")
                    .append(sanitize(preferences.getString("wae_color_preset", "green"))).append('\n');
            report.append("Pending restart: ")
                    .append(preferences.getBoolean("need_restart", false)).append('\n');
        }

        report.append("\nRecent redacted events:\n");
        for (String line : readEvents(context)) {
            report.append(sanitize(line)).append('\n');
        }
        report.append("\nNo messages, JIDs, phone numbers, tokens, keys, certificates, or private paths are intentionally included.\n");
        return report.toString();
    }

    public static void clear(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            try {
                Files.deleteIfExists(file(context).toPath());
            } catch (IOException ignored) {
            }
        }
    }

    public static String sanitize(String value) {
        if (value == null) return "";
        String result = value.replace('\r', ' ').replace('\n', ' ');
        // Cryptographic material and credentials first: they can contain characters the later,
        // narrower patterns would otherwise leave behind.
        result = PEM_BLOCK.matcher(result).replaceAll("<redacted-crypto>");
        result = TOKEN.matcher(result).replaceAll("<redacted-credential>");
        result = BEARER.matcher(result).replaceAll("<redacted-credential>");
        result = SECRET_FIELD.matcher(result).replaceAll("$1=<redacted-credential>");
        result = JID.matcher(result).replaceAll("<redacted-jid>");
        result = EMAIL.matcher(result).replaceAll("<redacted-email>");
        result = PHONE.matcher(result).replaceAll("<redacted-number>");
        result = PRIVATE_PATH.matcher(result).replaceAll("<redacted-path>");
        result = MESSAGE_FIELD.matcher(result).replaceAll("$1=<redacted>");
        if (result.length() > 2000) result = result.substring(0, 2000) + "…";
        return result;
    }

    private static List<String> readEvents(Context context) {
        synchronized (LOCK) {
            try {
                File file = file(context);
                if (!file.exists()) return List.of();
                List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
                int start = Math.max(0, lines.size() - MAX_EVENTS);
                return new ArrayList<>(lines.subList(start, lines.size()));
            } catch (IOException ignored) {
                return List.of();
            }
        }
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    private static void trim(File file) throws IOException {
        if (file.length() <= MAX_FILE_BYTES) return;
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        int start = Math.max(0, lines.size() - MAX_EVENTS);
        List<String> kept = new ArrayList<>(lines.subList(start, lines.size()));
        while (byteSize(kept) > MAX_FILE_BYTES && kept.size() > 1) kept.remove(0);
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(file, false)) {
            for (String line : kept) {
                output.write(line.getBytes(StandardCharsets.UTF_8));
                output.write('\n');
            }
        }
    }

    private static int byteSize(List<String> lines) {
        int size = 0;
        for (String line : lines) size += line.getBytes(StandardCharsets.UTF_8).length + 1;
        return size;
    }

    private static int readInt(SharedPreferences preferences, String key, int fallback) {
        try {
            Object value = preferences.getAll().get(key);
            if (value instanceof Number) return ((Number) value).intValue();
            if (value instanceof String) return Integer.parseInt((String) value);
        } catch (RuntimeException ignored) {
        }
        return fallback;
    }

    private static String packageVersion(Context context, String packageName) {
        try {
            return context.getPackageManager().getPackageInfo(packageName, 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            return "not installed";
        }
    }

    private static String utcTimestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
}
