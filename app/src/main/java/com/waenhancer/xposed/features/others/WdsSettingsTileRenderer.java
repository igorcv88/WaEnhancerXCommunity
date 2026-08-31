package com.waenhancer.xposed.features.others;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.waenhancer.xposed.core.components.AlertDialogWpp;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WdsSettingsTileRenderer {

    public interface PrefChangeListener {
        void onPrefChanged(String key, Object newValue);
    }

    public static JSONObject loadSettingsMap(Context context) {
        try {
            android.content.res.Resources res = com.waenhancer.xposed.utils.XResManager.moduleResources;
            if (res == null) res = context.getResources();
            int resId = res.getIdentifier("waex_settings_map", "raw",
                    com.waenhancer.BuildConfig.APPLICATION_ID);
            if (resId == 0) {
                resId = res.getIdentifier("waex_settings_map", "raw", context.getPackageName());
            }
            if (resId == 0) return null;
            InputStream is = res.openRawResource(resId);
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            return new JSONObject(new String(buffer, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    public static String resolveString(Context context, String str) {
        if (str == null) return "";
        if (str.startsWith("@string/")) {
            try {
                String name = str.substring(8);
                android.content.res.Resources res = com.waenhancer.xposed.utils.XResManager.moduleResources;
                int id = 0;
                if (res != null) {
                    id = res.getIdentifier(name, "string", com.waenhancer.BuildConfig.APPLICATION_ID);
                }
                if (id == 0) {
                    res = context.getResources();
                    id = res.getIdentifier(name, "string", context.getPackageName());
                }
                if (id == 0) {
                    id = res.getIdentifier(name, "string", com.waenhancer.BuildConfig.APPLICATION_ID);
                }
                if (id != 0) {
                    return res.getString(id);
                }
            } catch (Throwable ignored) {}
        }
        return str;
    }

    public static View buildCategoryList(Activity activity, JSONObject settingsMap, SharedPreferences prefs, PrefChangeListener listener) {
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setTag("WaEnhancerX Settings");

        float density = activity.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        container.setPadding(0, pad, 0, pad);

        try {
            JSONArray categories = settingsMap.getJSONArray("categories");
            for (int i = 0; i < categories.length(); i++) {
                JSONObject cat = categories.getJSONObject(i);
                String id = cat.getString("id");
                String title = cat.getString("title");
                String summary = cat.optString("summary", "");

                String iconName = SettingsIconRegistry.iconName(
                        id, cat.optString("icon", ""));
                android.graphics.drawable.Drawable icon =
                        SettingsIconRegistry.resolve(activity, id, iconName);
                if (icon == null) {
                    de.robv.android.xposed.XposedBridge.log(
                            "[WAEX] No drawable resolved for settings category '" + id
                                    + "' (icon name: " + iconName + ")");
                }

                View row = createWdsRow(activity, title, summary, icon, iconName, v -> {
                    if ("optimization".equals(id)) {
                        try {
                            Class<?> aboutClass = com.waenhancer.xposed.core.WppCore.getAboutActivityClass(activity.getClassLoader());
                            if (aboutClass != null) {
                                android.content.Intent intent = new android.content.Intent(activity, aboutClass);
                                intent.putExtra("wae_optimize_db", true);
                                activity.startActivity(intent);
                            }
                        } catch (Throwable t) {
                            de.robv.android.xposed.XposedBridge.log("[WAEX] Failed to start optimization from settings: " + t.getMessage());
                        }
                    } else {
                        android.content.Intent intent = new android.content.Intent(activity, activity.getClass());
                        intent.putExtra("waex_screen_id", id);
                        activity.startActivity(intent);
                    }
                });
                container.addView(row);
            }
        } catch (Exception ignored) {}

        ScrollView scrollView = new ScrollView(activity);
        scrollView.addView(container);
        return scrollView;
    }

    public static View buildSubScreenById(Activity activity, JSONObject settingsMap, String catId, SharedPreferences prefs, PrefChangeListener listener) {
        try {
            JSONArray categories = settingsMap.getJSONArray("categories");
            for (int i = 0; i < categories.length(); i++) {
                JSONObject cat = categories.getJSONObject(i);
                if (cat.getString("id").equals(catId)) {
                    return buildSubScreen(activity, cat, prefs, listener);
                }

                JSONArray subScreens = cat.optJSONArray("sub_screens");
                if (subScreens != null) {
                    for (int j = 0; j < subScreens.length(); j++) {
                        JSONObject sub = subScreens.getJSONObject(j);
                        if (sub.getString("id").equals(catId)) {
                            return buildSingleSubScreen(activity, sub, prefs, listener);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static View buildSingleSubScreen(Activity activity, JSONObject sub, SharedPreferences prefs, PrefChangeListener listener) {
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        String subTitle = sub.optString("title", "Settings");
        container.setTag(subTitle);

        float density = activity.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        container.setPadding(0, pad, 0, pad);

        try {
            JSONArray prefsArray = sub.getJSONArray("prefs");
            renderPrefsArray(activity, container, prefsArray, prefs, listener);
        } catch (Exception ignored) {}

        ScrollView scrollView = new ScrollView(activity);
        scrollView.addView(container);
        return scrollView;
    }

    private static View buildSubScreen(Activity activity, JSONObject category, SharedPreferences prefs, PrefChangeListener listener) {
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        String catTitle = category.optString("title", "Settings");
        container.setTag(catTitle);

        float density = activity.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        container.setPadding(0, pad, 0, pad);

        try {
            JSONArray subScreens = category.getJSONArray("sub_screens");

            // Add Category tiles for the remaining sub-screens at the TOP
            for (int i = 1; i < subScreens.length(); i++) {
                JSONObject sub = subScreens.getJSONObject(i);
                String subId = sub.getString("id");
                String subTitle = sub.getString("title");
                String subSummary = sub.optString("summary", "Customize " + subTitle + " settings");

                android.graphics.drawable.Drawable icon = null;
                String iconName = "";
                if ("home_screen_main".equals(subId)) {
                    iconName = "ic_home_black_24dp";
                } else if ("conversation_main".equals(subId)) {
                    iconName = "ic_home_tab_chats_unfilled";
                }

                if (!iconName.isEmpty()) {
                    icon = com.waenhancer.xposed.utils.DesignUtils.getDrawableByName(iconName);
                }
                if (icon == null) {
                    // Deliberately the _solid twin: ic_chevron_right tints from a theme attribute,
                    // which does not resolve in the WhatsApp process and draws blank.
                    icon = com.waenhancer.xposed.utils.DesignUtils.getDrawableByName("ic_chevron_right_solid");
                }

                View catTile = createWdsRow(activity, subTitle, subSummary, icon, iconName, v -> {
                    android.content.Intent intent = new android.content.Intent(activity, activity.getClass());
                    intent.putExtra("waex_screen_id", subId);
                    activity.startActivity(intent);
                });
                container.addView(catTile);
            }

            if (subScreens.length() > 1) {
                View divider = new View(activity);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (1 * density));
                lp.setMargins(0, (int) (16 * density), 0, (int) (16 * density));
                divider.setLayoutParams(lp);
                divider.setBackgroundColor(0xFF222d34);
                container.addView(divider);
            }

            // Render the first main sub-screen (general_main)
            if (subScreens.length() > 0) {
                JSONObject mainSub = subScreens.getJSONObject(0);
                JSONArray prefsArray = mainSub.getJSONArray("prefs");
                renderPrefsArray(activity, container, prefsArray, prefs, listener);
            }
        } catch (Exception ignored) {}

        ScrollView scrollView = new ScrollView(activity);
        scrollView.addView(container);
        return scrollView;
    }

    private static void renderPrefsArray(Context context, LinearLayout container, JSONArray prefsArray, SharedPreferences prefs, PrefChangeListener listener) {
        Map<String, View> tileViews = new HashMap<>();

        for (int j = 0; j < prefsArray.length(); j++) {
            // Per-entry isolation: this loop used to sit inside one big try/catch, so a single
            // malformed or mistyped preference (reading a StringSet as a boolean, for instance)
            // aborted the whole screen and every row below it silently vanished.
            String key = "?";
            try {
                JSONObject pref = prefsArray.getJSONObject(j);
                String type = pref.getString("type");
                key = pref.getString("key");
                String title = pref.getString("title");
                boolean isEnabled = pref.optBoolean("enabled", true);
                if (!isEnabled) {
                    title = title + " [Unavailable]";
                }
                String summary = pref.optString("summary", "");

                View tile;
                if (!isEnabled) {
                    final String displayTitle = title;
                    tile = createWdsRow(context, title, summary, null, v -> {
                        try {
                            AlertDialogWpp builder = new AlertDialogWpp(context);
                            builder.asBottomSheet();
                            builder.setTitle(displayTitle);
                            builder.setMessage("This feature is under development and will be available in the future updates. Stay tuned.");
                            builder.setPositiveButton("Dismiss", null);
                            builder.show();
                        } catch (Throwable t) {
                            de.robv.android.xposed.XposedBridge.log("[WAEX] Failed to show unavailable-feature sheet: " + t.getMessage());
                        }
                    });
                } else {
                    switch (type) {
                        case "switch": {
                            boolean def = pref.optBoolean("default", false);
                            tile = createSwitchTile(context, key, title, summary, def, prefs, listener, tileViews, prefsArray);
                            break;
                        }
                        case "list":
                            tile = createListTile(context, pref, prefs, listener);
                            break;
                        case "multi":
                            tile = createMultiTile(context, pref, prefs, listener);
                            break;
                        case "text":
                            tile = createTextTile(context, pref, prefs, listener);
                            break;
                        case "slider":
                            tile = createSliderTile(context, pref, prefs, listener);
                            break;
                        case "color":
                            tile = createColorTile(context, pref, prefs, listener);
                            break;
                        case "action":
                            tile = createActionTile(context, pref);
                            break;
                        // These need a picker the host process cannot host safely: the module's
                        // picker activities are deliberately not exported. Send the user to the
                        // module app instead of drawing a row that does nothing when tapped.
                        case "file":
                        case "contact_picker":
                        case "forward_rules":
                        case "theme_manager":
                            tile = createOpenInModuleTile(context, pref, prefs);
                            break;
                        default:
                            de.robv.android.xposed.XposedBridge.log(
                                    "[WAEX] Unknown settings tile type '" + type + "' for key " + key);
                            tile = createOpenInModuleTile(context, pref, prefs);
                            break;
                    }
                }

                if (tile != null) {
                    tileViews.put(key, tile);
                    container.addView(tile);
                }
            } catch (Exception e) {
                de.robv.android.xposed.XposedBridge.log(
                        "[WAEX] Skipped settings tile '" + key + "': " + e);
            }
        }

        checkDependencies(prefsArray, prefs, tileViews);
    }

    private static View createWdsRow(Context context, String title, String summary, android.graphics.drawable.Drawable icon, View.OnClickListener clickListener) {
        return createWdsRow(context, title, summary, icon, null, clickListener);
    }

    private static View createWdsRow(Context context, String title, String summary, android.graphics.drawable.Drawable icon, String iconName, View.OnClickListener clickListener) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        float density = context.getResources().getDisplayMetrics().density;
        row.setPadding((int) (24 * density), (int) (12 * density), (int) (24 * density), (int) (12 * density));

        TypedValue outValue = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        row.setBackgroundResource(outValue.resourceId);

        // Resolve theme colors dynamically
        boolean isDarkMode = false;
        try {
            int nightModeFlags = context.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            isDarkMode = nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Exception ignored) {}
        int primaryTextColor = isDarkMode ? 0xFFe9edef : 0xFF111B21;
        int secondaryTextColor = isDarkMode ? 0xFF8696a0 : 0xFF667781;
        try {
            TypedValue typedValue = new TypedValue();
            if (context.getTheme().resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)) {
                primaryTextColor = typedValue.data;
            }
            if (context.getTheme().resolveAttribute(android.R.attr.textColorSecondary, typedValue, true)) {
                secondaryTextColor = typedValue.data;
            }
        } catch (Exception ignored) {}

        title = resolveString(context, title);
        summary = resolveString(context, summary);

        if (icon != null) {
            ImageView iconView = new ImageView(context);
            int iconSizeDp = 24;
            int marginEndDp = 20;
            if ("ic_home_tab_status_unfilled".equals(iconName)) {
                iconSizeDp = 28; // Make status icon slightly larger to balance visual weight
                marginEndDp = 16; // Keep the total spacing (iconSizeDp + marginEndDp = 44dp) constant for alignment
            }
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams((int) (iconSizeDp * density), (int) (iconSizeDp * density));
            iconParams.setMarginEnd((int) (marginEndDp * density));
            iconView.setLayoutParams(iconParams);
            iconView.setImageDrawable(icon);
            iconView.setImageTintList(android.content.res.ColorStateList.valueOf(secondaryTextColor));
            row.addView(iconView);
        }

        LinearLayout textLayout = new LinearLayout(context);
        textLayout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        textLayout.setLayoutParams(textParams);

        TextView titleView = createWdsTextView(context);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleView.setTextColor(primaryTextColor);
        textLayout.addView(titleView);

        if (!TextUtils.isEmpty(summary)) {
            TextView summaryView = createWdsTextView(context);
            summaryView.setText(summary);
            summaryView.setTag("wds_summary");
            summaryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            summaryView.setTextColor(secondaryTextColor);
            summaryView.setPadding(0, (int) (4 * density), 0, 0);
            textLayout.addView(summaryView);
        }
        row.addView(textLayout);

        if (clickListener != null) {
            row.setOnClickListener(clickListener);
            row.setClickable(true);
            row.setFocusable(true);
        }

        return row;
    }

    private static View createSwitchTile(Context context, String key, String title, String summary, boolean defVal, SharedPreferences prefs, PrefChangeListener listener, Map<String, View> tileViews, JSONArray prefsArray) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        float density = context.getResources().getDisplayMetrics().density;
        row.setPadding((int) (24 * density), (int) (12 * density), (int) (24 * density), (int) (12 * density));

        TypedValue outValue = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        row.setBackgroundResource(outValue.resourceId);

        // Resolve theme colors dynamically
        boolean isDarkMode = false;
        try {
            int nightModeFlags = context.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            isDarkMode = nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Exception ignored) {}
        int primaryTextColor = isDarkMode ? 0xFFe9edef : 0xFF111B21;
        int secondaryTextColor = isDarkMode ? 0xFF8696a0 : 0xFF667781;
        try {
            TypedValue typedValue = new TypedValue();
            if (context.getTheme().resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)) {
                primaryTextColor = typedValue.data;
            }
            if (context.getTheme().resolveAttribute(android.R.attr.textColorSecondary, typedValue, true)) {
                secondaryTextColor = typedValue.data;
            }
        } catch (Exception ignored) {}

        LinearLayout textLayout = new LinearLayout(context);
        textLayout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        textLayout.setLayoutParams(lp);

        TextView titleView = createWdsTextView(context);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleView.setTextColor(primaryTextColor);
        textLayout.addView(titleView);

        if (!TextUtils.isEmpty(summary)) {
            TextView summaryView = createWdsTextView(context);
            summaryView.setText(summary);
            summaryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            summaryView.setTextColor(secondaryTextColor);
            summaryView.setPadding(0, (int) (4 * density), 0, 0);
            textLayout.addView(summaryView);
        }
        row.addView(textLayout);

        View wdsSwitch = createWdsSwitch(context);

        boolean currentVal = prefs.getBoolean(key, defVal);
        setSwitchChecked(wdsSwitch, currentVal);

        final View finalSwitch = wdsSwitch;
        row.setOnClickListener(v -> {
            boolean newVal = !getSwitchChecked(finalSwitch);
            setSwitchChecked(finalSwitch, newVal);
            prefs.edit().putBoolean(key, newVal).apply();
            if (listener != null) listener.onPrefChanged(key, newVal);
            checkDependencies(prefsArray, prefs, tileViews);
        });

        row.addView(wdsSwitch);
        return row;
    }

    private static View createListTile(Context context, JSONObject pref, SharedPreferences prefs, PrefChangeListener listener) {
        try {
            String key = pref.getString("key");
            String title = resolveString(context, pref.getString("title"));
            String summary = resolveString(context, pref.optString("summary", ""));
            String valueType = pref.optString("value_type", "string");
            JSONArray entriesJson = pref.getJSONArray("entries");

            String[] entries = new String[entriesJson.length()];
            String[] values = new String[entriesJson.length()];
            for (int i = 0; i < entriesJson.length(); i++) {
                JSONObject entryObj = entriesJson.getJSONObject(i);
                entries[i] = resolveString(context, entryObj.getString("label"));
                values[i] = String.valueOf(entryObj.get("value"));
            }

            int initialSelectedIndex = 0;
            if ("int".equals(valueType)) {
                int defaultVal = pref.optInt("default", 0);
                int current = prefs.getInt(key, defaultVal);
                for (int i = 0; i < values.length; i++) {
                    try {
                        if (Integer.parseInt(values[i]) == current) {
                            initialSelectedIndex = i;
                            break;
                        }
                    } catch (Exception ignored) {}
                }
            } else if ("boolean".equals(valueType)) {
                boolean defaultVal = pref.optBoolean("default", false);
                boolean current = prefs.getBoolean(key, defaultVal);
                for (int i = 0; i < values.length; i++) {
                    if (Boolean.parseBoolean(values[i]) == current) {
                        initialSelectedIndex = i;
                        break;
                    }
                }
            } else {
                String defaultVal = pref.optString("default", "");
                String current = prefs.getString(key, defaultVal);
                for (int i = 0; i < values.length; i++) {
                    if (values[i].equals(current)) {
                        initialSelectedIndex = i;
                        break;
                    }
                }
            }

            String currentLabel = initialSelectedIndex < entries.length ? entries[initialSelectedIndex] : "";
            String displaySummary = summary;
            if (displaySummary.contains("%s")) {
                displaySummary = displaySummary.replace("%s", currentLabel);
            } else if (displaySummary.isEmpty()) {
                displaySummary = currentLabel;
            }

            final String rawSummary = summary;
            final int finalInitialSelectedIndex = initialSelectedIndex;
            final View[] rowHolder = new View[1];

            rowHolder[0] = createWdsRow(context, title, displaySummary, null, v -> {
                int selectedIndex = 0;
                if ("int".equals(valueType)) {
                    int defaultVal = pref.optInt("default", 0);
                    int current = prefs.getInt(key, defaultVal);
                    for (int i = 0; i < values.length; i++) {
                        try {
                            if (Integer.parseInt(values[i]) == current) {
                                selectedIndex = i;
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                } else if ("boolean".equals(valueType)) {
                    boolean defaultVal = pref.optBoolean("default", false);
                    boolean current = prefs.getBoolean(key, defaultVal);
                    for (int i = 0; i < values.length; i++) {
                        if (Boolean.parseBoolean(values[i]) == current) {
                            selectedIndex = i;
                            break;
                        }
                    }
                } else {
                    String defaultVal = pref.optString("default", "");
                    String current = prefs.getString(key, defaultVal);
                    for (int i = 0; i < values.length; i++) {
                        if (values[i].equals(current)) {
                            selectedIndex = i;
                            break;
                        }
                    }
                }

                AlertDialogWpp builder = new AlertDialogWpp(context);
                builder.setTitle(title);
                builder.setSingleChoiceItems(entries, selectedIndex, (dialog, which) -> {
                    String selectedVal = values[which];
                    String selectedLabel = entries[which];
                    if ("int".equals(valueType)) {
                        int intVal = Integer.parseInt(selectedVal);
                        prefs.edit().putInt(key, intVal).apply();
                        if (listener != null) listener.onPrefChanged(key, intVal);
                    } else if ("boolean".equals(valueType)) {
                        boolean boolVal = Boolean.parseBoolean(selectedVal);
                        prefs.edit().putBoolean(key, boolVal).apply();
                        if (listener != null) listener.onPrefChanged(key, boolVal);
                    } else {
                        prefs.edit().putString(key, selectedVal).apply();
                        if (listener != null) listener.onPrefChanged(key, selectedVal);
                    }

                    // Dynamically update the summary text view on selection
                    try {
                        TextView summaryView = rowHolder[0].findViewWithTag("wds_summary");
                        if (summaryView != null) {
                            String newSummary = rawSummary;
                            if (newSummary.contains("%s")) {
                                newSummary = newSummary.replace("%s", selectedLabel);
                            } else if (newSummary.isEmpty()) {
                                newSummary = selectedLabel;
                            }
                            summaryView.setText(newSummary);
                        }
                    } catch (Exception ignored) {}

                    dialog.dismiss();
                });
                builder.setNegativeButton("Cancel", null);
                builder.show();
            });

            return rowHolder[0];
        } catch (Exception e) {
            return null;
        }
    }

    private static View createMultiTile(Context context, JSONObject pref, SharedPreferences prefs, PrefChangeListener listener) {
        try {
            String key = pref.getString("key");
            String title = resolveString(context, pref.getString("title"));
            String summary = resolveString(context, pref.optString("summary", ""));
            // Whether the feature reads a Set<String> or a comma-joined String. Writing the wrong
            // one is invisible in the UI and silently disables the feature: `hidetabs` is read by
            // HideTabs as a StringSet, but this tile used to always write a String.
            boolean asStringSet = "string_set".equals(pref.optString("value_type", "string"));
            JSONArray entriesJson = pref.getJSONArray("entries");

            String[] entries = new String[entriesJson.length()];
            String[] values = new String[entriesJson.length()];
            for (int i = 0; i < entriesJson.length(); i++) {
                JSONObject entryObj = entriesJson.getJSONObject(i);
                entries[i] = resolveString(context, entryObj.getString("label"));
                values[i] = String.valueOf(entryObj.get("value"));
            }

            final View[] rowHolder = new View[1];
            rowHolder[0] = createWdsRow(context, title,
                    multiSummary(summary, entries, selectedFlags(prefs, key, values, asStringSet)),
                    null, v -> {
                boolean[] checkedStates = selectedFlags(prefs, key, values, asStringSet);

                AlertDialogWpp builder = new AlertDialogWpp(context);
                builder.setTitle(title);
                builder.setMultiChoiceItems(entries, checkedStates, (dialog, which, isChecked) ->
                        checkedStates[which] = isChecked);
                builder.setPositiveButton("OK", (dialog, which) -> {
                    if (asStringSet) {
                        java.util.Set<String> selected = new java.util.LinkedHashSet<>();
                        for (int i = 0; i < values.length; i++) {
                            if (checkedStates[i]) selected.add(values[i]);
                        }
                        prefs.edit().putStringSet(key, selected).apply();
                        if (listener != null) listener.onPrefChanged(key, selected);
                    } else {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < values.length; i++) {
                            if (checkedStates[i]) {
                                if (sb.length() > 0) sb.append(",");
                                sb.append(values[i]);
                            }
                        }
                        String result = sb.toString();
                        prefs.edit().putString(key, result).apply();
                        if (listener != null) listener.onPrefChanged(key, result);
                    }
                    updateSummary(rowHolder[0], multiSummary(summary, entries, checkedStates));
                });
                builder.setNegativeButton("Cancel", null);
                builder.show();
            });
            return rowHolder[0];
        } catch (Exception e) {
            return null;
        }
    }

    /** Reads the stored selection in whichever shape the feature expects, never throwing. */
    private static boolean[] selectedFlags(SharedPreferences prefs, String key, String[] values,
                                           boolean asStringSet) {
        boolean[] flags = new boolean[values.length];
        try {
            if (asStringSet) {
                java.util.Set<String> stored = prefs.getStringSet(key, null);
                if (stored == null) return flags;
                for (int i = 0; i < values.length; i++) flags[i] = stored.contains(values[i]);
            } else {
                String stored = prefs.getString(key, "");
                if (stored == null || stored.isEmpty()) return flags;
                java.util.List<String> parts = java.util.Arrays.asList(stored.split(","));
                for (int i = 0; i < values.length; i++) flags[i] = parts.contains(values[i]);
            }
        } catch (Exception e) {
            de.robv.android.xposed.XposedBridge.log(
                    "[WAEX] Could not read multi-select '" + key + "': " + e);
        }
        return flags;
    }

    private static String multiSummary(String summary, String[] entries, boolean[] checked) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.length && i < checked.length; i++) {
            if (!checked[i]) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(entries[i]);
        }
        if (sb.length() == 0) return summary.isEmpty() ? "None selected" : summary;
        return sb.toString();
    }

    private static void updateSummary(View row, String text) {
        try {
            TextView summaryView = row.findViewWithTag("wds_summary");
            if (summaryView != null) summaryView.setText(text);
        } catch (Exception ignored) {}
    }

    /**
     * Numeric slider in a dialog. Ranges come from the settings map; without them the tile used to
     * fall through to an inert text row (this is what "Increase Video Size Limit (MB)" was).
     */
    private static View createSliderTile(Context context, JSONObject pref, SharedPreferences prefs, PrefChangeListener listener) {
        try {
            String key = pref.getString("key");
            String title = resolveString(context, pref.getString("title"));
            String summary = resolveString(context, pref.optString("summary", ""));
            float min = (float) pref.optDouble("min", 0d);
            float max = (float) pref.optDouble("max", 100d);
            float step = (float) pref.optDouble("step", 1d);
            float def = (float) pref.optDouble("default", min);
            if (max <= min) max = min + 1f;
            if (step <= 0f) step = 1f;

            final View[] rowHolder = new View[1];
            final float fMin = min, fMax = max, fStep = step, fDef = def;

            rowHolder[0] = createWdsRow(context, title,
                    sliderSummary(summary, readFloat(prefs, key, fDef)), null, v -> {
                float current = clamp(readFloat(prefs, key, fDef), fMin, fMax);
                float density = context.getResources().getDisplayMetrics().density;

                // A framework SeekBar on purpose: Material's Slider needs a Material theme, and
                // this dialog is built against WhatsApp's activity, which does not provide one.
                int steps = Math.max(1, Math.round((fMax - fMin) / fStep));
                android.widget.SeekBar seekBar = new android.widget.SeekBar(context);
                seekBar.setMax(steps);
                seekBar.setProgress(Math.round((current - fMin) / fStep));

                TextView valueLabel = createWdsTextView(context);
                valueLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                valueLabel.setGravity(Gravity.CENTER);
                valueLabel.setText(formatValue(fMin + seekBar.getProgress() * fStep));
                seekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean fromUser) {
                        valueLabel.setText(formatValue(fMin + progress * fStep));
                    }

                    @Override
                    public void onStartTrackingTouch(android.widget.SeekBar bar) {}

                    @Override
                    public void onStopTrackingTouch(android.widget.SeekBar bar) {}
                });

                LinearLayout box = new LinearLayout(context);
                box.setOrientation(LinearLayout.VERTICAL);
                int margin = (int) (24 * density);
                box.setPadding(margin, margin / 2, margin, margin / 2);
                box.addView(valueLabel);
                box.addView(seekBar);

                AlertDialogWpp builder = new AlertDialogWpp(context);
                builder.setTitle(title);
                builder.setView(box);
                builder.setPositiveButton("Save", (dialog, which) -> {
                    float value = clamp(fMin + seekBar.getProgress() * fStep, fMin, fMax);
                    prefs.edit().putFloat(key, value).apply();
                    if (listener != null) listener.onPrefChanged(key, value);
                    updateSummary(rowHolder[0], sliderSummary(summary, value));
                });
                builder.setNegativeButton("Cancel", null);
                builder.show();
            });
            return rowHolder[0];
        } catch (Exception e) {
            return null;
        }
    }

    /** Hex colour entry. {@code #00000000} means "use the theme default", as the summaries say. */
    private static View createColorTile(Context context, JSONObject pref, SharedPreferences prefs, PrefChangeListener listener) {
        try {
            String key = pref.getString("key");
            String title = resolveString(context, pref.getString("title"));
            String summary = resolveString(context, pref.optString("summary", ""));

            final View[] rowHolder = new View[1];
            rowHolder[0] = createWdsRow(context, title,
                    colorSummary(summary, readInt(prefs, key, 0)), null, v -> {
                float density = context.getResources().getDisplayMetrics().density;
                EditText input = new EditText(context);
                input.setSingleLine(true);
                input.setText(formatColor(readInt(prefs, key, 0)));
                input.setTextColor(0xFFE9EDEF);
                int margin = (int) (24 * density);

                LinearLayout box = new LinearLayout(context);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(margin, margin / 2, margin, margin / 2);
                box.addView(input);

                AlertDialogWpp builder = new AlertDialogWpp(context);
                builder.setTitle(title);
                builder.setView(box);
                builder.setPositiveButton("Save", (dialog, which) -> {
                    Integer parsed = parseColorValue(input.getText().toString());
                    if (parsed == null) {
                        com.waenhancer.xposed.utils.Utils.showToast(
                                "Use #RRGGBB or #AARRGGBB", android.widget.Toast.LENGTH_SHORT);
                        return;
                    }
                    prefs.edit().putInt(key, parsed).apply();
                    if (listener != null) listener.onPrefChanged(key, parsed);
                    updateSummary(rowHolder[0], colorSummary(summary, parsed));
                });
                builder.setNegativeButton("Cancel", null);
                builder.show();
            });
            return rowHolder[0];
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A row for settings whose editor lives in the module app — file pickers, contact pickers, the
     * theme manager. Those activities are intentionally not exported, so the host process cannot
     * launch them directly; opening the module is the honest alternative to an inert row.
     */
    private static View createOpenInModuleTile(Context context, JSONObject pref, SharedPreferences prefs) {
        try {
            String key = pref.getString("key");
            String title = resolveString(context, pref.getString("title"));
            String summary = resolveString(context, pref.optString("summary", ""));
            String current = "";
            try {
                Object stored = prefs.getAll().get(key);
                if (stored instanceof String && !((String) stored).isEmpty()) {
                    current = (String) stored;
                }
            } catch (Exception ignored) {}
            String shown = !current.isEmpty() ? current
                    : (summary.isEmpty() ? "Set this in the WaEnhancerX app" : summary);

            return createWdsRow(context, title, shown, null, v -> {
                try {
                    com.waenhancer.xposed.utils.Utils.openModule(context);
                } catch (Throwable t) {
                    de.robv.android.xposed.XposedBridge.log(
                            "[WAEX] Failed to open the module app: " + t.getMessage());
                }
            });
        } catch (Exception e) {
            return null;
        }
    }

    private static float readFloat(SharedPreferences prefs, String key, float fallback) {
        try {
            Object raw = prefs.getAll().get(key);
            if (raw instanceof Number) return ((Number) raw).floatValue();
            if (raw instanceof String) return Float.parseFloat((String) raw);
        } catch (Exception ignored) {}
        return fallback;
    }

    private static int readInt(SharedPreferences prefs, String key, int fallback) {
        try {
            Object raw = prefs.getAll().get(key);
            if (raw instanceof Number) return ((Number) raw).intValue();
            if (raw instanceof String) {
                Integer parsed = parseColorValue((String) raw);
                if (parsed != null) return parsed;
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String formatValue(float value) {
        return Math.abs(value - Math.round(value)) < 0.001f
                ? Integer.toString(Math.round(value))
                : String.format(java.util.Locale.US, "%.1f", value);
    }

    private static String sliderSummary(String summary, float value) {
        String shown = formatValue(value);
        if (summary.contains("%s")) return summary.replace("%s", shown);
        return summary.isEmpty() ? shown : summary + " — " + shown;
    }

    private static String colorSummary(String summary, int color) {
        String shown = formatColor(color);
        if (summary.contains("%s")) return summary.replace("%s", shown);
        return summary.isEmpty() ? shown : summary + " — " + shown;
    }

    private static String formatColor(int color) {
        return String.format(java.util.Locale.US, "#%08X", color);
    }

    private static Integer parseColorValue(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return null;
        if ("0".equals(value)) return 0;
        try {
            if (!value.startsWith("#")) value = "#" + value;
            if (value.length() == 7) value = "#FF" + value.substring(1);
            if (value.length() != 9) return null;
            return (int) Long.parseLong(value.substring(1), 16);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static View createTextTile(Context context, JSONObject pref, SharedPreferences prefs, PrefChangeListener listener) {
        try {
            String key = pref.getString("key");
            String title = pref.getString("title");
            String summary = pref.optString("summary", "");
            String valueType = pref.optString("value_type", "string");

            return createWdsRow(context, title, summary, null, v -> {
                AlertDialogWpp builder = new AlertDialogWpp(context);
                builder.setTitle(title);

                float density = context.getResources().getDisplayMetrics().density;
                EditText input = new EditText(context);
                String currentText;
                if ("int".equals(valueType)) {
                    currentText = String.valueOf(prefs.getInt(key, pref.optInt("default", 0)));
                } else {
                    currentText = prefs.getString(key, pref.optString("default", ""));
                }
                input.setText(currentText);
                input.setTextColor(0xFFE9EDEF);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                int margin = (int) (24 * density);
                lp.setMargins(margin, margin / 2, margin, margin / 2);
                input.setLayoutParams(lp);

                LinearLayout container = new LinearLayout(context);
                container.setOrientation(LinearLayout.VERTICAL);
                container.addView(input);
                builder.setView(container);

                builder.setPositiveButton("Save", (dialog, which) -> {
                    String newVal = input.getText().toString();
                    if ("int".equals(valueType)) {
                        int intVal = 0;
                        try {
                            intVal = Integer.parseInt(newVal);
                        } catch (Exception ignored) {}
                        prefs.edit().putInt(key, intVal).apply();
                        if (listener != null) listener.onPrefChanged(key, intVal);
                    } else {
                        prefs.edit().putString(key, newVal).apply();
                        if (listener != null) listener.onPrefChanged(key, newVal);
                    }
                });
                builder.setNegativeButton("Cancel", null);
                builder.show();
            });
        } catch (Exception e) {
            return null;
        }
    }

    /** Rows that navigate somewhere instead of storing a value. */
    private static final Map<String, String> ACTION_TARGETS = new HashMap<>();

    static {
        ACTION_TARGETS.put("open_deleted_messages", "com.waenhancer.activities.DeletedMessagesActivity");
        ACTION_TARGETS.put("call_recording_manage", "com.waenhancer.activities.RecordingsActivity");
    }

    private static View createActionTile(Context context, JSONObject pref) {
        try {
            String key = pref.getString("key");
            String title = resolveString(context, pref.getString("title"));
            String summary = resolveString(context, pref.optString("summary", ""));

            return createWdsRow(context, title, summary, null, v -> {
                String target = ACTION_TARGETS.get(key);
                // Only activities the manifest exports can be started from WhatsApp's process.
                // Everything else goes through the module app, which is allowed to open them.
                if (target == null) {
                    try {
                        com.waenhancer.xposed.utils.Utils.openModule(context);
                    } catch (Throwable t) {
                        de.robv.android.xposed.XposedBridge.log(
                                "[WAEX] Failed to open the module app for '" + key + "': " + t.getMessage());
                    }
                    return;
                }
                try {
                    android.content.Intent intent = new android.content.Intent();
                    intent.setClassName(com.waenhancer.BuildConfig.APPLICATION_ID, target);
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                } catch (Throwable t) {
                    de.robv.android.xposed.XposedBridge.log(
                            "[WAEX] Failed to open '" + target + "': " + t.getMessage());
                    try {
                        com.waenhancer.xposed.utils.Utils.openModule(context);
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Exception e) {
            return null;
        }
    }

    private static void setSwitchChecked(View view, boolean checked) {
        try {
            de.robv.android.xposed.XposedHelpers.callMethod(view, "setChecked", checked);
        } catch (Throwable ignored) {
            if (view instanceof android.widget.CompoundButton) {
                ((android.widget.CompoundButton) view).setChecked(checked);
            }
        }
    }

    private static boolean getSwitchChecked(View view) {
        try {
            return (boolean) de.robv.android.xposed.XposedHelpers.callMethod(view, "isChecked");
        } catch (Throwable ignored) {
            if (view instanceof android.widget.CompoundButton) {
                return ((android.widget.CompoundButton) view).isChecked();
            }
            return false;
        }
    }

    private static void checkDependencies(JSONArray prefsArray, SharedPreferences prefs, Map<String, View> tileViews) {
        try {
            for (int i = 0; i < prefsArray.length(); i++) {
                JSONObject pref = prefsArray.getJSONObject(i);
                String key = pref.getString("key");
                View tile = tileViews.get(key);
                if (tile == null) continue;

                if (pref.has("dep")) {
                    String depKey = pref.getString("dep");
                    boolean depVal = prefs.getBoolean(depKey, false);
                    tile.setVisibility(depVal ? View.VISIBLE : View.GONE);
                }
            }
        } catch (Exception ignored) {}
    }

    private static TextView createWdsTextView(Context context) {
        try {
            Class<?> wdsTvClass = context.getClassLoader().loadClass("com.whatsapp.ui.wds.components.textview.WDSTextView");
            return (TextView) wdsTvClass.getConstructor(Context.class, android.util.AttributeSet.class).newInstance(context, null);
        } catch (Throwable t) {
            return new TextView(context);
        }
    }

    private static View createWdsSwitch(Context context) {
        try {
            Class<?> wdsSwitchClass = context.getClassLoader().loadClass("com.whatsapp.ui.wds.components.toggle.WDSSwitch");
            return (View) wdsSwitchClass.getConstructor(Context.class, android.util.AttributeSet.class).newInstance(context, null);
        } catch (Throwable t) {
            try {
                Class<?> switchClass = Class.forName("X.0xb", true, context.getClassLoader());
                return (View) switchClass.getConstructor(Context.class, android.util.AttributeSet.class).newInstance(context, null);
            } catch (Throwable t2) {
                return new androidx.appcompat.widget.SwitchCompat(context);
            }
        }
    }
}
