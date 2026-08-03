#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    target = ROOT / path
    target.write_text(text, encoding="utf-8")
    print(f"updated {path}")


def replace(path: str, old: str, new: str, required: bool = True) -> None:
    text = read(path)
    if old not in text:
        if required:
            raise RuntimeError(f"missing expected text in {path}: {old[:100]!r}")
        return
    write(path, text.replace(old, new))


def regex(path: str, pattern: str, replacement: str, required: bool = True,
          flags: int = re.MULTILINE | re.DOTALL) -> None:
    text = read(path)
    new, count = re.subn(pattern, replacement, text, flags=flags)
    if count == 0 and required:
        raise RuntimeError(f"pattern not found in {path}: {pattern[:100]!r}")
    if count:
        write(path, new)
        print(f"  replacements: {count}")


def delete(*paths: str) -> None:
    for path in paths:
        target = ROOT / path
        if target.exists():
            target.unlink()
            print(f"deleted {path}")


# Feature loader: retain the built-in feature list, remove only external plugin lifecycle.
path = "app/src/main/java/com/waenhancer/xposed/core/FeatureLoader.java"
text = read(path)
text = re.sub(r"^import com\.waex\.api\.plugin\.[^;]+;\n", "", text, flags=re.MULTILINE)
limited_marker = "            // Initialize limited-free feature config in the Xposed context."
if limited_marker in text:
    start = text.index(limited_marker)
    end_marker = "            try {\n                /* Log removed */"
    end = text.index(end_marker, start)
    text = text[:start] + text[end:]
plugin_marker = "        // Load Pro features dynamically if installed"
if plugin_marker in text:
    start = text.index(plugin_marker)
    end = text.index("        executorService.shutdown();", start)
    text = text[:start] + text[end:]
text = text.replace("Failed to invoke doHook on Pro feature", "Failed to invoke doHook on feature")
write(path, text)

# Home screen: remove licensing and remote telemetry UI, keep local status/update functions.
path = "app/src/main/java/com/waenhancer/ui/fragments/HomeFragment.java"
text = read(path)
text = text.replace("https://github.com/mubashardev/WaEnhancer/releases", "https://github.com/igorcv88/WaEnhancerX/releases")
text = text.replace("https://github.com/mubashardev/WaEnhancer/issues", "https://github.com/igorcv88/WaEnhancerX/issues")
text = text.replace("    private BroadcastReceiver proStatusReceiver;\n", "")
text = re.sub(
    r"\n\s*proStatusReceiver = new BroadcastReceiver\(\) \{.*?\n\s*};\n",
    "\n",
    text,
    flags=re.DOTALL,
)
text = re.sub(
    r"\n\s*binding\.proStatusChip\.setOnClickListener\(v -> \{.*?\n\s*}\);\n",
    "\n",
    text,
    flags=re.DOTALL,
)
text = text.replace("\n        showConsentDialogIfNeeded();\n", "\n")
text = re.sub(
    r"\n\s*@Override\n\s*public void onViewCreated\(.*?\n\s*}\n\n\s*private void showConsentDialogIfNeeded\(\) \{.*?\n\s*}\n\n\s*private void openUrl",
    "\n\n    private void openUrl",
    text,
    flags=re.DOTALL,
)
text = re.sub(
    r"\n\s*if \(proStatusReceiver != null && getContext\(\) != null\) \{.*?\n\s*}\n\n\s*updateProUI\(\);",
    "",
    text,
    flags=re.DOTALL,
)
text = re.sub(
    r"\n\s*@Override\n\s*public void onPause\(\) \{.*?\n\s*}\n\n\s*private void updateProUI\(\) \{.*?\n\s*}\n\n\s*@SuppressLint\(\"StringFormatInvalid\"\)",
    "\n\n    @SuppressLint(\"StringFormatInvalid\")",
    text,
    flags=re.DOTALL,
)
text = re.sub(
    r"\n\s*private void launchLicenseActivity\(Context context\) \{.*?\n\s*}\n\n\s*private String getXposedFrameworkVersion",
    "\n\n    private String getXposedFrameworkVersion",
    text,
    flags=re.DOTALL,
)
text = text.replace("WaEnhancerX app", "WaEnhancer Community app")
write(path, text)

