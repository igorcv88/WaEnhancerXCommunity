#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")
    print("updated", path)


# Remove remaining helper/licensing references.
path = "app/src/main/java/com/waenhancer/xposed/features/others/EmbeddedBasePreferenceFragment.java"
text = read(path)
text = re.sub(r'\n\s*com\.waenhancer\.xposed\.utils\.ProHelper\.updatePreferences\([^;]+;\n', '\n', text)
write(path, text)

path = "app/src/main/java/com/waenhancer/xposed/utils/Utils.java"
text = read(path)
text = text.replace('new NotificationChannel("waex", "Wa Enhancer X",',
                    'new NotificationChannel("waex", "WaEnhancer Community",')
text = re.sub(r'\n\s*public static void handleSubscriptionDowngrade\(Context context, String reasonMsg\) \{.*?\n\s*}\n\n\s*public static void openLink',
              '\n\n    public static void openLink', text, flags=re.DOTALL)
write(path, text)

path = "app/src/main/java/com/waenhancer/activities/MainActivity.java"
text = read(path)
text = re.sub(r'\n\s*private void showStableRevertBottomSheet\(\) \{.*?\n\s*}\n\n\s*private void showDowngradeBottomSheet\(String message\) \{.*?\n\s*}\n(?=})',
              '\n', text, flags=re.DOTALL)
text = re.sub(r'\n\s*showStableRevertBottomSheet\(\);', '', text)
text = re.sub(r'\n\s*showDowngradeBottomSheet\([^;]+;', '', text)
text = text.replace("pending_downgrade_reason_msg", "obsolete_downgrade_notice")
write(path, text)

path = "app/src/main/java/com/waenhancer/xposed/features/general/Others.java"
text = read(path)
text = re.sub(r'^import com\.waenhancer\.xposed\.utils\.ProHelper;\n', '', text, flags=re.MULTILINE)
text = re.sub(r'''\n\s*if \(audio_type == 2\) \{\n\s*// Transcode local audio to Opus before sending as voice note.*?\n\s*}\n\n\s*param\.args\[audioType\.first\] = audio_type - 1;''',
              '''
                // The former value 2 depended on a closed external transcoder. Degrade it
                // to ordinary audio instead of mislabelling an incompatible file as Opus.
                int effectiveAudioType = audio_type == 2 ? 1 : audio_type;
                param.args[audioType.first] = effectiveAudioType - 1;''',
              text, flags=re.DOTALL)
write(path, text)

path = "app/src/main/java/com/waenhancer/xposed/features/general/NewChat.java"
text = read(path)
text = re.sub(r'''\n\s*String validationNumber = "\\+" \+ cc \+ phone;\n\s*boolean isValid = true;\n\n\s*// Final validation guard before starting the chat\n\s*try \{.*?\n\s*}\n\n\s*/\* Log removed \*/''',
              '''
                    String validationNumber = "+" + cc + phone;
                    boolean isValid = validationNumber.matches("\\\\+[1-9]\\\\d{7,14}");
''', text, flags=re.DOTALL)
text = re.sub(r'''\n\s*private static boolean isValidatorLoaded\(Activity activity\) \{.*?\n\s*}\n\n\s*private static String getCountryHint\(Activity activity, String cc, String activeIso\) \{.*?\n\s*}\n\n\s*private static int getCountryPhoneLength\(Activity activity, String cc\) \{.*?\n\s*}\n''',
              '''

    private static boolean isValidatorLoaded(Activity activity) {
        return true;
    }

    private static String getCountryHint(Activity activity, String cc, String activeIso) {
        String country = getCountryName(activeIso);
        return country == null || country.isBlank()
                ? "Phone number"
                : country + " phone number";
    }

    private static int getCountryPhoneLength(Activity activity, String cc) {
        int countryCodeLength = cc == null ? 0 : cc.replaceAll("\\\\D", "").length();
        return Math.max(4, Math.min(14, 15 - countryCodeLength));
    }
''', text, flags=re.DOTALL)
write(path, text)

