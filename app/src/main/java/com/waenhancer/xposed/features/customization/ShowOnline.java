package com.waenhancer.xposed.features.customization;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.text.TextUtilsCompat;

import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.components.WaContactWpp;
import com.waenhancer.xposed.core.devkit.Unobfuscator;
import com.waenhancer.xposed.core.devkit.UnobfuscatorCache;
import com.waenhancer.xposed.core.devkit.ViewHolderCompat;
import com.waenhancer.xposed.features.listeners.ContactItemListener;
import com.waenhancer.xposed.utils.ReflectionUtils;
import com.waenhancer.xposed.utils.Utils;

import java.lang.reflect.Method;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import android.content.SharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ShowOnline extends Feature {

    private Object mStatusUser;
    private Object mInstancePresence;
    private Method sendPresenceMethod;
    private Method tcTokenMethod;
    private Method getStatusUser;
    private java.lang.reflect.Field fieldTokenDBInstance;
    private Class<?> tokenClass;

    public ShowOnline(@NonNull ClassLoader loader, @NonNull SharedPreferences preferences) {
        super(loader, preferences);
    }

    private static void setStatus(String status, ImageView csDot, TextView lastSeenText) {
        if (!TextUtils.isEmpty(status) && status.trim().equals(UnobfuscatorCache.getInstance().getString("online"))) {
            if (csDot != null) {
                csDot.setVisibility(View.VISIBLE);
            }
        }

        if (lastSeenText != null) {
            if (!TextUtils.isEmpty(status)) {
                lastSeenText.setText(status);
                if (UnobfuscatorCache.getInstance().getString("online").equals(status)) {
                    lastSeenText.setTextColor(Color.GREEN);
                } else {
                    lastSeenText.setTextColor(0xffcac100);
                }
            } else {
                lastSeenText.setText("");
                lastSeenText.setTextColor(Color.GRAY);
            }
        }
    }

    @Override
    public void doHook() throws Throwable {

        var showOnlineText = prefs.getBoolean("showonlinetext", false);
        var showOnlineIcon = prefs.getBoolean("dotonline", false);
        if (!showOnlineText && !showOnlineIcon) return;

        var classViewHolder = ViewHolderCompat.loadViewHolder(classLoader);
        XposedBridge.hookAllConstructors(classViewHolder, new XC_MethodHook() {
            @SuppressLint("ResourceType")
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                // Some WhatsApp builds expose more than one constructor on this class.
                // Only the conversation-row constructor has Context + View as its first arguments.
                if (param.args == null || param.args.length < 2
                        || !(param.args[0] instanceof Context)
                        || !(param.args[1] instanceof View)) {
                    return;
                }

                var view = (View) param.args[1];
                var context = (Context) param.args[0];
                LinearLayout content = view.findViewById(Utils.getID("conversations_row_content", "id"));
                if (content == null) {
                    content = view.findViewById(Utils.getID("row_content", "id"));
                }
                if (content == null) return;

                if (showOnlineText) {
                    var linearLayout = new LinearLayout(context);
                    linearLayout.setGravity(Gravity.END | Gravity.TOP);
                    content.addView(linearLayout);

                    TextView lastSeenText = new TextView(context);
                    lastSeenText.setId(0x7FFF0002);
                    lastSeenText.setTextSize(12f);
                    lastSeenText.setText("");
                    lastSeenText.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
                    lastSeenText.setGravity(Gravity.CENTER_VERTICAL);
                    lastSeenText.setVisibility(View.VISIBLE);
                    linearLayout.addView(lastSeenText);
                }
                if (showOnlineIcon) {
                    var contactView = (FrameLayout) view.findViewById(Utils.getID("contact_selector", "id"));
                    if (contactView == null || contactView.getChildCount() == 0) return;
                    var firstChild = contactView.getChildAt(0);
                    var isLeftToRight = TextUtilsCompat.getLayoutDirectionFromLocale(Locale.getDefault()) == View.LAYOUT_DIRECTION_LTR;
                    if (firstChild instanceof ImageView) {
                        ImageView photoView = (ImageView) firstChild;
                        contactView.removeView(photoView);

                        if (photoView.getId() == View.NO_ID) {
                            photoView.setId(0x7FFF0004);
                        }

                        var relativeLayout = new RelativeLayout(context);
                        relativeLayout.setId(0x7FFF0003);
                        var params = new RelativeLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                        params.addRule(RelativeLayout.CENTER_IN_PARENT);
                        photoView.setLayoutParams(params);
                        relativeLayout.addView(photoView);
                        contactView.addView(relativeLayout);

                        var imageView = new ImageView(context);
                        imageView.setId(0x7FFF0001);
                        var params2 = new RelativeLayout.LayoutParams(Utils.dipToPixels(13), Utils.dipToPixels(13));
                        params2.addRule(RelativeLayout.ALIGN_BOTTOM, photoView.getId());
                        params2.addRule(isLeftToRight ? RelativeLayout.ALIGN_RIGHT : RelativeLayout.ALIGN_LEFT, photoView.getId());
                        if (isLeftToRight) {
                            params2.rightMargin = Utils.dipToPixels(1.5f);
                        } else {
                            params2.leftMargin = Utils.dipToPixels(1.5f);
                        }
                        params2.bottomMargin = Utils.dipToPixels(1.5f);
                        imageView.setLayoutParams(params2);

                        android.graphics.drawable.GradientDrawable dotDrawable = new android.graphics.drawable.GradientDrawable();
                        dotDrawable.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                        dotDrawable.setColor(0xFF25D366);
                        boolean isDark = (context.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
                        int strokeColor = isDark ? 0xFF121212 : Color.WHITE;
                        dotDrawable.setStroke(Utils.dipToPixels(1.5f), strokeColor);

                        imageView.setImageDrawable(dotDrawable);
                        imageView.setVisibility(View.INVISIBLE);
                        relativeLayout.addView(imageView);
                    } else if (firstChild instanceof RelativeLayout) {
                        RelativeLayout relativeLayout = (RelativeLayout) firstChild;
                        if (relativeLayout.getChildCount() == 0 || !(relativeLayout.getChildAt(0) instanceof ImageView)) return;
                        var photoView = (ImageView) relativeLayout.getChildAt(0);

                        if (photoView.getId() == View.NO_ID) {
                            photoView.setId(0x7FFF0004);
                        }

                        var params = new RelativeLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                        params.addRule(RelativeLayout.CENTER_IN_PARENT);
                        photoView.setLayoutParams(params);

                        var imageView = new ImageView(context);
                        imageView.setId(0x7FFF0001);
                        var params2 = new RelativeLayout.LayoutParams(Utils.dipToPixels(13), Utils.dipToPixels(13));
                        params2.addRule(RelativeLayout.ALIGN_BOTTOM, photoView.getId());
                        params2.addRule(isLeftToRight ? RelativeLayout.ALIGN_RIGHT : RelativeLayout.ALIGN_LEFT, photoView.getId());
                        if (isLeftToRight) {
                            params2.rightMargin = Utils.dipToPixels(1.5f);
                        } else {
                            params2.leftMargin = Utils.dipToPixels(1.5f);
                        }
                        params2.bottomMargin = Utils.dipToPixels(1.5f);
                        imageView.setLayoutParams(params2);

                        android.graphics.drawable.GradientDrawable dotDrawable = new android.graphics.drawable.GradientDrawable();
                        dotDrawable.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                        dotDrawable.setColor(0xFF25D366);
                        boolean isDark = (context.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
                        int strokeColor = isDark ? 0xFF121212 : Color.WHITE;
                        dotDrawable.setStroke(Utils.dipToPixels(1.5f), strokeColor);

                        imageView.setImageDrawable(dotDrawable);
                        imageView.setVisibility(View.INVISIBLE);
                        relativeLayout.addView(imageView);
                    }
                }
            }
        });

        getStatusUser = Unobfuscator.loadStatusUserMethod(classLoader);
        sendPresenceMethod = Unobfuscator.loadSendPresenceMethod(classLoader);
        tcTokenMethod = Unobfuscator.loadTcTokenMethod(classLoader);

        XposedBridge.hookAllConstructors(getStatusUser.getDeclaringClass(), new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                mStatusUser = param.thisObject;
            }
        });

        XposedBridge.hookAllConstructors(sendPresenceMethod.getDeclaringClass(), new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                mInstancePresence = param.thisObject;
            }
        });

        tokenClass = sendPresenceMethod.getParameterTypes()[2];
        fieldTokenDBInstance = ReflectionUtils.getFieldByExtendType(sendPresenceMethod.getDeclaringClass(), tcTokenMethod.getDeclaringClass());
        if (fieldTokenDBInstance == null) {
            throw new NoSuchFieldException("Presence token DB field not found");
        }
        fieldTokenDBInstance.setAccessible(true);

        ContactItemListener.contactListeners.add(new ContactItemListener.OnContactItemListener() {
            @Override
            @SuppressLint("ResourceType")
            public void onBind(WaContactWpp waContact, View view) {
                try {
                    var userJid = waContact.getUserJid();
                    if (userJid == null || userJid.isGroup()) return;

                    ImageView csDot = showOnlineIcon ? view.findViewById(0x7FFF0001) : null;
                    if (showOnlineIcon && csDot != null) {
                        csDot.setVisibility(View.INVISIBLE);
                    }
                    TextView lastSeenText = showOnlineText ? view.findViewById(0x7FFF0002) : null;

                    if (mInstancePresence == null || mStatusUser == null) return;
                    var tokenDBInstance = fieldTokenDBInstance.get(mInstancePresence);
                    var tokenData = ReflectionUtils.callMethod(tcTokenMethod, tokenDBInstance, userJid.userJid);
                    var tokenObj = tokenClass.getConstructors()[0].newInstance(tokenData == null ? null : XposedHelpers.getObjectField(tokenData, "A01"));
                    sendPresenceMethod.invoke(null, userJid.userJid, null, tokenObj, mInstancePresence);
                    var status = (String) ReflectionUtils.callMethod(getStatusUser, mStatusUser, waContact.getObject(), false);
                    setStatus(status, csDot, lastSeenText);
                } catch (Exception e) {
                    XposedBridge.log(e);
                }
            }
        });
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Conversation";
    }
}
