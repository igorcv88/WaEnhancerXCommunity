#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def load(path):
    return (ROOT / path).read_text(encoding="utf-8")


def save(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")
    print("updated", path)


def required_replace(path, old, new):
    text = load(path)
    if old not in text:
        raise RuntimeError(f"Expected text not found in {path}: {old[:80]!r}")
    save(path, text.replace(old, new))


# ---------------------------------------------------------------------------
# Block B: replace raw SharedPreferences dump/clear with BackupCodec.
# ---------------------------------------------------------------------------
path = "app/src/main/java/com/waenhancer/ui/fragments/HomeFragment.java"
text = load(path)
if "import com.waenhancer.backup.BackupCodec;" not in text:
    text = text.replace("import com.waenhancer.App;\n", "import com.waenhancer.App;\nimport com.waenhancer.backup.BackupCodec;\n")
start = text.index("    private static @NonNull\n    JSONObject getJsonObject")
end = text.index("    private boolean isInitialCheck", start)
replacement = '''    private void saveConfigs(Context context) {
        if (FilePicker.fileSalve == null) {
            Toast.makeText(context,
                    "Please use the standalone WaEnhancer Community app for file operations.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        Runnable launchExport = () -> {
            FilePicker.setOnUriPickedListener(uri -> {
                try (var output = context.getContentResolver().openOutputStream(uri)) {
                    if (output == null) throw new IllegalStateException("Unable to open destination.");
                    SharedPreferences preferences =
                            PreferenceManager.getDefaultSharedPreferences(context);
                    String backup = BackupCodec.exportSettings(
                            preferences, BuildConfig.VERSION_NAME);
                    output.write(backup.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    preferences.edit().putBoolean("backup_privacy_notice_seen", true).apply();
                    Toast.makeText(context, R.string.configs_saved, Toast.LENGTH_SHORT).show();
                } catch (Exception exception) {
                    Log.e("saveConfigs", "Unable to export settings", exception);
                    Toast.makeText(context, exception.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);
            FilePicker.fileSalve.launch("WaEnhancerCommunity-settings-"
                    + format.format(new Date()) + ".json");
        };

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (preferences.getBoolean("backup_privacy_notice_seen", false)) {
            launchExport.run();
            return;
        }

        com.waenhancer.ui.helpers.BottomSheetHelper.showConfirmation(
                requireActivity(),
                "Export settings",
                "The export contains only allowlisted module settings. It excludes keys, tokens, "
                        + "certificates, license data, internal paths, diagnostics, messages and media. "
                        + "Review the file before sharing it.",
                "Export",
                false,
                launchExport);
    }

    private void importConfigs(Context context) {
        if (FilePicker.fileCapture == null) {
            Toast.makeText(context,
                    "Please use the standalone WaEnhancer Community app for file operations.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        FilePicker.setOnUriPickedListener(uri -> {
            try (var input = context.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalStateException("Unable to open backup.");
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int total = 0;
                int read;
                while ((read = input.read(chunk)) != -1) {
                    total += read;
                    if (total > BackupCodec.MAX_BYTES) {
                        throw new BackupCodec.BackupException(
                                "Backup exceeds the 2 MB safety limit.");
                    }
                    buffer.write(chunk, 0, read);
                }

                SharedPreferences preferences =
                        PreferenceManager.getDefaultSharedPreferences(context);
                BackupCodec.ImportPlan plan = BackupCodec.parseAndValidate(buffer.toByteArray());
                BackupCodec.ImportReport report = BackupCodec.apply(
                        context, preferences, plan);

                com.waenhancer.ui.helpers.BottomSheetHelper.showInfo(
                        requireActivity(), "Import complete", report.summary());
                App.getInstance().restartApp(FeatureLoader.PACKAGE_WPP);
                App.getInstance().restartApp(FeatureLoader.PACKAGE_BUSINESS);
                if (getActivity() != null
                        && context.getPackageName().equals(BuildConfig.APPLICATION_ID)) {
                    getActivity().recreate();
                }
            } catch (Exception exception) {
                Log.e("importConfigs", "Unable to import settings", exception);
                Toast.makeText(context, exception.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        FilePicker.fileCapture.launch(new String[]{"application/json"});
    }

'''
text = text[:start] + replacement + text[end:]
save(path, text)


# ---------------------------------------------------------------------------
# Updater: fork-only public releases and no compiled token.
# ---------------------------------------------------------------------------
path = "app/src/main/java/com/waenhancer/UpdateChecker.java"
text = load(path)
text = text.replace(
    'private static final String RELEASES_API = "https://api.github.com/repos/mubashardev/WaEnhancer/releases";',
    'private static final String RELEASES_API = "https://api.github.com/repos/igorcv88/WaEnhancerX/releases";')
text = text.replace(
    'private static final String TELEGRAM_UPDATE_URL = "https://github.com/mubashardev/WaEnhancer/releases";',
    'private static final String TELEGRAM_UPDATE_URL = "https://github.com/igorcv88/WaEnhancerX/releases";')
text = text.replace('.header("User-Agent", "WaEnhancer X-UpdateChecker")',
                    '.header("User-Agent", "WaEnhancer-Community-UpdateChecker")')
text = re.sub(r'\n\s*if \(BuildConfig\.GH_PUBLIC_TOKEN != null.*?\n\s*}\n', '\n', text,
              flags=re.DOTALL)
save(path, text)


# ---------------------------------------------------------------------------
# CSS validation, last-known-good rollback and safe mode.
# ---------------------------------------------------------------------------
path = "app/src/main/java/com/waenhancer/activities/TextEditorActivity.java"
text = load(path)
if "import com.waenhancer.theme.CssSafetyManager;" not in text:
    text = text.replace("import com.waenhancer.preference.ThemePreference;\n",
                        "import com.waenhancer.preference.ThemePreference;\nimport com.waenhancer.theme.CssSafetyManager;\n")
old = '''            case R.id.menuitem_save -> {
                try {
                    getTextareaContentAsync().thenAccept(content -> {
                        String code = content;
                        File folderFolder = new File(ThemePreference.rootDirectory, folderName);
                        File cssCode = new File(folderFolder, "style.css");
                        FilesKt.writeText(cssCode, code, Charset.defaultCharset());
                        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
                        var prefs = PreferenceManager.getDefaultSharedPreferences(this);
                        var key = getIntent().getStringExtra("key");
                        if (key != null && prefs.getString(key, "").equals(folderName)) {
                            prefs.edit().putString("custom_css", code).commit();
                        }
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
'''
new = '''            case R.id.menuitem_save -> getTextareaContentAsync().thenAccept(content ->
                    runOnUiThread(() -> {
                        try {
                            var preferences = PreferenceManager.getDefaultSharedPreferences(this);
                            CssSafetyManager.SaveResult result =
                                    CssSafetyManager.save(preferences, content);
                            if (!result.saved) {
                                new MaterialAlertDialogBuilder(this)
                                        .setTitle("CSS validation failed")
                                        .setMessage(result.validation.message())
                                        .setPositiveButton(android.R.string.ok, null)
                                        .show();
                                return;
                            }

                            File folder = new File(ThemePreference.rootDirectory, folderName);
                            File cssFile = new File(folder, "style.css");
                            FilesKt.writeText(cssFile, content, Charset.defaultCharset());
                            Toast.makeText(this,
                                    result.validation.warnings.isEmpty()
                                            ? getString(R.string.saved)
                                            : "Saved with warnings: "
                                                + result.validation.message(),
                                    Toast.LENGTH_LONG).show();
                        } catch (Exception exception) {
                            Toast.makeText(this, exception.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }));
'''
if old not in text:
    raise RuntimeError("TextEditor save block not found")
text = text.replace(old, new)
if "import com.google.android.material.dialog.MaterialAlertDialogBuilder;" not in text:
    text = text.replace("import androidx.preference.PreferenceManager;\n",
                        "import androidx.preference.PreferenceManager;\n\nimport com.google.android.material.dialog.MaterialAlertDialogBuilder;\n")
save(path, text)

path = "app/src/main/java/com/waenhancer/xposed/features/customization/CustomThemeV2.java"
text = load(path)
if "import com.waenhancer.theme.CssSafetyManager;" not in text:
    text = text.replace("import com.waenhancer.utils.IColors;\n",
                        "import com.waenhancer.utils.IColors;\nimport com.waenhancer.theme.CssSafetyManager;\n")
text = text.replace('''        if (prefs.getBoolean("lite_mode", false)) {
            return;
        }

        properties = Utils.getProperties(prefs, "custom_css", "custom_filters");
''', '''        if (prefs.getBoolean("lite_mode", false)
                || prefs.getBoolean(CssSafetyManager.KEY_SAFE_MODE, false)) {
            return;
        }

        properties = Utils.getProperties(prefs, "custom_css", "custom_filters");
''')
save(path, text)

path = "app/proguard-rules.pro"
text = load(path)
keep = '''
# jStyleParser uses reflective enum and grammar lookups. These names must survive R8.
-keep class cz.vutbr.web.css.** { *; }
-keep class cz.vutbr.web.csskit.** { *; }
-keepclassmembers enum * { public static **[] values(); public static ** valueOf(java.lang.String); }
-keepattributes Signature,InnerClasses,EnclosingMethod
'''
if "jStyleParser uses reflective" not in text:
    text += keep
save(path, text)


# ---------------------------------------------------------------------------
# Floating Bottom Bar settings entry and runtime controls.
# ---------------------------------------------------------------------------
path = "app/src/main/res/xml/fragment_customization.xml"
text = load(path)
pattern = r'''\n    <PreferenceCategory\n        app:iconSpaceReserved="false"\n        app:title="@string/floating_bottom_bar">.*?\n    </PreferenceCategory>'''
category = '''
    <PreferenceCategory
        app:iconSpaceReserved="false"
        app:title="@string/floating_bottom_bar">

        <rikka.material.preference.MaterialSwitchPreference
            app:key="floating_bottom_bar"
            app:summary="@string/floating_bottom_bar_sum"
            app:title="@string/floating_bottom_bar" />

        <Preference
            app:dependency="floating_bottom_bar"
            app:key="floating_bottom_bar_customizer"
            app:summary="Configure size, glass, margins, FAB, selected indicator and presets"
            app:title="Floating Bottom Bar settings" />

    </PreferenceCategory>'''
text, count = re.subn(pattern, category, text, flags=re.DOTALL)
if count != 1:
    raise RuntimeError(f"floating category replacements: {count}")
save(path, text)

path = "app/src/main/java/com/waenhancer/xposed/features/customization/FloatingBottomBar.java"
text = load(path)
text = text.replace('''    private static boolean scrollHideEnabled = true;
    private static boolean glassEnabled = false;
    private static float glassOpacity = 35f;
    private static int glassFillColor = 0;
''', '''    private static boolean scrollHideEnabled = true;
    private static String scrollHideMode = "tabs";
    private static boolean glassEnabled = false;
    private static float glassOpacity = 35f;
    private static int glassFillColor = 0;
    private static int pillRadiusDp = 28;
    private static int pillSideMarginDp = PILL_SIDE_MARGIN_DP;
    private static int pillBottomMarginDp = PILL_BOTTOM_MARGIN_DP;
    private static int pillManualHeightDp = 64;
    private static boolean pillManualHeight = false;
    private static int fabVisibleOffsetDp = FAB_VISIBLE_OFFSET_DP;
    private static String fabMode = "default";
    private static boolean indicatorVisible = true;
''')
text = text.replace('''        scrollHideEnabled = prefs.getBoolean("floating_bottom_bar_scroll_hide", true);
        glassEnabled = prefs.getBoolean("floating_bottom_bar_glass", true);
        glassOpacity = getPrefFloat(prefs, "floating_bottom_bar_glass_opacity", 35f);
        glassFillColor = getPrefColor(prefs, "floating_bottom_bar_fill_color", 0);
''', '''        scrollHideMode = getPrefString(prefs, "floating_bottom_bar_scroll_hide_mode",
                prefs.getBoolean("floating_bottom_bar_scroll_hide", true) ? "tabs" : "off");
        scrollHideEnabled = !"off".equals(scrollHideMode);
        glassEnabled = prefs.getBoolean("floating_bottom_bar_glass", true);
        glassOpacity = normalized("floating_bottom_bar_glass_opacity");
        glassFillColor = getPrefColor(prefs, "floating_bottom_bar_fill_color", 0);
        pillRadiusDp = prefs.getBoolean("floating_bottom_bar_fully_rounded", false)
                ? 1000 : Math.round(normalized("floating_bottom_bar_radius"));
        pillSideMarginDp = Math.round(normalized("floating_bottom_bar_horizontal_margin"));
        pillBottomMarginDp = Math.round(normalized("floating_bottom_bar_bottom_margin"));
        pillManualHeight = "manual".equals(getPrefString(
                prefs, "floating_bottom_bar_height_mode", "automatic"));
        pillManualHeightDp = Math.round(normalized("floating_bottom_bar_manual_height"));
        fabVisibleOffsetDp = Math.round(normalized("floating_bottom_bar_fab_offset"));
        fabMode = getPrefString(prefs, "floating_bottom_bar_fab_mode", "default");
        indicatorVisible = prefs.getBoolean("floating_bottom_bar_indicator_visible", true);
''')
text = text.replace("shape.setCornerRadius(28 * density);",
                    "shape.setCornerRadius(pillRadiusDp * density);")
text = text.replace("int marginSide = dp(density, PILL_SIDE_MARGIN_DP);",
                    "int marginSide = dp(density, pillSideMarginDp);")
text = text.replace("int marginBottom = dp(density, PILL_BOTTOM_MARGIN_DP);",
                    "int marginBottom = dp(density, pillBottomMarginDp);")
text = text.replace("glassShape.setCornerRadius(28 * density);",
                    "glassShape.setCornerRadius(pillRadiusDp * density);")
text = text.replace("shape.setCornerRadius(28 * density);",
                    "shape.setCornerRadius(pillRadiusDp * density);")
text = text.replace("return -dp(density, FAB_VISIBLE_OFFSET_DP);",
                    "return -dp(density, fabVisibleOffsetDp);")
text = text.replace('''                    final View fab = (View) param.thisObject;
                    if (fab == null) return;
                    fab.addOnAttachStateChangeListener''', '''                    final View fab = (View) param.thisObject;
                    if (fab == null) return;
                    if ("hidden".equals(fabMode)) {
                        fab.setVisibility(View.GONE);
                        return;
                    }
                    if ("minimal".equals(fabMode)) {
                        applyMinimalFab(fab);
                    }
                    fab.addOnAttachStateChangeListener''')
text = text.replace('''                        int paddingVertical = dp(density, Math.round(getPrefFloat(prefs, "floating_bottom_bar_padding_vertical", 6f)));
                        view.setPadding(view.getPaddingLeft(), paddingVertical, view.getPaddingRight(), paddingVertical);
''', '''                        int paddingVertical = dp(density,
                                Math.round(normalized("floating_bottom_bar_padding_vertical")));
                        view.setPadding(view.getPaddingLeft(), paddingVertical,
                                view.getPaddingRight(), paddingVertical);
                        if (pillManualHeight) {
                            ViewGroup.LayoutParams heightParams = view.getLayoutParams();
                            if (heightParams != null) {
                                heightParams.height = dp(density, pillManualHeightDp);
                                view.setLayoutParams(heightParams);
                            }
                        }
''')
text = text.replace('''            boolean isMetaAiActive = isMetaAiTabActive(bottomNav);
''', '''            boolean isMetaAiActive = isMetaAiTabActive(bottomNav);
            applySelectedIndicator(bottomNav, tabId, density);
''')
insert_before = "    private static int getTabIdForIndex(View bottomNav, int index) {"
helpers = '''    private static void applySelectedIndicator(View bottomNav, int selectedId, float density) {
        try {
            ViewGroup menu = findMenuView(bottomNav);
            if (menu == null) return;
            for (int i = 0; i < menu.getChildCount(); i++) {
                View item = menu.getChildAt(i);
                if (!indicatorVisible || item.getId() != selectedId) {
                    item.setBackground(null);
                    continue;
                }
                GradientDrawable indicator = new GradientDrawable();
                int color = getPrefColor(prefs,
                        "floating_bottom_bar_indicator_color", 0);
                if (color == 0) color = DesignUtils.getPrimaryColor();
                int opacity = Math.round(normalized(
                        "floating_bottom_bar_indicator_opacity"));
                indicator.setColor((Math.max(0, Math.min(255,
                        Math.round(opacity * 2.55f))) << 24) | (color & 0x00FFFFFF));
                indicator.setCornerRadius(normalized(
                        "floating_bottom_bar_indicator_radius") * density);
                item.setBackground(indicator);
                item.setTranslationY(normalized(
                        "floating_bottom_bar_indicator_offset") * density);
                int horizontal = Math.round(normalized(
                        "floating_bottom_bar_indicator_padding_horizontal") * density);
                int vertical = Math.round(normalized(
                        "floating_bottom_bar_indicator_padding_vertical") * density);
                item.setPadding(horizontal, vertical, horizontal, vertical);
                if ("manual".equals(getPrefString(prefs,
                        "floating_bottom_bar_indicator_width_mode", "automatic"))) {
                    ViewGroup.LayoutParams params = item.getLayoutParams();
                    params.width = Math.round(normalized(
                            "floating_bottom_bar_indicator_width") * density);
                    if ("manual".equals(getPrefString(prefs,
                            "floating_bottom_bar_indicator_height_mode", "automatic"))) {
                        params.height = Math.round(normalized(
                                "floating_bottom_bar_indicator_height") * density);
                    }
                    item.setLayoutParams(params);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void applyMinimalFab(View fab) {
        try {
            float density = fab.getResources().getDisplayMetrics().density;
            int size = Math.round(normalized("floating_bottom_bar_minimal_fab_size") * density);
            ViewGroup.LayoutParams params = fab.getLayoutParams();
            if (params != null) {
                params.width = size;
                params.height = size;
                fab.setLayoutParams(params);
            }
            GradientDrawable background = new GradientDrawable();
            int color = getPrefColor(prefs, "floating_bottom_bar_minimal_fab_color", 0);
            if (color == 0) color = DesignUtils.getPrimaryColor();
            int opacity = Math.round(normalized("floating_bottom_bar_minimal_fab_opacity"));
            background.setColor((Math.max(0, Math.min(255, Math.round(opacity * 2.55f))) << 24)
                    | (color & 0x00FFFFFF));
            background.setCornerRadius(normalized(
                    "floating_bottom_bar_minimal_fab_radius") * density);
            fab.setBackground(background);
            if (fab instanceof android.widget.ImageView) {
                ((android.widget.ImageView) fab).setImageTintList(ColorStateList.valueOf(
                        getPrefColor(prefs,
                                "floating_bottom_bar_minimal_fab_icon_color", 0xffffffff)));
            }
        } catch (Throwable ignored) {
        }
    }

'''
if insert_before not in text:
    raise RuntimeError("Floating indicator insertion point missing")
text = text.replace(insert_before, helpers + insert_before)
text = text.replace('''    private static float getPrefFloat(SharedPreferences prefs, String key, float defaultValue) {
''', '''    private static float normalized(String key) {
        try {
            return com.waenhancer.config.BottomBarPreferenceSchema.read(prefs, key);
        } catch (Throwable ignored) {
            return 0f;
        }
    }

    private static String getPrefString(SharedPreferences preferences,
                                        String key, String defaultValue) {
        try {
            Object raw = preferences.getAll().get(key);
            return raw == null ? defaultValue : String.valueOf(raw);
        } catch (Throwable ignored) {
            return defaultValue;
        }
    }

    private static float getPrefFloat(SharedPreferences prefs, String key, float defaultValue) {
''')
save(path, text)


# ---------------------------------------------------------------------------
# Settings icons and open feature labels.
# ---------------------------------------------------------------------------
path = "app/src/main/java/com/waenhancer/xposed/features/others/WdsSettingsTileRenderer.java"
text = load(path)
old = '''                String iconName = cat.optString("icon", "ic_settings");
                android.graphics.drawable.Drawable icon = com.waenhancer.xposed.utils.DesignUtils.getDrawableByName(iconName);
                if (icon == null) {
                    icon = com.waenhancer.xposed.utils.DesignUtils.getDrawableByName("ic_settings");
                }
'''
new = '''                String iconName = SettingsIconRegistry.iconName(
                        id, cat.optString("icon", ""));
                android.graphics.drawable.Drawable icon =
                        SettingsIconRegistry.resolve(activity, id, iconName);
'''
if old not in text:
    raise RuntimeError("WDS category icon block missing")
text = text.replace(old, new)
text = text.replace('''                if (!isEnabled) {
                    title = title + " [Pro]";
                }
''', '''                if (!isEnabled) {
                    title = title + " [Unavailable]";
                }
''')
text = text.replace("Failed to show pro bottom sheet", "Failed to show unavailable-feature sheet")
save(path, text)


# Remove obsolete Pro status UI from generated binding layout.
path = "app/src/main/res/layout/fragment_home.xml"
text = load(path)
text = re.sub(r'\n\s*<!-- Pro Status Details.*?</com\.google\.android\.material\.textview\.MaterialTextView>',
              '', text, count=1, flags=re.DOTALL)
text = re.sub(r'\n\s*<!-- Pro Status Floating Chip.*?</com\.google\.android\.material\.textview\.MaterialTextView>',
              '', text, count=1, flags=re.DOTALL)
save(path, text)


# ---------------------------------------------------------------------------
# Identity/version and user-visible strings.
# ---------------------------------------------------------------------------
path = "gradle.properties"
text = load(path)
text = re.sub(r'^VERSION_CODE=.*$', 'VERSION_CODE=18001', text, flags=re.MULTILINE)
text = re.sub(r'^VERSION_NAME=.*$', 'VERSION_NAME=1.8.0-alpha1', text, flags=re.MULTILINE)
save(path, text)

for strings in ROOT.glob("app/src/main/res/values*/strings.xml"):
    text = strings.read_text(encoding="utf-8")
    text = text.replace("WaEnhancer X", "WaEnhancer Community")
    text = text.replace("Wa Enhancer X", "WaEnhancer Community")
    text = text.replace("WaEnhancerX", "WaEnhancer Community")
    if strings.parent.name == "values":
        text = re.sub(r'<string name="app_name"[^>]*>.*?</string>',
                      '<string name="app_name" translatable="false">WaEnhancer Community</string>',
                      text, count=1)
        text = re.sub(r'<string name="app_desc">.*?</string>',
                      '<string name="app_desc">Open-source WhatsApp customization and privacy module with local-only diagnostics.</string>',
                      text, count=1)
    strings.write_text(text, encoding="utf-8")
    print("updated", strings.relative_to(ROOT))

for java in [
    ROOT / "app/src/main/java/com/waenhancer/ui/fragments/HomeFragment.java",
    ROOT / "app/src/main/java/com/waenhancer/activities/AboutActivity.java",
    ROOT / "app/src/main/java/com/waenhancer/activities/ChangelogActivity.java",
]:
    if java.exists():
        text = java.read_text(encoding="utf-8")
        text = text.replace("WaEnhancer X", "WaEnhancer Community")
        text = text.replace("WaEnhancerX", "WaEnhancer Community")
        java.write_text(text, encoding="utf-8")
        print("updated", java.relative_to(ROOT))

print("Block A2 + Block B integration complete")
