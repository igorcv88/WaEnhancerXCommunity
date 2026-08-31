package com.waenhancer.xposed.features.customization;

import android.app.Activity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.WppCore;
import com.waenhancer.xposed.core.db.MessageStore;
import com.waenhancer.xposed.utils.Utils;
import android.content.Intent;
import android.os.SystemClock;

import de.robv.android.xposed.XC_MethodHook;
import android.content.SharedPreferences;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ChatScrollButtons extends Feature {

    public ChatScrollButtons(@NonNull ClassLoader loader, @NonNull SharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        // Check if feature is enabled
        if (!prefs.getBoolean("go_to_first_message", true)) return;
        
        try {
            Class<?> conversationClass = XposedHelpers.findClass("com.whatsapp.Conversation", this.classLoader);
            
            // Hook onCreateOptionsMenu to add menu item
            XposedBridge.hookAllMethods(conversationClass, "onCreateOptionsMenu", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Menu menu = (Menu) param.args[0];
                    Activity activity = (Activity) param.thisObject;
                    
                    // Add "Go to First Message" menu item
                    MenuItem goToFirstItem = menu.add(0, 1001, 0, "Go to First Message");
                    goToFirstItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
                    goToFirstItem.setOnMenuItemClickListener(item -> {
                        jumpToFirstMessage(activity);
                        return true;
                    });
                }
            });
        } catch (Exception e) {
            XposedBridge.log("ChatScrollButtons hook failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void jumpToFirstMessage(@NonNull Activity activity) {
        var userJid = WppCore.getCurrentUserJid();
        if (userJid == null || userJid.isNull()) {
            return;
        }

        var rawJid = userJid.getPhoneRawString();
        if (rawJid == null || rawJid.isEmpty()) {
            rawJid = userJid.getUserRawString();
        }
        if (rawJid == null || rawJid.isEmpty()) {
            return;
        }

        var firstMessageInfo = MessageStore.getInstance().getFirstMessageInfoByChatRawJid(rawJid);
        if (firstMessageInfo == null) {
            return;
        }

        try {
            Intent intent = new Intent(activity, activity.getClass());
            intent.putExtra("jid", rawJid);
            intent.putExtra("sort_id", firstMessageInfo.sortId);
            intent.putExtra("row_id", firstMessageInfo.rowId);
            intent.putExtra("start_t", SystemClock.uptimeMillis());
            intent.putExtra("mat_entry_point", 64);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            activity.startActivity(intent);
            activity.overridePendingTransition(0, 0);
        } catch (Exception e) {
            XposedBridge.log(e);
        }
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Chat Scroll Buttons";
    }
}
