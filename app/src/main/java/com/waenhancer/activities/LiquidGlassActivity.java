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
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.waenhancer.BuildConfig;
import com.waenhancer.config.LiquidGlassOptics;
import com.waenhancer.config.LiquidGlassSettings;
import com.waenhancer.theme.GlassOptics;
import com.waenhancer.theme.LensModel;

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
    private com.waenhancer.theme.GlassOpticsLabView opticsLab;
    private MaterialButton diagnosticMode;
    private String pendingOpticsFile;
    private static final int EXPORT_OPTICS_PNG = 903;
    /** Optics rows, enabled and disabled as their dependencies change. */
    private final java.util.Map<String, MaterialSwitch> opticsSwitches = new java.util.HashMap<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (savedInstanceState != null) pendingOpticsFile = savedInstanceState.getString("optics_export");
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
                + "is missing in your WhatsApp version, it is left unchanged. On Android 13+ every surface "
                + "is live glass that refracts what is behind it; in a chat, messages scroll behind the "
                + "header and the message input. In power saving, or where the lens is unavailable, "
                + "surfaces become a plain translucent pane with no GPU effect.");

        addOpticsSection();

        root.addView(controls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private String surfaceSummary(LiquidGlassSettings.Surface surface) {
        return switch (surface) {
            case TOOLBARS -> "Chat header with messages scrolling behind it, and the other page headers.";
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

    /**
     * The experimental optical corrections, for a side-by-side comparison on a device. The master
     * switch off is the original renderer; each group can then be switched off on its own.
     * Dependencies are shown by disabling rows; {@link GlassOptics#resolve} enforces them anyway.
     */
    private void addOpticsSection() {
        addSection("Optical corrections (experimental)");
        addCaption("Compare the corrected renderer with the original one. Changes apply when you "
                + "return to WhatsApp; no restart is needed. Off by default: nothing changes until "
                + "you turn this on, and your style, opacity and surface settings are not changed.");

        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        MaterialButton all = new MaterialButton(this);
        all.setText("All improvements");
        all.setOnClickListener(v -> {
            LiquidGlassOptics.applyAllImprovements(prefs);
            refreshOpticsRows();
            notifyChanged();
        });
        MaterialButton original = new MaterialButton(this,
                null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        original.setText("Original");
        original.setOnClickListener(v -> {
            LiquidGlassOptics.applyOriginal(prefs);
            refreshOpticsRows();
            notifyChanged();
        });
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half.setMarginEnd(dp(8));
        presets.addView(all, half);
        presets.addView(original, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        controls.addView(presets);

        addOpticsRow(LiquidGlassOptics.MASTER, false, "Corrected renderer",
                "Master switch. Off is the original renderer. On always includes stable refraction: "
                        + "a bounded warp with no fold at the rounded ends, corners or selected tab, "
                        + "1:1 capture and exact placement.");
        addOpticsRow(LiquidGlassOptics.FILTERING, true, "1. Optical blur",
                "Platform blur and selectable rim reconstruction. The requested blur radius is "
                        + "calibrated by the PNG lab; it is not a measured Gaussian sigma.");
        addOpticsRow(LiquidGlassOptics.ADAPTIVE, true, "2. Adaptive contrast",
                "Darkens or lightens the glass only where the content behind would make icons and "
                        + "text hard to read, and tints toward the backdrop. Needs Optical blur.");
        addOpticsRow(LiquidGlassOptics.COLOR, true, "3. Colour and highlights",
                "Linear-light colour, saturation 1.10 instead of 1.55, at most 1.5px colour fringe, "
                        + "quieter rim light.");
        addOpticsRow(LiquidGlassOptics.TEMPORAL, true, "4. Capture timing and budget",
                "Captures only when the screen redraws, keeps the selected tab across colour changes, "
                        + "and counts every live surface in one budget.");
        addOpticsRow(LiquidGlassOptics.CLEAR, false, "Clear profile (iOS-inspired)",
                "More transparent glass on the app surfaces, with contrast protection doing the work "
                        + "of the tint. Needs Adaptive contrast. The bottom bar keeps its own style.");

        MaterialButton profile = new MaterialButton(this);
        profile.setText("Optical profile: " + prefs.getString(LiquidGlassOptics.PROFILE, "baseline"));
        profile.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setTitle("Optical A/B profile")
                .setItems(new String[]{"Baseline: original V2", "Balanced: rim transfer only",
                        "Strong: rim transfer only", "Reconstruction: filtered detail, footprint and volume"},
                        (dialog, which) -> {
                            GlassOptics.Profile next = GlassOptics.Profile.values()[which];
                            prefs.edit().putString(LiquidGlassOptics.PROFILE, next.key()).apply();
                            profile.setText("Optical profile: " + next.key());
                            refreshOpticsLab(); notifyChanged();
                        }).show());
        controls.addView(profile);
        MaterialButton candidate = new MaterialButton(this);
        candidate.setText("Apply reconstruction candidate (Clear off)");
        candidate.setOnClickListener(v -> {
            LiquidGlassOptics.applyReconstruction(prefs);
            profile.setText("Optical profile: reconstruct");
            refreshOpticsRows(); refreshOpticsLab(); notifyChanged();
        });
        controls.addView(candidate);
        addCaption("Profiles apply to app surfaces when Optical blur is on. Baseline reproduces V2. "
                + "Reconstruction is a candidate for device calibration. The floating bar retains "
                + "its established finish. Keep Search off when reproducing the reported scenes.");

        addSection("Developer diagnostics");
        addCaption("Shader views for checking the corrected renderer. Only active with the "
                + "Corrected renderer on.");
        MaterialButton debug = new MaterialButton(this,
                null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        diagnosticMode = debug;
        GlassOptics.Debug[] views = GlassOptics.Debug.values();
        debug.setText("View: " + debugLabel(GlassOptics.Debug.from(
                prefs.getString(LiquidGlassOptics.DEBUG, null))));
        debug.setOnClickListener(v -> {
            GlassOptics.Debug current = GlassOptics.Debug.from(prefs.getString(LiquidGlassOptics.DEBUG, null));
            GlassOptics.Debug next = views[(current.ordinal() + 1) % views.length];
            prefs.edit().putString(LiquidGlassOptics.DEBUG, next.key()).apply();
            debug.setText("View: " + debugLabel(next));
            refreshOpticsLab();
            notifyChanged();
        });
        controls.addView(debug);

        addCaption("Synthetic GPU lab: native-pixel 1/2/4/8 bars, diagonal, step edge and colour fields. "
                + "The lab always uses the corrected renderer. Choose RAW, SHARP or SOFT to export "
                + "an isolated graphical stage; final modes include material finishing.");
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            opticsLab = new com.waenhancer.theme.GlassOpticsLabView(this);
            controls.addView(opticsLab, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(96)));
            refreshOpticsLab();
            MaterialButton export = new MaterialButton(this);
            export.setText("Export synthetic GPU PNG");
            export.setOnClickListener(v -> opticsLab.copyGpuPng(this, (bitmap, error) -> {
                if (isFinishing() || isDestroyed()) { if (bitmap != null) bitmap.recycle(); return; }
                if (error != null) {
                    android.widget.Toast.makeText(this, error, android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                export.setEnabled(false);
                new Thread(() -> {
                    java.io.File cached = null;
                    try {
                        cached = java.io.File.createTempFile("glass-optics-", ".png", getCacheDir());
                        try (java.io.OutputStream out = new java.io.FileOutputStream(cached)) {
                            if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out))
                                throw new java.io.IOException("PNG write failed");
                        }
                        java.io.File ready = cached;
                        runOnUiThread(() -> {
                            export.setEnabled(true);
                            if (isFinishing() || isDestroyed()) { ready.delete(); return; }
                            if (pendingOpticsFile != null) new java.io.File(pendingOpticsFile).delete();
                            pendingOpticsFile = ready.getAbsolutePath();
                            android.content.Intent save = new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT);
                            save.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                            save.setType("image/png");
                            save.putExtra(android.content.Intent.EXTRA_TITLE, "glass_"
                                    + prefs.getString(LiquidGlassOptics.PROFILE,"baseline") + "_"
                                    + prefs.getString(LiquidGlassOptics.DEBUG,"none") + ".png");
                            startActivityForResult(save, EXPORT_OPTICS_PNG);
                        });
                    } catch (java.io.IOException | RuntimeException e) {
                        if (cached != null) cached.delete();
                        runOnUiThread(() -> {
                            export.setEnabled(true);
                            android.widget.Toast.makeText(this,"PNG capture could not be saved",android.widget.Toast.LENGTH_LONG).show();
                        });
                    } finally { bitmap.recycle(); }
                }, "glass-png-capture").start();
            }));
            controls.addView(export);
            MaterialButton metadata = new MaterialButton(this);
            metadata.setText("Copy lab parameters");
            metadata.setOnClickListener(v -> {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Glass lab", opticsLab.metadata()));
            });
            controls.addView(metadata);
        }

        TextView amount = new TextView(this);
        amount.setTextSize(13);
        Slider displacement = new Slider(this);
        displacement.setValueFrom(LensModel.MIN_DISPLACEMENT);
        displacement.setValueTo(LensModel.MAX_EFFECTIVE);
        displacement.setStepSize(0.01f);
        float stored = prefs.getFloat(LiquidGlassOptics.DISPLACEMENT, LensModel.DEFAULT_DISPLACEMENT);
        float value = Math.round(Math.max(LensModel.MIN_DISPLACEMENT,
                Math.min(LensModel.MAX_EFFECTIVE, stored)) * 100f) / 100f;
        displacement.setValue(value);
        amount.setText(displacementLabel(value));
        displacement.addOnChangeListener((slider, v, fromUser) -> amount.setText(displacementLabel(v)));
        displacement.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override public void onStartTrackingTouch(@androidx.annotation.NonNull Slider slider) { }
            @Override public void onStopTrackingTouch(@androidx.annotation.NonNull Slider slider) {
                prefs.edit().putFloat(LiquidGlassOptics.DISPLACEMENT, slider.getValue()).apply();
                refreshOpticsLab();
                notifyChanged();
            }
        });
        amount.setPadding(0, dp(10), 0, 0);
        controls.addView(amount);
        controls.addView(displacement);
        refreshOpticsRows();
    }

    private static String displacementLabel(float value) {
        return String.format(java.util.Locale.ROOT,
                "Refraction amount: %.2f of the rim width (default %.2f, hard cap %.2f)",
                value, LensModel.DEFAULT_DISPLACEMENT, LensModel.MAX_EFFECTIVE);
    }

    private static String debugLabel(GlassOptics.Debug debug) {
        return switch (debug) {
            case NONE -> "material (off)";
            case RAW_INPUT -> "raw backdrop";
            case DISPLACEMENT -> "displacement field";
            case JACOBIAN -> "warp stability (green ok, yellow weak, red fold)";
            case GRID -> "synthetic grid";
            case PROTECTION -> "contrast protection (red) / blur share (green)";
            case LEGACY_WARP -> "original warp (diagnostic only: folds and duplicates at the edges)";
            case SHARP_ONLY -> "refracted raw input (no material)";
            case SOFT_ONLY -> "refracted platform blur (no material)";
            case BETA_HEATMAP -> "soft share (red) / residual detail (green)";
            case FOOTPRINT -> "sigma min (red), sigma max / 2 (green), displacement / cap (blue)";
            case COVERAGE -> "shape coverage";
            case FINAL_NO_LIGHT -> "final material without lighting";
            case TIME_GENERATION -> "capture generation / age / rebuilds (app panes)";
        };
    }

    private void refreshOpticsLab() {
        if (opticsLab == null) return;
        GlassOptics lab = GlassOptics.resolve(true,
                prefs.getBoolean(LiquidGlassOptics.FILTERING,true),
                prefs.getBoolean(LiquidGlassOptics.ADAPTIVE,true),
                prefs.getBoolean(LiquidGlassOptics.COLOR,true),
                prefs.getBoolean(LiquidGlassOptics.TEMPORAL,true),
                prefs.getBoolean(LiquidGlassOptics.CLEAR,false),
                GlassOptics.Debug.from(prefs.getString(LiquidGlassOptics.DEBUG,null)),
                prefs.getFloat(LiquidGlassOptics.DISPLACEMENT,LensModel.DEFAULT_DISPLACEMENT))
                .withProfile(GlassOptics.Profile.from(prefs.getString(LiquidGlassOptics.PROFILE,null)));
        opticsLab.configure(lab);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if (requestCode != EXPORT_OPTICS_PNG) return;
        String path = pendingOpticsFile;
        pendingOpticsFile = null;
        if (path == null || !new java.io.File(path).isFile()) {
            android.widget.Toast.makeText(this,"Export frame expired; capture a new PNG",android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        java.io.File cached = new java.io.File(path);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) { cached.delete(); return; }
        android.net.Uri destination = data.getData();
        new Thread(() -> {
            String message;
            try (java.io.InputStream in = new java.io.FileInputStream(cached);
                 java.io.OutputStream out = getContentResolver().openOutputStream(destination)) {
                if (out == null) throw new java.io.IOException("PNG write failed");
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer,0,count);
                message = "Synthetic GPU PNG saved";
            } catch (java.io.IOException | RuntimeException e) { message = "PNG export failed"; }
            finally { cached.delete(); }
            String result = message;
            runOnUiThread(() -> android.widget.Toast.makeText(this,result,android.widget.Toast.LENGTH_LONG).show());
        },"glass-png-export").start();
    }

    @Override protected void onSaveInstanceState(android.os.Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("optics_export", pendingOpticsFile);
    }

    @Override protected void onDestroy() {
        if (isFinishing() && pendingOpticsFile != null) new java.io.File(pendingOpticsFile).delete();
        super.onDestroy();
    }

    private void addOpticsRow(String key, boolean defaultValue, String title, String summary) {
        MaterialSwitch control = addSwitchRow(title, summary, prefs.getBoolean(key, defaultValue),
                checked -> {
                    prefs.edit().putBoolean(key, checked).apply();
                    refreshOpticsRows();
                });
        opticsSwitches.put(key, control);
    }

    /** Mirrors {@link GlassOptics#resolve}'s dependencies in the rows' enabled state. */
    private void refreshOpticsRows() {
        refreshOpticsLab();
        if (diagnosticMode != null) diagnosticMode.setText("View: " + debugLabel(
                GlassOptics.Debug.from(prefs.getString(LiquidGlassOptics.DEBUG,null))));
        boolean master = prefs.getBoolean(LiquidGlassOptics.MASTER, false);
        boolean filtering = prefs.getBoolean(LiquidGlassOptics.FILTERING, true);
        boolean adaptive = prefs.getBoolean(LiquidGlassOptics.ADAPTIVE, true);
        for (java.util.Map.Entry<String, MaterialSwitch> entry : opticsSwitches.entrySet()) {
            String key = entry.getKey();
            MaterialSwitch control = entry.getValue();
            boolean defaultValue = !LiquidGlassOptics.MASTER.equals(key) && !LiquidGlassOptics.CLEAR.equals(key);
            boolean checked = prefs.getBoolean(key, defaultValue);
            if (control.isChecked() != checked) control.setChecked(checked);
            boolean enabled = LiquidGlassOptics.MASTER.equals(key) || master;
            if (LiquidGlassOptics.ADAPTIVE.equals(key)) enabled &= filtering;
            if (LiquidGlassOptics.CLEAR.equals(key)) enabled &= filtering && adaptive;
            control.setEnabled(enabled);
        }
    }

    private void addSurfaceRow(String title, String summary, String key) {
        addSwitchRow(title, summary, prefs.getBoolean(key, false),
                checked -> prefs.edit().putBoolean(key, checked).apply());
    }

    private MaterialSwitch addSwitchRow(String title, String summary, boolean checked,
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
        return control;
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
