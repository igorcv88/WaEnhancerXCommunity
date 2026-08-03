package com.waenhancer.activities;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.waenhancer.BuildConfig;
import com.waenhancer.config.BottomBarPreferenceSchema;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Complete open-source editor for the classic floating bottom bar. */
public class BottomBarCustomizationActivity extends AppCompatActivity {

    private SharedPreferences prefs;
    private LinearLayout controls;
    private LinearLayout previewPill;
    private MaterialButton previewFab;
    private final Map<String, Slider> sliders = new LinkedHashMap<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        BottomBarPreferenceSchema.normalizePersistedValues(prefs);
        setContentView(buildContent());
        refreshPreview();
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle("Floating Bottom Bar");
        toolbar.setNavigationIcon(android.R.drawable.ic_media_previous);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        ScrollView scroll = new ScrollView(this);
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(20), dp(12), dp(20), dp(40));
        scroll.addView(controls, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        addPreview();
        addSection("Core");
        addSwitch("Enable floating bar", "floating_bottom_bar", false);
        addSwitch("Glassmorphism", "floating_bottom_bar_glass", true);
        addColorField("Fill color", "floating_bottom_bar_fill_color", 0);
        addSwitch("Fully rounded", "floating_bottom_bar_fully_rounded", false);
        addSlider("Corner radius", "floating_bottom_bar_radius");
        addSlider("Bottom margin", "floating_bottom_bar_bottom_margin");
        addSlider("Horizontal margin", "floating_bottom_bar_horizontal_margin");
        addSlider("Glass opacity", "floating_bottom_bar_glass_opacity");
        addSlider("Icon size", "floating_bottom_bar_icon_size");
        addSlider("Text size", "floating_bottom_bar_text_size");
        addSlider("Vertical padding", "floating_bottom_bar_padding_vertical");
        addSlider("Icon-label spacing", "floating_bottom_bar_icon_label_spacing");
        addDropdown("Pill height", "floating_bottom_bar_height_mode",
                new String[]{"Automatic", "Manual"}, new String[]{"automatic", "manual"}, "automatic");
        addSlider("Manual pill height", "floating_bottom_bar_manual_height");
        addDropdown("Hide while scrolling", "floating_bottom_bar_scroll_hide_mode",
                new String[]{"Off", "Main tabs", "All scrollable views"},
                new String[]{"off", "tabs", "all"}, "tabs");

        addSection("Floating action button");
        addDropdown("FAB mode", "floating_bottom_bar_fab_mode",
                new String[]{"Default", "Minimal", "Hidden"},
                new String[]{"default", "minimal", "hidden"}, "default");
        addSlider("FAB vertical offset", "floating_bottom_bar_fab_offset");
        addSlider("Minimal FAB size", "floating_bottom_bar_minimal_fab_size");
        addSlider("Minimal FAB radius", "floating_bottom_bar_minimal_fab_radius");
        addSlider("Minimal FAB opacity", "floating_bottom_bar_minimal_fab_opacity");
        addSlider("Minimal FAB side margin", "floating_bottom_bar_minimal_fab_margin");
        addColorField("Minimal FAB background", "floating_bottom_bar_minimal_fab_color", 0);
        addColorField("Minimal FAB icon", "floating_bottom_bar_minimal_fab_icon_color", Color.WHITE);

        addSection("Selected tab indicator");
        addSwitch("Show indicator", "floating_bottom_bar_indicator_visible", false);
        addDropdown("Indicator width", "floating_bottom_bar_indicator_width_mode",
                new String[]{"Automatic", "Manual"}, new String[]{"automatic", "manual"}, "automatic");
        addDropdown("Indicator height", "floating_bottom_bar_indicator_height_mode",
                new String[]{"Automatic", "Manual"}, new String[]{"automatic", "manual"}, "automatic");
        addSlider("Indicator width", "floating_bottom_bar_indicator_width");
        addSlider("Indicator height", "floating_bottom_bar_indicator_height");
        addSlider("Indicator radius", "floating_bottom_bar_indicator_radius");
        addSlider("Horizontal padding", "floating_bottom_bar_indicator_padding_horizontal");
        addSlider("Vertical padding", "floating_bottom_bar_indicator_padding_vertical");
        addSlider("Vertical offset", "floating_bottom_bar_indicator_offset");
        addSlider("Indicator opacity", "floating_bottom_bar_indicator_opacity");
        addColorField("Indicator color", "floating_bottom_bar_indicator_color", 0);

        addSection("Presets");
        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        presetRow.setWeightSum(3f);
        presetRow.addView(presetButton("Stable Glass", "stable_glass"), weighted());
        presetRow.addView(presetButton("Compact", "compact"), weighted());
        presetRow.addView(presetButton("Accessibility", "accessibility"), weighted());
        controls.addView(presetRow);

        MaterialButton reset = new MaterialButton(this);
        reset.setText("Reset floating bar only");
        reset.setOnClickListener(v -> resetBottomBar());
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resetParams.topMargin = dp(12);
        controls.addView(reset, resetParams);

        return root;
    }

    private void addPreview() {
        addSection("Live preview");
        FrameLayout stage = new FrameLayout(this);
        stage.setPadding(dp(16), dp(20), dp(16), dp(20));
        stage.setBackgroundColor(resolveSurfaceVariant());

        previewPill = new LinearLayout(this);
        previewPill.setOrientation(LinearLayout.HORIZONTAL);
        previewPill.setGravity(Gravity.CENTER);
        String[] labels = {"Chats", "Updates", "Communities", "Calls"};
        for (String label : labels) {
            TextView item = new TextView(this);
            item.setText(label);
            item.setGravity(Gravity.CENTER);
            item.setCompoundDrawablesWithIntrinsicBounds(0, android.R.drawable.presence_invisible, 0, 0);
            previewPill.addView(item, new LinearLayout.LayoutParams(0, dp(52), 1f));
        }
        FrameLayout.LayoutParams pillParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        stage.addView(previewPill, pillParams);

        previewFab = new MaterialButton(this);
        previewFab.setText("+");
        previewFab.setTextSize(24);
        previewFab.setInsetTop(0);
        previewFab.setInsetBottom(0);
        FrameLayout.LayoutParams fabParams = new FrameLayout.LayoutParams(dp(56), dp(56),
                Gravity.END | Gravity.BOTTOM);
        fabParams.setMargins(0, 0, dp(12), dp(68));
        stage.addView(previewFab, fabParams);

        controls.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(190)));
    }

    private void addSection(String title) {
        TextView view = new TextView(this);
        view.setText(title);
        view.setTextSize(18);
        view.setTextColor(resolvePrimary());
        view.setPadding(0, dp(22), 0, dp(8));
        controls.addView(view);
    }

    private void addSwitch(String title, String key, boolean defaultValue) {
        MaterialSwitch control = new MaterialSwitch(this);
        control.setText(title);
        control.setChecked(prefs.getBoolean(key, defaultValue));
        control.setPadding(0, dp(6), 0, dp(6));
        control.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(key, checked).apply();
            notifyChanged();
        });
        controls.addView(control, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addSlider(String title, String key) {
        BottomBarPreferenceSchema.Spec spec = BottomBarPreferenceSchema.spec(key);
        TextView label = new TextView(this);
        label.setText(title + ": " + format(BottomBarPreferenceSchema.read(prefs, key)));
        label.setPadding(0, dp(8), 0, 0);
        controls.addView(label);

        Slider slider = new Slider(this);
        slider.setValueFrom(spec.min);
        slider.setValueTo(spec.max);
        slider.setStepSize(spec.step);
        slider.setValue(BottomBarPreferenceSchema.read(prefs, key));
        slider.addOnChangeListener((control, value, fromUser) -> {
            if (!fromUser) return;
            float normalized = BottomBarPreferenceSchema.normalize(key, value);
            prefs.edit().putFloat(key, normalized).apply();
            label.setText(title + ": " + format(normalized));
            notifyChanged();
        });
        sliders.put(key, slider);
        controls.addView(slider);
    }

    private void addDropdown(String title, String key, String[] entries, String[] values,
                             String defaultValue) {
        TextInputLayout layout = new TextInputLayout(this);
        layout.setHint(title);
        MaterialAutoCompleteTextView input = new MaterialAutoCompleteTextView(this);
        input.setInputType(InputType.TYPE_NULL);
        input.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, entries));
        String current = prefs.getString(key, defaultValue);
        int selected = indexOf(values, current);
        input.setText(entries[Math.max(0, selected)], false);
        input.setOnItemClickListener((parent, view, position, id) -> {
            prefs.edit().putString(key, values[position]).apply();
            notifyChanged();
        });
        layout.addView(input);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        controls.addView(layout, params);
    }

    private void addColorField(String title, String key, int defaultValue) {
        TextInputLayout layout = new TextInputLayout(this);
        layout.setHint(title + " (#AARRGGBB, 0 = automatic)");
        TextInputEditText input = new TextInputEditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        int current = safeColor(key, defaultValue);
        input.setText(current == 0 ? "0" : String.format(Locale.US, "#%08X", current));
        input.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) {
                Integer parsed = parseColor(input.getText() == null ? "" : input.getText().toString());
                if (parsed == null) {
                    input.setError("Use #RRGGBB, #AARRGGBB, or 0");
                } else {
                    prefs.edit().putInt(key, parsed).apply();
                    notifyChanged();
                }
            }
        });
        layout.addView(input);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        controls.addView(layout, params);
    }

    private MaterialButton presetButton(String label, String preset) {
        MaterialButton button = new MaterialButton(this);
        button.setText(label);
        button.setTextSize(11);
        button.setOnClickListener(v -> applyPreset(preset));
        return button;
    }

    private void applyPreset(String preset) {
        SharedPreferences.Editor editor = prefs.edit();
        for (Map.Entry<String, Float> value : BottomBarPreferenceSchema.preset(preset).entrySet()) {
            editor.putFloat(value.getKey(), value.getValue());
        }
        editor.putBoolean("floating_bottom_bar", true);
        editor.putBoolean("floating_bottom_bar_glass", true);
        editor.putString("floating_bottom_bar_height_mode",
                "accessibility".equals(preset) ? "manual" : "automatic");
        editor.apply();
        recreate();
        notifyChanged();
    }

    private void resetBottomBar() {
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : BottomBarPreferenceSchema.all().keySet()) editor.remove(key);
        String[] keys = {
                "floating_bottom_bar", "floating_bottom_bar_glass",
                "floating_bottom_bar_fill_color", "floating_bottom_bar_fully_rounded",
                "floating_bottom_bar_height_mode", "floating_bottom_bar_scroll_hide_mode",
                "floating_bottom_bar_fab_mode", "floating_bottom_bar_minimal_fab_color",
                "floating_bottom_bar_minimal_fab_icon_color",
                "floating_bottom_bar_indicator_visible",
                "floating_bottom_bar_indicator_width_mode",
                "floating_bottom_bar_indicator_height_mode",
                "floating_bottom_bar_indicator_color"
        };
        for (String key : keys) editor.remove(key);
        editor.apply();
        recreate();
        notifyChanged();
    }

    private void notifyChanged() {
        getContentResolver().notifyChange(
                android.net.Uri.parse("content://" + BuildConfig.APPLICATION_ID
                        + ".hookprovider/preferences"), null);
        refreshPreview();
    }

    private void refreshPreview() {
        if (previewPill == null || prefs == null) return;
        float density = getResources().getDisplayMetrics().density;
        int radius = prefs.getBoolean("floating_bottom_bar_fully_rounded", false)
                ? 1000 : BottomBarPreferenceSchema.readInt(prefs, "floating_bottom_bar_radius");
        int fill = safeColor("floating_bottom_bar_fill_color", 0);
        if (fill == 0) fill = resolveSurface();
        int opacity = BottomBarPreferenceSchema.readInt(prefs,
                "floating_bottom_bar_glass_opacity");
        if (prefs.getBoolean("floating_bottom_bar_glass", true)) {
            fill = Color.argb(Math.round(255f * opacity / 100f),
                    Color.red(fill), Color.green(fill), Color.blue(fill));
        }
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(fill);
        pill.setCornerRadius(radius * density);
        pill.setStroke(dp(1), withAlpha(resolvePrimary(), 0.30f));
        previewPill.setBackground(pill);
        int vertical = BottomBarPreferenceSchema.readInt(prefs,
                "floating_bottom_bar_padding_vertical");
        previewPill.setPadding(dp(4), dp(vertical), dp(4), dp(vertical));

        String fabMode = prefs.getString("floating_bottom_bar_fab_mode", "default");
        previewFab.setVisibility("hidden".equals(fabMode) ? View.GONE : View.VISIBLE);
        if ("minimal".equals(fabMode)) {
            int size = BottomBarPreferenceSchema.readInt(prefs,
                    "floating_bottom_bar_minimal_fab_size");
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) previewFab.getLayoutParams();
            params.width = dp(size);
            params.height = dp(size);
            previewFab.setLayoutParams(params);
            int color = safeColor("floating_bottom_bar_minimal_fab_color", resolvePrimary());
            int alpha = BottomBarPreferenceSchema.readInt(prefs,
                    "floating_bottom_bar_minimal_fab_opacity");
            previewFab.setBackgroundTintList(ColorStateList.valueOf(withAlpha(color, alpha / 100f)));
            previewFab.setTextColor(safeColor("floating_bottom_bar_minimal_fab_icon_color", Color.WHITE));
            previewFab.setCornerRadius(dp(BottomBarPreferenceSchema.readInt(prefs,
                    "floating_bottom_bar_minimal_fab_radius")));
        } else {
            previewFab.setBackgroundTintList(ColorStateList.valueOf(resolvePrimary()));
            previewFab.setTextColor(Color.WHITE);
        }
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(2), 0, dp(2), 0);
        return params;
    }

    private int safeColor(String key, int fallback) {
        Object raw = prefs.getAll().get(key);
        if (raw instanceof Number) return ((Number) raw).intValue();
        if (raw instanceof String) {
            Integer parsed = parseColor((String) raw);
            return parsed == null ? fallback : parsed;
        }
        return fallback;
    }

    private static Integer parseColor(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || "0".equals(value)) return 0;
        try {
            if (!value.startsWith("#")) value = "#" + value;
            if (value.length() == 7) value = "#FF" + value.substring(1);
            if (value.length() != 9) return null;
            return (int) Long.parseLong(value.substring(1), 16);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private int resolvePrimary() {
        android.util.TypedValue value = new android.util.TypedValue();
        return getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimary, value, true)
                ? value.data : Color.rgb(79, 175, 80);
    }

    private int resolveSurface() {
        android.util.TypedValue value = new android.util.TypedValue();
        return getTheme().resolveAttribute(com.google.android.material.R.attr.colorSurface, value, true)
                ? value.data : Color.WHITE;
    }

    private int resolveSurfaceVariant() {
        android.util.TypedValue value = new android.util.TypedValue();
        return getTheme().resolveAttribute(com.google.android.material.R.attr.colorSurfaceVariant, value, true)
                ? value.data : Color.LTGRAY;
    }

    private static int withAlpha(int color, float alpha) {
        return Color.argb(Math.round(Math.max(0f, Math.min(1f, alpha)) * 255),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int indexOf(String[] values, String current) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) return i;
        }
        return 0;
    }

    private static String format(float value) {
        return Math.abs(value - Math.round(value)) < 0.001f
                ? Integer.toString(Math.round(value))
                : String.format(Locale.US, "%.1f", value);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
