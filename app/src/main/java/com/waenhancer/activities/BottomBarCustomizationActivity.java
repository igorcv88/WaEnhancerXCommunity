package com.waenhancer.activities;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.waenhancer.BuildConfig;
import com.waenhancer.R;
import com.waenhancer.config.BottomBarPreferenceSchema;
import com.waenhancer.config.BottomBarPreviewModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Complete open-source editor for the classic floating bottom bar. */
public class BottomBarCustomizationActivity extends AppCompatActivity {

    /** Any radius at or beyond half the pill height reads as fully rounded. */
    private static final int PREVIEW_PILL_MAX_RADIUS_DP = 48;
    private static final int PREVIEW_DEFAULT_FAB_SIZE_DP = 56;
    private static final int PREVIEW_DEFAULT_FAB_RADIUS_DP = 16;
    private static final int PREVIEW_DEFAULT_FAB_MARGIN_DP = 16;
    private static final int PREVIEW_WA_ACCENT = 0xFF00A884;
    private static final int PREVIEW_WA_INACTIVE = 0xFF8696A0;
    private static final int PREVIEW_WA_ACTIVE_LABEL = 0xFFE9EDEF;
    private static final int PREVIEW_WA_SURFACE = 0xFF1F2C34;

    private SharedPreferences prefs;
    private LinearLayout controls;
    private View previewStage;
    private LinearLayout previewPill;
    private FrameLayout previewPillContainer;
    private MaterialCardView previewFab;
    private ImageView previewFabIcon;
    /** One entry per mock tab, in on-screen order. */
    private final List<ImageView> previewIcons = new ArrayList<>();
    private final List<TextView> previewLabels = new ArrayList<>();
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

        addSection("Core");
        addSwitch("Enable floating bar", "floating_bottom_bar", false);
        addSwitch("Glassmorphism", "floating_bottom_bar_glass", true);
        addDropdown("Glass style", "floating_bottom_bar_glass_variant",
                new String[]{"Stable", "Advanced Glass", "Liquid", "Frost", "Clear"},
                new String[]{"stable", "advanced", "liquid", "frost", "clear"}, "advanced");
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

        // The preview is a fixed footer, not a list item: dragging a slider further down the list
        // must not scroll the thing it is changing off screen.
        root.addView(buildPreviewFooter(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return root;
    }

    /**
     * Inflates the mock WhatsApp screen ported from upstream.
     *
     * <p>This fork used to build the preview by hand out of four bare {@code TextView}s whose
     * "icon" was {@code android.R.drawable.presence_invisible} — so the preview showed neither
     * real icons nor anything resembling the bar that lands in WhatsApp. Worse, it was a second,
     * independent implementation of the layout maths in {@code FloatingBottomBar}, and the two
     * drifted: the same slider moved both by different amounts.
     */
    private View buildPreviewFooter() {
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setBackgroundColor(resolveSurfaceVariant());
        footer.setElevation(dp(8));

        TextView caption = new TextView(this);
        caption.setText(R.string.bottom_bar_preview_caption);
        caption.setTextSize(12);
        caption.setTextColor(resolvePrimary());
        caption.setPadding(dp(20), dp(10), dp(20), dp(6));
        footer.addView(caption);

        View preview = LayoutInflater.from(this)
                .inflate(R.layout.view_bottom_bar_preview, footer, false);

        previewStage = preview.findViewById(R.id.preview_stage);
        previewPill = preview.findViewById(R.id.preview_bottom_bar);
        previewPillContainer = preview.findViewById(R.id.preview_pill_container);
        previewFab = preview.findViewById(R.id.preview_fab);
        previewFabIcon = preview.findViewById(R.id.preview_fab_icon);

        previewIcons.add(preview.findViewById(R.id.preview_icon_chats));
        previewIcons.add(preview.findViewById(R.id.preview_icon_updates));
        previewIcons.add(preview.findViewById(R.id.preview_icon_communities));
        previewIcons.add(preview.findViewById(R.id.preview_icon_calls));

        previewLabels.add(preview.findViewById(R.id.preview_label_chats));
        previewLabels.add(preview.findViewById(R.id.preview_label_updates));
        previewLabels.add(preview.findViewById(R.id.preview_label_communities));
        previewLabels.add(preview.findViewById(R.id.preview_label_calls));

        footer.addView(preview);
        return footer;
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
        // Without an end icon in dropdown mode, Material never installs the delegate that opens
        // the popup on touch, so tapping the field only focused it and no list ever appeared.
        layout.setEndIconMode(TextInputLayout.END_ICON_DROPDOWN_MENU);

        MaterialAutoCompleteTextView input = new MaterialAutoCompleteTextView(this);
        input.setInputType(InputType.TYPE_NULL);
        input.setKeyListener(null);
        input.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, entries));
        String current = prefs.getString(key, defaultValue);
        int selected = indexOf(values, current);
        input.setText(entries[Math.max(0, selected)], false);
        input.setOnItemClickListener((parent, view, position, id) -> {
            prefs.edit().putString(key, values[position]).apply();
            notifyChanged();
        });
        // Belt and braces: open the list on tap regardless of how the end-icon delegate resolves
        // against the current theme, which is what silently failed before.
        input.setOnClickListener(v -> input.showDropDown());
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
                "floating_bottom_bar_minimal_fab_icon_color"
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

