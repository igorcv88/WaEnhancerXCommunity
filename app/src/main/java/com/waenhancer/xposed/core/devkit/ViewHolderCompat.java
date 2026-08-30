package com.waenhancer.xposed.core.devkit;

import android.content.Context;

import com.waenhancer.xposed.utils.ReflectionUtils;
import com.waenhancer.xposed.utils.Utils;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Compatibility resolver for the conversation-list row holder.
 *
 * WhatsApp 2.26.33 moved the resource-bearing member matched by the old resolver
 * into a constructor. DexKit therefore returns a MethodData representing <init>.
 * Calling MethodData#getMethodInstance() on that result throws "not a method".
 * This resolver only needs the declaring class, so it deliberately accepts both
 * normal methods and constructors and resolves the class directly from MethodData.
 */
public final class ViewHolderCompat {

    private static volatile Class<?> cachedViewHolderClass;
    private static volatile Field cachedContainerField;
    private static final Object LOCK = new Object();

    private ViewHolderCompat() { }

    public static Class<?> loadViewHolder(ClassLoader loader) throws Exception {
        Class<?> existing = cachedViewHolderClass;
        if (existing != null) return existing;

        synchronized (LOCK) {
            existing = cachedViewHolderClass;
            if (existing != null) return existing;

            List<Integer> ids = new ArrayList<>();
            for (String name : new String[]{
                    "conversations_row_header_stub",
                    "pin_indicator",
                    "mute_indicator",
                    "contact_photo"
            }) {
                int id = Utils.getID(name, "id");
                if (id > 0) ids.add(id);
            }

            if (ids.isEmpty()) {
                throw new ClassNotFoundException("Conversation view holder resource anchors not found");
            }

            MethodMatcher matcher = MethodMatcher.create().usingNumbers(ids);
            List<MethodData> candidates = Unobfuscator.getDexKit()
                    .findMethod(FindMethod.create().matcher(matcher));

            MethodData fallback = null;
            for (MethodData candidate : candidates) {
                List<?> params = candidate.getParamTypes();
                if (params == null || params.isEmpty()) continue;

                String firstParam = candidate.getParamTypeNames().get(0);
                if (!Context.class.getName().equals(firstParam)) continue;

                // Prefer the shape used by current conversation-list holders:
                // Context + View + dependencies. Do not require it, because older
                // builds can expose the same resource anchors from a normal method.
                if (candidate.getParamTypeNames().size() > 1
                        && "android.view.View".equals(candidate.getParamTypeNames().get(1))) {
                    cachedViewHolderClass = candidate.getDeclaredClass().getInstance(loader);
                    return cachedViewHolderClass;
                }

                if (fallback == null) fallback = candidate;
            }

            if (fallback != null) {
                cachedViewHolderClass = fallback.getDeclaredClass().getInstance(loader);
                return cachedViewHolderClass;
            }

            throw new ClassNotFoundException("Conversation view holder not found");
        }
    }

    public static Field loadContainerField(ClassLoader loader) throws Exception {
        Field existing = cachedContainerField;
        if (existing != null) return existing;

        synchronized (LOCK) {
            existing = cachedContainerField;
            if (existing != null) return existing;

            Class<?> viewHolder = loadViewHolder(loader);
            Class<?> owner = Unobfuscator.loadOnChangeStatus(loader).getDeclaringClass().getSuperclass();

            for (Class<?> current = owner; current != null && current != Object.class;
                 current = current.getSuperclass()) {
                Field field = ReflectionUtils.getFieldByType(current, viewHolder);
                if (field == null) {
                    field = ReflectionUtils.getFieldByExtendType(current, viewHolder);
                }
                if (field != null) {
                    field.setAccessible(true);
                    cachedContainerField = field;
                    return field;
                }
            }

            throw new NoSuchFieldException("Conversation view holder field not found in " + owner.getName());
        }
    }
}
