package com.waenhancer.activities;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.waenhancer.BuildConfig;
import com.waenhancer.config.LiquidGlassSettings;

/**
 * Where the Liquid Glass theme is switched on, surface by surface.
 *
 * <p>The theme is not a single toggle, because glass is not free and not every surface wants it.
 * Each row here is one surface that has been measured on a device and found to hold the material;
 * a surface that has not been measured yet is not listed, so this page never offers a switch whose
 * result nobody has looked at.</p>
 *
 * <p>Built in code rather than as a preference screen, following
 * {@link BottomBarCustomizationActivity}: these rows are not all plain booleans. The floating bar's
 * row is a view onto its style picker rather than a preference of its own, and that is a behaviour
 * a {@code MaterialSwitchPreference} cannot express.</p>
 */
public class LiquidGlassActivity extends AppCompatActivity {

    private SharedPreferences prefs;
    private LinearLayout controls;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        setContentView(buildContent());
    }

    private ScrollView buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle("Liquid Glass");
        toolbar.setNavigationIcon(android.R.drawable.ic_media_previous);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(20), dp(4), dp(20), dp(40));

        addCaption("Glass is applied only where it earns its cost: chrome that sits still, over "
                + "content that moves behind it. Lists, message bubbles and settings rows are "
                + "deliberately left alone.");

        addSection("Home");
        addBarRow();

        addSection("Conversation");
        addSurfaceRow("Scroll-to-bottom button",
                "The round button that floats over the messages. Bubbles and wallpaper move behind "
                        + "it as you scroll, which is what the material is made of.",
                LiquidGlassSettings.SCROLL_BUTTON);

        addCaption("The message input row was tried and dropped: the list is padded so bubbles stop "
                + "above it, so the only thing behind it is a wallpaper that never moves. Measured "
                + "at 49 luma of variation against the 235 behind the message list.");

        root.addView(controls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    /**
     * The floating bar's row, which is its style picker rather than a switch of its own.
     *
     * <p>The bar had a glass style before the theme existed, and it is the same piece of state. A
     * separate boolean here would let this page claim the bar is glass while the bar editor says
     * Frost, so the row reads and writes the style directly. Turning it off restores whichever
     * style was in use before, rather than picking a default and discarding the user's choice.</p>
     */
    private void addBarRow() {
        addSwitchRow("Floating bottom bar",
                "Linked to Floating Bottom Bar settings, Glass style. Turning this on selects the "
                        + "Liquid style there; turning it off puts back the previous one.",
                LiquidGlassSettings.isBarLiquid(prefs),
                checked -> LiquidGlassSettings.setBarLiquid(prefs, checked));
    }

    private void addSurfaceRow(String title, String summary, String key) {
        addSwitchRow(title, summary, prefs.getBoolean(key, false),
                checked -> prefs.edit().putBoolean(key, checked).apply());
    }

    private void addSwitchRow(String title, String summary, boolean checked,
                              java.util.function.Consumer<Boolean> onChanged) {
        MaterialSwitch control = new MaterialSwitch(this);
        control.setText(title);
        control.setChecked(checked);
        control.setPadding(0, dp(10), 0, dp(2));
        control.setOnCheckedChangeListener((button, isChecked) -> {
            onChanged.accept(isChecked);
            notifyChanged();
        });
        controls.addView(control, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView caption = new TextView(this);
        caption.setText(summary);
        caption.setTextSize(12);
        caption.setAlpha(0.7f);
        caption.setPadding(0, 0, 0, dp(6));
        controls.addView(caption);
    }

    private void addSection(String title) {
        TextView view = new TextView(this);
        view.setText(title);
        view.setTextSize(18);
        view.setPadding(0, dp(22), 0, dp(4));
        controls.addView(view);
    }

    private void addCaption(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(12);
        view.setAlpha(0.7f);
        view.setPadding(0, dp(10), 0, dp(2));
        controls.addView(view);
    }

    /**
     * Tells the hooked process a preference moved.
     *
     * <p>The same notification the bar editor sends. Without it the change sits in the module's
     * store until WhatsApp is restarted, which reads as the switch having done nothing.</p>
     */
    private void notifyChanged() {
        getContentResolver().notifyChange(
                android.net.Uri.parse("content://" + BuildConfig.APPLICATION_ID
                        + ".hookprovider/preferences"), null);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
