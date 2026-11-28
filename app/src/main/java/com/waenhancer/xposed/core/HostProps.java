package com.waenhancer.xposed.core;

import com.waenhancer.xposed.compat.HostArgCompat;
import com.waenhancer.xposed.core.devkit.Unobfuscator;
import com.waenhancer.xposed.utils.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * The host's AB-property overrides, shared by every feature that forces a property.
 *
 * <p>The boolean and the integer getter are independent slots: each is resolved and hooked on
 * its own, so a failure of one never removes or blocks the other. A feature calls
 * {@link #requireBooleanOverrides} or {@link #requireIntegerOverrides} for the kind it writes and
 * reports a degraded install when that kind is unavailable, instead of relying on another feature
 * having installed the hook.</p>
 */
public final class HostProps {
    public static final ConcurrentHashMap<Integer, Boolean> BOOLEAN = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<Integer, Integer> INTEGER = new ConcurrentHashMap<>();
    /** Property key that callers in the data-usage screen must read unmodified. */
    static final int DATA_USAGE_EXEMPT = 4023;

    /** Installs one getter hook; throws when the getter cannot be resolved or hooked. */
    interface Installer {
        void install(ClassLoader classLoader) throws Exception;
    }

    /**
     * One getter: installed at most once per process. A failure is remembered (resolution is a
     * deterministic DEX scan) and reported to every later caller of that kind only.
     */
    static final class Slot {
        private final String kind;
        private final Installer installer;
        private final java.util.function.Consumer<String> log;
        private boolean active;
        private Exception failure;

        Slot(String kind, Installer installer) {
            this(kind, installer, XposedBridge::log);
        }

        Slot(String kind, Installer installer, java.util.function.Consumer<String> log) {
            this.kind = kind;
            this.installer = installer;
            this.log = log;
        }

        synchronized void require(ClassLoader classLoader) throws Exception {
            if (active) return;
            if (failure == null) {
                try {
                    installer.install(classLoader);
                    active = true;
                    return;
                } catch (Exception e) {
                    failure = e;
                    log.accept("[WAEX] AB " + kind + " overrides unavailable: " + e.getMessage());
                }
            }
            throw new IllegalStateException("AB " + kind + " override hook unavailable", failure);
        }

        synchronized boolean active() {
            return active;
        }
    }

    static Slot booleanSlot = new Slot("boolean", HostProps::installBooleanGetter);
    static Slot integerSlot = new Slot("integer", HostProps::installIntegerGetter);

    private HostProps() {}

    /** Throws unless boolean overrides are applied by the host getter hook. */
    public static void requireBooleanOverrides(ClassLoader classLoader) throws Exception {
        booleanSlot.require(classLoader);
    }

    /** Throws unless integer overrides are applied by the host getter hook. */
    public static void requireIntegerOverrides(ClassLoader classLoader) throws Exception {
        integerSlot.require(classLoader);
    }

    /**
     * Whether a boolean override applies to this read. Key 4023 is left unmodified for callers in
     * the data-usage screen; when that screen's class cannot be resolved, the caller cannot be
     * told apart, so the stock value is kept for 4023 rather than overriding it everywhere.
     */
    static boolean overrideApplies(int key, Class<?> dataUsageActivity, boolean calledFromDataUsage) {
        if (key != DATA_USAGE_EXEMPT) return true;
        return dataUsageActivity != null && !calledFromDataUsage;
    }

    private static void installBooleanGetter(ClassLoader classLoader) throws Exception {
        Method getter = Unobfuscator.loadPropsBooleanMethod(classLoader);
        int keyIndex = requireKeyIndex(getter);
        Class<?> dataUsage = dataUsageActivity(classLoader);
        XposedBridge.hookMethod(getter, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Integer key = HostArgCompat.integerAt(param.args, keyIndex);
                if (key == null) return;
                Boolean value = BOOLEAN.get(key);
                if (value == null) return;
                boolean fromDataUsage = key == DATA_USAGE_EXEMPT && dataUsage != null
                        && ReflectionUtils.isCalledFromClass(dataUsage);
                if (overrideApplies(key, dataUsage, fromDataUsage)) param.setResult(value);
            }
        });
    }

    private static void installIntegerGetter(ClassLoader classLoader) throws Exception {
        Method getter = Unobfuscator.loadPropsIntegerMethod(classLoader);
        int keyIndex = requireKeyIndex(getter);
        XposedBridge.hookMethod(getter, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Integer key = HostArgCompat.integerAt(param.args, keyIndex);
                if (key == null) return;
                Integer value = INTEGER.get(key);
                if (value != null) param.setResult(value);
            }
        });
    }

    private static int requireKeyIndex(Method getter) throws NoSuchMethodException {
        int index = HostArgCompat.findPreferredIntParameterIndex(getter.getParameterTypes());
        if (index < 0) {
            throw new NoSuchMethodException("ambiguous property key in " + Unobfuscator.getMethodDescriptor(getter));
        }
        return index;
    }

    private static Class<?> dataUsageActivity(ClassLoader classLoader) {
        try {
            return WppCore.getDataUsageActivityClass(classLoader);
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] data-usage screen not found; key " + DATA_USAGE_EXEMPT + " keeps its stock value");
            return null;
        }
    }
}
