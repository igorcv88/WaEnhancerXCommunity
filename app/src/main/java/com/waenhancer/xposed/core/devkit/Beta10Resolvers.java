package com.waenhancer.xposed.core.devkit;

import android.os.Message;
import android.view.Menu;
import android.view.MenuItem;

import com.waenhancer.xposed.utils.Utils;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;
import org.luckypray.dexkit.util.DexSignUtil;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Compatibility resolvers reconstructed from the WaEnhancerX beta-10 behavior.
 *
 * Keep these isolated from the general resolver catalog until the strategies have
 * accumulated enough host-version evidence to be considered generic contracts.
 */
public final class Beta10Resolvers {

    private Beta10Resolvers() {}

    public static Method[] loadOnDispatchMessage(ClassLoader classLoader) throws Exception {
        DexKitBridge dexkit = requireDexKit();
        Set<Method> matches = new LinkedHashSet<>();

        collectAndroidMessageMethods(
                dexkit.findMethod(FindMethod.create().matcher(MethodMatcher.create().addUsingNumber(419))),
                classLoader,
                matches
        );
        collectAndroidMessageMethods(
                dexkit.findMethod(FindMethod.create().matcher(MethodMatcher.create().addUsingNumber(89))),
                classLoader,
                matches
        );
        collectAndroidMessageMethods(
                dexkit.findMethod(FindMethod.create().matcher(
                        MethodMatcher.create().addUsingString("ConnectionWriter/sendReadReceipts")
                )),
                classLoader,
                matches
        );

        if (matches.isEmpty()) {
            throw new Exception("onDispatchMessage methods not found");
        }
        return matches.toArray(new Method[0]);
    }

    public static Method loadViewOnceDownloadMenuMethod(ClassLoader classLoader) throws Exception {
        DexKitBridge dexkit = requireDexKit();

        try {
            int icViewOnceId = Utils.getID("ic_viewonce", "drawable");
            if (icViewOnceId > 0) {
                Method setShowAsAction = MenuItem.class.getMethod("setShowAsAction", int.class);
                MethodDataList candidates = dexkit.findMethod(
                        FindMethod.create().matcher(
                                MethodMatcher.create()
                                        .addUsingNumber(icViewOnceId)
                                        .addInvoke(DexSignUtil.getMethodDescriptor(setShowAsAction))
                        )
                );

                for (MethodData candidate : candidates) {
                    Method method = Unobfuscator.convertRealMethod(candidate, classLoader);
                    if (method != null && hasParameter(method, Menu.class)) {
                        return method;
                    }
                }
            }
        } catch (Throwable ignored) {
            // The legacy MediaViewFragment strategies below remain authoritative fallback.
        }

        return Unobfuscator.loadViewOnceDownloadMenuMethod(classLoader);
    }

    private static void collectAndroidMessageMethods(MethodDataList candidates,
                                                     ClassLoader classLoader,
                                                     Set<Method> destination) {
        for (MethodData candidate : candidates) {
            Method method = Unobfuscator.convertRealMethod(candidate, classLoader);
            if (method != null && hasParameter(method, Message.class)) {
                destination.add(method);
            }
        }
    }

    private static boolean hasParameter(Method method, Class<?> parameterType) {
        for (Class<?> type : method.getParameterTypes()) {
            if (type == parameterType) return true;
        }
        return false;
    }

    private static DexKitBridge requireDexKit() {
        DexKitBridge dexkit = Unobfuscator.getDexKit();
        if (dexkit == null) {
            throw new IllegalStateException("DexKit is not initialized");
        }
        return dexkit;
    }
}