# Preference fragments: remove license/keybox-helper hooks while preserving normal preferences.
for path in [
    "app/src/main/java/com/waenhancer/ui/fragments/base/BasePreferenceFragment.java",
    "app/src/main/java/com/waenhancer/xposed/features/others/EmbeddedBasePreferenceFragment.java",
]:
    text = read(path)
    text = re.sub(r"\n\s*try \{\n\s*com\.waenhancer\.utils\.KeyboxFetcher\.syncKeyboxAsync\(.*?\n\s*}\s*catch \(Throwable ignored\) \{\}\n", "\n", text, flags=re.DOTALL)
    text = re.sub(r"\n\s*// Lockdown pro preferences dynamically if not verified\n\s*com\.waenhancer\.xposed\.utils\.ProHelper\.updatePreferences\(.*?;\n", "\n", text)
    text = re.sub(r"\n\s*if \(\"bootloader_spoofer_verify\"\.equals\(preference\.getKey\(\)\)\) \{.*?\n\s*}\n", "\n", text, flags=re.DOTALL)
    write(path, text)

# General settings: remove external plugin installer/update behavior.
path = "app/src/main/java/com/waenhancer/ui/fragments/GeneralFragment.java"
text = read(path)
text = text.replace("            updatePluginPreference();\n", "")
start_marker = "        private void updatePluginPreference() {"
if start_marker in text:
    start = text.index(start_marker)
    end_marker = "    public static class HomeScreenGeneralPreference"
    end = text.index(end_marker, start)
    # Close GeneralPreferenceFragment once, then continue with the next nested class.
    text = text[:start] + "    }\n\n" + text[end:]
write(path, text)

# Remove license gates from filter editor.
path = "app/src/main/java/com/waenhancer/activities/FilterItemsActivity.java"
text = read(path)
text = re.sub(r"\n\s*if \(pos > 0 && !com\.waenhancer\.xposed\.utils\.ProHelper\.isFilterItemsProEnabled\(\)\) \{.*?\n\s*}\n", "\n", text, flags=re.DOTALL)
text = re.sub(r"\n\s*if \(behaviorPos > 0 && !com\.waenhancer\.xposed\.utils\.ProHelper\.isFilterItemsProEnabled\(\)\) \{.*?\n\s*}\n", "\n", text, flags=re.DOTALL)
write(path, text)

# Floating bar: keep the open regular/glass implementation and remove closed design loading.
path = "app/src/main/java/com/waenhancer/xposed/features/customization/FloatingBottomBar.java"
text = read(path)
text = text.replace("    private static boolean pillDesignPro = true;\n", "")
text = re.sub(r"\n\s*// Read pref — default to \"regular\".*?pillDesignPro = .*?;\n", "\n", text, flags=re.DOTALL)
text = re.sub(r"\n\s*if \(pillDesignPro\) \{\n\s*try \{.*?\n\s*}\n\s*}\n", "\n", text, flags=re.DOTALL)
text = text.replace("int paddingVertical = (int) ((pillDesignPro ? 3 : 6) * density);", "int paddingVertical = dp(density, Math.round(getPrefFloat(prefs, \"floating_bottom_bar_padding_vertical\", 6f)));" )
text = re.sub(r"\n\s*if \(pillDesignPro && v\.getMinimumHeight\(\) != 0\) \{\n\s*v\.setMinimumHeight\(0\);\n\s*}\n", "\n", text)
write(path, text)

# Remove helper-specific exception path in typing privacy.
path = "app/src/main/java/com/waenhancer/xposed/features/privacy/TypingPrivacy.java"
text = read(path)
text = re.sub(r"\n\s*if \(\"true\"\.equals\(System\.getProperty\(\"com\.waex\.helper\.AlwaysTyping\.isEngineTriggering\"\)\)\) \{.*?\n\s*}\n", "\n", text, flags=re.DOTALL)
write(path, text)

