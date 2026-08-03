#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text, encoding="utf-8")
    print("updated", path)


def replace(path: str, old: str, new: str, expected: int | None = 1) -> None:
    text = read(path)
    count = text.count(old)
    if expected is not None and count != expected:
        raise RuntimeError(f"Expected {expected} occurrences in {path}, found {count}: {old[:100]!r}")
    write(path, text.replace(old, new))


# 1. Preference XML must not instantiate classes removed with the Pro subsystem.
replace(
    "app/src/main/res/xml/fragment_privacy.xml",
    "<com.waenhancer.preference.ProPreferenceCategory",
    "<PreferenceCategory",
)
replace(
    "app/src/main/res/xml/fragment_privacy.xml",
    "</com.waenhancer.preference.ProPreferenceCategory>",
    "</PreferenceCategory>",
)
replace(
    "app/src/main/res/xml/fragment_media.xml",
    "<com.waenhancer.preference.ProSwitchPreference",
    "<rikka.material.preference.MaterialSwitchPreference",
)
replace(
    "app/src/main/res/xml/preference_general_conversation.xml",
    "<com.waenhancer.preference.ProSwitchPreference",
    "<rikka.material.preference.MaterialSwitchPreference",
    expected=3,
)

# 2. Runtime package targets must follow the Community application ID. The Java
# namespace intentionally remains com.waenhancer, so activity class names remain unchanged.
path = "app/src/main/java/com/waenhancer/xposed/utils/Utils.java"
text = read(path)
if "import com.waenhancer.BuildConfig;" not in text:
    text = text.replace("import com.waenhancer.App;\n", "import com.waenhancer.App;\nimport com.waenhancer.BuildConfig;\n")
text = text.replace('getLaunchIntentForPackage("com.waenhancer")',
                    'getLaunchIntentForPackage(BuildConfig.APPLICATION_ID)')
text = text.replace('new ComponentName("com.waenhancer", "com.waenhancer.activities.MainActivity")',
                    'new ComponentName(BuildConfig.APPLICATION_ID, "com.waenhancer.activities.MainActivity")')
text = text.replace("Error opening WaEnhancer X: ", "Error opening WaEnhancer Community: ")
write(path, text)

path = "app/src/main/java/com/waenhancer/xposed/core/FeatureLoader.java"
text = read(path)
text = text.replace('createPackageContext("com.waenhancer", 0)',
                    'createPackageContext(BuildConfig.APPLICATION_ID, 0)')
text = text.replace('new android.content.ComponentName("com.waenhancer", "com.waenhancer.activities.',
                    'new android.content.ComponentName(BuildConfig.APPLICATION_ID, "com.waenhancer.activities.')
text = text.replace('new ComponentName("com.waenhancer", "com.waenhancer.activities.',
                    'new ComponentName(BuildConfig.APPLICATION_ID, "com.waenhancer.activities.')
write(path, text)

# 3. Restore valid repository/channel slugs after the display-name rebrand.
path = "app/src/main/java/com/waenhancer/ui/fragments/HomeFragment.java"
text = read(path)
text = text.replace("https://github.com/igorcv88/WaEnhancer Community",
                    "https://github.com/igorcv88/WaEnhancerX")
text = text.replace("https://t.me/WaEnhancer Community", "https://t.me/WaEnhancerX")
write(path, text)

# 4. Saving a non-selected theme validates and writes only that theme's file.
# Also eliminate the existing menu switch fall-through, which could execute every
# action after Save/Test in sequence.
path = "app/src/main/java/com/waenhancer/activities/TextEditorActivity.java"
text = read(path)
text = text.replace(
    "    private String folderName;\n",
    "    private String folderName;\n    private String preferenceKey;\n",
    1,
)
text = text.replace(
    '''        folderName = getIntent().getStringExtra("folder_name");
        if (!TextUtils.isEmpty(folderName)) {
''',
    '''        folderName = getIntent().getStringExtra("folder_name");
        preferenceKey = getIntent().getStringExtra("key");
        if (TextUtils.isEmpty(preferenceKey)) {
            preferenceKey = "folder_theme";
        }
        if (!TextUtils.isEmpty(folderName)) {
''',
    1,
)

