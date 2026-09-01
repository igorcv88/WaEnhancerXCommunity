package com.waenhancer.xposed.features.others;

import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.WppCore;
import com.waenhancer.xposed.core.components.FMessageWpp;
import com.waenhancer.xposed.core.components.WaContactWpp;
import com.waenhancer.xposed.core.db.MessageStore;
import com.waenhancer.xposed.core.devkit.Unobfuscator;
import com.waenhancer.xposed.features.general.Tasker;
import com.waenhancer.xposed.utils.Utils;

import org.luckypray.dexkit.query.enums.StringMatchType;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

public class ToastViewer extends Feature {

    private static final long MIN_INTERVAL = 1000;
    private static final long CLEANUP_INTERVAL_SECONDS = 30;
    private static final AtomicBoolean cleanupStarted = new AtomicBoolean(false);
    private static final Map<String, Long> lastEventTimeMap = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "WaEnhancer-ToastViewerCleanup");
        thread.setDaemon(true);
        return thread;
    });

    public ToastViewer(@NonNull ClassLoader classLoader, @NonNull SharedPreferences preferences) {
        super(classLoader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        var toastViewedStatus = prefs.getBoolean("toast_viewed_status", false);
        var toastViewedMessage = prefs.getBoolean("toast_viewed_message", false);

        // These hooks also emit independent Tasker viewed_message/viewed_status events, so they
        // must stay active even when both visual toast options are disabled.
        try {
            var onInsertReceipt = Unobfuscator.loadOnInsertReceipt(classLoader);
            XposedBridge.hookMethod(onInsertReceipt, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    processNewWA(param, toastViewedMessage, toastViewedStatus);
                }
            });
        } catch (Throwable e) {
            XposedBridge.log(e);
        }

        try {
            var onSeenReceiptForStatus = Unobfuscator.loadSeenReceiptForStatus(classLoader);
            XposedBridge.hookMethod(onSeenReceiptForStatus, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args == null || param.args.length < 3) return;

                    int receiptType = -1;
                    if (param.args[2] instanceof Integer) {
                        receiptType = (int) param.args[2];
                    } else if (param.args[1] instanceof Integer) {
                        receiptType = (int) param.args[1];
                    }

                    if (receiptType != 13) return;

                    var userJid = new FMessageWpp.UserJid(param.args[0]);
                    String contactName = WppCore.getContactName(userJid);
                    if (TextUtils.isEmpty(contactName)) {
                        var waContact = WaContactWpp.getWaContactFromJid(userJid);
                        if (waContact != null && !TextUtils.isEmpty(waContact.getDisplayName())) {
                            contactName = waContact.getDisplayName();
                        }
                    }
                    if (TextUtils.isEmpty(contactName)) {
                        contactName = userJid.getPhoneNumber();
                    }

                    if (toastViewedStatus) {
                        String msg = !TextUtils.isEmpty(contactName)
                                ? contactName + " viewed your status"
                                : "Someone viewed your status";
                        Utils.showToast(msg, Toast.LENGTH_LONG);
                    }
                    Tasker.sendTaskerEvent(contactName, userJid.getPhoneNumber(), "viewed_status");
                }
            });
        } catch (Throwable e) {
            XposedBridge.log(e);
        }
    }

    private void processNewWA(XC_MethodHook.MethodHookParam param, boolean toastViewedMessage,
                              boolean toastViewedStatus) throws Exception {
        if (param.args == null || param.args.length == 0 || param.args[0] == null) return;
        Collection<?> collection;
        if (!(param.args[0] instanceof Collection)) {
            collection = Collections.singleton(param.args[0]);
        } else {
            collection = (Collection<?>) param.args[0];
        }
        var jidClass = Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.EndsWith, "jid.Jid");

        for (var messageStatusUpdateReceipt : collection) {
            if (messageStatusUpdateReceipt == null) continue;

            var receiptClass = messageStatusUpdateReceipt.getClass();
            int type = -1;
            long id = -1;
            Object rawUserJid = null;
            Object rawFMessage = null;

            for (var f : receiptClass.getDeclaredFields()) {
                f.setAccessible(true);
                Class<?> fType = f.getType();
                if (fType == int.class) {
                    int val = f.getInt(messageStatusUpdateReceipt);
                    if (val == 13 || val == 8 || val == 5 || val == 16 || val == 17) {
                        type = val;
                    }
                } else if (fType == long.class) {
                    long val = f.getLong(messageStatusUpdateReceipt);
                    if (val > 0) {
                        id = val;
                    }
                } else if (jidClass != null && jidClass.isAssignableFrom(fType)) {
                    rawUserJid = f.get(messageStatusUpdateReceipt);
                } else if (FMessageWpp.TYPE != null && FMessageWpp.TYPE.isAssignableFrom(fType)) {
                    rawFMessage = f.get(messageStatusUpdateReceipt);
                }
            }

            if (type != 13) continue;

            if (rawUserJid == null && rawFMessage != null) {
                try {
                    var fMsg = new FMessageWpp(rawFMessage);
                    var key = fMsg.getKey();
                    if (key != null && key.remoteJid != null) {
                        rawUserJid = key.remoteJid.userJid != null ? key.remoteJid.userJid : key.remoteJid.phoneJid;
                    }
                } catch (Exception e) {
                    XposedBridge.log(e);
                }
            }

            if (rawUserJid == null) continue;

            final long finalId = id;
            var userJid = new FMessageWpp.UserJid(rawUserJid);
            final Object fmessageObj = rawFMessage;

            CompletableFuture.runAsync(() -> {
                try {
                    String contactName = WppCore.getContactName(userJid);
                    long rowId = finalId;

                    if (TextUtils.isEmpty(contactName)) {
                        contactName = userJid.getPhoneNumber();
                    }

                    var sql = MessageStore.getInstance().getDatabase();

                    if (fmessageObj != null) {
                        rowId = new FMessageWpp(fmessageObj).getRowId();
                    }

                    checkDataBase(sql, rowId, contactName, userJid.getPhoneRawString(),
                            toastViewedMessage, toastViewedStatus);
                } catch (Exception e) {
                    XposedBridge.log(e);
                }
            });
        }
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Toast Viewer";
    }

    private synchronized void checkDataBase(SQLiteDatabase sql, long id, String contactName,
                                            String rawJid, boolean toastViewedMessage,
                                            boolean toastViewedStatus) {
        if (sql == null || !sql.isOpen()) return;
        try (var result2 = sql.query("message", null, "_id = ?",
                new String[]{String.valueOf(id)}, null, null, null)) {
            if (result2 == null || !result2.moveToNext()) return;

            var participantHash = result2.getString(result2.getColumnIndexOrThrow("participant_hash"));
            if (participantHash != null) {
                if (toastViewedStatus) {
                    String msg = !TextUtils.isEmpty(contactName)
                            ? contactName + " viewed your status"
                            : "Someone viewed your status";
                    Utils.showToast(msg, Toast.LENGTH_LONG);
                }
                Tasker.sendTaskerEvent(contactName, WppCore.stripJID(rawJid), "viewed_status");
                return;
            }

            var currentUserJid = WppCore.getCurrentUserJid();
            if (rawJid != null && currentUserJid != null
                    && Objects.equals(currentUserJid.getPhoneRawString(), rawJid)) return;

            var chatId = result2.getLong(result2.getColumnIndexOrThrow("chat_row_id"));
            try (var result3 = sql.query("chat", null,
                    "_id = ? AND (subject IS NULL OR subject = '')",
                    new String[]{String.valueOf(chatId)}, null, null, null)) {
                if (result3 == null || !result3.moveToNext()) return;

                if (TextUtils.isEmpty(rawJid)) {
                    int rawJidIndex = result3.getColumnIndex("raw_string_jid");
                    if (rawJidIndex >= 0) {
                        rawJid = result3.getString(rawJidIndex);
                    } else {
                        int jidRowIdIndex = result3.getColumnIndex("jid_row_id");
                        if (jidRowIdIndex >= 0) {
                            long jidRowId = result3.getLong(jidRowIdIndex);
                            try (var jidResult = sql.query("jid", new String[]{"raw_string"}, "_id = ?",
                                    new String[]{String.valueOf(jidRowId)}, null, null, null)) {
                                if (jidResult != null && jidResult.moveToNext()) {
                                    rawJid = jidResult.getString(0);
                                }
                            }
                        }
                    }
                }

                if (rawJid != null) {
                    var userJidObj = new FMessageWpp.UserJid(rawJid);
                    if (TextUtils.isEmpty(contactName)) {
                        contactName = WppCore.getContactName(userJidObj);
                    }
                    if (TextUtils.isEmpty(contactName)) {
                        contactName = userJidObj.getPhoneNumber();
                    }
                }

                startCleanupTask();
                var key = rawJid + "_viewed_message";
                long currentTime = System.currentTimeMillis();
                Long lastEventTime = lastEventTimeMap.get(key);
                if (lastEventTime == null || (currentTime - lastEventTime) >= MIN_INTERVAL) {
                    lastEventTimeMap.put(key, currentTime);
                    Tasker.sendTaskerEvent(contactName, WppCore.stripJID(rawJid), "viewed_message");
                    if (toastViewedMessage) {
                        String msg = !TextUtils.isEmpty(contactName)
                                ? contactName + " viewed your message"
                                : "Someone viewed your message";
                        Utils.showToast(msg, Toast.LENGTH_LONG);
                    }
                }
            } catch (Exception e) {
                XposedBridge.log(e);
            }
        } catch (Exception e) {
            XposedBridge.log(e);
        }
    }

    private void startCleanupTask() {
        if (!cleanupStarted.compareAndSet(false, true)) return;
        scheduler.scheduleWithFixedDelay(() -> {
            long currentTime = System.currentTimeMillis();
            lastEventTimeMap.entrySet().removeIf(
                    entry -> (currentTime - entry.getValue()) >= MIN_INTERVAL);
        }, CLEANUP_INTERVAL_SECONDS, CLEANUP_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }
}
