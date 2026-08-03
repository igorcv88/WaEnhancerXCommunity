#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")
    print("updated", path)


# Repair NewChat by replacing complete structural regions, not nested regex fragments.
path = "app/src/main/java/com/waenhancer/xposed/features/general/NewChat.java"
text = read(path)
validation_start = text.index('                    String validationNumber = "+" + cc + phone;')
validation_end = text.index('                    if (!isValid) {', validation_start)
validation = '''                    String validationNumber = "+" + cc + phone;
                    // Local conservative E.164 validation. WhatsApp performs the final account check.
                    boolean isValid = validationNumber.matches("\\+[1-9]\\d{7,14}");

'''
text = text[:validation_start] + validation + text[validation_end:]
helpers_start = text.index('    private static boolean isValidatorLoaded(Activity activity) {')
helpers_end = text.index('    private static void updatePhoneHintAndLength(', helpers_start)
helpers = '''    private static boolean isValidatorLoaded(Activity activity) {
        return true;
    }

    private static String getCountryHint(Activity activity, String cc, String activeIso) {
        String country = getCountryName(activeIso);
        return country == null || country.isBlank()
                ? "Phone number"
                : country + " phone number";
    }

    private static int getCountryPhoneLength(Activity activity, String cc) {
        int countryCodeLength = cc == null ? 0 : cc.replaceAll("\\D", "").length();
        return Math.max(4, Math.min(14, 15 - countryCodeLength));
    }

'''
text = text[:helpers_start] + helpers + text[helpers_end:]
write(path, text)

# Remove obsolete subscription/reversion UI and any invocation sites.
path = "app/src/main/java/com/waenhancer/activities/MainActivity.java"
text = read(path)
text = re.sub(r'^\s*showReversionBottomSheet\(\);\s*$', '', text, flags=re.MULTILINE)
text = re.sub(r'^\s*showDowngradeBottomSheet\([^;]*\);\s*$', '', text, flags=re.MULTILINE)
method_start = text.find('    private void showReversionBottomSheet() {')
if method_start >= 0:
    text = text[:method_start].rstrip() + '\n}\n'
write(path, text)

# Static callbacks in FloatingBottomBar need a stable preference reference.
path = "app/src/main/java/com/waenhancer/xposed/features/customization/FloatingBottomBar.java"
text = read(path)
field_anchor = '    private static boolean indicatorVisible = true;\n'
if 'private static SharedPreferences activePrefs;' not in text:
    text = text.replace(field_anchor, field_anchor + '    private static SharedPreferences activePrefs;\n')
text = text.replace('''    public void doHook() throws Throwable {
        if (!prefs.getBoolean("floating_bottom_bar", false)) return;
''', '''    public void doHook() throws Throwable {
        activePrefs = prefs;
        if (!prefs.getBoolean("floating_bottom_bar", false)) return;
''')
text = text.replace('getPrefColor(prefs,', 'getPrefColor(activePrefs,')
text = text.replace('getPrefString(prefs,', 'getPrefString(activePrefs,')
text = text.replace('return com.waenhancer.config.BottomBarPreferenceSchema.read(prefs, key);',
                    'return activePrefs == null ? 0f : com.waenhancer.config.BottomBarPreferenceSchema.read(activePrefs, key);')
write(path, text)

print("structural compile repairs complete")
