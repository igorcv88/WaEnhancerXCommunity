package com.waenhancer.xposed.features.general;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Text-only matching for the home conversation filter. No message bodies are inspected. */
public final class HomeInlineSearchMatcher {

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private HomeInlineSearchMatcher() { }

    public static boolean matches(String query, String displayName, String number, String rawJid) {
        String needle = normalize(query);
        if (needle.isEmpty()) return true;
        if (normalize(displayName).contains(needle)) return true;

        String digits = digits(query);
        if (!digits.isEmpty()) {
            return digits(number).contains(digits) || digits(rawJid).contains(digits);
        }
        return false;
    }

    public static String normalize(String value) {
        if (value == null) return "";
        String decomposed = Normalizer.normalize(value.trim(), Normalizer.Form.NFD);
        return COMBINING_MARKS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT);
    }

    private static String digits(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') out.append(c);
        }
        return out.toString();
    }
}