# XML: remove telemetry, helper-only features, license categories and closed pill designs.
path = "app/src/main/res/xml/fragment_general.xml"
text = read(path)
text = re.sub(r"\n\s*<rikka\.material\.preference\.MaterialSwitchPreference\n\s*app:defaultValue=\"false\"\n\s*app:key=\"enable_crash_analytics\".*?/>\n", "\n", text, flags=re.DOTALL)
text = re.sub(r"\n\s*<Preference\n\s*android:key=\"bootloader_spoofer_verify\".*?/>\n", "\n", text, flags=re.DOTALL)
text = re.sub(r"\n\s*<com\.waenhancer\.preference\.ProSwitchPreference.*?/>\n", "\n", text, flags=re.DOTALL)
text = re.sub(r"\n\s*<com\.waenhancer\.preference\.ProPreferenceCategory.*?</com\.waenhancer\.preference\.ProPreferenceCategory>\n", "\n", text, flags=re.DOTALL)
text = re.sub(r"\n\s*<PreferenceCategory\n\s*android:key=\"plugin_pack_category\".*?</PreferenceCategory>\n", "\n", text, flags=re.DOTALL)
text = text.replace('android:targetPackage="com.waenhancer"', 'android:targetPackage="com.waenhancer.community"')
write(path, text)

path = "app/src/main/res/xml/fragment_customization.xml"
text = read(path)
text = re.sub(r"\n\s*<com\.waenhancer\.preference\.ProListPreference.*?/>\n", "\n", text, flags=re.DOTALL)
write(path, text)

# Remove Pro/helper-specific resources and runtime implementation.
delete(
    "app/src/main/aidl/com/waex/helper/IProService.aidl",
    "app/src/main/java/com/waenhancer/activities/LicenseActivity.java",
    "app/src/main/java/com/waenhancer/adapter/ProFeatureAdapter.java",
    "app/src/main/java/com/waenhancer/preference/LicensePreference.java",
    "app/src/main/java/com/waenhancer/preference/ProListPreference.java",
    "app/src/main/java/com/waenhancer/preference/ProPreferenceCategory.java",
    "app/src/main/java/com/waenhancer/preference/ProSwitchPreference.java",
    "app/src/main/java/com/waenhancer/xposed/utils/LicenseManager.java",
    "app/src/main/java/com/waenhancer/xposed/utils/ProHelper.java",
    "app/src/main/java/com/waenhancer/xposed/utils/Config.java",
    "app/src/main/java/com/waenhancer/utils/AnalyticsManager.java",
    "app/src/main/java/com/waenhancer/utils/KeyboxValidator.java",
    "app/src/main/java/com/waenhancer/xposed/core/plugins/IsolatedParentClassLoader.java",
    "app/src/main/java/com/waenhancer/xposed/core/plugins/PluginContextImpl.java",
    "app/src/main/java/com/waenhancer/xposed/core/plugins/impl/CoreBridgeImpl.java",
    "app/src/main/java/com/waenhancer/xposed/core/plugins/impl/HookServiceImpl.java",
    "app/src/main/java/com/waenhancer/xposed/core/plugins/impl/ObfuscationServiceImpl.java",
    "app/src/main/java/com/waenhancer/xposed/core/plugins/impl/StateServiceImpl.java",
    "app/src/main/res/layout/activity_license.xml",
    "app/src/main/res/layout/item_pro_feature_card.xml",
    "app/src/main/res/layout/bottom_sheet_keybox_verify.xml",
)

# Remove obsolete keep rules that refer to deleted licensing classes.
path = "app/proguard-rules.pro"
text = read(path)
text = re.sub(r"\n# Keep the names and reflective entrypoints of LicenseManager.*?\n}\n", "\n", text, flags=re.DOTALL)
write(path, text)

print("Block A1 transformation complete")
