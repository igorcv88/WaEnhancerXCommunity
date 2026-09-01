package com.waenhancer.xposed.features.others;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.waenhancer.R;
import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.WppCore;
import com.waenhancer.xposed.core.components.AlertDialogWpp;
import com.waenhancer.xposed.core.components.FMessageWpp;
import com.waenhancer.xposed.core.devkit.Unobfuscator;
import com.waenhancer.xposed.features.listeners.ConversationItemListener;
import com.waenhancer.xposed.utils.DesignUtils;
import com.waenhancer.xposed.utils.ModuleContextWrapper;
import com.waenhancer.xposed.utils.Utils;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

public class CopySelectionMessage extends Feature {

    public CopySelectionMessage(@NonNull ClassLoader loader, @NonNull SharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        if (!prefs.getBoolean("copy_selection_message", false)) return;

        var popupWindowMessage = Unobfuscator.loadPopupWindowMessageClass(classLoader);
        XposedBridge.hookAllConstructors(popupWindowMessage, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Activity activity = WppCore.getCurrentActivity();
                if (activity == null) {
                    logDebug("CurrentActivity is null");
                    return;
                }
                
                PopupWindow mainPopupWindow = (PopupWindow) param.thisObject;
                ViewGroup viewGroup = (ViewGroup) mainPopupWindow.getContentView();
                if (viewGroup == null) return;

                Object fMessageObj = null;
                if (param.args != null) {
                    for (Object arg : param.args) {
                        if (FMessageWpp.TYPE.isInstance(arg)) {
                            fMessageObj = arg;
                            break;
                        }
                    }
                }
                if (fMessageObj == null) return;
                
                FMessageWpp fMessage = new FMessageWpp(fMessageObj);
                String messageText = fMessage.getMessageStr();
                if (messageText == null) messageText = "";

                if (messageText.isEmpty()) return;

                MaterialButton copyButton = buildActionPill(activity);
                String finalMessageText = messageText;
                copyButton.setOnClickListener(v -> {
                    try {
                        mainPopupWindow.dismiss();
                    } catch (Throwable ignored) {}
                    
                    View view = null;
                    for (var entry : ConversationItemListener.listItems.entrySet()) {
                        if (entry.getValue().messageId.equals(fMessage.getKey().messageID) &&
                                ConversationItemListener.isViewBoundToMessage(entry.getKey(), fMessage.getKey().messageID)) {
                            view = entry.getKey();
                            break;
                        }
                    }
                    
                    String textToShow = finalMessageText;
                    if (view != null) {
                        TextView textView = view.findViewById(Utils.getID("message_text", "id"));
                        if (textView != null && textView.getText() != null) {
                            textToShow = textView.getText().toString();
                        }
                    }
                    
                    showSelectionDialog(activity, textToShow);
                });

                LinearLayout layout = viewGroup.findViewById(Utils.getID("reactions_tray_layout", "id"));
                if (layout == null) return;
                
                layout.setOrientation(LinearLayout.VERTICAL);
                
                int childCount = layout.getChildCount();
                View[] parentItems = new View[childCount];
                for (int i = 0; i < childCount; i++) {
                    parentItems[i] = layout.getChildAt(i);
                }
                layout.removeAllViews();
                
                LinearLayout newContainer = new LinearLayout(viewGroup.getContext());
                newContainer.setOrientation(LinearLayout.HORIZONTAL);
                for (View item : parentItems) {
                    newContainer.addView(item);
                }
                
                layout.addView(newContainer);
                layout.addView(copyButton);
            }
        });
    }

    private MaterialButton buildActionPill(Activity activity) {
        ModuleContextWrapper ctx = new ModuleContextWrapper(activity);
        int textColor = DesignUtils.getPrimaryTextColor();
        int strokeColor = Color.argb(
                80,
                Color.red(textColor),
                Color.green(textColor),
                Color.blue(textColor)
        );
        MaterialButton button = new MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(Utils.getString(R.string.copy_selection_action));
        button.setTextColor(textColor);
        button.setStrokeColor(ColorStateList.valueOf(strokeColor));
        button.setCornerRadius((int) Utils.dipToPixels(50f));
        return button;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void showSelectionDialog(Activity activity, CharSequence messageText) {
        float d = activity.getResources().getDisplayMetrics().density;
        
        ModuleContextWrapper ctx = new ModuleContextWrapper(activity);

        TextInputLayout textInputLayout = new TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        textInputLayout.setCounterEnabled(true);
        textInputLayout.setHint(Utils.getString(R.string.message));
        
        TextInputEditText editText = new TextInputEditText(textInputLayout.getContext());
        editText.setText(messageText);
        editText.setTextSize(14f);
        editText.setMinLines(3);
        editText.setMaxLines(10);
        editText.setGravity(Gravity.TOP);
        editText.setLineSpacing(0f, 1.4f);
        editText.setVerticalScrollBarEnabled(true);
        editText.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        editText.setSelection(0);
        
        textInputLayout.addView(editText);

        NestedScrollView scrollView = new NestedScrollView(ctx);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
        scrollView.addView(textInputLayout);

        int textColor = DesignUtils.getPrimaryTextColor();
        int outlineColor = Color.argb(
                60,
                Color.red(textColor),
                Color.green(textColor),
                Color.blue(textColor)
        );

        MaterialButton closeButton = new MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        closeButton.setText(Utils.getString(R.string.close));
        closeButton.setTextColor(textColor);
        closeButton.setStrokeColor(ColorStateList.valueOf(outlineColor));
        closeButton.setCornerRadius((int) Utils.dipToPixels(50f));
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        closeParams.gravity = Gravity.END;
        closeParams.topMargin = (int) (12 * d);
        closeButton.setLayoutParams(closeParams);

        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding((int)(16*d), (int)(8*d), (int)(16*d), (int)(16*d));
        container.addView(scrollView);
        container.addView(closeButton);

        AlertDialogWpp dialog = new AlertDialogWpp(activity);
        dialog.setTitle(Utils.getString(R.string.copy_selection_dialog_title));
        dialog.setView(container);
        var createdDialog = dialog.create();

        closeButton.setOnClickListener(v -> createdDialog.dismiss());
        createdDialog.show();
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Copy Selection Message";
    }
}
