#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")
    print("updated", path)


def delete(path):
    target = ROOT / path
    if target.exists():
        target.unlink()
        print("deleted", path)


# Remove the obsolete Pro submodule discovery path entirely.
write("settings.gradle.kts", '''pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://api.xposed.info/")
        maven("https://jitpack.io")
    }
}

rootProject.name = "WaEnhancer Community"
include(":app")
include(":api")
''')

# Remove obsolete external-plugin capability registry from FeatureLoader.
path = "app/src/main/java/com/waenhancer/xposed/core/FeatureLoader.java"
text = read(path)
start = text.find('    private static class CapabilityRegistryImpl implements ICapabilityRegistry {')
if start >= 0:
    end = text.index('    private static class ErrorItem {', start)
    text = text[:start] + text[end:]
text = text.replace("WaEnhancer X", "WaEnhancer Community")
write(path, text)

# Remove the embedded private key/certificate fallback and related manager UI.
path = "app/src/main/java/com/waenhancer/ui/fragments/base/BasePreferenceFragment.java"
text = read(path)
text = text.replace('import com.waenhancer.utils.KeyboxValidator;\n', '')
text = text.replace('    private static final String RELEASES_URL = "https://github.com/mubashardev/WaEnhancer/releases";',
                    '    private static final String RELEASES_URL = "https://github.com/igorcv88/WaEnhancerX/releases";')
text = text.replace('    private static final String LATEST_STABLE_URL = "https://github.com/mubashardev/WaEnhancer/releases/latest";',
                    '    private static final String LATEST_STABLE_URL = "https://github.com/igorcv88/WaEnhancerX/releases/latest";')
text = text.replace('    // Default keybox verify results are persisted in SharedPreferences via KeyboxVerificationImpl (pro module).\n', '')
text = text.replace('        updateKeyboxVerifySummary();\n', '')
text = re.sub(r'''\n\s*if \(Objects\.equals\(s, "bootloader_spoofer_xml"\).*?\n\s*}\n\n\s*// Flag that a restart''',
              '\n\n        // Flag that a restart', text, flags=re.DOTALL)
start = text.find('    private void updateKeyboxVerifySummary() {')
if start >= 0:
    text = text[:start].rstrip() + '\n}\n'
write(path, text)

# The verifier and fetcher were tied to keybox material that must not ship in Block A.
delete("app/src/main/java/com/waenhancer/utils/KeyboxVerification.java")
delete("app/src/main/java/com/waenhancer/utils/KeyboxFetcher.java")

# Remove any remaining imports of deleted keybox classes.
for source in (ROOT / "app/src/main/java").rglob("*.java"):
    text = source.read_text(encoding="utf-8")
    updated = re.sub(r'^import com\.waenhancer\.utils\.Keybox(?:Validator|Verification|Fetcher);\n',
                     '', text, flags=re.MULTILINE)
    if updated != text:
        source.write_text(updated, encoding="utf-8")
        print("updated", source.relative_to(ROOT))

print("keybox material and plugin scaffold removal complete")
