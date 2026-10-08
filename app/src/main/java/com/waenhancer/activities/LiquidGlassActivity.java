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
 * <p>Existing controls keep their settings. App-wide adapters are separately opt-in and marked
 * experimental until their discovery, rendering and performance have been checked on a device.</p>
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

        addCaption("Choose where to apply Liquid Glass. New surfaces are experimental and off by "
                + "default. The effect is more visible over a detailed wallpaper. Your existing "
                + "navigation bar and scroll button settings are kept.");

        addSection("Home");
        addBarRow();

        addSection("Conversation");
        addSurfaceRow("Scroll-to-bottom button",
                "The round button that floats over the messages. Bubbles and wallpaper move behind "
                        + "it as you scroll, which is what the material is made of.",
                LiquidGlassSettings.SCROLL_BUTTON);

        addSection("Across WhatsApp");
        for (LiquidGlassSettings.Surface surface : LiquidGlassSettings.Surface.values()) {
            addSurfaceRow(surface.title, surfaceSummary(surface), surface.key);
        }

        addCaption("New surface switches take effect when you return to WhatsApp. If a surface "
                + "is missing in your WhatsApp version, it is left unchanged. In a chat, the header "
                + "and the message input are live glass on Android 13+: messages scroll behind them "
                + "and are refracted. Other surfaces use a static translucent material for now.");

        root.addView(controls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private String surfaceSummary(LiquidGlassSettings.Surface surface) {
        return switch (surface) {
            case TOOLBARS -> "Chat header as live glass, with messages scrolling behind it. Other page headers use the static material.";
            case SEARCH -> "Search capsules and conversation filter controls.";
            case FAB -> "New chat, new broadcast and other named floating buttons.";
            case COMPOSER -> "The message input as live glass, with messages scrolling behind it. The keyboard stays native.";
            case QUOTES -> "Reply previews, quoted message frames and voice-note drafts.";
            case BUBBLES -> "Keeps native bubble shape and padding. Requires a compatible bubble resolver; use a wallpaper for visible detail.";
            case CARDS -> "Named information and action cards. Lists keep their native scrolling and row layout.";
            case PANELS -> "Native popup menus, dialogs and sheets. Buttons and dismissal gestures stay native.";
        };
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
