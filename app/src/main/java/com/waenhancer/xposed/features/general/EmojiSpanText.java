package com.waenhancer.xposed.features.general;

/** Conservative Unicode guard: never replace an arbitrary WhatsApp ReplacementSpan. */
public final class EmojiSpanText {
    private EmojiSpanText() {}

    public static boolean isEmojiOnly(CharSequence text, int start, int end) {
        if (text == null || start < 0 || end > text.length() || start >= end || end - start > 96) {
            return false;
        }
        boolean hasEmoji = false;
        boolean keycapBase = false;
        for (int index = start; index < end; ) {
            int cp = Character.codePointAt(text, index);
            if (index + Character.charCount(cp) > end) return false;
            index += Character.charCount(cp);
            if (keycapBase && cp != 0x20E3 && cp != 0xFE0F) return false;
            if (cp == 0x200D || cp == 0xFE0E || cp == 0xFE0F
                    || (cp >= 0xE0020 && cp <= 0xE007F)) {
                continue;
            }
            if (cp == 0x20E3) {
                if (!keycapBase) return false;
                hasEmoji = true;
                keycapBase = false;
                continue;
            }
            if (cp == '#' || cp == '*' || (cp >= '0' && cp <= '9')) {
                if (keycapBase) return false;
                keycapBase = true;
                continue;
            }
            if (!isEmojiBase(cp)) return false;
            hasEmoji = true;
            keycapBase = false;
        }
        return hasEmoji && !keycapBase;
    }

    private static boolean isEmojiBase(int cp) {
        return (cp >= 0x1F000 && cp <= 0x1FAFF)
                || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0x2194 && cp <= 0x21AA)
                || (cp >= 0x2934 && cp <= 0x2935)
                || (cp >= 0x2B05 && cp <= 0x2B55)
                || cp == 0x00A9 || cp == 0x00AE || cp == 0x203C || cp == 0x2049
                || cp == 0x2122 || cp == 0x2139 || cp == 0x24C2
                || cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299;
    }
}
