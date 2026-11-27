package com.waenhancer.xposed.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Hooks a host method where the host really declares it.
 *
 * <p>{@code XposedHelpers.findAndHookMethod} and {@code hookAllMethods} only see methods declared
 * by the class itself. WhatsApp moves Activity callbacks between a class and its new abstract
 * parents: 2.26.38.74 declares {@code onCreateOptionsMenu}/{@code onPrepareOptionsMenu} in
 * {@code HomeActivity}; in 2.26.46.82 {@code HomeActivity} declares only {@code onCreate} and
 * {@code onResume}, and the rest are in {@code HomeActivityKt}. Here the nearest declaration in the
 * hierarchy is hooked once, and when it belongs to a parent the callback runs only for instances
 * of the requested class, so other activities sharing that parent stay untouched.</p>
 */
public final class HostHooks {
    private HostHooks() {}

    /** Nearest non-abstract declaration of {@code name(params)} in {@code scope} or a superclass. */
    public static Method nearestDeclared(Class<?> scope, String name, Class<?>... params)
            throws NoSuchMethodException {
        for (Class<?> current = scope; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name, params);
                if (Modifier.isAbstract(method.getModifiers())) continue;
                return method;
            } catch (NoSuchMethodException ignored) {
                // Declared further up.
            }
        }
        throw new NoSuchMethodException(scope.getName() + "#" + name + Arrays.toString(params)
                + " is not declared in its hierarchy");
    }

    /** Argument order of {@code findAndHookMethod}, for a no-argument method. */
    public static XC_MethodHook.Unhook hookNearest(Class<?> scope, String name, XC_MethodHook callback) {
        return hook(scope, name, new Class<?>[0], callback);
    }

    /** Argument order of {@code findAndHookMethod}, for a one-argument method. */
    public static XC_MethodHook.Unhook hookNearest(Class<?> scope, String name, Class<?> param,
            XC_MethodHook callback) {
        return hook(scope, name, new Class<?>[]{param}, callback);
    }

    /** Like {@code findAndHookMethod}: an unchecked {@link NoSuchMethodError} when not declared. */
    public static XC_MethodHook.Unhook hook(Class<?> scope, String name, Class<?>[] params,
            XC_MethodHook callback) {
        Method target;
        try {
            target = nearestDeclared(scope, name, params);
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(e.getMessage());
        }
        return XposedBridge.hookMethod(target, scoped(scope, target, callback));
    }

    /** For callers that resolve the method themselves: limits {@code callback} to {@code scope}. */
    public static XC_MethodHook scopedTo(Class<?> scope, Method target, XC_MethodHook callback) {
        return scoped(scope, target, callback);
    }

    /** {@code callback} itself when declared by {@code scope}, otherwise limited to its instances. */
    static XC_MethodHook scoped(Class<?> scope, Method target, XC_MethodHook callback) {
        if (!needsScope(scope, target)) return callback;
        return new Scoped(scope, callback);
    }

    /** An inherited declaration also runs for sibling classes, so its callback must be scoped. */
    static boolean needsScope(Class<?> scope, Method target) {
        return target.getDeclaringClass() != scope;
    }

    static boolean appliesTo(Class<?> scope, Object thisObject) {
        return scope.isInstance(thisObject);
    }

    /** Forwards to the wrapped callback for instances of {@code scope} only. */
    static final class Scoped extends XC_MethodHook {
        private static final Method BEFORE = callbackMethod("beforeHookedMethod");
        private static final Method AFTER = callbackMethod("afterHookedMethod");
        final Class<?> scope;
        final XC_MethodHook delegate;

        Scoped(Class<?> scope, XC_MethodHook delegate) {
            this.scope = scope;
            this.delegate = delegate;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            if (appliesTo(scope, param.thisObject)) forward(BEFORE, param);
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            if (appliesTo(scope, param.thisObject)) forward(AFTER, param);
        }

        private void forward(Method callback, MethodHookParam param) throws Throwable {
            try {
                callback.invoke(delegate, param);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private static Method callbackMethod(String name) {
            try {
                Method method = XC_MethodHook.class.getDeclaredMethod(name, XC_MethodHook.MethodHookParam.class);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