# Remove helper consent resource text even if no longer referenced.
for strings in ROOT.glob("app/src/main/res/values*/strings.xml"):
    text = strings.read_text(encoding="utf-8")
    text = re.sub(r'\n\s*<string name="pro_download_consent_msg">.*?</string>', '', text)
    text = re.sub(r'\n\s*<string name="pro_download_consent_title">.*?</string>', '', text)
    strings.write_text(text, encoding="utf-8")

# Semantic preset integration in the injected process.
path = "app/src/main/java/com/waenhancer/xposed/utils/DesignUtils.java"
text = read(path)
if "import com.waenhancer.theme.SemanticTheme;" not in text:
    text = text.replace("import com.waenhancer.xposed.core.WppCore;\n",
                        "import com.waenhancer.xposed.core.WppCore;\nimport com.waenhancer.theme.SemanticTheme;\n")
text = re.sub(r'''    public static int getPrimaryTextColor\(\) \{.*?\n    }\n\n    public static int getUnSeenColor''', '''    public static int getPrimaryTextColor() {
        try {
            if (mPrefs == null) return isNightMode() ? 0xfffffffe : 0xff000001;
            if (!mPrefs.getBoolean("changecolor", false)) {
                return isNightMode() ? 0xfffffffe : 0xff000001;
            }
            int explicit = mPrefs.getInt("text_color", 0);
            if (shouldUseMonetColors()) {
                int monet = resolveMonetColor(isNightMode()
                        ? "system_neutral1_100" : "system_neutral1_900");
                if (monet != 0) explicit = monet;
            }
            return explicit != 0 ? explicit : semanticToken("onSurface");
        } catch (Throwable ignored) {
            return isNightMode() ? 0xfffffffe : 0xff000001;
        }
    }

    public static int getUnSeenColor''', text, flags=re.DOTALL)
text = re.sub(r'''    public static int getUnSeenColor\(\) \{.*?\n    }\n\n    public static int getPrimarySurfaceColor''', '''    public static int getUnSeenColor() {
        try {
            if (mPrefs == null || !mPrefs.getBoolean("changecolor", false)) return 0xFF25d366;
            int explicit = mPrefs.getInt("primary_color", 0);
            if (shouldUseMonetColors()) {
                int monet = resolveMonetColor(isNightMode()
                        ? "system_accent1_300" : "system_accent1_600");
                if (monet != 0) explicit = monet;
            }
            return explicit != 0 ? explicit : semanticToken("primary");
        } catch (Throwable ignored) {
            return 0xFF25d366;
        }
    }

    public static int getPrimaryColor() {
        return getUnSeenColor();
    }

    public static int getPrimarySurfaceColor''', text, flags=re.DOTALL)
text = re.sub(r'''    public static int getPrimarySurfaceColor\(\) \{.*?\n    }\n\n    public static Drawable generatePrimaryColorDrawable''', '''    public static int getPrimarySurfaceColor() {
        try {
            if (mPrefs == null || !mPrefs.getBoolean("changecolor", false)) {
                return isNightMode() ? 0xff121212 : 0xfffffffe;
            }
            int explicit = mPrefs.getInt("background_color", 0);
            if (shouldUseMonetColors()) {
                int monet = resolveMonetColor(isNightMode()
                        ? "system_neutral1_900" : "system_neutral1_10");
                if (monet != 0) explicit = monet;
            }
            return explicit != 0 ? explicit : semanticToken("surface");
        } catch (Throwable ignored) {
            return isNightMode() ? 0xff121212 : 0xfffffffe;
        }
    }

    private static int semanticToken(String token) {
        String preset = mPrefs == null ? "green"
                : mPrefs.getString("wae_color_preset", "green");
        return SemanticTheme.fromPreset(preset, isNightMode()).get(token);
    }

    public static Drawable generatePrimaryColorDrawable''', text, flags=re.DOTALL)
