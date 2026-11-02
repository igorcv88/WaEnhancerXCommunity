package com.waenhancer.xposed.core.devkit;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * String descriptors for {@link UnobfuscatorCache}.
 *
 * <p>The historical layout is retained ({@code owner:name[:p1,p2]} for methods,
 * {@code owner:name} for fields, {@code &}-joined arrays) so a module update does not need
 * a format migration. Version 2 appends {@code |returnType} / {@code |fieldType}: a cached
 * member whose owner and name still exist but whose type changed after an in-place WhatsApp
 * update is rejected and re-resolved instead of being hooked with an incompatible result
 * type. Descriptors without the suffix (written before this change) still decode.</p>
 *
 * <p>An empty array is encoded as {@link #EMPTY_ARRAY}; the old encoding wrote {@code ""},
 * which decoded as one class named "" and failed permanently.</p>
 */
public final class ReflectionDescriptors {

    /** Resolves a binary class name, including primitive names, in the host class loader. */
    public interface ClassFinder {
        Class<?> find(String name) throws Exception;
    }

    static final String EMPTY_ARRAY = "∅";
    private static final char TYPE_SEPARATOR = '|';

    private ReflectionDescriptors() {
    }

    // ---- classes ----

    public static String encodeClass(Class<?> value) {
        return value.getName();
    }

    public static Class<?> decodeClass(ClassFinder finder, String raw) throws Exception {
        requireText(raw);
        return finder.find(raw);
    }

    public static String encodeClasses(Class<?>[] values) {
        if (values.length == 0) return EMPTY_ARRAY;
        List<String> parts = new ArrayList<>(values.length);
        for (Class<?> value : values) parts.add(encodeClass(value));
        return String.join("&", parts);
    }

    public static Class<?>[] decodeClasses(ClassFinder finder, String raw) throws Exception {
        if (EMPTY_ARRAY.equals(raw)) return new Class<?>[0];
        String[] parts = splitArray(raw);
        Class<?>[] result = new Class<?>[parts.length];
        for (int i = 0; i < parts.length; i++) result[i] = decodeClass(finder, parts[i]);
        return result;
    }

    // ---- methods ----

    public static String encodeMethod(Method method) {
        StringBuilder out = new StringBuilder(method.getDeclaringClass().getName())
                .append(':').append(method.getName());
        Class<?>[] params = method.getParameterTypes();
        if (params.length > 0) {
            out.append(':');
            for (int i = 0; i < params.length; i++) {
                if (i > 0) out.append(',');
                out.append(params[i].getName());
            }
        }
        return out.append(TYPE_SEPARATOR).append(method.getReturnType().getName()).toString();
    }

    public static Method decodeMethod(ClassFinder finder, String raw) throws Exception {
        requireText(raw);
        String expectedType = null;
        int typeAt = raw.indexOf(TYPE_SEPARATOR);
        if (typeAt >= 0) {
            expectedType = raw.substring(typeAt + 1);
            raw = raw.substring(0, typeAt);
        }
        String[] parts = raw.split(":", -1);
        if (parts.length < 2 || parts.length > 3 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw new IllegalArgumentException("Malformed method descriptor");
        }
        Class<?> owner = finder.find(parts[0]);
        Class<?>[] params = parts.length == 3 ? findAll(finder, parts[2].split(",", -1)) : new Class<?>[0];
        Method method = owner.getDeclaredMethod(parts[1], params);
        if (expectedType != null && !method.getReturnType().getName().equals(expectedType)) {
            throw new NoSuchMethodException("Cached return type changed for " + parts[0] + "." + parts[1]);
        }
        method.setAccessible(true);
        return method;
    }

    public static String encodeMethods(Method[] methods) {
        if (methods.length == 0) return EMPTY_ARRAY;
        List<String> parts = new ArrayList<>(methods.length);
        for (Method method : methods) parts.add(encodeMethod(method));
        return String.join("&", parts);
    }

    public static Method[] decodeMethods(ClassFinder finder, String raw) throws Exception {
        if (EMPTY_ARRAY.equals(raw)) return new Method[0];
        String[] parts = splitArray(raw);
        Method[] result = new Method[parts.length];
        for (int i = 0; i < parts.length; i++) result[i] = decodeMethod(finder, parts[i]);
        return result;
    }

    // ---- constructors ----

    public static String encodeConstructor(Constructor<?> constructor) {
        StringBuilder out = new StringBuilder(constructor.getDeclaringClass().getName());
        Class<?>[] params = constructor.getParameterTypes();
        if (params.length > 0) {
            out.append(':');
            for (int i = 0; i < params.length; i++) {
                if (i > 0) out.append(',');
                out.append(params[i].getName());
            }
        }
        return out.toString();
    }

    public static Constructor<?> decodeConstructor(ClassFinder finder, String raw) throws Exception {
        requireText(raw);
        String[] parts = raw.split(":", -1);
        if (parts.length > 2 || parts[0].isEmpty()) {
            throw new IllegalArgumentException("Malformed constructor descriptor");
        }
        Class<?> owner = finder.find(parts[0]);
        Class<?>[] params = parts.length == 2 ? findAll(finder, parts[1].split(",", -1)) : new Class<?>[0];
        Constructor<?> constructor = owner.getDeclaredConstructor(params);
        constructor.setAccessible(true);
        return constructor;
    }

    // ---- fields ----

    public static String encodeField(Field field) {
        return field.getDeclaringClass().getName() + ':' + field.getName()
                + TYPE_SEPARATOR + field.getType().getName();
    }

    public static Field decodeField(ClassFinder finder, String raw) throws Exception {
        requireText(raw);
        String expectedType = null;
        int typeAt = raw.indexOf(TYPE_SEPARATOR);
        if (typeAt >= 0) {
            expectedType = raw.substring(typeAt + 1);
            raw = raw.substring(0, typeAt);
        }
        String[] parts = raw.split(":", -1);
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw new IllegalArgumentException("Malformed field descriptor");
        }
        Field field = findFieldInHierarchy(finder.find(parts[0]), parts[1]);
        if (expectedType != null && !field.getType().getName().equals(expectedType)) {
            throw new NoSuchFieldException("Cached field type changed for " + parts[0] + "." + parts[1]);
        }
        field.setAccessible(true);
        return field;
    }

    public static String encodeFields(Field[] fields) {
        if (fields.length == 0) return EMPTY_ARRAY;
        List<String> parts = new ArrayList<>(fields.length);
        for (Field field : fields) parts.add(encodeField(field));
        return String.join("&", parts);
    }

    public static Field[] decodeFields(ClassFinder finder, String raw) throws Exception {
        if (EMPTY_ARRAY.equals(raw)) return new Field[0];
        String[] parts = splitArray(raw);
        Field[] result = new Field[parts.length];
        for (int i = 0; i < parts.length; i++) result[i] = decodeField(finder, parts[i]);
        return result;
    }

    /** JSON object of name to field descriptor. Any entry that no longer resolves rejects the whole map. */
    public static String encodeFieldMap(Map<String, Field> map) throws JSONException {
        JSONObject json = new JSONObject();
        for (Map.Entry<String, Field> entry : map.entrySet()) {
            json.put(entry.getKey(), encodeField(entry.getValue()));
        }
        return json.toString();
    }

    public static HashMap<String, Field> decodeFieldMap(ClassFinder finder, String raw) throws Exception {
        JSONObject json = new JSONObject(raw);
        HashMap<String, Field> map = new HashMap<>();
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            map.put(key, decodeField(finder, json.getString(key)));
        }
        return map;
    }

    // ---- numbers ----

    public static String encodeNumber(Number number) {
        return number.getClass().getName() + ":" + number;
    }

    public static Number decodeNumber(String value) {
        requireText(value);
        String[] parts = value.split(":", 2);
        String className = parts.length == 2 ? parts[0] : Integer.class.getName();
        String numberValue = parts.length == 2 ? parts[1] : value;
        switch (className) {
            case "java.lang.Integer":
                return Integer.valueOf(numberValue);
            case "java.lang.Long":
                return Long.valueOf(numberValue);
            case "java.lang.Float":
                return Float.valueOf(numberValue);
            case "java.lang.Double":
                return Double.valueOf(numberValue);
            case "java.lang.Short":
                return Short.valueOf(numberValue);
            case "java.lang.Byte":
                return Byte.valueOf(numberValue);
            default:
                return numberValue.contains(".") ? Double.valueOf(numberValue) : Long.valueOf(numberValue);
        }
    }

    // ---- helpers ----

    private static Field findFieldInHierarchy(Class<?> owner, String name) throws NoSuchFieldException {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(owner.getName() + "#" + name);
    }

    private static Class<?>[] findAll(ClassFinder finder, String[] names) throws Exception {
        Class<?>[] result = new Class<?>[names.length];
        for (int i = 0; i < names.length; i++) {
            requireText(names[i]);
            result[i] = finder.find(names[i]);
        }
        return result;
    }

    private static String[] splitArray(String raw) {
        requireText(raw);
        return raw.split("&", -1);
    }

    private static void requireText(String raw) {
        if (raw == null || raw.isEmpty()) throw new IllegalArgumentException("Empty descriptor");
    }
}
