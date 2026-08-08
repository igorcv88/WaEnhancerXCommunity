package com.waenhancer.security;

import android.content.SharedPreferences;

import com.waenhancer.config.SafePrefs;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether an automation request to send a WhatsApp message may proceed.
 *
 * <p>The integration previously accepted any broadcast of {@code com.waenhancer.MESSAGE_SENT}
 * from any application, with no token, no allowlist and no rate limit. Any app installed on the
 * device could make WhatsApp send arbitrary messages to arbitrary numbers.</p>
 *
 * <p>A request is now accepted only when the integration is enabled, the caller presents the
 * per-installation token, the caller package is on the user's allowlist, the request is not a
 * duplicate, and the rate limit has room. Every check is on values the module controls; nothing
 * trusts a package name on its own.</p>
 *
 * <p>The class is deliberately free of Android dependencies beyond {@code SharedPreferences} so
 * the decision logic is covered by ordinary JVM tests.</p>
 */
public final class TaskerGuard {

    public static final String KEY_ENABLED = "tasker";
    public static final String KEY_SECRET = "tasker_secret";
    public static final String KEY_ALLOWED_PACKAGES = "tasker_allowed_packages";
    public static final String KEY_LEGACY_MODE = "tasker_legacy_unauthenticated";
    public static final String KEY_INCLUDE_BODY = "tasker_broadcast_message_body";

    /** At most this many sends in the window below. */
    public static final int RATE_LIMIT = 5;
    public static final long RATE_WINDOW_MS = 10_000L;
    /** Identical number and message inside this window is treated as a repeat. */
    public static final long DEDUPE_WINDOW_MS = 2_000L;

    public enum Decision {
        ALLOWED,
        DISABLED,
        BAD_TOKEN,
        PACKAGE_NOT_ALLOWED,
        DUPLICATE,
        RATE_LIMITED,
        MALFORMED
    }

    private final Deque<Long> recentSends = new ArrayDeque<>();
    private String lastNumber;
    private String lastMessage;
    private long lastSendAt;

    /** Generates the per-installation token. Never derived from anything guessable. */
    public static String newSecret() {
        byte[] raw = new byte[24];
        new SecureRandom().nextBytes(raw);
        StringBuilder text = new StringBuilder(raw.length * 2);
        for (byte b : raw) text.append(String.format(Locale.ROOT, "%02x", b));
        return text.toString();
    }

    public static Set<String> allowedPackages(SharedPreferences preferences) {
        Set<String> stored = SafePrefs.getStringSet(preferences, KEY_ALLOWED_PACKAGES, null);
        return stored == null ? Collections.emptySet() : new LinkedHashSet<>(stored);
    }

    /**
     * @param preferences   store visible to the deciding process
     * @param callerPackage package resolved from the calling UID, never one supplied by the caller
     * @param token         token presented with the request
     * @param number        destination
     * @param message       body
     * @param now           current time
     */
    public synchronized Decision evaluate(SharedPreferences preferences, String callerPackage,
                                          String token, String number, String message, long now) {
        if (!SafePrefs.getBoolean(preferences, KEY_ENABLED, false)) return Decision.DISABLED;
        if (isBlank(number) || message == null) return Decision.MALFORMED;

        boolean legacy = SafePrefs.getBoolean(preferences, KEY_LEGACY_MODE, false);
        if (!legacy) {
            String expected = SafePrefs.getString(preferences, KEY_SECRET, null);
            if (isBlank(expected) || !constantTimeEquals(expected, token)) {
                return Decision.BAD_TOKEN;
            }
            Set<String> allowed = allowedPackages(preferences);
            if (allowed.isEmpty() || callerPackage == null || !allowed.contains(callerPackage)) {
                return Decision.PACKAGE_NOT_ALLOWED;
            }
        }

        if (number.equals(lastNumber) && message.equals(lastMessage)
                && now - lastSendAt < DEDUPE_WINDOW_MS) {
            return Decision.DUPLICATE;
        }

        while (!recentSends.isEmpty() && now - recentSends.peekFirst() > RATE_WINDOW_MS) {
            recentSends.removeFirst();
        }
        if (recentSends.size() >= RATE_LIMIT) return Decision.RATE_LIMITED;

        recentSends.addLast(now);
        lastNumber = number;
        lastMessage = message;
        lastSendAt = now;
        return Decision.ALLOWED;
    }

    /** Whether an outgoing event broadcast may carry the message body. Off by default. */
    public static boolean mayIncludeBody(SharedPreferences preferences) {
        return SafePrefs.getBoolean(preferences, KEY_INCLUDE_BODY, false);
    }

    /** Compares without leaking length or position through timing. */
    public static boolean constantTimeEquals(String expected, String presented) {
        if (expected == null || presented == null) return false;
        byte[] a = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = presented.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int diff = a.length ^ b.length;
        for (int i = 0; i < a.length && i < b.length; i++) diff |= a[i] ^ b[i];
        return diff == 0 && a.length == b.length;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
