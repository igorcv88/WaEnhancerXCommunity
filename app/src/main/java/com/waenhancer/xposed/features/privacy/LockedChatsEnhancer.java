package com.waenhancer.xposed.features.privacy;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.components.FMessageWpp;
import com.waenhancer.xposed.core.components.WaContactWpp;
import com.waenhancer.xposed.core.devkit.Unobfuscator;
import com.waenhancer.xposed.utils.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import de.robv.android.xposed.XC_MethodHook;
import android.content.SharedPreferences;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class LockedChatsEnhancer extends Feature {

    private Object chatCache;

    public LockedChatsEnhancer(@NonNull ClassLoader classLoader, @NonNull SharedPreferences preferences) {
        super(classLoader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        if (!prefs.getBoolean("lockedchats_enhancer", false)) return;

        Method jidNotifications = Unobfuscator.loadNotificationMethod(classLoader);
        Method lockedChatsMethod = Unobfuscator.loadLockedChatsMethod(classLoader);

        XposedBridge.hookMethod(jidNotifications, new XC_MethodHook() {
            private Unhook unhook;

            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                this.unhook = XposedBridge.hookMethod(lockedChatsMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(new ArrayList<>());
                    }
                });
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                unhook.unhook();
            }
        });

        var chatCacheClass = Unobfuscator.loadChatCacheClass(classLoader);
        var lockedChatsFields = ReflectionUtils.findAllFieldsUsingFilter(chatCacheClass, f -> f.getType() == HashSet.class);

        XposedBridge.hookAllConstructors(chatCacheClass, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                chatCache = param.thisObject;
            }
        });

        var loadedContacts = Unobfuscator.loadLoadedContactsMethod(classLoader);

        XposedBridge.hookMethod(loadedContacts, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (param.args.length == 0 || param.args[0] == null || chatCache == null
                        || lockedChatsFields.length < 2) return;
                Object contacts = param.args[0];
                // Keep the known field when present; only accept an unambiguous typed fallback.
                java.lang.reflect.Field listField = null;
                try {
                    listField = contacts.getClass().getDeclaredField("A01");
                    if (!List.class.isAssignableFrom(listField.getType())) listField = null;
                } catch (NoSuchFieldException ignored) {}
                if (listField == null) {
                    for (var field : contacts.getClass().getDeclaredFields()) {
                        if (!List.class.isAssignableFrom(field.getType())) continue;
                        if (listField != null) return;
                        listField = field;
                    }
                }
                if (listField == null) return;
                listField.setAccessible(true);
                Object rawList = listField.get(contacts);
                if (!(rawList instanceof List<?>)) return;
                var list = new ArrayList<>((List<?>) rawList);
                lockedChatsFields[1].setAccessible(true);
                HashSet<?> lockedChats = (HashSet<?>) lockedChatsFields[1].get(chatCache);
                if (lockedChats == null) return;
                var lockedNumbers = lockedChats.stream().map(userjid -> new FMessageWpp.UserJid(userjid).getPhoneNumber()).collect(Collectors.toList());
                list.removeIf(item -> {
                    if (!WaContactWpp.TYPE.isInstance(item)) return false;
                    var waContact = new WaContactWpp(item);
                    var phoneNumber = waContact.getUserJid().getPhoneNumber();
                    return lockedNumbers.contains(phoneNumber);
                });
                listField.set(contacts, list);
            }
        });
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Locked Chats Enhancer";
    }
}
