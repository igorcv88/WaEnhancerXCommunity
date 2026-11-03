package com.waenhancer.xposed.features.general;

import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.WppCore;
import com.waenhancer.xposed.core.components.FMessageWpp;
import com.waenhancer.xposed.core.components.FStatusWpp;
import com.waenhancer.xposed.core.db.DelMessageStore;
import com.waenhancer.xposed.core.db.MessageStore;
import com.waenhancer.xposed.core.devkit.Unobfuscator;
import com.waenhancer.xposed.core.devkit.UnobfuscatorCache;
import com.waenhancer.xposed.features.listeners.ConversationItemListener;
import com.waenhancer.xposed.utils.ReflectionUtils;
import com.waenhancer.R;
import com.waenhancer.xposed.utils.Utils;

import java.lang.reflect.Field;
import java.text.DateFormat;
import java.util.Date;
import java.util.Objects;
import com.waenhancer.xposed.core.db.RevocationArchive;

import de.robv.android.xposed.XC_MethodHook;
import android.content.SharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class AntiRevoke extends Feature {

    private static final ThreadLocal<DateFormat> DATE_FORMAT_THREAD_LOCAL = ThreadLocal
            .withInitial(() -> DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT,
                    Utils.getApplication().getResources().getConfiguration().getLocales().get(0)));

    public AntiRevoke(ClassLoader loader, SharedPreferences preferences) {
        super(loader, preferences);
    }

    @Nullable
    private static Object findObjectFMessage(XC_MethodHook.MethodHookParam param) throws IllegalAccessException {
        if (param.args == null || param.args.length == 0)
            return null;

        for (Object arg : param.args) {
            if (arg == null) continue;
            if (FMessageWpp.TYPE.isInstance(arg)) return arg;
        }

        Object arg0 = param.args[0];
        if (arg0 == null) return null;

        // Try to find FMessage field in the object
        var FMessageField = ReflectionUtils.findFieldUsingFilterIfExists(arg0.getClass(),
                f -> FMessageWpp.TYPE.isAssignableFrom(f.getType()));
        if (FMessageField != null) {
            return FMessageField.get(arg0);
        }

        // Try status data logic
        try {
            var classLoader = arg0.getClass().getClassLoader();
            if (classLoader != null) {
                var mapFStatusToFMessage = Unobfuscator.loadFStatusToFMessage(classLoader);
                var fStatusClass = mapFStatusToFMessage.getParameterTypes()[0];
                var fStatusField = ReflectionUtils.findFieldUsingFilterIfExists(arg0.getClass(),
                        f -> fStatusClass.isAssignableFrom(f.getType()));
                if (fStatusField != null) {
                    return WppCore.getFMessageFromFStatus(fStatusField.get(arg0));
                }
            }
        } catch (Exception ignored) {}

        var field1 = ReflectionUtils.findFieldUsingFilterIfExists(arg0.getClass(),
                f -> f.getType() == FMessageWpp.Key.TYPE);
        if (field1 != null) {
            var key = field1.get(arg0);
            return WppCore.getFMessageFromKey(key);
        }
        return null;

    }

    private RevocationArchive archive;
    private AntiRevokeSqlGuard sqlGuard;
    private volatile String uiScope;

    private final java.util.Map<String, String> restoredText = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, String>(32, .75f, true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, String> entry) { return size() > 32; }
            });
    private final java.util.Set<String> pendingText = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ThreadPoolExecutor restoreWorker = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0, java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(16),
            runnable -> { Thread thread = new Thread(runnable, "WaEX-RevokeRestore"); thread.setDaemon(true); return thread; });

    private void restorePlaceholderText(String scope, long row, String key, String identity,
            TextView date, TextView text) {
        if (restoredText.containsKey(identity)) {
            String archived = restoredText.get(identity);
            if (archived != null) text.setText(archived);
            return;
        }
        if (pendingText.size() >= 16 || !pendingText.add(identity)) return;
        try {
            restoreWorker.execute(() -> {
                try {
                    String archived = archive.archivedText(scope, row, key);
                    restoredText.put(identity, archived);
                    if (archived != null) text.post(() -> {
                        if (identity.equals(XposedHelpers.getAdditionalInstanceField(date, "waexRevokeIdentity")))
                            text.setText(archived);
                    });
                } catch (Exception failure) { sqlGuard.warn("snapshot-read-failed", failure); }
                finally { pendingText.remove(identity); }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) { pendingText.remove(identity); }
    }

    private void refreshConversation() {
        ConversationItemListener.notifyDataSetChanged();
    }

    @Override
    public void doHook() throws Exception {

        archive = new RevocationArchive(DelMessageStore.getInstance(Utils.getApplication()));
        uiScope = RevocationArchive.activeScope();
        WppCore.addListenerActivity((activity, state) -> {
            if (state == WppCore.ActivityChangeState.ChangeType.RESUMED)
                uiScope = RevocationArchive.activeScope();
        });
        // Install before any optional WhatsApp/status resolver can throw.
        sqlGuard = new AntiRevokeSqlGuard(archive, prefs, this::refreshConversation);
        if (protectionEnabled("antirevoke") || protectionEnabled("antirevokestatus")) {
            sqlGuard.install();
            try { archive.prepare(); }
            catch (Exception unavailable) { sqlGuard.warn("archive-initialization-failed", unavailable); }
        }
        installMessageHook();
        installStatusHooks();
        ConversationItemListener.conversationListeners.add(new ConversationItemListener.OnConversationItemListener() {
            @Override public void onItemBind(FMessageWpp fMessage, ViewGroup viewGroup) {
                if (fMessage.getKey().isFromMe) return;
                var date = (TextView) viewGroup.findViewById(Utils.getID("date", "id"));
                var text = (TextView) viewGroup.findViewById(Utils.getID("message_text", "id"));
                bindRevokedMessageUI(fMessage, date, text, "antirevoke");
            }
        });
    }

    private void installMessageHook() {
        try {
            var antiRevokeMessageMethod = Unobfuscator.loadAntiRevokeMessageMethod(classLoader);
            /* Log removed */

            XposedBridge.hookMethod(antiRevokeMessageMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Exception {
                    sqlGuard.enterCall();
                    diagnosticTriggered();
                    if (param.args == null || param.args.length == 0) return;

                    Object fMessageObj = ReflectionUtils.getArg(param.args, FMessageWpp.TYPE, 0);
                    if (fMessageObj == null) {
                        /* Log removed */
                        return;
                    }

                    var fMessage = new FMessageWpp(fMessageObj);
                    var messageKey = fMessage.getKey();
                    var deviceJid = fMessage.getDeviceJid();
                    String messageId = messageKey.messageID;

                    if (WppCore.getPrivBoolean(messageId + "_delpass", false)) {
                        WppCore.removePrivKey(messageId + "_delpass");
                        var source = MessageStore.getInstance().getDatabase();
                        if (source != null) sqlGuard.protectCall(source, fMessage.getRowId(), messageId, true);
                        var activity = WppCore.getCurrentActivity();
                        Class<?> StatusPlaybackActivityClass = classLoader
                                .loadClass("com.whatsapp.status.playback.StatusPlaybackActivity");
                        if (activity != null && StatusPlaybackActivityClass.isInstance(activity)) {
                            activity.finish();
                        }
                        return;
                    }

                    boolean block;
                    if (messageKey.remoteJid.isGroup()) {
                        block = deviceJid != null && handleRevocationAttempt(fMessage) != 0;
                    } else {
                        block = !messageKey.isFromMe && handleRevocationAttempt(fMessage) != 0;
                    }
                    if (block) {
                        Object blocked = blockedMessageRevokeResult(((java.lang.reflect.Method) param.method).getReturnType());
                        if (blocked != RevokeResultPolicy.UNSUPPORTED) param.setResult(blocked);
                        else sqlGuard.warn(sqlGuard.isInstalled() ? "return-type-delegated-to-sql"
                                : "unsupported-return-without-sql-guard", null);
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam param) { sqlGuard.leaveCall(); }
            });

        } catch (Throwable failure) { sqlGuard.warn("message-hook-unavailable", failure); }
    }

    private void installStatusHooks() {
        try {
            var antiRevokeFStatusMethod = Unobfuscator.loadAntiRevokeFStatusMethod(classLoader);
            /* Log removed */

            XposedBridge.hookMethod(antiRevokeFStatusMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    sqlGuard.enterCall();
                    diagnosticTriggered();
                    if (param.args == null || param.args.length < 2 || param.args[1] == null) return;
                    var fStatusKey = new FStatusWpp.FStatusKey(param.args[1]);
                    var fStatus = fStatusKey.fStatus;
                    if (fStatus == null) return;
                    var fMessage = fStatus.getFMessage();
                    if (fMessage == null) return;
                    if (!fStatusKey.isFromMe && handleRevocationAttempt(fMessage) != 0) {
                        // Dev4Mod 01a8bb7: newer builds return a store-result enum, not int.
                        Object blocked = RevokeResultPolicy.blockedStatusRevokeResult(
                                ((java.lang.reflect.Method) param.method).getReturnType());
                        if (blocked != RevokeResultPolicy.UNSUPPORTED) param.setResult(blocked);
                        else sqlGuard.warn(sqlGuard.isInstalled() ? "return-type-delegated-to-sql"
                                : "unsupported-return-without-sql-guard", null);
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam param) { sqlGuard.leaveCall(); }
            });

        } catch (Throwable failure) { sqlGuard.warn("status-hook-unavailable", failure); }
        try {
            var unknownStatusPlaybackMethod = Unobfuscator.loadUnknownStatusPlaybackMethod(classLoader);
            Class<?> statusPlaybackClass = Unobfuscator.loadStatusPlaybackViewClass(classLoader);
            XposedBridge.hookMethod(unknownStatusPlaybackMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object obj = ReflectionUtils.getArg(param.args, param.method.getDeclaringClass(), 0);
                    var objFMessage = findObjectFMessage(param);
                    var field = ReflectionUtils.getFieldByType(param.method.getDeclaringClass(), statusPlaybackClass);

                    if (obj == null || field == null || objFMessage == null)
                        return;

                    Object objView = field.get(obj);
                    if (objView == null)
                        return;

                    var textViews = ReflectionUtils.getFieldsByType(statusPlaybackClass, TextView.class);
                    if (textViews.isEmpty()) {
                        /* Log removed */
                        return;
                    }
                    int dateId = Utils.getID("date", "id");
                    for (Field textView : textViews) {
                        TextView textView1 = (TextView) textView.get(objView);
                        if (textView1 != null && textView1.getId() == dateId) {
                            bindRevokedMessageUI(new FMessageWpp(objFMessage), textView1, null, "antirevokestatus");
                            break;
                        }
                    }
                }
            });

        } catch (Throwable failure) { sqlGuard.warn("status-ui-hook-unavailable", failure); }
    }

    private static String stringMessageDeleted;

    private void bindRevokedMessageUI(FMessageWpp fMessage, TextView dateTextView, TextView messageTextView, String antirevokeType) {
        if (dateTextView == null) return;

        int antirevokeValue = 0;
        try {
            antirevokeValue = Integer.parseInt(prefs.getString(antirevokeType, "0"));
        } catch (Exception ignored) {}
        if (antirevokeValue == 0) return;

        var key = fMessage.getKey();
        String boundIdentity = uiScope + ":" + fMessage.getRowId() + ":" + key.messageID;
        String oldIdentity = (String) XposedHelpers.getAdditionalInstanceField(dateTextView, "waexRevokeIdentity");
        if (!boundIdentity.equals(oldIdentity)) {
            XposedHelpers.setAdditionalInstanceField(dateTextView, "originalMessage", null);
            XposedHelpers.setAdditionalInstanceField(dateTextView, "waexRevokeIdentity", boundIdentity);
        }
        String originalMessage = (String) XposedHelpers.getAdditionalInstanceField(dateTextView, "originalMessage");

        Integer originalDateColor = (Integer) XposedHelpers.getAdditionalInstanceField(dateTextView, "originalColor");
        if (originalDateColor == null) {
            originalDateColor = dateTextView.getCurrentTextColor();
            XposedHelpers.setAdditionalInstanceField(dateTextView, "originalColor", originalDateColor);
        }

        Integer originalMessageColor = null;
        if (messageTextView != null) {
            originalMessageColor = (Integer) XposedHelpers.getAdditionalInstanceField(messageTextView, "originalColor");
            if (originalMessageColor == null) {
                originalMessageColor = messageTextView.getCurrentTextColor();
                XposedHelpers.setAdditionalInstanceField(messageTextView, "originalColor", originalMessageColor);
            }
        }

        int customColor = 0;
        try {
            customColor = prefs.getInt("deleted_message_color", 0);
        } catch (Exception e) {
            try {
                String colorStr = prefs.getString("deleted_message_color", "");
                if (!colorStr.isEmpty()) {
                    try {
                        customColor = android.graphics.Color.parseColor(colorStr);
                    } catch (Exception parseException) {
                        customColor = Integer.parseInt(colorStr);
                    }
                }
            } catch (Exception ignored) {}
        }

        long timestamp = archive.timestamp(uiScope, fMessage.getRowId(), key.messageID);
        // v12 has no account field. Do not reuse a newly scoped record from another account.
        if (timestamp == 0) timestamp = DelMessageStore.getInstance(Utils.getApplication())
                .getLegacyRevokedTimestamp(key.messageID);
        if (timestamp > 0) {
            if (messageTextView != null && fMessage.getMediaType() == 15) {
                restorePlaceholderText(uiScope, fMessage.getRowId(), key.messageID, boundIdentity,
                        dateTextView, messageTextView);
            }
            if (timestamp > 0) {
                var date = Objects.requireNonNull(DATE_FORMAT_THREAD_LOCAL.get()).format(new Date(timestamp));
                dateTextView.getPaint().setUnderlineText(true);
                dateTextView.setOnClickListener(v -> {
                    String toastFormat = com.waenhancer.xposed.core.FeatureLoader.getModuleString(com.waenhancer.xposed.utils.Utils.getApplication(), R.string.message_removed_on);
                    if (toastFormat == null || toastFormat.isEmpty() || !toastFormat.contains("%s")) {
                        toastFormat = "Deleted on: %s"; // Fallback
                    }
                    String toastMessage = String.format(toastFormat, date);
                    Utils.showToast(toastMessage, Toast.LENGTH_LONG);
                });
            }

            if (customColor != 0) {
                dateTextView.setTextColor(customColor);
                if (messageTextView != null) {
                    messageTextView.setTextColor(customColor);
                }
            }

            if (antirevokeValue == 1) {
                String messageText = originalMessage != null ? originalMessage : dateTextView.getText().toString();
                if (stringMessageDeleted == null) {
                    stringMessageDeleted = UnobfuscatorCache.getInstance().getString("messagedeleted");
                }
                String newTextData = (stringMessageDeleted != null ? stringMessageDeleted : "Deleted") + " | " + messageText;
                dateTextView.setText(newTextData);
                XposedHelpers.setAdditionalInstanceField(dateTextView, "originalMessage", messageText);
            } else if (antirevokeValue == 2) {
                var drawable = com.waenhancer.xposed.utils.DesignUtils.getDrawable(R.drawable.deleted);
                dateTextView.setCompoundDrawablesWithIntrinsicBounds(null, null, drawable, null);
                dateTextView.setCompoundDrawablePadding(5);
            }
        } else {
            if (originalDateColor != null) {
                dateTextView.setTextColor(originalDateColor);
            }
            if (messageTextView != null && originalMessageColor != null) {
                messageTextView.setTextColor(originalMessageColor);
            }
            dateTextView.setCompoundDrawables(null, null, null, null);
            if (originalMessage != null) {
                dateTextView.setText(originalMessage);
            }
            dateTextView.getPaint().setUnderlineText(false);
            dateTextView.setOnClickListener(null);
        }
    }

    private boolean protectionEnabled(String preference) {
        try { return Integer.parseInt(prefs.getString(preference, "0")) != 0; }
        catch (RuntimeException invalidPreference) { return false; }
    }

    private int handleRevocationAttempt(FMessageWpp fMessage) {
        // This reports the remote attempt, independently of whether preservation is enabled.
        try { showRevocationToast(fMessage); }
        catch (Exception failure) { sqlGuard.warn("notification-failed", failure); }
        String stripJID = fMessage.getKey().remoteJid.getPhoneNumber();
        int enabled;
        try {
            enabled = Integer.parseInt(prefs.getString("status".equals(stripJID)
                    ? "antirevokestatus" : "antirevoke", "0"));
        } catch (RuntimeException invalidPreference) { return 0; }
        if (enabled == 0) return 0;
        try {
            var source = MessageStore.getInstance().getDatabase();
            if (source == null || !source.isOpen()) throw new IllegalStateException("Message database unavailable");
            // Read from the original row before the native callback can replace its content.
            // The archive uses the row's actual key, not the event/wrapper's obfuscated ID.
            archive.capture(source, fMessage.getRowId(), fMessage.getKey().messageID);
            sqlGuard.protectCall(source, fMessage.getRowId(), fMessage.getKey().messageID, false);
        } catch (Exception failure) {
            sqlGuard.warn("snapshot-not-committed", failure);
            return 0; // SQL guard retains the original if a destructive write follows.
        }
        refreshConversation();
        return enabled;
    }

    private void showRevocationToast(FMessageWpp fMessage) {
        var jidAuthor = fMessage.getKey().remoteJid;
        var messageSuffix = com.waenhancer.xposed.core.FeatureLoader.getModuleString(com.waenhancer.xposed.utils.Utils.getApplication(), R.string.deleted_message);
        if (jidAuthor.isStatus()) {
            messageSuffix = com.waenhancer.xposed.core.FeatureLoader.getModuleString(com.waenhancer.xposed.utils.Utils.getApplication(), R.string.deleted_status);
            jidAuthor = fMessage.getUserJid();
        }
        if (jidAuthor.userJid == null)
            return;
        String name = WppCore.getContactName(jidAuthor);
        if (TextUtils.isEmpty(name)) {
            name = jidAuthor.getPhoneNumber();
        }
        String message;
        // Show "Participant deleted a message in GroupName" for group messages where we
        // know
        // who the sender is (!isNull). The old condition was inverted — it showed this
        // only
        // when getUserJid().isNull() which is exactly when we do NOT know the
        // participant.
        if (jidAuthor.isGroup() && !fMessage.getUserJid().isNull()) {
            var participantJid = fMessage.getUserJid();
            String participantName = WppCore.getContactName(participantJid);
            if (TextUtils.isEmpty(participantName)) {
                participantName = participantJid.getPhoneNumber();
            }
            message = Utils.getApplication().getString(R.string.deleted_a_message_in_group, participantName, name);
        } else {
            message = name + " " + messageSuffix;
        }
        if (prefs.getBoolean("toastdeleted", false)) {
            Utils.showToast(message, Toast.LENGTH_LONG);
        }
        Tasker.sendTaskerEvent(name, jidAuthor.getPhoneNumber(),
                jidAuthor.isStatus() ? "deleted_status" : "deleted_message");
    }

    /**
     * Dev4Mod 01a8bb7: a non-boolean message revoke callback returns a result object; build it
     * with WhatsApp's own "None" incoming-ack singleton instead of null where it can be found.
     */
    private Object blockedMessageRevokeResult(Class<?> returnType) {
        return RevokeResultPolicy.blockedMessageRevokeResult(returnType, type -> {
            try {
                Class<?> none = Unobfuscator.loadNoneIncomingAckClass(classLoader, type);
                return RevokeResultPolicy.staticSingleton(none);
            } catch (Throwable unavailable) { return null; }
        });
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Anti Revoke";
    }

}
