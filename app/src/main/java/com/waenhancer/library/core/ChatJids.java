package com.waenhancer.library.core;

import java.util.regex.Pattern;

/** Recognises a WhatsApp chat JID among arbitrary strings (e.g. an activity's intent extras). */
public final class ChatJids {
    private static final Pattern CHAT = Pattern.compile("[0-9]{5,20}(-[0-9]{5,20})?@(s\\.whatsapp\\.net|lid|g\\.us)");

    private ChatJids() { }

    public static boolean isChatJid(String value) {
        return value != null && CHAT.matcher(value).matches();
    }

    /** The first chat JID in {@code values}, or {@code null}. */
    public static String first(Iterable<?> values) {
        if (values == null) return null;
        for (Object value : values) {
            if (value instanceof String && isChatJid((String) value)) return (String) value;
        }
        return null;
    }
}
