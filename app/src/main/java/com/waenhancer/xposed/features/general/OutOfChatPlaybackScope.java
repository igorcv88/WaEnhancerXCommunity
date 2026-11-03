package com.waenhancer.xposed.features.general;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;

import com.waenhancer.theme.GlassProbe;
import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.WppCore;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Keeps WhatsApp's out-of-chat audio strip on its chat list, not inside an open conversation.
 * This changes only the view's visibility; playback and in-message audio controls stay native.
 */
public final class OutOfChatPlaybackScope extends Feature {
    private static final String CONVERSATION = "com.whatsapp.Conversation";
    private static final String HOLDER = "out_of_chat_playback_holder";
    private static final String PARENT = "conversation_root_layout";
    private static final String CONTENT = "out_of_chat_layout";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Activity, Watcher> watchers = new IdentityHashMap<>();

    public OutOfChatPlaybackScope(@NonNull ClassLoader loader,
                                  @NonNull SharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public String getPluginName() {
        return "Out-of-chat player scope";
    }

    @Override
    public void doHook() {
        WppCore.addListenerActivity((activity, state) -> {
            if (!CONVERSATION.equals(activity.getClass().getName())) return;
            switch (state) {
                case RESUMED:
                    install(activity);
                    break;
                case PAUSED:
                case ENDED:
                case DESTROYED:
                    uninstall(activity);
                    break;
            }
        });

        // Hook loading can finish after the first Conversation has already resumed.
        main.post(() -> {
            Activity activity = WppCore.getCurrentActivity();
            if (activity != null && CONVERSATION.equals(activity.getClass().getName())
                    && WppCore.getActivityState(activity)
                    == WppCore.ActivityChangeState.ChangeType.RESUMED) install(activity);
        });
    }

    private void install(Activity activity) {
        if (watchers.containsKey(activity) || activity.isFinishing() || activity.isDestroyed()
                || activity.getWindow() == null) return;
        View decor = activity.getWindow().getDecorView();
        if (decor == null) return;
        int holderId = id(activity, HOLDER);
        int parentId = id(activity, PARENT);
        int contentId = id(activity, CONTENT);
        // Unknown resource layout: fail closed, without touching any other audio UI.
        if (holderId == 0 || parentId == 0 || contentId == 0) return;
        Watcher watcher = new Watcher(decor, holderId, parentId, contentId);
        watchers.put(activity, watcher);
        watcher.start();
    }

    private void uninstall(Activity activity) {
        Watcher watcher = watchers.remove(activity);
        if (watcher != null) watcher.stop();
    }

    private static int id(Activity activity, String entry) {
        return activity.getResources().getIdentifier(entry, "id", activity.getPackageName());
    }

    private static final class Watcher implements ViewTreeObserver.OnGlobalLayoutListener,
            ViewTreeObserver.OnPreDrawListener {
        private final View decor;
        private final int holderId;
        private final int parentId;
        private final int contentId;
        private ViewTreeObserver observer;

        Watcher(View decor, int holderId, int parentId, int contentId) {
            this.decor = decor;
            this.holderId = holderId;
            this.parentId = parentId;
            this.contentId = contentId;
        }

        void start() {
            observer = decor.getViewTreeObserver();
            observer.addOnGlobalLayoutListener(this);
            observer.addOnPreDrawListener(this);
            hide();
        }

        void stop() {
            if (observer != null && observer.isAlive()) {
                observer.removeOnGlobalLayoutListener(this);
                observer.removeOnPreDrawListener(this);
            }
            observer = null;
        }

        @Override
        public void onGlobalLayout() {
            hide();
        }

        /**
         * WhatsApp can re-show the strip between layouts (playback state change). Hiding it here
         * and cancelling that one frame means it is never drawn, nor its empty space shown.
         */
        @Override
        public boolean onPreDraw() {
            return !hide();
        }

        /** @return true when the strip was visible in this Conversation and is now gone */
        private boolean hide() {
            View holder = decor.findViewById(holderId);
            if (!(holder instanceof ViewGroup) || holder.getVisibility() == View.GONE) return false;
            ViewParent parent = holder.getParent();
            if (!(parent instanceof View) || ((View) parent).getId() != parentId) return false;
            View content = holder.findViewById(contentId);
            if (content == null || content.getParent() != holder) return false;
            holder.setVisibility(View.GONE);
            GlassProbe.event("PLAYER_SCOPE_HIDDEN", holder, "owner=Conversation holder=out_of_chat_playback_holder");
            return true;
        }
    }
}
