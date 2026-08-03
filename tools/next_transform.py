#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")
    print("updated", path)


# Register local diagnostics activity.
path = "app/src/main/AndroidManifest.xml"
text = read(path)
activity = '''        <activity
            android:name=".activities.DiagnosticsActivity"
            android:exported="false"
            android:parentActivityName=".activities.MainActivity"
            android:theme="@style/AppTheme" />
'''
if '.activities.DiagnosticsActivity' not in text:
    anchor = '        <activity android:name=".activities.AboutActivity" android:theme="@style/AppTheme" />\n'
    text = text.replace(anchor, anchor + activity)
write(path, text)

# Expose diagnostics from General settings using an explicit local intent.
path = "app/src/main/res/xml/fragment_general.xml"
text = read(path)
if 'android:key="local_diagnostics"' not in text:
    preference = '''
    <Preference
        android:key="local_diagnostics"
        android:summary="Preview and share redacted local diagnostics. No automatic upload."
        android:title="Local diagnostics">
        <intent
            android:targetClass="com.waenhancer.activities.DiagnosticsActivity"
            android:targetPackage="com.waenhancer.community" />
    </Preference>
'''
    text = text.replace('</PreferenceScreen>', preference + '\n</PreferenceScreen>')
write(path, text)

# Add CSS test, rollback and safe-mode menu behavior.
path = "app/src/main/java/com/waenhancer/activities/TextEditorActivity.java"
text = read(path)
if 'import com.waenhancer.BuildConfig;' not in text:
    text = text.replace('import com.waenhancer.R;\n',
                        'import com.waenhancer.R;\nimport com.waenhancer.BuildConfig;\nimport com.waenhancer.App;\nimport com.waenhancer.diagnostics.LocalDiagnostics;\n')
insert = '''            case R.id.menuitem_test_theme -> getTextareaContentAsync().thenAccept(content ->
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
                        LocalDiagnostics.record(this, "css", "Temporary two-minute CSS test started");
                        notifyCssChanged();
                        restartWhatsAppVariants();
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            LocalDiagnostics.record(this, "css", "Temporary CSS test expired");
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
            }
            case R.id.menuitem_css_safe_mode -> {
                var preferences = PreferenceManager.getDefaultSharedPreferences(this);
                CssSafetyManager.enableSafeMode(preferences);
                LocalDiagnostics.record(this, "css", "CSS safe mode enabled manually");
                notifyCssChanged();
                restartWhatsAppVariants();
                Toast.makeText(this, "CSS safe mode enabled", Toast.LENGTH_LONG).show();
            }
'''
anchor = '            case R.id.menuitem_exit -> finish();\n'
if 'case R.id.menuitem_test_theme' not in text:
    text = text.replace(anchor, insert + anchor)
helper_anchor = '    private void exportAsZip(Uri uri) {\n'
helpers = '''    private void notifyCssChanged() {
        try {
            getContentResolver().notifyChange(
                    Uri.parse("content://" + BuildConfig.APPLICATION_ID
                            + ".hookprovider/preferences"), null);
        } catch (RuntimeException ignored) {
        }
    }

    private void restartWhatsAppVariants() {
        App.getInstance().restartApp("com.whatsapp");
        App.getInstance().restartApp("com.whatsapp.w4b");
    }

'''
if 'private void notifyCssChanged()' not in text:
    text = text.replace(helper_anchor, helpers + helper_anchor)
# Record successful permanent save and notify runtime.
text = text.replace('''                            FilesKt.writeText(cssFile, content, Charset.defaultCharset());
                            Toast.makeText(this,''', '''                            FilesKt.writeText(cssFile, content, Charset.defaultCharset());
                            LocalDiagnostics.record(this, "css", "Validated CSS saved");
                            notifyCssChanged();
                            Toast.makeText(this,''')
write(path, text)

