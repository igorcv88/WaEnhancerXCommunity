#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace(path: str, old: str, new: str) -> None:
    target = ROOT / path
    text = target.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"Expected text missing in {path}: {old[:120]!r}")
    target.write_text(text.replace(old, new), encoding="utf-8")
    print("updated", path)


replace(
    "app/src/main/java/com/waenhancer/diagnostics/LocalDiagnostics.java",
    '''        Files.write(file.toPath(), kept, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
''',
    '''        try (java.io.FileOutputStream output = new java.io.FileOutputStream(file, false)) {
            for (String line : kept) {
                output.write(line.getBytes(StandardCharsets.UTF_8));
                output.write('\\n');
            }
        }
''',
)

replace(
    "app/src/main/java/com/waenhancer/activities/BottomBarCustomizationActivity.java",
    "com.waenhancer.R.attr.colorPrimary",
    "android.R.attr.colorAccent",
)

print("Final Android API compatibility fixes complete")