    /**
     * Repaints the preview from a single resolved model, so every control on the screen has a
     * visible effect instead of only the handful that used to be read here.
     */
    private void refreshPreview() {
        if (previewPill == null || prefs == null) return;
        BottomBarPreviewModel model = BottomBarPreviewModel.from(prefs.getAll());
        float density = getResources().getDisplayMetrics().density;

        // Disabled bar still previews, but reads as inactive.
        previewStage.setAlpha(model.barEnabled ? 1f : 0.45f);

        float pillRadiusPx = model.isFullyRounded()
                ? dp(PREVIEW_PILL_MAX_RADIUS_DP) : model.radiusDp * density;
        if (model.glassEnabled) {
            // Same renderer the hooked bar uses, so highlight and refraction preview honestly
            // instead of being approximated by a flat fill here.
            previewPill.setBackground(com.waenhancer.theme.GlassRenderer.background(
                    model.glassSpec(PREVIEW_WA_SURFACE), pillRadiusPx, density));
        } else {
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(model.resolvedFillColor(PREVIEW_WA_SURFACE));
            pill.setCornerRadius(pillRadiusPx);
            previewPill.setBackground(pill);
        }
        previewPill.setPadding(0, dp(model.paddingVerticalDp), 0, dp(model.paddingVerticalDp));

        ViewGroup.MarginLayoutParams pillParams =
                (ViewGroup.MarginLayoutParams) previewPill.getLayoutParams();
        pillParams.leftMargin = dp(model.sideMarginDp);
        pillParams.rightMargin = dp(model.sideMarginDp);
        pillParams.bottomMargin = dp(model.bottomMarginDp);
        pillParams.height = model.manualHeight
                ? dp(model.manualHeightDp) : ViewGroup.LayoutParams.WRAP_CONTENT;
        previewPill.setLayoutParams(pillParams);

        applyTabStyling(model);
        applyFabStyling(model);
    }

    private void applyTabStyling(BottomBarPreviewModel model) {
        for (int i = 0; i < previewIcons.size(); i++) {
            ImageView icon = previewIcons.get(i);
            ViewGroup.LayoutParams iconParams = icon.getLayoutParams();
            iconParams.width = dp(model.iconSizeDp);
            iconParams.height = dp(model.iconSizeDp);
            icon.setLayoutParams(iconParams);

            TextView label = previewLabels.get(i);
            label.setTextSize(model.textSizeSp);
            // The first tab is the selected one in the mock, so it keeps WhatsApp's active colours.
            label.setTextColor(i == 0 ? PREVIEW_WA_ACTIVE_LABEL : PREVIEW_WA_INACTIVE);

            ViewGroup.MarginLayoutParams labelParams =
                    (ViewGroup.MarginLayoutParams) label.getLayoutParams();
            labelParams.topMargin = dp(model.iconLabelSpacingDp);
            label.setLayoutParams(labelParams);
        }
    }

    private void applyFabStyling(BottomBarPreviewModel model) {
        previewFab.setVisibility(model.isFabHidden() ? View.GONE : View.VISIBLE);
        if (model.isFabHidden()) return;

        ViewGroup.MarginLayoutParams params =
                (ViewGroup.MarginLayoutParams) previewFab.getLayoutParams();
        // The stored offset is the complete translation above the bar; its bottom margin already
        // moves the reference position, so adding another stock baseline would double-count it.
        params.bottomMargin = dp(model.fabOffsetDp);

        if (model.isFabMinimal()) {
            params.width = dp(model.minimalFabSizeDp);
            params.height = dp(model.minimalFabSizeDp);
            params.rightMargin = dp(model.minimalFabMarginDp);
            previewFab.setCardBackgroundColor(
                    model.resolvedMinimalFabColor(PREVIEW_WA_ACCENT));
            previewFab.setRadius(dp(model.minimalFabRadiusDp));
            previewFabIcon.setImageTintList(
                    android.content.res.ColorStateList.valueOf(model.minimalFabIconColor));
        } else {
            params.width = dp(PREVIEW_DEFAULT_FAB_SIZE_DP);
            params.height = dp(PREVIEW_DEFAULT_FAB_SIZE_DP);
            params.rightMargin = dp(PREVIEW_DEFAULT_FAB_MARGIN_DP);
            previewFab.setCardBackgroundColor(PREVIEW_WA_ACCENT);
            previewFab.setRadius(dp(PREVIEW_DEFAULT_FAB_RADIUS_DP));
            previewFabIcon.setImageTintList(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#111b21")));
        }
        previewFab.setLayoutParams(params);
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
        return BottomBarPreviewModel.parseColor(raw);
    }

    private int resolvePrimary() {
        android.util.TypedValue value = new android.util.TypedValue();
        return getTheme().resolveAttribute(android.R.attr.colorAccent, value, true)
                ? value.data : Color.rgb(79, 175, 80);
    }

    private int resolveSurface() {
        android.util.TypedValue value = new android.util.TypedValue();
        return getTheme().resolveAttribute(com.google.android.material.R.attr.colorSurface, value, true)
                ? value.data : Color.WHITE;
    }

    private int resolveOnSurface() {
        android.util.TypedValue value = new android.util.TypedValue();
        return getTheme().resolveAttribute(android.R.attr.textColorPrimary, value, true)
                ? value.data : Color.DKGRAY;
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
