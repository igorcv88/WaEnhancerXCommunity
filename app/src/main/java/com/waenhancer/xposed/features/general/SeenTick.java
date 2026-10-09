package com.waenhancer.xposed.features.general;


import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.Pair;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.compat.HostArgCompat;
import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.WppCore;
import com.waenhancer.xposed.core.components.FMessageWpp;
import com.waenhancer.xposed.core.db.MessageHistory;
import com.waenhancer.xposed.core.db.MessageStore;
import com.waenhancer.xposed.core.devkit.Unobfuscator;
import com.waenhancer.xposed.features.customization.HideSeenView;
import com.waenhancer.xposed.features.privacy.ReceiptRelease;
import com.waenhancer.xposed.features.listeners.MenuStatusListener;
import com.waenhancer.xposed.utils.DesignUtils;
import com.waenhancer.xposed.utils.ReflectionUtils;
import com.waenhancer.R;
import com.waenhancer.xposed.utils.Utils;

import org.luckypray.dexkit.query.enums.StringMatchType;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import android.content.SharedPreferences;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class SeenTick extends Feature {

    private static final String RECEIPT_TAG = "WaEnhancerX/Receipts";

    private static java.lang.reflect.Field cachedStatusFMessageField;
    private static java.lang.reflect.Field cachedViewButtonFMessageField;

    private final Set<FMessageWpp> statuses = ConcurrentHashMap.newKeySet();
    private static Object mWaJobManager;
    private static Class<?> mSendReadClass;
    private static Method WaJobManagerMethod;
    private static volatile FMessageWpp.UserJid currentJid;
    private static volatile String currentScreen = "none";
    private final ConcurrentHashMap<String, WeakReference<ImageView>> messageMap = new ConcurrentHashMap<>();

    public SeenTick(@NonNull ClassLoader loader, @NonNull SharedPreferences preferences) {
        super(loader, preferences);
    }

    public static void setSeenButton(ImageView buttonImage, boolean b) {
        Drawable originalDrawable = DesignUtils.getDrawableByName("ic_notif_mark_read");
        if (originalDrawable == null) {
            buttonImage.setImageResource(Utils.getID("ic_notif_mark_read", "drawable"));
            if (b) buttonImage.setColorFilter(Color.CYAN, PorterDuff.Mode.SRC_ATOP);
            return;
        }

        Drawable clonedDrawable;

        if (originalDrawable instanceof BitmapDrawable) {
            BitmapDrawable bitmapDrawable = (BitmapDrawable) originalDrawable;
            Bitmap bitmap = bitmapDrawable.getBitmap();
            Bitmap.Config config = bitmap.getConfig() != null ? bitmap.getConfig() : Bitmap.Config.ARGB_8888;
            Bitmap clonedBitmap;
            try {
                clonedBitmap = bitmap.copy(config, true);
            } catch (Exception ex) {
                clonedBitmap = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
                try {
                    android.graphics.Canvas canvas = new android.graphics.Canvas(clonedBitmap);
                    canvas.drawBitmap(bitmap, 0f, 0f, null);
                } catch (Exception ignore) {
                }
            }
            clonedDrawable = new BitmapDrawable(buttonImage.getResources(), clonedBitmap);
        } else {
            var cs = originalDrawable.getConstantState();
            if (cs != null) {
                clonedDrawable = cs.newDrawable().mutate();
            } else {
                clonedDrawable = originalDrawable.mutate();
            }
        }
        if (b) {
            clonedDrawable.setColorFilter(Color.CYAN, PorterDuff.Mode.SRC_ATOP);
        }
        buttonImage.setImageDrawable(clonedDrawable);
        buttonImage.postInvalidate();
    }

    private void registerMessageView(String messageId, ImageView view) {
        if (messageId == null || view == null) return;
        messageMap.put(messageId, new WeakReference<>(view));
    }

    private ImageView getRegisteredView(String messageId) {
        WeakReference<ImageView> ref = messageMap.get(messageId);
        return ref == null ? null : ref.get();
    }

    @Override
    public void doHook() throws Throwable {


        WaJobManagerMethod = Unobfuscator.loadBlueOnReplayWaJobManagerMethod(classLoader);

        mSendReadClass = Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.EndsWith, "SendReadReceiptJob");

        // hook instance of WaJobManager;

        XposedBridge.hookAllConstructors(WaJobManagerMethod.getDeclaringClass(), new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                mWaJobManager = param.thisObject;
            }
        });

        // hook conversation screen

        WppCore.addListenerActivity((activity, type) -> {
            if (activity.getClass().getSimpleName().equals("Conversation") && (type == WppCore.ActivityChangeState.ChangeType.STARTED || type == WppCore.ActivityChangeState.ChangeType.RESUMED)) {
                var jid = WppCore.getCurrentUserJid();
                if (!Objects.equals(jid, currentJid)) {
                    currentJid = jid;
                }
                currentScreen = "conversation";
            }
        });

        // hook messages
        hookOnSendMessages();

        // hook current status for other features (e.g. StatusDownload activeStatusObj tracking)
        try {
            var setPageActiveMethod = Unobfuscator.loadStatusActivePage(classLoader);
            var fieldList = ReflectionUtils.getFieldByType(setPageActiveMethod.getDeclaringClass(), List.class);
            if (fieldList == null) {
                throw new NoSuchFieldException("StatusActivePage page list field not found");
            }
            final String listFieldName = fieldList.getName();

            XposedBridge.hookMethod(setPageActiveMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    // WhatsApp 2.26.33 reaches setPageActive through call shapes where the
                    // legacy int slot is absent, null or moved. Never auto-unbox args[1]
                    // blindly: that is the Integer.intValue() NPE seen in LSPosed logs.
                    if (param.args == null || param.args.length < 2 || param.args[0] == null) return;
                    Number positionArg = HostArgCompat.numberAt(param.args, 1);
                    if (positionArg == null) {
                        int idx = HostArgCompat.findIndexOfType(param.args, int.class);
                        positionArg = idx >= 1 ? HostArgCompat.numberAt(param.args, idx) : null;
                    }
                    if (positionArg == null) return;
                    int position = positionArg.intValue();

                    Object listObject = XposedHelpers.getObjectField(param.args[0], listFieldName);
                    if (!(listObject instanceof List<?>)) return;
                    var list = (List<?>) listObject;
                    if (position < 0 || position >= list.size()) return;
                    var rawObject = list.get(position);
                    if (rawObject == null) return;
                    com.waenhancer.xposed.features.media.StatusDownload.activeStatusObj = rawObject;
                    
                    // Reply release is independent of the manual receipt button setting.
                    var object = ReflectionUtils.findFMessageInObject(rawObject, FMessageWpp.TYPE, FMessageWpp.Key.TYPE, classLoader);
                    if (object == null) {
                        return;
                    }
                    var fMessage = new FMessageWpp(object);
                    statuses.clear();
                    statuses.add(fMessage);
                    currentJid = fMessage.getUserJid();
                    currentScreen = "status";
                }
            });
        } catch (Throwable t) {
            logDebug("Error hooking StatusActivePage: " + t.getMessage());
        }

        // Send Seen functions
        var ticktype = Integer.parseInt(prefs.getString("seentick", "0"));
        if (ticktype == 0) return;

        // Add button to send Seen in conversation
        hookConversationScreen(ticktype);

        /// Add button to send View Once to target
        hookViewOnceScreen(ticktype);

        // Add button to send Seen in status
        hookStatusScreen(ticktype);

    }

    private void hookStatusScreen(int ticktype) throws Exception {
        var viewButtonMethod = Unobfuscator.loadBlueOnReplayViewButtonMethod(classLoader);
        /* Log removed */
        var viewStatusField = Unobfuscator.loadBlueOnReplayViewButtonOutSideField(classLoader);
        if (ticktype == 1) {
            XposedBridge.hookMethod(viewButtonMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!prefs.getBoolean("hidestatusview", false)) return;
                    var fMessageObj = ReflectionUtils.findFMessageInObject(param.thisObject, FMessageWpp.TYPE, FMessageWpp.Key.TYPE, classLoader);
                    if (fMessageObj == null) {
                        /* Log removed */
                        return;
                    }
                    var fMessage = new FMessageWpp(fMessageObj);
                    var key = fMessage.getKey();
                    if (key.isFromMe) return;
                    var view = (View) param.getResult();
                    var contentView = (LinearLayout) view.findViewById(Utils.getID("bottom_sheet", "id"));
                    var buttonImage = new ImageView(view.getContext());
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Utils.dipToPixels(32), Utils.dipToPixels(32));
                    params.gravity = Gravity.CENTER_VERTICAL;
                    params.setMargins(Utils.dipToPixels(5), Utils.dipToPixels(5), 0, 0);
                    buttonImage.setLayoutParams(params);
                    buttonImage.setImageResource(Utils.getID("ic_notif_mark_read", "drawable"));
                    GradientDrawable border = new GradientDrawable();
                    border.setShape(GradientDrawable.RECTANGLE);
                    border.setStroke(1, Color.WHITE);
                    border.setCornerRadius(20);
                    border.setColor(Color.parseColor("#80000000"));
                    buttonImage.setBackground(border);
                    contentView.setOrientation(LinearLayout.HORIZONTAL);
                    contentView.addView(buttonImage, 0);
                    registerMessageView(key.messageID, buttonImage);
                    buttonImage.setOnClickListener(v -> CompletableFuture.runAsync(() -> {
                        Utils.showToast(view.getContext().getString(R.string.sending_read_blue_tick), Toast.LENGTH_SHORT);
                        sendBlueTickStatus(currentJid);
                        buttonImage.post(() -> setSeenButton(buttonImage, true));
                    }));
                    CompletableFuture.runAsync(() -> {
                        var seen = MessageStore.getInstance().isReadMessageStatus(key.messageID);
                        buttonImage.post(() -> setSeenButton(buttonImage, seen));
                    });
                }
            });
        } else {

            MenuStatusListener.registerStatusListener(
                    new MenuStatusListener.OnMenuItemStatusListener() {
                        @Override
                        public MenuItem addMenu(Menu menu, List<FMessageWpp> fMessageList, int currentIndex) {
                            if (menu.findItem(R.string.send_blue_tick) != null) return null;
                            var fMessage = fMessageList.get(currentIndex);
                            if (fMessage.getKey().isFromMe) return null;
                            return menu.add(0, R.string.send_blue_tick, 0, com.waenhancer.xposed.core.FeatureLoader.getModuleString(com.waenhancer.xposed.utils.Utils.getApplication(), com.waenhancer.R.string.send_blue_tick, "Send blue tick"));
                        }

                        @Override
                        public void onClick(MenuItem item, Object fragmentInstance, List<FMessageWpp> fMessageList, int currentIndex) {
                            sendBlueTickStatus(currentJid);
                            Utils.showToast(com.waenhancer.xposed.core.FeatureLoader.getModuleString(com.waenhancer.xposed.utils.Utils.getApplication(), R.string.sending_read_blue_tick, "Sending read receipt..."), Toast.LENGTH_SHORT);
                        }
                    });
        }
    }

    private void hookConversationScreen(int ticktype) throws Exception {
        var onCreateMenuConversationMethod = Unobfuscator.loadBlueOnReplayCreateMenuConversationMethod(classLoader);
        /* Log removed */
        XposedBridge.hookMethod(onCreateMenuConversationMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                var menu = (Menu) param.args[0];
                var menuItem = menu.add(0, R.string.send_blue_tick, 0, com.waenhancer.xposed.core.FeatureLoader.getModuleString(com.waenhancer.xposed.utils.Utils.getApplication(), com.waenhancer.R.string.send_blue_tick, "Send blue tick"));
                if (ticktype == 1) menuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                menuItem.setIcon(Utils.getID("ic_notif_mark_read", "drawable"));
                menuItem.setOnMenuItemClickListener(item -> {
                    sendBlueTick(currentJid);
                    Utils.showToast(com.waenhancer.xposed.core.FeatureLoader.getModuleString(com.waenhancer.xposed.utils.Utils.getApplication(), R.string.sending_read_blue_tick, "Sending read receipt..."), Toast.LENGTH_SHORT);
                    HideSeenView.updateAllBubbleViews();
                    return true;
                });
            }
        });

        var menuClass = onCreateMenuConversationMethod.getDeclaringClass();
        var onOptionsItemSelectedMethod = ReflectionUtils.findMethodUsingFilterIfExists(menuClass,
                m -> m.getName().equals("onOptionsItemSelected") && m.getParameterCount() == 1 && m.getParameterTypes()[0].equals(MenuItem.class));
        if (onOptionsItemSelectedMethod != null) {
            XposedBridge.hookMethod(onOptionsItemSelectedMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    var item = (MenuItem) param.args[0];
                    if (item.getItemId() == R.string.send_blue_tick) {
                        sendBlueTick(currentJid);
                        Utils.showToast(com.waenhancer.xposed.core.FeatureLoader.getModuleString(com.waenhancer.xposed.utils.Utils.getApplication(), R.string.sending_read_blue_tick, "Sending read receipt..."), Toast.LENGTH_SHORT);
                        HideSeenView.updateAllBubbleViews();
                        param.setResult(true);
                    }
                }
            });
        }

        MenuStatusListener.registerStatusListener(
                new MenuStatusListener.OnMenuItemStatusListener() {
                    @Override
                    public MenuItem addMenu(Menu menu, List<FMessageWpp> fMessageList, int currentIndex) {
                        if (menu.findItem(R.string.read_all_mark_as_read) != null) return null;
                        var fMessage = fMessageList.get(currentIndex);
                        if (fMessage.getKey().isFromMe) return null;
                        return menu.add(0, R.string.read_all_mark_as_read, 0, com.waenhancer.xposed.core.FeatureLoader.getModuleString(com.waenhancer.xposed.utils.Utils.getApplication(), com.waenhancer.R.string.read_all_mark_as_read, "Read all (Mark as read)"));
                    }

                    @Override
                    public void onClick(MenuItem item, Object fragmentInstance, List<FMessageWpp> fMessageList, int currentIndex) {
                        try {
                            statuses.clear();
                            for (FMessageWpp fMessage : fMessageList) {
                                if (fMessage == null) continue;
                                var messageId = fMessage.getKey().messageID;
                                if (!fMessage.getKey().isFromMe) {
                                    statuses.add(fMessage);
                                }
                                var view = getRegisteredView(messageId);
                                if (view != null) {
                                    view.post(() -> setSeenButton(view, true));
                                }
                            }
                        } catch (Exception e) {
                            log(e);
                        }
                        sendBlueTickStatus(currentJid);
                        Utils.showToast(com.waenhancer.xposed.core.FeatureLoader.getModuleString(com.waenhancer.xposed.utils.Utils.getApplication(), R.string.sending_read_blue_tick, "Sending read receipt..."), Toast.LENGTH_SHORT);
                    }
                });
    }

    private void hookViewOnceScreen(int ticktype) throws Exception {
        var menuMethod = Unobfuscator.loadViewOnceDownloadMenuMethod(classLoader);
        /* Log removed */

        XposedBridge.hookMethod(menuMethod, new XC_MethodHook() {
            @Override
            @SuppressLint("DiscouragedApi")
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object fmessageObj = ReflectionUtils.findFMessageInObject(param.thisObject, FMessageWpp.TYPE, FMessageWpp.Key.TYPE, classLoader);
                if (fmessageObj == null) return;
                FMessageWpp fMessage = new FMessageWpp(fmessageObj);
                if (!fMessage.isViewOnce()) return;
                Menu menu = (Menu) param.args[0];
                MenuItem item = menu.add(0, 0, 0, com.waenhancer.xposed.core.FeatureLoader.getModuleString(com.waenhancer.xposed.utils.Utils.getApplication(), com.waenhancer.R.string.send_blue_tick, "Send blue tick")).setIcon(Utils.getID("ic_notif_mark_read", "drawable"));
                if (ticktype == 1) item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                item.setOnMenuItemClickListener(item1 -> {
                    var userJid = fMessage.getKey().remoteJid;
                    var messageID = fMessage.getKey().messageID;
                    MessageHistory.getInstance().updateViewedMessage(userJid.getPhoneRawString(), messageID, MessageHistory.MessageType.VIEW_ONCE_TYPE, true);
                    MessageHistory.getInstance().updateViewedMessage(userJid.getPhoneRawString(), messageID, MessageHistory.MessageType.MESSAGE_TYPE, true);
                    sendBlueTickMedia(fMessage);
                    statuses.clear();
                    Utils.showToast(com.waenhancer.xposed.core.FeatureLoader.getModuleString(com.waenhancer.xposed.utils.Utils.getApplication(), R.string.sending_read_blue_tick, "Sending read receipt..."), Toast.LENGTH_SHORT);
                    HideSeenView.updateAllBubbleViews();
                    return true;
                });
            }
        });

        XposedHelpers.findAndHookMethod(WppCore.getViewOnceViewerActivityClass(classLoader), "onCreateOptionsMenu", classLoader.loadClass("android.view.Menu"),
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Menu menu = (Menu) param.args[0];
                        MenuItem item = menu.add(0, 0, 0, com.waenhancer.xposed.core.FeatureLoader.getModuleString(com.waenhancer.xposed.utils.Utils.getApplication(), com.waenhancer.R.string.send_blue_tick, "Send blue tick")).setIcon(Utils.getID("ic_notif_mark_read", "drawable"));
                        if (ticktype == 1) item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                        item.setOnMenuItemClickListener(item1 -> {
                            CompletableFuture.runAsync(() -> {
                                var keyClass = FMessageWpp.Key.TYPE;
                                var fieldType = ReflectionUtils.getFieldByType(param.thisObject.getClass(), keyClass);
                                var keyMessage = ReflectionUtils.getObjectField(fieldType, param.thisObject);
                                var fMessage = new FMessageWpp.Key(keyMessage).getFMessage();
                                var rawJid = fMessage.getKey().remoteJid.getPhoneRawString();
                                var messageID = fMessage.getKey().messageID;
                                MessageHistory.getInstance().updateViewedMessage(rawJid, messageID, MessageHistory.MessageType.VIEW_ONCE_TYPE, true);
                                MessageHistory.getInstance().updateViewedMessage(rawJid, messageID, MessageHistory.MessageType.MESSAGE_TYPE, true);
                                sendBlueTickMedia(fMessage);
                                statuses.clear();
                                Utils.showToast(com.waenhancer.xposed.core.FeatureLoader.getModuleString(com.waenhancer.xposed.utils.Utils.getApplication(), R.string.sending_read_blue_tick, "Sending read receipt..."), Toast.LENGTH_SHORT);
                                HideSeenView.updateAllBubbleViews();
                            });
                            return true;
                        });

                    }
                });


    }

    private void hookOnSendMessages() throws Exception {
        var messageJobMethod = Unobfuscator.loadBlueOnReplayMessageJobMethod(classLoader);
        var messageSendClass = Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.EndsWith, "SendE2EMessageJob");

        // The method is found by its log string; on some builds that string sits in a helper or
        // lambda class rather than in SendE2EMessageJob, so record where the hook actually landed.
        Log.i(RECEIPT_TAG, "send hook on " + messageJobMethod.getDeclaringClass().getName()
                + "#" + messageJobMethod.getName() + " jobClass=" + messageSendClass.getName()
                + " declaredOnJob=" + messageSendClass.isAssignableFrom(messageJobMethod.getDeclaringClass()));

        XposedBridge.hookMethod(messageJobMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!prefs.getBoolean("blueonreply", false)) return;
                try {
                    releaseOnSend(param, messageSendClass);
                } catch (Throwable failure) {
                    Log.w(RECEIPT_TAG, "reply release failed: " + failure);
                }
            }
        });
    }

    /** The job is the hooked instance, an argument, or a field of a helper that owns it. */
    private static Object findSendJob(XC_MethodHook.MethodHookParam param, Class<?> jobClass) {
        if (jobClass.isInstance(param.thisObject)) return param.thisObject;
        if (param.args != null) {
            for (Object arg : param.args) {
                if (jobClass.isInstance(arg)) return arg;
            }
        }
        if (param.thisObject == null) return null;
        for (Class<?> c = param.thisObject.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (var field : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                if (!field.getType().isAssignableFrom(jobClass) && !jobClass.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(param.thisObject);
                    if (jobClass.isInstance(value)) return value;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private void releaseOnSend(XC_MethodHook.MethodHookParam param, Class<?> messageSendClass) {
        var obj = findSendJob(param, messageSendClass);
        if (obj == null) {
            Log.i(RECEIPT_TAG, "reply release skipped: no send job in "
                    + (param.thisObject == null ? "static call" : param.thisObject.getClass().getName()));
            return;
        }
        var rawJid = (String) XposedHelpers.getObjectField(obj, "jid");
        var userJid = new FMessageWpp.UserJid(WppCore.createUserJid(rawJid));

        if (userJid.isNull()) {
            Log.i(RECEIPT_TAG, "reply release skipped: unresolved destination");
            return;
        }
        // Resolve the outgoing message itself; the viewer may have advanced or closed.
        // Do not use Key(String, ..., true): that legacy constructor hardcodes false.
        FMessageWpp outgoing = null;
        FMessageWpp quotedStatus = null;
        boolean statusQuote = false;
        String lookup = "ok";
        try {
            var outgoingId = (String) XposedHelpers.getObjectField(obj, "id");
            outgoing = findOutgoing(userJid, outgoingId);
            if (outgoing == null) {
                lookup = outgoingId == null || outgoingId.isEmpty() ? "no-id" : "not-found";
            } else {
                var quote = outgoing.getOriginalKey();
                statusQuote = quote != null && quote.remoteJid != null
                        && quote.remoteJid.isStatus();
                if (statusQuote && !quote.isFromMe && quote.messageID != null
                        && !quote.messageID.isEmpty()) {
                    // getOriginalKey().getFMessage() wraps the outgoing message, so load
                    // the quoted key explicitly rather than relying on that cached wrapper.
                    var quotedObject = WppCore.getFMessageFromKey(quote.thisObject);
                    if (quotedObject != null) {
                        var candidate = new FMessageWpp(quotedObject);
                        var selected = StatusReplyRouting.selectQuoted(quote.messageID, candidate,
                                item -> item.getKey().messageID,
                                item -> !item.getKey().isFromMe && item.getKey().remoteJid != null
                                        && item.getKey().remoteJid.isStatus());
                        if (!selected.isEmpty()) quotedStatus = selected.get(0);
                    }
                }
            }
        } catch (Throwable failure) {
            outgoing = null;
            statusQuote = false;
            lookup = "error:" + failure.getClass().getSimpleName();
        }
        var author = quotedStatus == null ? null : quotedStatus.getUserJid();
        boolean quotedMatches = author != null && StatusReplyRouting.matches(true,
                userJid.getPhoneRawString(), userJid.getUserRawString(),
                author.getPhoneRawString(), author.getUserRawString());
        var route = StatusReplyRouting.route(userJid.isStatus(), outgoing != null,
                statusQuote, quotedMatches);
        Log.i(RECEIPT_TAG, "reply release route=" + route + " lookup=" + lookup
                + " jid=" + jidKind(userJid));
        if (route == StatusReplyRouting.Route.STATUS) {
            sendBlueTickStatus(author, List.of(quotedStatus));
        } else if (route == StatusReplyRouting.Route.CHAT) {
            sendBlueTick(chatReleaseJid(userJid, outgoing));
        }
        HideSeenView.updateAllBubbleViews();
    }

    /**
     * Hidden rows are keyed by the PN form of the incoming messages' remote JID (the same form the
     * bubble indicator reads). Prefer the outgoing message's own key, then the open conversation
     * when it is the destination, and only then the job's raw JID, which may lack a PN mapping.
     */
    private static FMessageWpp.UserJid chatReleaseJid(FMessageWpp.UserJid destination, FMessageWpp outgoing) {
        if (destination.getPhoneRawString() != null) return destination;
        try {
            var key = outgoing == null ? null : outgoing.getKey();
            if (key != null && key.remoteJid != null && key.remoteJid.getPhoneRawString() != null) {
                return key.remoteJid;
            }
        } catch (Throwable ignored) {
        }
        var open = currentJid;
        if (open != null && !open.isNull() && open.getPhoneRawString() != null
                && StatusReplyRouting.matches(true, destination.getPhoneRawString(),
                destination.getUserRawString(), open.getPhoneRawString(), open.getUserRawString())) {
            return open;
        }
        return destination;
    }

    /** The outgoing key may be stored under the LID or the PN form of the destination. */
    private static FMessageWpp findOutgoing(FMessageWpp.UserJid destination, String outgoingId) {
        if (outgoingId == null || outgoingId.isEmpty()) return null;
        for (Object jid : new Object[]{destination.userJid, destination.phoneJid}) {
            if (jid == null) continue;
            var key = XposedHelpers.newInstance(FMessageWpp.Key.TYPE, jid, outgoingId, true);
            var object = WppCore.getFMessageFromKey(key);
            if (object == null) continue;
            var message = new FMessageWpp(object);
            var messageKey = message.getKey();
            if (messageKey != null && messageKey.isFromMe && outgoingId.equals(messageKey.messageID)) {
                return message;
            }
        }
        return null;
    }

    /** Log the address form only; never the number. */
    private static String jidKind(FMessageWpp.UserJid jid) {
        if (jid.isStatus()) return "status";
        if (jid.isGroup()) return "group";
        return (jid.userJid != null ? "lid" : "") + (jid.phoneJid != null ? "+pn" : "");
    }

    private static void updateMessageStatusView(String rawJid, List<FMessageWpp> messages) {
        for (var msg : messages) {
            MessageHistory.getInstance().updateViewedMessage(rawJid, msg.getKey().messageID, MessageHistory.MessageType.MESSAGE_TYPE, true);
        }
        HideSeenView.updateAllBubbleViews();
    }

    private void sendBlueTick(FMessageWpp.UserJid userJid) {
        CompletableFuture.runAsync(() -> {
            if (Objects.equals(userJid.getPhoneNumber(), Utils.getMyNumber()) || Objects.requireNonNullElse(userJid.getUserRawString(), "").contains("lid_me"))
                return;
            var messages = new ArrayList<FMessageWpp>();
            var hideSeenMessagesssages = MessageHistory.getInstance().getHideSeenMessages(userJid.getPhoneRawString(), MessageHistory.MessageType.MESSAGE_TYPE, false);
            if (hideSeenMessagesssages == null) hideSeenMessagesssages = List.of();
            for (var message : hideSeenMessagesssages) {
                var fmessage = message.getFMessage();
                if (fmessage == null) continue;
                messages.add(fmessage);
            }
            Log.i(RECEIPT_TAG, "chat release pending=" + messages.size()
                    + " rows=" + hideSeenMessagesssages.size() + " jid=" + jidKind(userJid));
            if (messages.isEmpty())
                return;
            for (var m : messages) {
                if (m.getMediaType() == 2) sendBlueTickMedia(m);
            }
            var sendedMessages = sendBlueTickMsg(userJid, messages);
            updateMessageStatusView(userJid.getPhoneRawString(), sendedMessages);
        }, Utils.getExecutor());
    }

    private List<FMessageWpp> sendBlueTickMsg(FMessageWpp.UserJid userJid, ArrayList<FMessageWpp> messages) {
        int totalMessages = messages.size();
        if (totalMessages == 0)
            return messages;

        var sentMessages = new ArrayList<FMessageWpp>(totalMessages);

        List<? extends Pair<Integer, ? extends Class<?>>> jidIndexes;
        Constructor<?> sendJobConstrutor;
        int messageIdIndex;
        Object[] args;

        try {
            sendJobConstrutor = mSendReadClass.getConstructors()[0];
            var paramTypes = sendJobConstrutor.getParameterTypes();
            jidIndexes = ReflectionUtils.findClassesOfType(paramTypes, FMessageWpp.UserJid.TYPE_JID);
            if (jidIndexes.size() < 2) {
                /* Log removed */
                return Collections.emptyList();
            }
            messageIdIndex = ReflectionUtils.findIndexOfType(paramTypes, String[].class);
            if (messageIdIndex == -1) {
                /* Log removed */
                return Collections.emptyList();
            }

            args = ReflectionUtils.initArray(paramTypes);
            args[jidIndexes.get(0).first] = userJid.userJid;

        } catch (Exception e) {
            logDebug(e);
            return Collections.emptyList();
        }

        HashMap<FMessageWpp.UserJid, List<FMessageWpp>> groupedMap = new HashMap<>(4);
        boolean isGroup = userJid.isGroup();

        for (int i = 0; i < totalMessages; i++) {
            FMessageWpp message = messages.get(i);
            var userJidMsg = isGroup ? message.getUserJid() : message.getKey().remoteJid;
            List<FMessageWpp> groupList = groupedMap.computeIfAbsent(userJidMsg, k -> new ArrayList<>(isGroup ? 4 : totalMessages));
            groupList.add(message);
        }

        for (var entry : groupedMap.entrySet()) {
            try {
                var userJidMsg = entry.getKey();
                List<FMessageWpp> groupMessages = entry.getValue();
                int groupSize = groupMessages.size();

                String[] messageIds = new String[groupSize];
                for (int i = 0; i < groupSize; i++) {
                    messageIds[i] = groupMessages.get(i).getKey().messageID;
                }

                args[jidIndexes.get(1).first] = isGroup ? userJidMsg.userJid : null;
                args[messageIdIndex] = messageIds;

                Object sendJob = sendJobConstrutor.newInstance(args);
                XposedHelpers.setAdditionalInstanceField(sendJob, "blue_on_reply", true);
                // Publish authorization first: the queue can run the job before invoke returns.
                // Every suppression layer reads the same record, including the direct guard.
                ReceiptRelease.enqueue(groupMessages,
                        message -> {
                            if (!MessageHistory.getInstance().updateViewedMessage(
                                    userJid.getPhoneRawString(), message.getKey().messageID,
                                    MessageHistory.MessageType.MESSAGE_TYPE, true)) {
                                throw new IllegalStateException("Could not authorize pending read receipt");
                            }
                        },
                        message -> MessageHistory.getInstance().updateViewedMessage(
                                userJid.getPhoneRawString(), message.getKey().messageID,
                                MessageHistory.MessageType.MESSAGE_TYPE, false),
                        () -> WaJobManagerMethod.invoke(mWaJobManager, sendJob));

                sentMessages.addAll(groupMessages);

            } catch (Exception ex) {
                logDebug(ex);
            }
        }

        return sentMessages;
    }

    private void sendBlueTickStatus(FMessageWpp.UserJid currentJid) {
        if (statuses.isEmpty() || currentJid == null || "status_me".equals(currentJid.getPhoneNumber())) {
            return;
        }

        List<FMessageWpp> snapshot = new ArrayList<>(statuses);
        snapshot.forEach(statuses::remove);
        sendBlueTickStatus(currentJid, snapshot);
    }

    private void sendBlueTickStatus(FMessageWpp.UserJid author, List<FMessageWpp> selected) {
        if (author == null || "status_me".equals(author.getPhoneNumber()) || selected.isEmpty()) return;
        // Freeze the requested items before scheduling; automatic replies pass only their quote.
        List<FMessageWpp> snapshot = List.copyOf(selected);

        CompletableFuture.runAsync(() -> {
            try {
                int size = snapshot.size();

                Constructor<?> sendJobConstrutor = mSendReadClass.getConstructors()[0];
                Class<?>[] paramTypes = sendJobConstrutor.getParameterTypes();

                var jidIndexes = ReflectionUtils.findClassesOfType(paramTypes, FMessageWpp.UserJid.TYPE_JID);
                if (jidIndexes.size() < 2) {
                    /* Log removed */
                    return;
                }

                int messageIdIndex = ReflectionUtils.findIndexOfType(paramTypes, String[].class);
                if (messageIdIndex == -1) {
                    /* Log removed */
                    return;
                }

                String[] arr_s = new String[size];
                MessageStore store = MessageStore.getInstance();

                for (int i = 0; i < size; i++) {
                    String msgId = snapshot.get(i).getKey().messageID;
                    arr_s[i] = msgId;
                    store.storeMessageRead(msgId);
                }

                var userJidSender = WppCore.createUserJid("status@broadcast");
                Object[] args = ReflectionUtils.initArray(paramTypes);

                args[jidIndexes.get(0).first] = userJidSender;
                args[jidIndexes.get(1).first] = author.phoneJid;
                args[messageIdIndex] = arr_s;

                Object sendJob2 = sendJobConstrutor.newInstance(args);
                XposedHelpers.setAdditionalInstanceField(sendJob2, "blue_on_reply", true);
                WaJobManagerMethod.invoke(mWaJobManager, sendJob2);

            } catch (Exception e) {
                logDebug(e);
            }
        }, Utils.getExecutor());
    }


    private void sendBlueTickMedia(FMessageWpp fMessage) {
        CompletableFuture.runAsync(() -> {
            try {
                var userJid = fMessage.getKey().remoteJid;
                Object participant = null;
                if (userJid.isGroup()) {
                    participant = fMessage.getUserJid().userJid;
                }
                var sendPlayerClass = Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.Contains, "SendPlayedReceiptJob");
                var constructor = sendPlayerClass.getDeclaredConstructors()[0];
                var classParticipantInfo = constructor.getParameterTypes()[0];
                var rowsId = new Long[]{fMessage.getRowId()};
                var messageId = fMessage.getKey().messageID;
                constructor = classParticipantInfo.getDeclaredConstructors()[0];
                var participantInfo = constructor.newInstance(userJid.userJid, participant, rowsId, new String[]{messageId});
                var sendJob = XposedHelpers.newInstance(sendPlayerClass, participantInfo, false);
                WaJobManagerMethod.invoke(mWaJobManager, sendJob);
            } catch (Throwable e) {
                logDebug(e);
            }
        }, Utils.getExecutor());
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Seen Tick";
    }


}
