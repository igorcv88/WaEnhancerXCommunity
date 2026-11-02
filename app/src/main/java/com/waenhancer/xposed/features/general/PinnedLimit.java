package com.waenhancer.xposed.features.general;

import android.annotation.SuppressLint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.devkit.Unobfuscator;
import com.waenhancer.xposed.utils.ReflectionUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import android.content.SharedPreferences;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import lombok.Setter;

public class PinnedLimit extends Feature {

    public PinnedLimit(@NonNull ClassLoader loader, @NonNull SharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    @SuppressLint("DiscouragedApi")
    public void doHook() throws Throwable {
        var pinnedHashSetMethod = Unobfuscator.loadPinnedHashSetMethod(classLoader);
        var pinnedInChatMethod = Unobfuscator.loadPinnedInChatMethod(classLoader);


        // increase pinned limit in chat to 60
        XposedBridge.hookMethod(pinnedInChatMethod, XC_MethodReplacement.returnConstant(60));

        // With the chat-pin option off, the legacy path below only re-imposed WhatsApp's own limit
        // of 3 while hooking every ArrayList(int)/LinkedHashSet(int) construction in the process.
        if (!prefs.getBoolean("pinnedlimit", false)) return;

        // Disable pinned called by Server to prevent it from clearing the pinned list
        var setPinnedLimitMethod = Unobfuscator.loadSetPinnedLimitMethod(classLoader);
        XposedBridge.hookMethod(setPinnedLimitMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (ReflectionUtils.isCalledFromString("SyncResponseHandler"))
                    param.setResult(null);
            }
        });

        if (hookInlinedPinnedLimit(pinnedHashSetMethod)) return;
        XposedBridge.log("[WAE] PinnedLimit: inline-safe anchors unavailable; using legacy size-faking path");

