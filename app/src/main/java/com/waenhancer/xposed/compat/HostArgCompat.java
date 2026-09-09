package com.waenhancer.xposed.compat;

/**
 * Small, Android-free argument helper for hooks whose host signatures drift between WhatsApp
 * releases. This ports the useful part of upstream's primitive-aware ReflectionUtils change
 * without changing the semantics of every legacy Community caller at once.
 */
public final class HostArgCompat {

    public static Class<?> boxedType(Class<?> type) {
        if (type == null || !type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        if (type == void.class) return Void.class;
        return type;
    }

    public static boolean isInstance(Class<?> requestedType, Object value) {
        if (requestedType == null || value == null) return false;
        Class<?> boxed = boxedType(requestedType);
        return boxed != null && boxed.isInstance(value);
    }

    public static int findIndexOfType(Object[] args, Class<?> requestedType) {
        if (args == null || requestedType == null) return -1;
        Class<?> boxed = boxedType(requestedType);
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg == null) continue;
            if (arg instanceof Class<?>) {
                Class<?> argClass = boxedType((Class<?>) arg);
                if (boxed != null && argClass != null && boxed.isAssignableFrom(argClass)) {
                    return i;
                }
                continue;
            }
            if (boxed != null && boxed.isInstance(arg)) return i;
        }
        return -1;
    }

    public static Number numberAt(Object[] args, int index) {
        if (args == null || index < 0 || index >= args.length) return null;
        Object value = args[index];
        return value instanceof Number ? (Number) value : null;
    }

    /**
     * Returns the {@code ordinal}-th argument assignable to {@code requestedType}, or the last
     * matching argument when {@code ordinal} is {@code -1}. Primitive types are matched against
     * their boxed counterparts, so {@code getArg(args, int.class, 0)} finds an {@code Integer}.
     * Returns {@code null} when nothing matches instead of throwing.
     */
    @SuppressWarnings("unchecked")
    public static <T> T getArg(Object[] args, Class<T> requestedType, int ordinal) {
        if (args == null || requestedType == null) return null;
        Class<?> boxed = boxedType(requestedType);
        if (boxed == null) return null;
        int seen = 0;
        Object last = null;
        for (Object arg : args) {
            if (!boxed.isInstance(arg)) continue;
            last = arg;
            if (ordinal >= 0 && seen++ == ordinal) return (T) arg;
        }
        return ordinal == -1 ? (T) last : null;
    }

    private HostArgCompat() {
    }
}