text = text.replace('''    public static int getThemeAccentColor(android.content.Context context) {
        int color = resolveColorAttr(context, android.R.attr.colorAccent);
        if (color == 0) return 0xff25d366; // WhatsApp Green
        return color;
    }
''', '''    public static int getThemeAccentColor(android.content.Context context) {
        if (mPrefs != null && mPrefs.getBoolean("changecolor", false)) {
            return getPrimaryColor();
        }
        int color = resolveColorAttr(context, android.R.attr.colorAccent);
        return color == 0 ? 0xff25d366 : color;
    }
''')
write(path, text)

# Theme selection and zip import use the same CSS validation policy and safe extraction.
path = "app/src/main/java/com/waenhancer/preference/ThemePreference.java"
text = read(path)
if "import com.waenhancer.theme.CssSafetyManager;" not in text:
    text = text.replace("import com.waenhancer.activities.TextEditorActivity;\n",
                        "import com.waenhancer.activities.TextEditorActivity;\nimport com.waenhancer.theme.CssSafetyManager;\n")
text = text.replace("Please use the standalone WaEnhancerX app", "Please use the standalone WaEnhancer Community app")
text = text.replace('''                    var code = FilesKt.readText(cssFile, Charset.defaultCharset());
                    getSafeSharedPreferences().edit().putString("custom_css", code).commit();
''', '''                    var code = FilesKt.readText(cssFile, Charset.defaultCharset());
                    CssSafetyManager.SaveResult result =
                            CssSafetyManager.save(getSafeSharedPreferences(), code);
                    if (!result.saved) {
                        Toast.makeText(context, result.validation.message(), Toast.LENGTH_LONG).show();
                        return;
                    }
''')
# Canonical path check + simple quotas in zip loop.
text = text.replace('''                while ((zipEntry = zipInputStream.getNextEntry()) != null) {
                    var entryName = zipEntry.getName();
''', '''                int entryCount = 0;
                long extractedBytes = 0;
                while ((zipEntry = zipInputStream.getNextEntry()) != null) {
                    if (++entryCount > 100) throw new IllegalArgumentException("Theme has too many files.");
                    var entryName = zipEntry.getName();
                    if (entryName.contains("..") || entryName.startsWith("/")
                            || entryName.startsWith("\\\\")) {
                        throw new IllegalArgumentException("Unsafe path in theme archive.");
                    }
''')
text = text.replace('''                    var file = new File(rootDirectory, targetPath);
                    Files.copy(zipInputStream, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
''', '''                    var file = new File(rootDirectory, targetPath);
                    String rootPath = rootDirectory.getCanonicalPath() + File.separator;
                    if (!file.getCanonicalPath().startsWith(rootPath)) {
                        throw new IllegalArgumentException("Theme archive escapes the theme directory.");
                    }
                    byte[] bytes = zipInputStream.readAllBytes();
                    extractedBytes += bytes.length;
                    if (extractedBytes > 20L * 1024L * 1024L) {
                        throw new IllegalArgumentException("Theme exceeds the 20 MB extraction limit.");
                    }
                    Files.write(file.toPath(), bytes);
                    if (file.getName().equalsIgnoreCase("style.css")) {
                        String css = Files.readString(file.toPath());
                        CssSafetyManager.ValidationResult validation = CssSafetyManager.validate(css);
                        if (!validation.valid) {
                            file.delete();
                            throw new IllegalArgumentException(validation.message());
                        }
                    }
''')
write(path, text)

# Small compile/compatibility corrections.
path = "app/src/main/java/com/waenhancer/backup/BackupCodec.java"
text = read(path).replace("new JSONArray(value)", "new JSONArray((Set<?>) value)")
write(path, text)

path = "app/src/main/java/com/waenhancer/activities/BottomBarCustomizationActivity.java"
text = read(path).replace("androidx.appcompat.R.drawable.abc_ic_ab_back_material",
                          "android.R.drawable.ic_media_previous")
write(path, text)

print("residual closed-system removal and semantic theme integration complete")
