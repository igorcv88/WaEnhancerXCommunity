package com.waenhancer.theme;

import android.graphics.Color;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Generates semantic color tokens from one accent instead of replacing green pixels blindly. */
public final class SemanticTheme {

    public static final class Tokens {
        private final Map<String, Integer> values;

        private Tokens(Map<String, Integer> values) {
            this.values = Collections.unmodifiableMap(values);
        }

        public int get(String token) {
            Integer value = values.get(token);
            if (value == null) throw new IllegalArgumentException("Unknown token: " + token);
            return value;
        }

        public Map<String, Integer> asMap() {
            return values;
        }
    }

    private static final Map<String, Integer> PRESETS;

    static {
        Map<String, Integer> colors = new LinkedHashMap<>();
        colors.put("green", Color.rgb(79, 175, 80));
        colors.put("blue", Color.rgb(59, 130, 246));
        colors.put("cyan", Color.rgb(6, 182, 212));
        colors.put("purple", Color.rgb(139, 92, 246));
        colors.put("orange", Color.rgb(249, 115, 22));
        colors.put("red", Color.rgb(239, 68, 68));
        colors.put("pink", Color.rgb(236, 72, 153));
        PRESETS = Collections.unmodifiableMap(colors);
    }

    private SemanticTheme() {
    }

    public static Map<String, Integer> presets() {
        return PRESETS;
    }

    public static int presetColor(String name) {
        if (name == null) return PRESETS.get("green");
        return PRESETS.getOrDefault(name.toLowerCase(Locale.ROOT), PRESETS.get("green"));
    }

    public static Tokens fromPreset(String name, boolean dark) {
        return generate(presetColor(name), dark);
    }

    public static Tokens generate(int accent, boolean dark) {
        int primary = ensureControlContrast(accent, dark ? Color.rgb(18, 18, 18) : Color.WHITE, 3.0);
        int onPrimary = bestTextColor(primary);
        int primaryContainer = blend(primary, dark ? Color.BLACK : Color.WHITE, dark ? 0.58f : 0.78f);
        int surface = dark ? Color.rgb(18, 18, 18) : Color.rgb(255, 255, 255);
        int surfaceVariant = dark ? Color.rgb(43, 47, 49) : Color.rgb(238, 241, 242);
        int onSurface = dark ? Color.rgb(245, 247, 248) : Color.rgb(28, 31, 32);
        int outline = ensureControlContrast(blend(primary, onSurface, 0.65f), surface, 3.0);

        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("primary", primary);
        map.put("onPrimary", onPrimary);
        map.put("primaryContainer", primaryContainer);
        map.put("onPrimaryContainer", bestTextColor(primaryContainer));
        map.put("secondary", rotateHue(primary, 24f));
        map.put("surface", surface);
        map.put("surfaceVariant", surfaceVariant);
        map.put("onSurface", onSurface);
        map.put("outline", outline);
        map.put("link", ensureTextContrast(primary, surface, 4.5));
        map.put("selection", withAlpha(primary, 0.24f));
        map.put("fab", primary);
        map.put("activeIndicator", withAlpha(primary, dark ? 0.30f : 0.18f));
        map.put("unreadBadge", primary);
        map.put("outgoingBubble", blend(primary, dark ? Color.BLACK : Color.WHITE, dark ? 0.50f : 0.80f));
        map.put("incomingBubble", surfaceVariant);
        map.put("onOutgoingBubble", bestTextColor(map.get("outgoingBubble")));
        map.put("onIncomingBubble", bestTextColor(surfaceVariant));
        map.put("success", Color.rgb(34, 197, 94));
        map.put("warning", Color.rgb(245, 158, 11));
        map.put("error", Color.rgb(220, 38, 38));
        return new Tokens(map);
    }

    public static int bestTextColor(int background) {
        return contrastRatio(Color.BLACK, background) >= contrastRatio(Color.WHITE, background)
                ? Color.BLACK : Color.WHITE;
    }

    public static int ensureTextContrast(int foreground, int background, double targetRatio) {
        if (contrastRatio(foreground, background) >= targetRatio) return foreground;
        int target = bestTextColor(background);
        int result = foreground;
        for (int i = 1; i <= 20; i++) {
            result = blend(foreground, target, i / 20f);
            if (contrastRatio(result, background) >= targetRatio) return result;
        }
        return target;
    }

    public static int ensureControlContrast(int foreground, int background, double targetRatio) {
        return ensureTextContrast(foreground, background, targetRatio);
    }

    public static double contrastRatio(int first, int second) {
        double l1 = luminance(first);
        double l2 = luminance(second);
        double lighter = Math.max(l1, l2);
        double darker = Math.min(l1, l2);
        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double luminance(int color) {
        double r = linear(Color.red(color) / 255.0);
        double g = linear(Color.green(color) / 255.0);
        double b = linear(Color.blue(color) / 255.0);
        return (0.2126 * r) + (0.7152 * g) + (0.0722 * b);
    }

    private static double linear(double channel) {
        return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    public static int blend(int first, int second, float amountSecond) {
        float t = Math.max(0f, Math.min(1f, amountSecond));
        int a = Math.round(Color.alpha(first) * (1f - t) + Color.alpha(second) * t);
        int r = Math.round(Color.red(first) * (1f - t) + Color.red(second) * t);
        int g = Math.round(Color.green(first) * (1f - t) + Color.green(second) * t);
        int b = Math.round(Color.blue(first) * (1f - t) + Color.blue(second) * t);
        return Color.argb(a, r, g, b);
    }

    public static int withAlpha(int color, float alpha) {
        return Color.argb(Math.round(Math.max(0f, Math.min(1f, alpha)) * 255),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int rotateHue(int color, float degrees) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[0] = (hsv[0] + degrees) % 360f;
        return Color.HSVToColor(Color.alpha(color), hsv);
    }
}
