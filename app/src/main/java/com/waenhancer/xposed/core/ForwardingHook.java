package com.waenhancer.xposed.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Forwards both callbacks to another hook while a condition holds; otherwise the hooked method
 * runs unchanged. The delegate's priority is kept.
 */
abstract class ForwardingHook extends XC_MethodHook {
    private static final Method BEFORE = callbackMethod("beforeHookedMethod");
    private static final Method AFTER = callbackMethod("afterHookedMethod");
    final XC_MethodHook delegate;

    ForwardingHook(XC_MethodHook delegate) {
        super(delegate.priority);
        this.delegate = delegate;
    }

    abstract boolean applies(MethodHookParam param);

    @Override
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
        if (applies(param)) forward(BEFORE, param);
    }

    @Override
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
        if (applies(param)) forward(AFTER, param);
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
