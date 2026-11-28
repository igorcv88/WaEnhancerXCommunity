package com.waenhancer.xposed.features.customization;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Decides whether a chat-list row belongs to the Chats or the Groups tab.
 *
 * <p>The row's chat is read through the item's own {@code getJid()} (present, unobfuscated, on
 * the list item of both supported hosts), else through the first field whose value is a JID
 * (has {@code getServer()}). Obfuscated field names are not used: on both hosts {@code A00} is a
 * non-JID chat-info object and {@code A01} the JID, and reading {@code A00} first kept every such
 * row in both tabs.</p>
 */
final class ChatListClassifier {
    private ChatListClassifier() {}

    /** @return true when the row stays in the tab; rows without a readable chat stay in both. */
    static boolean keep(Object chat, boolean groupsTab) {
        String server = serverOf(jidOf(chat));
        if (server == null) return true;
        if (groupsTab) return "broadcast".equals(server) || "g.us".equals(server);
        return "s.whatsapp.net".equals(server) || "lid".equals(server);
    }

    static Object jidOf(Object chat) {
        if (chat == null) return null;
        Method getJid = noArg(chat.getClass(), "getJid");
        if (getJid != null) {
            try {
                Object jid = getJid.invoke(chat);
                if (serverOf(jid) != null) return jid;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        for (Class<?> type = chat.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(chat);
                    if (serverOf(value) != null) return value;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }
        return null;
    }

    static String serverOf(Object jid) {
        if (jid == null) return null;
        Method getServer = noArg(jid.getClass(), "getServer");
        if (getServer == null || getServer.getReturnType() != String.class) return null;
        try {
            return (String) getServer.invoke(jid);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Method noArg(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Method method = c.getDeclaredMethod(name);
                if (Modifier.isStatic(method.getModifiers())) return null;
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }
}