# Ensure the injected theme parser uses temporary/last-known-good CSS.
path = "app/src/main/java/com/waenhancer/xposed/features/customization/CustomThemeV2.java"
text = read(path)
text = text.replace('''        properties = Utils.getProperties(prefs, "custom_css", "custom_filters");
''', '''        properties = Utils.getPropertiesFromText(
                CssSafetyManager.effectiveCss(prefs),
                prefs.getBoolean("custom_filters", false));
''')
write(path, text)

# Add parser overload for an explicit effective CSS value.
path = "app/src/main/java/com/waenhancer/xposed/utils/Utils.java"
text = read(path)
old = '''    public static Properties getProperties(SharedPreferences prefs, String key, String checkKey) {
        Properties properties = new Properties();
        if (checkKey != null && !prefs.getBoolean(checkKey, false))
            return properties;
        String text = prefs.getString(key, "");
        Pattern pattern = Pattern.compile("^/\\*\\s*(.*?)\\s*\\*/", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(text);

        if (matcher.find()) {
            String propertiesText = matcher.group(1);
            String[] lines = propertiesText.split("\\s*\\n\\s*");

            for (String line : lines) {
                String[] keyValue = line.split("\\s*=\\s*");
                String skey = keyValue[0].strip();
                String value = keyValue[1].strip().replaceAll("^\\\"|\\\"$", ""); // Remove quotes, if any
                properties.put(skey, value);
            }
        }

        return properties;
    }
'''
new = '''    public static Properties getProperties(SharedPreferences prefs, String key, String checkKey) {
        boolean enabled = checkKey == null || prefs.getBoolean(checkKey, false);
        return getPropertiesFromText(prefs.getString(key, ""), enabled);
    }

    public static Properties getPropertiesFromText(String text, boolean enabled) {
        Properties properties = new Properties();
        if (!enabled || text == null) return properties;
        Pattern pattern = Pattern.compile("^/\\*\\s*(.*?)\\s*\\*/", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return properties;

        String[] lines = matcher.group(1).split("\\s*\\n\\s*");
        for (String line : lines) {
            if (line == null || line.isBlank() || !line.contains("=")) continue;
            String[] keyValue = line.split("\\s*=\\s*", 2);
            if (keyValue.length != 2) continue;
            String propertyKey = keyValue[0].strip();
            String value = keyValue[1].strip().replaceAll("^\\\"|\\\"$", "");
            if (!propertyKey.isEmpty()) properties.put(propertyKey, value);
        }
        return properties;
    }
'''
if old not in text:
    raise RuntimeError("Utils.getProperties block changed unexpectedly")
text = text.replace(old, new)
write(path, text)

# Use the unique tune/sparkle entry icon everywhere, not WhatsApp's gear.
path = "app/src/main/java/com/waenhancer/xposed/features/others/SettingsInjector.java"
text = read(path)
text = text.replace('DesignUtils.getDrawableByName("ic_settings")',
                    'SettingsIconRegistry.resolve(activity, "waenhancer", "ic_waenhancer_entry")')
text = text.replace('"WaEnhancerX Settings"', '"WaEnhancer Community"')
text = text.replace('"WaeX Settings"', '"WaEnhancer Community"')
text = text.replace('"Configure WaEnhancerX features, UI customization, and privacy settings."',
                    '"Configure WaEnhancer Community features, appearance, and privacy settings."')
text = text.replace('"[WaEnhancerX]', '"[WaEnhancer Community]')
write(path, text)

# Start a local diagnostic session at manager-app startup.
path = "app/src/main/java/com/waenhancer/App.java"
text = read(path)
if 'import com.waenhancer.diagnostics.LocalDiagnostics;' not in text:
    text = text.replace('import androidx.preference.PreferenceManager;\n',
                        'import androidx.preference.PreferenceManager;\n\nimport com.waenhancer.diagnostics.LocalDiagnostics;\n')
text = text.replace('''        instance = this;

        var sharedPreferences''', '''        instance = this;
        LocalDiagnostics.record(this, "lifecycle", "Manager process started");

        var sharedPreferences''')
write(path, text)

print("diagnostics, CSS test, and unique entry icon integration complete")
