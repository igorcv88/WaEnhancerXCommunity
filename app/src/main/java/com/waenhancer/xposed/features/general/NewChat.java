package com.waenhancer.xposed.features.general;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.WppCore;
import com.waenhancer.xposed.core.components.AlertDialogWpp;
import com.waenhancer.R;
import com.waenhancer.xposed.utils.Utils;

import de.robv.android.xposed.XC_MethodHook;
import android.content.SharedPreferences;
import de.robv.android.xposed.XposedHelpers;

public class NewChat extends Feature {
    public NewChat(@NonNull ClassLoader loader, @NonNull SharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() {
        if (!prefs.getBoolean("newchat", true)) return;

        XposedHelpers.findAndHookMethod(
            WppCore.getHomeActivityClass(classLoader),
            "onResume",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    final Activity activity = (Activity) param.thisObject;
                    int fabId = activity.getResources().getIdentifier("fab", "id", activity.getPackageName());
                    if (fabId != 0) {
                        final View fab = activity.findViewById(fabId);
                        if (fab != null) {
                            fab.setOnLongClickListener(new View.OnLongClickListener() {
                                @Override
                                public boolean onLongClick(View v) {
                                    triggerNewChat(activity);
                                    return true;
                                }
                            });
                        }
                    }
                }
            }
        );
    }

    public static void triggerNewChat(Activity activity) {
        String title = "New Chat";
        try {
            title = com.waenhancer.xposed.core.FeatureLoader.getModuleString(activity, R.string.new_chat, "New Chat");
        } catch (Throwable ignored) {}

        final String finalTitle = title;
        var view = new LinearLayout(activity);
        view.setGravity(Gravity.CENTER);
        view.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        var edt = new EditText(view.getContext());
        edt.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f));
        edt.setMaxLines(1);
        edt.setInputType(InputType.TYPE_CLASS_PHONE);
        edt.setTransformationMethod(null);
        edt.setHint(com.waenhancer.xposed.core.FeatureLoader.getModuleString(activity, R.string.number_with_country_code, "Number with country code"));
        view.addView(edt);

        new AlertDialogWpp(activity)
            .setTitle(finalTitle)
            .setView(view)
            .setPositiveButton(com.waenhancer.xposed.core.FeatureLoader.getModuleString(activity, R.string.message, "Message"), (dialog, which) -> {
                var number = edt.getText().toString();
                var numberFormatted = number.replaceAll("[+\\-()/\\s]", "");
                var intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse("https://wa.me/" + numberFormatted));
                intent.setPackage(Utils.getApplication().getPackageName());
                activity.startActivity(intent);
            })
            .setNegativeButton(com.waenhancer.xposed.core.FeatureLoader.getModuleString(activity, R.string.cancel, "Cancel"), null)
            .show();
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "New Chat";
    }
}
