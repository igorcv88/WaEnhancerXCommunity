package com.waenhancer.xposed.features.listeners;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.HeaderViewListAdapter;
import android.widget.ListAdapter;
import android.widget.ListView;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.WppCore;
import com.waenhancer.xposed.core.components.FMessageWpp;

import java.lang.reflect.Field;
import java.util.HashSet;

import de.robv.android.xposed.XC_MethodHook;
import android.content.SharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ConversationItemListener extends Feature {

    public static HashSet<OnConversationItemListener> conversationListeners = new HashSet<>();
    private static ListAdapter mAdapter;
    private static XC_MethodHook.Unhook hooked;

    public ConversationItemListener(@NonNull ClassLoader loader, @NonNull SharedPreferences preferences) {
        super(loader, preferences);
    }

    public static ListAdapter getAdapter() {
        return mAdapter;
    }

    /**
     * Port of upstream's adapter unwrapping. Recent WhatsApp builds can insert an adapter
     * wrapper between the ListView and the real BaseAdapter, so assuming only
     * HeaderViewListAdapter is no longer sufficient.
     */
    public static BaseAdapter unwrapBaseAdapter(ListAdapter adapter) {
        Object current = adapter;
        if (current == null) return null;
        if (current instanceof HeaderViewListAdapter) {
            current = ((HeaderViewListAdapter) current).getWrappedAdapter();
        }
        if (current instanceof BaseAdapter) {
            return (BaseAdapter) current;
        }

        for (Field field : current.getClass().getDeclaredFields()) {
            if (!BaseAdapter.class.isAssignableFrom(field.getType())) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(current);
                if (value instanceof BaseAdapter) return (BaseAdapter) value;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public static void notifyDataSetChanged() {
        new Handler(Looper.getMainLooper()).post(() -> {
            BaseAdapter adapter = unwrapBaseAdapter(mAdapter);
            if (adapter != null) adapter.notifyDataSetChanged();
        });
    }

    @Override
    public void doHook() throws Throwable {
        XposedHelpers.findAndHookMethod(ListView.class, "setAdapter", ListAdapter.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (conversationListeners.isEmpty()) return;

                var currentActivity = WppCore.getCurrentActivity();
                if (currentActivity == null
                        || !currentActivity.getClass().getSimpleName().equals("Conversation")) {
                    return;
                }
                if (!(param.thisObject instanceof ListView)
                        || ((ListView) param.thisObject).getId() != android.R.id.list) {
                    return;
                }
                if (param.args == null || param.args.length == 0
                        || !(param.args[0] instanceof ListAdapter)) {
                    return;
                }

                ListAdapter adapter = (ListAdapter) param.args[0];
                if (adapter instanceof HeaderViewListAdapter) {
                    adapter = ((HeaderViewListAdapter) adapter).getWrappedAdapter();
                }
                if (adapter == null) return;

                mAdapter = adapter;
                if (hooked != null) hooked.unhook();

                var method = mAdapter.getClass().getDeclaredMethod(
                        "getView", int.class, View.class, ViewGroup.class);
                hooked = XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (conversationListeners.isEmpty() || param.thisObject != mAdapter) return;
                        if (param.args == null || param.args.length == 0
                                || !(param.args[0] instanceof Number)) {
                            return;
                        }

                        int position = ((Number) param.args[0]).intValue();
                        if (position < 0 || position >= mAdapter.getCount()) return;

                        Object result = param.getResult();
                        if (!(result instanceof ViewGroup)) return;
                        ViewGroup viewGroup = (ViewGroup) result;

                        Object fMessageObj = mAdapter.getItem(position);
                        if (fMessageObj == null || FMessageWpp.TYPE == null
                                || !FMessageWpp.TYPE.isInstance(fMessageObj)) {
                            return;
                        }

                        var fMessage = new FMessageWpp(fMessageObj);
                        for (OnConversationItemListener listener : conversationListeners) {
                            try {
                                listener.onItemBind(fMessage, viewGroup);
                            } catch (Throwable t) {
                                XposedBridge.log("[WAEX] Conversation item listener failed open: " + t);
                            }
                        }
                        XposedHelpers.setAdditionalInstanceField(viewGroup, "fMessage", fMessage);
                    }
                });
            }
        });
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Conversation Item Listener";
    }

    public abstract static class OnConversationItemListener {
        /**
         * Called when a message item is rendered in the conversation.
         */
        public abstract void onItemBind(FMessageWpp fMessage, ViewGroup viewGroup);
    }
}
