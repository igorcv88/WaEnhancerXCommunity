package com.waenhancer.xposed.features.general;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Type-safe replacement results for blocked revoke callbacks.
 *
 * <p>Ported from Dev4Mod/WaEnhancer commit 01a8bb7 (GPL-3.0). Newer WhatsApp builds return
 * an enum ({@code STORED, STORED_ORPHAN, FAILED_TO_STORE, DIDNT_STORE}) from the status revoke
 * path, so forcing {@code 0} raised {@link ClassCastException} in WhatsApp's status pipeline.
 * The result is derived from the hooked method's declared return type instead.</p>
 */
public final class RevokeResultPolicy {
    /** Marker meaning "no compatible blocked result; let WhatsApp run". */
    public static final Object UNSUPPORTED = new Object();

    private RevokeResultPolicy() {
    }

    public static Object blockedStatusRevokeResult(Class<?> returnType) {
        if (returnType == null) return UNSUPPORTED;
        if (returnType == void.class || returnType == Void.class) return null;
        if (returnType.isEnum()) {
            Object[] constants = returnType.getEnumConstants();
            if (constants == null || constants.length == 0) return UNSUPPORTED;
            for (Object constant : constants) {
                if ("DIDNT_STORE".equals(((Enum<?>) constant).name())) return constant;
            }
            return constants.length > 3 ? constants[3] : constants[constants.length - 1];
        }
        if (returnType == int.class || returnType == Integer.class) return 0;
        if (returnType == long.class || returnType == Long.class) return 0L;
        if (returnType == boolean.class || returnType == Boolean.class) return Boolean.FALSE;
        return UNSUPPORTED;
    }

    public static boolean isBooleanResult(Class<?> returnType) {
        return returnType == boolean.class || returnType == Boolean.class;
    }

    /** Static self-typed singleton such as WhatsApp's "None" incoming ack. */
    public static Object staticSingleton(Class<?> type) {
        if (type == null) return null;
        for (Field field : type.getDeclaredFields()) {
            if (field.getType() == type && Modifier.isStatic(field.getModifiers())) {
                try {
                    field.setAccessible(true);
                    return field.get(null);
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }
}
