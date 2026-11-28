package com.waenhancer.xposed.core;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the account's own phone JID is kept inside WhatsApp's MeManager. Up to 2.26.38 the
 * manager holds a {@code PhoneUserJid} field itself. In 2.26.46 it holds only the current
 * {@code Me} record, which in turn holds the {@code PhoneUserJid}. The path is found by
 * type, never by obfuscated name, and must be unique at each step. It is read on every call
 * because WhatsApp replaces the {@code Me} record when the registration changes.
 */
public final class SelfJidPath {
    private final Field[] path;

    private SelfJidPath(Field... path) {
        this.path = path;
        for (Field field : path) field.setAccessible(true);
    }

    /** The unique path from {@code owner} to a field of {@code jidType}, or {@code null}. */
    public static SelfJidPath resolve(Class<?> owner, Class<?> jidType) {
        if (owner == null || jidType == null) return null;
        Field direct = unique(instanceFields(owner, jidType));
        if (direct != null) return new SelfJidPath(direct);
        if (!instanceFields(owner, jidType).isEmpty()) return null; // ambiguous: fail closed
        List<Field[]> nested = new ArrayList<>();
        for (Field holder : owner.getDeclaredFields()) {
            if (Modifier.isStatic(holder.getModifiers()) || !isHostType(holder.getType())) continue;
            List<Field> inner = instanceFields(holder.getType(), jidType);
            if (inner.size() == 1) nested.add(new Field[]{holder, inner.get(0)});
            else if (inner.size() > 1) return null;
        }
        return nested.size() == 1 ? new SelfJidPath(nested.get(0)) : null;
    }

    /** The JID object reached from {@code instance}, or {@code null} while it is not set. */
    public Object read(Object instance) throws IllegalAccessException {
        Object value = instance;
        for (Field field : path) {
            if (value == null) return null;
            value = field.get(value);
        }
        return value;
    }

    /** The class whose instance this path starts from. */
    public Class<?> owner() {
        return path[0].getDeclaringClass();
    }

    public int depth() {
        return path.length;
    }

    private static List<Field> instanceFields(Class<?> cls, Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Field field : cls.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && field.getType() == type) out.add(field);
        }
        return out;
    }

    private static Field unique(List<Field> fields) {
        return fields.size() == 1 ? fields.get(0) : null;
    }

    /** Only the host's own record types are followed, never platform or library containers. */
    private static boolean isHostType(Class<?> type) {
        if (type.isPrimitive() || type.isArray() || type.isInterface()) return false;
        String name = type.getName();
        return !(name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("android.")
                || name.startsWith("androidx.") || name.startsWith("kotlin."));
    }
}
