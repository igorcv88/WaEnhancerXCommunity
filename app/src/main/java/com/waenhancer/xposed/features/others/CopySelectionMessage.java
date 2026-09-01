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
import android.widget.Toast;

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
import com.waenhancer.xposed.utils.DesignUtils;
import com.waenhancer.xposed.utils.ModuleContextWrapper;
import com.waenhancer.xposed.utils.Utils;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class CopySelectionMessage extends Feature {

    private static final String INJECTED_FIELD = "waex_copy_selection_injected";

    public CopySelectionMessage(@NonNull ClassLoader loader,
                                @NonNull SharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        if (!prefs.getBoolean("copy_selection_message", false)) return;

        Class<?> popupWindowMessage = Unobfuscator.loadPopupWindowMessageClass(classLoader);
        XposedBridge.hookAllConstructors(popupWindowMessage, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!(param.thisObject instanceof PopupWindow)) return;
                PopupWindow mainPopupWindow = (PopupWindow) param.thisObject;
                if (Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                        mainPopupWindow, INJECTED_FIELD))) return;

                Activity activity = WppCore.getCurrentActivity();
                if (activity == null) return;

                View contentView = mainPopupWindow.getContentView();
                if (!(contentView instanceof ViewGroup)) return;
                ViewGroup viewGroup = (ViewGroup) contentView;

                Object fMessageObj = findFMessageArgument(param.args);
                if (fMessageObj == null) return;

                FMessageWpp fMessage = new FMessageWpp(fMessageObj);
                String messageText = fMessage.getMessageStr();
                if (messageText == null || messageText.isEmpty()) return;

                int trayId = Utils.getID("reactions_tray_layout", "id");
                if (trayId <= 0) return;
                View trayView = viewGroup.findViewById(trayId);
                if (!(trayView instanceof LinearLayout)) return;
                LinearLayout layout = (LinearLayout) trayView;

                MaterialButton copyButton = buildActionPill(activity);
                copyButton.setOnClickListener(v -> {
                    try {
                        mainPopupWindow.dismiss();
                    } catch (Throwable ignored) {
                    }
                    showSelectionDialog(activity, messageText);
                });

                int childCount = layout.getChildCount();
                View[] originalItems = new View[childCount];
                for (int i = 0; i < childCount; i++) {
                    originalItems[i] = layout.getChildAt(i);
                }

                layout.removeAllViews();
                layout.setOrientation(LinearLayout.VERTICAL);

                LinearLayout originalRow = new LinearLayout(viewGroup.getContext());
                originalRow.setOrientation(LinearLayout.HORIZONTAL);
                for (View item : originalItems) {
                    originalRow.addView(item);
                }
                layout.addView(originalRow);
                layout.addView(copyButton);
                XposedHelpers.setAdditionalInstanceField(mainPopupWindow, INJECTED_FIELD, true);
            }
        });
    }

    private Object findFMessageArgument(Object[] args) {
        if (args == null || FMessageWpp.TYPE == null) return null;
        for (Object arg : args) {
            if (arg != null && FMessageWpp.TYPE.isInstance(arg)) {
                return arg;
            }
        }
        return null;
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
        MaterialButton button = new MaterialButton(
                ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(ctx.getString(R.string.copy_selection_action));
        button.setTextColor(textColor);
        button.setStrokeColor(ColorStateList.valueOf(strokeColor));
        button.setCornerRadius((int) Utils.dipToPixels(50f));
        return button;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void showSelectionDialog(Activity activity, CharSequence messageText) {
        float density = activity.getResources().getDisplayMetrics().density;
        ModuleContextWrapper ctx = new ModuleContextWrapper(activity);

        TextInputLayout textInputLayout = new TextInputLayout(
                ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        textInputLayout.setCounterEnabled(true);
        textInputLayout.setHint(ctx.getString(R.string.message));

        TextInputEditText editText = new TextInputEditText(textInputLayout.getContext());
        editText.setText(messageText);
        editText.setTextSize(14f);
        editText.setMinLines(3);
        editText.setMaxLines(10);
        editText.setGravity(Gravity.TOP);
        editText.setLineSpacing(0f, 1.4f);
        editText.setVerticalScrollBarEnabled(true);
        editText.setSelectAllOnFocus(false);
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

        MaterialButton copySelectedButton = buildDialogButton(
                ctx, ctx.getString(R.string.copy_selection_action), textColor, outlineColor);
        MaterialButton closeButton = buildDialogButton(
                ctx, ctx.getString(R.string.close), textColor, outlineColor);

        LinearLayout actions = new LinearLayout(ctx);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        actionParams.topMargin = (int) (12 * density);
        actionParams.setMarginStart((int) (8 * density));
        actions.addView(copySelectedButton, actionParams);
        actions.addView(closeButton, actionParams);

        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(
                (int) (16 * density),
                (int) (8 * density),
                (int) (16 * density),
                (int) (16 * density));
        container.addView(scrollView);
        container.addView(actions);

        AlertDialogWpp dialog = new AlertDialogWpp(activity);
        dialog.setTitle(ctx.getString(R.string.copy_selection_dialog_title));
        dialog.setView(container);
        DialogHolder holder = new DialogHolder(dialog.create());

        copySelectedButton.setOnClickListener(v -> {
            int start = editText.getSelectionStart();
            int end = editText.getSelectionEnd();
            if (start < 0 || end < 0 || start == end || editText.getText() == null) {
                Utils.showToast("Select text first", Toast.LENGTH_SHORT);
                return;
            }
            int from = Math.min(start, end);
            int to = Math.max(start, end);
            CharSequence selected = editText.getText().subSequence(from, to);
            if (selected.length() == 0) return;
            Utils.setToClipboard(selected.toString());
            holder.dialog.dismiss();
        });
        closeButton.setOnClickListener(v -> holder.dialog.dismiss());
        holder.dialog.show();
    }

    private MaterialButton buildDialogButton(ModuleContextWrapper ctx, String text,
                                             int textColor, int outlineColor) {
        MaterialButton button = new MaterialButton(
                ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(text);
        button.setTextColor(textColor);
        button.setStrokeColor(ColorStateList.valueOf(outlineColor));
        button.setCornerRadius((int) Utils.dipToPixels(50f));
        return button;
    }

    private static final class DialogHolder {
        final Dialog dialog;

        DialogHolder(Dialog dialog) {
            this.dialog = dialog;
        }
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Copy Selection Message";
    }
}