replacement = r'''    @SuppressLint("NonConstantResourceId")
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menuitem_save -> {
                getTextareaContentAsync().thenAccept(content ->
                        runOnUiThread(() -> saveThemeContent(content)));
                return true;
            }
            case R.id.menuitem_test_theme -> {
                getTextareaContentAsync().thenAccept(content ->
                        runOnUiThread(() -> {
                            var preferences = PreferenceManager.getDefaultSharedPreferences(this);
                            CssSafetyManager.SaveResult result = CssSafetyManager.beginTest(
                                    preferences, content, CssSafetyManager.DEFAULT_TEST_DURATION_MS);
                            if (!result.saved) {
                                new MaterialAlertDialogBuilder(this)
                                        .setTitle("CSS validation failed")
                                        .setMessage(result.validation.message())
                                        .setPositiveButton(android.R.string.ok, null)
                                        .show();
                                return;
                            }
                            LocalDiagnostics.record(this, "css",
                                    "Temporary two-minute CSS test started");
                            notifyCssChanged();
                            restartWhatsAppVariants();
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                LocalDiagnostics.record(this, "css",
                                        "Temporary CSS test expired");
                                notifyCssChanged();
                                restartWhatsAppVariants();
                            }, CssSafetyManager.DEFAULT_TEST_DURATION_MS);
                            new MaterialAlertDialogBuilder(this)
                                    .setTitle("Temporary theme test")
                                    .setMessage("The CSS was validated and enabled for two minutes. "
                                            + "WhatsApp is restarted now and again when the test expires. "
                                            + "The saved theme is not replaced.")
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show();
                        }));
                return true;
            }
            case R.id.menuitem_rollback_theme -> {
                var preferences = PreferenceManager.getDefaultSharedPreferences(this);
                boolean restored = CssSafetyManager.rollback(preferences);
                LocalDiagnostics.record(this, "css", restored
                        ? "Previous valid CSS restored" : "CSS rollback unavailable");
                if (restored) {
                    notifyCssChanged();
                    restartWhatsAppVariants();
                }
                Toast.makeText(this, restored
                                ? "Previous valid CSS restored"
                                : "No previous valid CSS is available",
                        Toast.LENGTH_LONG).show();
                return true;
            }
            case R.id.menuitem_css_safe_mode -> {
                var preferences = PreferenceManager.getDefaultSharedPreferences(this);
                CssSafetyManager.enableSafeMode(preferences);
                LocalDiagnostics.record(this, "css", "CSS safe mode enabled manually");
                notifyCssChanged();
                restartWhatsAppVariants();
                Toast.makeText(this, "CSS safe mode enabled", Toast.LENGTH_LONG).show();
                return true;
            }
            case R.id.menuitem_exit -> {
                finish();
                return true;
            }
            case R.id.menuitem_clear -> {
                updateWebViewContent("");
                return true;
            }
            case R.id.menuitem_import_image -> {
                mGetContent.launch("image/*");
                return true;
            }
            case R.id.menuitem_export -> {
                mExportFile.launch(folderName + ".zip");
                return true;
            }
            default -> {
                return super.onOptionsItemSelected(item);
            }
        }
    }

    private void saveThemeContent(String content) {
        try {
            var preferences = PreferenceManager.getDefaultSharedPreferences(this);
            CssSafetyManager.ValidationResult validation = CssSafetyManager.validate(content);
            if (!validation.valid) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle("CSS validation failed")
                        .setMessage(validation.message())
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                return;
            }

            String selectedTheme = preferences.getString(preferenceKey, null);
            boolean activeTheme = !TextUtils.isEmpty(folderName)
                    && folderName.equals(selectedTheme);
            if (activeTheme) {
                CssSafetyManager.SaveResult result = CssSafetyManager.save(preferences, content);
                if (!result.saved) {
                    Toast.makeText(this, "Could not update the active CSS state",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                validation = result.validation;
            }

            File folder = new File(ThemePreference.rootDirectory, folderName);
            if (!folder.exists() && !folder.mkdirs()) {
                throw new IllegalStateException("Could not create the theme folder");
            }
            File cssFile = new File(folder, "style.css");
            FilesKt.writeText(cssFile, content == null ? "" : content,
                    Charset.defaultCharset());

            LocalDiagnostics.record(this, "css", activeTheme
                    ? "Validated active CSS saved"
                    : "Validated inactive theme CSS saved without activation");
            if (activeTheme) {
                notifyCssChanged();
            }
            Toast.makeText(this,
                    validation.warnings.isEmpty()
                            ? getString(R.string.saved)
                            : "Saved with warnings: " + validation.message(),
                    Toast.LENGTH_LONG).show();
        } catch (Exception exception) {
            Toast.makeText(this, exception.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

'''
pattern = re.compile(
    r'    @SuppressLint\("NonConstantResourceId"\)\n'
    r'    @Override\n'
    r'    public boolean onOptionsItemSelected\(@NonNull MenuItem item\) \{.*?'
    r'(?=    private void notifyCssChanged\(\))',
    re.S,
)
text, count = pattern.subn(replacement, text)
if count != 1:
    raise RuntimeError(f"Expected one menu method replacement, found {count}")
write(path, text)

print("Selected Codex review fixes applied")
