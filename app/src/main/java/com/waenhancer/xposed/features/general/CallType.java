package com.waenhancer.xposed.features.general;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;

import com.waenhancer.R;
import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.WppCore;
import com.waenhancer.xposed.core.components.AlertDialogWpp;
import com.waenhancer.xposed.core.components.FMessageWpp;
import com.waenhancer.xposed.core.components.SharedPreferencesWrapper;
import com.waenhancer.xposed.core.components.WaContactWpp;
import com.waenhancer.xposed.core.devkit.Unobfuscator;
import com.waenhancer.xposed.core.devkit.UnobfuscatorCache;
import com.waenhancer.xposed.utils.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.Objects;

import android.content.SharedPreferences;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class CallType extends Feature {

    public CallType(@NonNull ClassLoader loader, @NonNull SharedPreferences preferences) {
        super(loader, preferences);
    }

    @SuppressLint("ApplySharedPref")
    @Override
    public void doHook() throws Throwable {
        boolean callTypeEnabled = false;
        try {
            callTypeEnabled = prefs.getBoolean("calltype", false);
        } catch (ClassCastException e) {
            try {
                String strVal = prefs.getString("calltype", "false");
                callTypeEnabled = "true".equalsIgnoreCase(strVal) || "1".equals(strVal);
                prefs.edit().putBoolean("calltype", callTypeEnabled).apply();
            } catch (Exception ignored) {
            }
        }
        if (!callTypeEnabled) return;

        SharedPreferencesWrapper.addHook((key, value) -> {
            if (Objects.equals(key, "call_confirmation_dialog_count")) {
                return 1;
            }
            return value;
        });

        Method startCallMethod = null;
        try {
            startCallMethod = Unobfuscator.loadStartOutgoingCallMethod(classLoader);
        } catch (Throwable ignored) {
        }

        if (startCallMethod != null) {
            hookSemanticOutgoingCall(startCallMethod);
        } else {
            hookLegacyCallConfirmation();
        }
    }

    private void hookSemanticOutgoingCall(Method startCallMethod) {
        XposedBridge.hookMethod(startCallMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (param.args == null) return;

                Context callContext = ReflectionUtils.getArg(param.args, Context.class, 0);
                if (callContext == null) return;
                Activity activity = callContext instanceof Activity
                        ? (Activity) callContext
                        : WppCore.getCurrentActivity();
                if (activity == null || activity.isFinishing()) return;

                Object contactObj = findContactArgument(param.args);
                if (contactObj == null) return;

                Boolean isVideo = resolveVideoFlag(param.args);
                // If WhatsApp changes this signature and the call type cannot be identified
                // confidently, leave the original call path untouched rather than intercepting a
                // possible video call as voice.
                if (isVideo == null || isVideo) return;

                WaContactWpp waContact;
                try {
                    waContact = new WaContactWpp(contactObj);
                } catch (Throwable ignored) {
                    return;
                }

                FMessageWpp.UserJid userJid = waContact.getUserJid();
                if (userJid == null || userJid.isNull()) return;
                String phoneNumber = userJid.getPhoneNumber();
                if (phoneNumber == null || phoneNumber.isEmpty()) return;

                Object[] originalArgs = param.args.clone();
                Object originalThis = param.thisObject;
                Method originalMethod = (Method) param.method;
                param.setResult(ReflectionUtils.getDefaultValue(originalMethod.getReturnType()));

                AlertDialogWpp alertDialog = new AlertDialogWpp(activity);
                alertDialog.setTitle(UnobfuscatorCache.getInstance().getString("selectcalltype"));
                alertDialog.setItems(new String[]{
                        com.waenhancer.xposed.core.FeatureLoader.getModuleString(
                                com.waenhancer.xposed.utils.Utils.getApplication(),
                                R.string.phone_call),
                        com.waenhancer.xposed.core.FeatureLoader.getModuleString(
                                com.waenhancer.xposed.utils.Utils.getApplication(),
                                R.string.whatsapp_call)
                }, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) {
                        Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:+" + phoneNumber));
                        activity.startActivity(intent);
                    } else if (which == 1) {
                        try {
                            XposedBridge.invokeOriginalMethod(originalMethod, originalThis, originalArgs);
                        } catch (Throwable e) {
                            XposedBridge.log(e);
                        }
                    }
                });
                alertDialog.show();
            }
        });
    }

    private Object findContactArgument(Object[] args) {
        if (args == null || WaContactWpp.TYPE == null) return null;
        for (Object arg : args) {
            if (arg != null && WaContactWpp.TYPE.isInstance(arg)) {
                return arg;
            }
        }
        return null;
    }

    private Boolean resolveVideoFlag(Object[] args) {
        if (args == null) return null;

        // This is the known Dev4Mod/WA signature. Keep it as the preferred path but do not
        // assume it exists on future builds.
        if (args.length > 3 && args[3] instanceof Boolean) {
            return (Boolean) args[3];
        }

        Boolean singleBoolean = null;
        int booleanCount = 0;
        for (Object arg : args) {
            if (arg instanceof Boolean) {
                singleBoolean = (Boolean) arg;
                booleanCount++;
            }
        }
        return booleanCount == 1 ? singleBoolean : null;
    }

    private void hookLegacyCallConfirmation() throws Throwable {
        Class<?> callConfirmationFragment = XposedHelpers.findClass(
                "com.whatsapp.calling.fragment.CallConfirmationFragment", classLoader);
        Method method = ReflectionUtils.findMethodUsingFilter(
                callConfirmationFragment,
                m -> m.getParameterCount() == 1
                        && m.getParameterTypes()[0].equals(Bundle.class));

        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Bundle bundle = ReflectionUtils.getArg(param.args, Bundle.class, 0);
                if (bundle == null) return;
                param.setObjectExtra("waex_call_jid", bundle.getString("jid"));
                param.setObjectExtra("waex_is_video_call", bundle.getBoolean("is_video_call", false));
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                String jid = (String) param.getObjectExtra("waex_call_jid");
                Boolean isVideo = (Boolean) param.getObjectExtra("waex_is_video_call");
                if (jid == null || Boolean.TRUE.equals(isVideo)) return;
                if (!(param.getResult() instanceof Dialog)) return;

                Dialog originalDialog = (Dialog) param.getResult();
                Context context = originalDialog.getContext();
                FMessageWpp.UserJid userJid = new FMessageWpp.UserJid(jid);
                if (userJid.isNull()) return;
                String phoneNumber = userJid.getPhoneNumber();
                if (phoneNumber == null || phoneNumber.isEmpty()) return;

                AlertDialogWpp alertDialog = new AlertDialogWpp(context);
                alertDialog.setTitle(UnobfuscatorCache.getInstance().getString("selectcalltype"));
                final Dialog[] replacementDialog = new Dialog[1];
                alertDialog.setItems(new String[]{
                        com.waenhancer.xposed.core.FeatureLoader.getModuleString(
                                com.waenhancer.xposed.utils.Utils.getApplication(),
                                R.string.phone_call),
                        com.waenhancer.xposed.core.FeatureLoader.getModuleString(
                                com.waenhancer.xposed.utils.Utils.getApplication(),
                                R.string.whatsapp_call)
                }, (dialog, which) -> {
                    if (replacementDialog[0] != null) {
                        replacementDialog[0].dismiss();
                    }
                    if (which == 0) {
                        Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:+" + phoneNumber));
                        if (!(context instanceof Activity)) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        }
                        context.startActivity(intent);
                    } else if (which == 1) {
                        originalDialog.show();
                    }
                });
                replacementDialog[0] = alertDialog.create();
                param.setResult(replacementDialog[0]);
            }
        });
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Call Type";
    }
}
