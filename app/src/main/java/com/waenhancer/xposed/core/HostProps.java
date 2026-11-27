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
 * <p>Previously the getter hook was installed only by {@code Others}, after unrelated resolvers,
 * so a feature such as Hide Seen could put its override into a map that nothing read (when
 * {@code Others} failed earlier) and the failure stayed silent. Each feature that relies on an
 * override now calls {@link #ensureInstalled} itself: the hook is installed exactly once, and a
 * failure is reported by every dependent feature instead of disappearing.</p>
 */
public final class HostProps {
    public static final ConcurrentHashMap<Integer, Boolean> BOOLEAN = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<Integer, Integer> INTEGER = new ConcurrentHashMap<>();
    /** Property keys that a caller in the data-usage screen must read unmodified. */
    private static final int DATA_USAGE_EXEMPT = 4023;

    private static final Object LOCK = new Object();
    private static boolean installed;
    private static Exception failure;

    private HostProps() {}

    public static void ensureInstalled(ClassLoader classLoader) throws Exception {
        synchronized (LOCK) {
            if (installed) return;
            if (failure != null) throw failure;
            try {
                install(classLoader);
                installed = true;
            } catch (Exception e) {
                failure = e;
                throw e;
            }
        }
    }

    /** Throws unless boolean overrides are actually applied by the host getter hook. */
    public static void requireBooleanOverrides(ClassLoader classLoader) throws Exception {
        ensureInstalled(classLoader);
        if (!booleanActive) throw new IllegalStateException("AB boolean override hook unavailable");
    }

    private static volatile boolean booleanActive;

    private static void install(ClassLoader classLoader) throws Exception {
        Method booleanGetter = Unobfuscator.loadPropsBooleanMethod(classLoader);
        int booleanKey = HostArgCompat.findPreferredIntParameterIndex(booleanGetter.getParameterTypes());
        // The integer getter is independent: its failure must not remove boolean overrides.
        Method integerGetter = null;
        try {
            integerGetter = Unobfuscator.loadPropsIntegerMethod(classLoader);
        } catch (Exception e) {
            XposedBridge.log("[WAEX] AB integer overrides unavailable: " + e.getMessage());
        }
        int integerKey = integerGetter == null ? -1
                : HostArgCompat.findPreferredIntParameterIndex(integerGetter.getParameterTypes());
        Class<?> dataUsageActivity = WppCore.getDataUsageActivityClass(classLoader);

        HookTransaction tx = new HookTransaction();
        try {
            // As before, an ambiguous key parameter skips only that getter (and is logged).
            if (booleanKey < 0) {
                XposedBridge.log("[WAEX] AB boolean overrides skipped: ambiguous key in "
                        + Unobfuscator.getMethodDescriptor(booleanGetter));
            } else {
                tx.hook(booleanGetter, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Integer key = HostArgCompat.integerAt(param.args, booleanKey);
                        if (key == null) return;
                        Boolean value = BOOLEAN.get(key);
                        if (value == null) return;
                        if (key == DATA_USAGE_EXEMPT && ReflectionUtils.isCalledFromClass(dataUsageActivity)) return;
                        param.setResult(value);
                    }
                });
            }
            if (integerGetter == null) {
                // Already logged above.
            } else if (integerKey < 0) {
                XposedBridge.log("[WAEX] AB integer overrides skipped: ambiguous key in "
                        + Unobfuscator.getMethodDescriptor(integerGetter));
            } else {
                tx.hook(integerGetter, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Integer key = HostArgCompat.integerAt(param.args, integerKey);
                        if (key == null) return;
                        Integer value = INTEGER.get(key);
                        if (value != null) param.setResult(value);
                    }
                });
            }
            tx.commit();
            booleanActive = booleanKey >= 0;
        } catch (Throwable t) {
            tx.rollback();
            throw t instanceof Exception ? (Exception) t : new IllegalStateException(t);
        }
    }
}
