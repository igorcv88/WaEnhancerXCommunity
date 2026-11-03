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
    /** Marker meaning "no verified typed result; use the installed SQL guard". */
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
            return UNSUPPORTED; // Unknown enum ordering is not a semantic contract.
        }
        if (returnType == int.class || returnType == Integer.class) return 0;
        if (returnType == long.class || returnType == Long.class) return 0L;
        if (returnType == boolean.class || returnType == Boolean.class) return Boolean.FALSE;
        return UNSUPPORTED;
    }

    /** Resolve only the known scalar or a single None-ack wrapper contract. */
    public static Object blockedMessageRevokeResult(Class<?> type,
            java.util.function.Function<Class<?>, Object> noneAck) {
        if (type == null) return UNSUPPORTED;
        if (isBooleanResult(type)) return Boolean.TRUE;
        Object scalar = blockedStatusRevokeResult(type);
        if (scalar != UNSUPPORTED) return scalar;
        if (type.isPrimitive() || type.isEnum()) return UNSUPPORTED;
        try {
            var constructors = type.getDeclaredConstructors();
            if (constructors.length != 1) return UNSUPPORTED;
            var constructor = constructors[0];
            Class<?>[] types = constructor.getParameterTypes();
            Object[] args = new Object[types.length];
            boolean foundAck = false;
            for (int i = 0; i < types.length; i++) {
                if (types[i].isPrimitive()) {
                    args[i] = blockedStatusRevokeResult(types[i]);
                    if (args[i] == UNSUPPORTED) return UNSUPPORTED;
                } else {
                    Object ack = noneAck.apply(types[i]);
                    if (ack == null || !types[i].isInstance(ack) || foundAck) return UNSUPPORTED;
                    args[i] = ack;
                    foundAck = true;
                }
            }
            if (!foundAck) return UNSUPPORTED;
            constructor.setAccessible(true);
            return constructor.newInstance(args);
        } catch (Throwable incompatible) { return UNSUPPORTED; }
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
                    Object singleton = field.get(null);
                    if (singleton != null) return singleton;
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }
}
