#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text, encoding="utf-8")
    print("updated", path)


def replace(path: str, old: str, new: str) -> None:
    text = read(path)
    if old not in text:
        raise RuntimeError(f"Expected text missing in {path}: {old[:120]!r}")
    write(path, text.replace(old, new))


# Android's desugared java.nio surface does not expose Java 11 Files.writeString here.
path = "app/src/main/java/com/waenhancer/diagnostics/LocalDiagnostics.java"
text = read(path)
old = '''                Files.writeString(file.toPath(), line + "\\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
'''
new = '''                try (java.io.FileOutputStream output =
                             new java.io.FileOutputStream(file, true)) {
                    output.write((line + "\\n").getBytes(StandardCharsets.UTF_8));
                }
'''
if old not in text:
    raise RuntimeError("LocalDiagnostics append block changed unexpectedly")
text = text.replace(old, new)
text = text.replace("import java.nio.file.StandardOpenOption;\n", "")
write(path, text)

# BottomSheetHelper expects its own no-argument listener, not Runnable.
replace(
    "app/src/main/java/com/waenhancer/ui/fragments/HomeFragment.java",
    '''                false,
                launchExport);
''',
    '''                false,
                () -> launchExport.run());
''',
)

# Use Kotlin FilesKt already supported by this Android project.
replace(
    "app/src/main/java/com/waenhancer/preference/ThemePreference.java",
    '''                        String css = Files.readString(file.toPath());
''',
    '''                        String css = FilesKt.readText(file, Charset.defaultCharset());
''',
)

# Resolve the app theme attribute rather than a generated Material library attr.
replace(
    "app/src/main/java/com/waenhancer/activities/BottomBarCustomizationActivity.java",
    "com.google.android.material.R.attr.colorPrimary",
    "com.waenhancer.R.attr.colorPrimary",
)

# Remove the last invocation of the deleted embedded keybox subsystem.
path = "app/src/main/java/com/waenhancer/activities/MainActivity.java"
text = read(path)
text, count = re.subn(
    r'''\n\s*try \{\n\s*com\.waenhancer\.utils\.KeyboxFetcher\.syncKeyboxAsync\(this\);\n\s*} catch \(Throwable ignored\) \{}\n''',
    "\n",
    text,
)
if count != 1:
    raise RuntimeError(f"Expected one MainActivity KeyboxFetcher call, found {count}")
write(path, text)

# Public GitHub release endpoints need no token compiled into the APK.
for path in [
    "app/src/main/java/com/waenhancer/activities/ReleaseDetailsActivity.java",
    "app/src/main/java/com/waenhancer/activities/ChangelogActivity.java",
]:
    text = read(path)
    text = text.replace("https://api.github.com/repos/mubashardev/WaEnhancer/releases",
                        "https://api.github.com/repos/igorcv88/WaEnhancerX/releases")
    text = text.replace("WaEnhancer X-UpdateChecker", "WaEnhancer-Community-UpdateChecker")
    text = re.sub(
        r'''\n\s*if \(BuildConfig\.GH_PUBLIC_TOKEN != null && !BuildConfig\.GH_PUBLIC_TOKEN\.isEmpty\(\)\) \{\n\s*requestBuilder\.header\("Authorization", "Bearer " \+ BuildConfig\.GH_PUBLIC_TOKEN\);\n\s*}\n''',
        "\n",
        text,
    )
    if "BuildConfig." not in text:
        text = text.replace("import com.waenhancer.BuildConfig;\n\n", "")
    write(path, text)

print("Final javac integration fixes complete")