        // Fix bug in initialCapacity of LinkedHashSet
        XposedHelpers.findAndHookConstructor(LinkedHashSet.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if ((int) param.args[0] < 0) {
                    param.args[0] = Math.abs((int) param.args[0]);
                }
            }
        });

        // Fix bug in initialCapacity of ArrayList
        XposedHelpers.findAndHookConstructor(ArrayList.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if ((int) param.args[0] < 0) {
                    param.args[0] = Math.abs((int) param.args[0]);
                }
            }
        });

        // This creates a modified linkedhashMap to return 0 if the fixed list is less than 60.
        XposedBridge.hookMethod(pinnedHashSetMethod, new XC_MethodHook() {

            @Override
            @SuppressWarnings("unchecked")
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                var map = (Map) param.getResult();
                var thisObject = Modifier.isStatic(param.method.getModifiers()) ? param.args[0] : param.thisObject;

                PinnedLinkedHashMap<Object> pinnedMod;
                if (!(map instanceof PinnedLinkedHashMap)) {
                    pinnedMod = new PinnedLinkedHashMap<>();
                    pinnedMod.putAll(map);
                    param.setResult(pinnedMod);
                } else {
                    pinnedMod = (PinnedLinkedHashMap<Object>) map;
                }
                pinnedMod.setLimit(prefs.getBoolean("pinnedlimit", false) ? 60 : 3);
                var keySet = map.keySet();
                var sets = ReflectionUtils.getFieldsByType(thisObject.getClass(), Set.class);
                for (var setField : sets) {
                    var set = (Set) setField.get(thisObject);
                    if (Objects.equals(set, keySet)) {
                        var newKeySet = pinnedMod.keySet();
                        newKeySet.setDisableInterator(false);
                        setField.set(thisObject, newKeySet);
                    }
                }
            }
        });

        var method = Unobfuscator.loadPinnedFilterMethod(classLoader);
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!(param.args[0] instanceof PinnedLinkedHashMap.PinnedKeySet<?>)) {
                    return;
                }
                var set = (Set) param.getResult();
                PinnedLinkedHashMap<Object> pinnedMod;
                pinnedMod = new PinnedLinkedHashMap<>();
                pinnedMod.setLimit(prefs.getBoolean("pinnedlimit", false) ? 60 : 3);
                for (Object item : set) {
                    pinnedMod.put(item, item);
                }
                PinnedLinkedHashMap.PinnedKeySet<Object> newKeySet = pinnedMod.keySet();
                newKeySet.setDisableInterator(false);
                param.setResult(pinnedMod.keySet());
            }
        });

    }

    private static final int PIN_BATCH_SIZE = 3;

    /**
     * Dev4Mod 790a7e2 (GPL-3.0): newer builds inline ConversationPinHelper's limit (3, or 20 for
     * premium) into pinSelectedJids, trimExcessPinnedChats and PinChatHandler, so faking the size
     * of the pinned set no longer affects those checks. While pinSelectedJids runs, the pinned set
     * only reports pinned chats inside the selection and selections above three are pinned in
     * batches; while trimExcessPinnedChats runs it reports nothing to trim; unpins issued while
     * PinChatHandler applies synced pins are ignored.
     *
     * @return false when the required anchors are absent, so the caller keeps the legacy path.
     */
    private boolean hookInlinedPinnedLimit(java.lang.reflect.Method pinnedHashSetMethod) {
        java.lang.reflect.Method pinSelectedMethod;
        java.lang.reflect.Method trimExcessMethod;
        try {
            pinSelectedMethod = Unobfuscator.loadPinSelectedChatsMethod(classLoader);
            trimExcessMethod = Unobfuscator.loadTrimExcessPinnedChatsMethod(classLoader);
        } catch (Throwable t) {
            return false;
        }
        var pinSelection = new ThreadLocal<Set<Object>>();
        var trimming = new ThreadLocal<Boolean>();

        try {
            XposedBridge.hookMethod(Unobfuscator.loadPinnedChatsPremiumUpsellMethod(classLoader),
                    XC_MethodReplacement.returnConstant(false));
        } catch (Throwable t) {
            XposedBridge.log("[WAE] PinnedLimit: premium upsell hook unavailable (" + t.getClass().getSimpleName() + ")");
        }

        XposedBridge.hookMethod(pinnedHashSetMethod, new XC_MethodHook() {
            @Override
            @SuppressWarnings("unchecked")
            protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.getResult() instanceof Map)) return;
                var map = (Map<Object, Object>) param.getResult();
                if (Boolean.TRUE.equals(trimming.get())) {
                    param.setResult(new LinkedHashMap<>());
                    return;
                }
                var selection = pinSelection.get();
                if (selection == null) return;
                var filtered = new LinkedHashMap<Object, Object>();
                for (var entry : map.entrySet()) {
                    if (selection.contains(entry.getKey())) filtered.put(entry.getKey(), entry.getValue());
                }
                param.setResult(filtered);
            }
        });

        XposedBridge.hookMethod(pinSelectedMethod, new XC_MethodHook() {
            @Override
            @SuppressWarnings("unchecked")
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (param.thisObject == null) return;
                Field selectionField = findJidSetField(param.thisObject);
                if (selectionField == null) return;
                var selection = (Set<Object>) selectionField.get(param.thisObject);
                if (selection.size() <= PIN_BATCH_SIZE) {
                    pinSelection.set(new java.util.HashSet<>(selection));
                    return;
                }
                try {
                    var items = new ArrayList<>(selection);
                    for (int start = 0; start < items.size(); start += PIN_BATCH_SIZE) {
                        var batch = new LinkedHashSet<>(items.subList(start, Math.min(items.size(), start + PIN_BATCH_SIZE)));
                        selectionField.set(param.thisObject, batch);
                        pinSelection.set(batch);
                        XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                    }
                } finally {
                    selectionField.set(param.thisObject, selection);
                    pinSelection.remove();
                }
                param.setResult(null);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                pinSelection.remove();
            }
        });

        XposedBridge.hookMethod(trimExcessMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                trimming.set(Boolean.TRUE);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                trimming.remove();
            }
        });

        try {
            var pinChatHandlerMethod = Unobfuscator.loadPinChatHandlerMethod(classLoader);
            var applyingSync = new ThreadLocal<Boolean>();
            XposedBridge.hookMethod(pinChatHandlerMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    applyingSync.set(Boolean.TRUE);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    applyingSync.remove();
                }
            });
            XposedBridge.hookMethod(Unobfuscator.loadSetPinnedLimitMethod(classLoader), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(applyingSync.get()) && param.args.length > 2
                            && Boolean.FALSE.equals(param.args[2])) {
                        param.setResult(null);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[WAE] PinnedLimit: sync unpin guard unavailable (" + t.getClass().getSimpleName() + ")");
        }
        return true;
    }

    /** The pin helper's selection: a non-empty Set whose elements are all user JIDs. */
    private static Field findJidSetField(Object instance) {
        var jidType = com.waenhancer.xposed.core.components.FMessageWpp.UserJid.TYPE_JID;
        if (jidType == null) return null;
        for (Field field : instance.getClass().getDeclaredFields()) {
            if (!Set.class.isAssignableFrom(field.getType())) continue;
            try {
                field.setAccessible(true);
                var value = (Set<?>) field.get(instance);
                if (value == null || value.isEmpty()) continue;
                boolean allJids = true;
                for (Object item : value) {
                    if (!jidType.isInstance(item)) {
                        allJids = false;
                        break;
                    }
                }
                if (allJids) return field;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Pinned Limit";
    }


    @Setter
    private static class PinnedLinkedHashMap<T> extends LinkedHashMap<T, T> {

        @Override
        public int size() {
            if (super.size() >= limit) {
                return super.size();
            }
            return -this.limit;
        }

        private int limit;

        public void setLimit(int limit) {
            this.limit = limit;
        }

        @NonNull
        @Override
        public PinnedKeySet<T> keySet() {
            return new PinnedKeySet<>(this, super.keySet());
        }

        record PinnedKeySet<T>(PinnedLinkedHashMap<T> pinnedKeySet, Set<T> set) implements Set<T> {

            private static boolean disableInterator = true;

            @Override
            public int size() {
                return pinnedKeySet.size();
            }

            @Override
            public boolean isEmpty() {
                return set.isEmpty();
            }

            @Override
            public boolean contains(@Nullable Object o) {
                return set.contains(o);
            }

            @NonNull
            @Override
            public Iterator<T> iterator() {
                if (disableInterator) {
                    if (pinnedKeySet.size() < pinnedKeySet.limit) {
                        return new Iterator<T>() {
                            @Override
                            public boolean hasNext() {
                                return false;
                            }

                            @Override
                            public T next() {
                                return null;
                            }
                        };
                    }
                }
                return set.iterator();
            }

            @NonNull
            @Override
            public Object[] toArray() {
                return set.toArray();
            }

            @NonNull
            @Override
            public <T1> T1[] toArray(@NonNull T1[] a) {
                return set.toArray(a);
            }

            @Override
            public boolean add(@Nullable T t) {
                var hadKey = pinnedKeySet.containsKey(t);
                pinnedKeySet.put(t, t);
                return !hadKey;
            }

            @Override
            public boolean remove(@Nullable Object o) {
                var hadKey = pinnedKeySet.containsKey(o);
                if (hadKey) {
                    pinnedKeySet.remove(o);
                }
                return hadKey;
            }


            @Override
            public boolean containsAll(@NonNull Collection<?> c) {
                return set.containsAll(c);
            }

            @Override
            public boolean addAll(@NonNull Collection<? extends T> c) {
                var changed = false;
                for (T item : c) {
                    changed |= add(item);
                }
                return changed;
            }

            @Override
            public boolean retainAll(@NonNull Collection<?> c) {
                return set.retainAll(c);
            }

            @Override
            public boolean removeAll(@NonNull Collection<?> c) {
                return set.removeAll(c);
            }


            @Override
            public void clear() {
                set.clear();
            }

            @NonNull
            @Override
            public Spliterator<T> spliterator() {
                return set.spliterator();
            }

            public void setDisableInterator(boolean b) {
                this.disableInterator = b;
            }
        }
    }

}
