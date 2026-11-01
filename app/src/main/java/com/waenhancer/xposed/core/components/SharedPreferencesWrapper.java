package com.waenhancer.xposed.core.components;

import android.content.ContextWrapper;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.waenhancer.xposed.core.devkit.Unobfuscator;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class SharedPreferencesWrapper implements SharedPreferences {

    private final static HashSet<SPrefHook> prefHook = new HashSet<>();
    private final SharedPreferences mPreferences;

    public SharedPreferencesWrapper(SharedPreferences sharedPreferences) {
        mPreferences = sharedPreferences;
    }

    @Override
    public Map<String, ?> getAll() {
        return mPreferences.getAll();
    }

    @Nullable
    @Override
    public String getString(String s, @Nullable String s1) {
        Object value;
        try {
            value = mPreferences.getString(s, s1);
        } catch (ClassCastException e) {
            try {
                value = mPreferences.getBoolean(s, false) ? "1" : "0";
            } catch (Exception ignored) {
                try {
                    value = String.valueOf(mPreferences.getAll().get(s));
                } catch (Exception ignored2) {
                    value = s1;
                }
            }
        }
        return (String) applyHook(s, value);
    }

    /**
     * @noinspection unchecked
     */
    @Nullable
    @Override
    public Set<String> getStringSet(String s, @Nullable Set<String> set) {
        var value = mPreferences.getStringSet(s, set);
        return (Set<String>) applyHook(s, value);
    }

    @Override
    public int getInt(String s, int i) {
        var value = mPreferences.getInt(s, i);
        return (int) applyHook(s, value);
    }

    @Override
    public long getLong(String s, long l) {
        var value = mPreferences.getLong(s, l);
        return (long) applyHook(s, value);
    }

    @Override
    public float getFloat(String s, float v) {
        Object value;
        try {
            value = mPreferences.getFloat(s, v);
        } catch (ClassCastException e) {
            try {
                value = (float) mPreferences.getInt(s, (int) v);
            } catch (Exception ignored) {
                value = v;
            }
        }
        return (float) applyHook(s, value);
    }

    @Override
    public boolean getBoolean(String s, boolean b) {
        var value = mPreferences.getBoolean(s, b);
        return (boolean) applyHook(s, value);
    }

    @Override
    public boolean contains(String s) {
        var value = mPreferences.contains(s);
        return (boolean) applyHook(s, value);
    }

    @Override
    public Editor edit() {
        return mPreferences.edit();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener onSharedPreferenceChangeListener) {
        mPreferences.registerOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener);
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener onSharedPreferenceChangeListener) {
        mPreferences.unregisterOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener);
    }

    public static void hookInit(ClassLoader classLoader) throws Exception {
        XposedHelpers.findAndHookMethod("android.app.ContextImpl", classLoader, "getSharedPreferences", String.class, int.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                var pref = (SharedPreferences) param.getResult();
                if (pref == null || pref instanceof SharedPreferencesWrapper) return;
                param.setResult(new SharedPreferencesWrapper(pref));
            }
        });
        var sharedPreferencesClasses = Unobfuscator.loadSharedPreferencesClasses(classLoader);
        if (sharedPreferencesClasses == null || sharedPreferencesClasses.length == 0) return;

        XC_MethodHook getStringHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                var key = (String) param.args[0];
                var value = param.getResult();
                param.setResult(applyHook(key, value));
            }
        };

        XC_MethodHook getBooleanHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                var key = (String) param.args[0];
                var value = param.getResult();
                param.setResult((boolean) applyHook(key, value));
            }
        };

        XC_MethodHook getIntHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                var key = (String) param.args[0];
                var value = param.getResult();
                param.setResult((int) applyHook(key, value));
            }
        };

        XC_MethodHook getLongHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                var key = (String) param.args[0];
                var value = param.getResult();
                param.setResult((long) applyHook(key, value));
            }
        };

        XC_MethodHook getFloatHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                var key = (String) param.args[0];
                var value = param.getResult();
                param.setResult((float) applyHook(key, value));
            }
        };

        XC_MethodHook containsHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                var key = (String) param.args[0];
                var value = param.getResult();
                param.setResult((boolean) applyHook(key, value));
            }
        };

        XC_MethodHook getAllHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) param.getResult();
                if (result == null || result.isEmpty()) return;
                var updated = new java.util.HashMap<String, Object>(result.size());
                for (var entry : result.entrySet()) {
                    updated.put(entry.getKey(), applyHook(entry.getKey(), entry.getValue()));
                }
                param.setResult(updated);
            }
        };

        for (var sharedPreferencesClass : sharedPreferencesClasses) {
            if (sharedPreferencesClass == null) continue;
            if (SharedPreferencesWrapper.class.getName().equals(sharedPreferencesClass.getName())) continue;
            // Dev4Mod f2551e7: abstract bases/interfaces have nothing concrete to hook, and one
            // missing getter must not abort hooking the remaining implementations.
            int modifiers = sharedPreferencesClass.getModifiers();
            if (java.lang.reflect.Modifier.isAbstract(modifiers) || sharedPreferencesClass.isInterface()) continue;
            hookIfPresent(sharedPreferencesClass, "getString", getStringHook, String.class, String.class);
            hookIfPresent(sharedPreferencesClass, "getStringSet", getStringHook, String.class, Set.class);
            hookIfPresent(sharedPreferencesClass, "getInt", getIntHook, String.class, int.class);
            hookIfPresent(sharedPreferencesClass, "getLong", getLongHook, String.class, long.class);
            hookIfPresent(sharedPreferencesClass, "getFloat", getFloatHook, String.class, float.class);
            hookIfPresent(sharedPreferencesClass, "getBoolean", getBooleanHook, String.class, boolean.class);
            hookIfPresent(sharedPreferencesClass, "contains", containsHook, String.class);
            hookIfPresent(sharedPreferencesClass, "getAll", getAllHook);
        }
    }

    private static void hookIfPresent(Class<?> owner, String name, XC_MethodHook hook, Class<?>... params) {
        try {
            XposedHelpers.findAndHookMethod(owner, name, (Object[]) appendHook(params, hook));
        } catch (Throwable missing) {
            XposedBridge.log("[WAE] SharedPreferencesWrapper: " + owner.getName() + "#" + name
                    + " not hooked (" + missing.getClass().getSimpleName() + ")");
        }
    }

    private static Object[] appendHook(Class<?>[] params, XC_MethodHook hook) {
        Object[] result = new Object[params.length + 1];
        System.arraycopy(params, 0, result, 0, params.length);
        result[params.length] = hook;
        return result;
    }

    public static void addHook(SPrefHook hook) {
        prefHook.add(hook);
    }

    private static Object applyHook(String key, Object value) {
        if ("software_forced_expiration".equals(key) || "expiration_timestamp".equals(key) || "client_expiration_time".equals(key)) {
            if (value instanceof Long) {
                long longVal = (Long) value;
                if (longVal > 0 && longVal < 10000000000L) {
                    return 4102444800L; // Year 2100 in seconds
                } else {
                    return 4102444800000L; // Year 2100 in milliseconds
                }
            } else if (value instanceof Integer) {
                return 2147483647;
            } else if (value instanceof String) {
                String strVal = (String) value;
                if (strVal.length() < 11) {
                    return "4102444800"; // Year 2100 in seconds
                } else {
                    return "4102444800000"; // Year 2100 in milliseconds
                }
            }
        }

        for (SPrefHook hook : prefHook) {
            value = hook.hookValue(key, value);
        }
        return value;
    }

    public interface SPrefHook {
        @Nullable
        Object hookValue(String key, Object value);
    }
}
