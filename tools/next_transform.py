#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace(path, old, new):
    target = ROOT / path
    text = target.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"Expected text missing in {path}: {old!r}")
    target.write_text(text.replace(old, new), encoding="utf-8")
    print("updated", path)


replace(
    "app/src/main/java/com/waenhancer/xposed/utils/Utils.java",
    'Pattern.compile("^/\\*\\s*(.*?)\\s*\\*/", Pattern.DOTALL)',
    'Pattern.compile("^/\\\\*\\\\s*(.*?)\\\\s*\\\\*/", Pattern.DOTALL)',
)
replace(
    "app/src/main/java/com/waenhancer/xposed/utils/Utils.java",
    'split("\\s*\\n\\s*")',
    'split("\\\\s*\\\\n\\\\s*")',
)
replace(
    "app/src/main/java/com/waenhancer/xposed/utils/Utils.java",
    'split("\\s*=\\s*", 2)',
    'split("\\\\s*=\\\\s*", 2)',
)
replace(
    "app/src/main/java/com/waenhancer/xposed/features/general/NewChat.java",
    'matches("\\+[1-9]\\d{7,14}")',
    'matches("\\\\+[1-9]\\\\d{7,14}")',
)
replace(
    "app/src/main/java/com/waenhancer/xposed/features/general/NewChat.java",
    'replaceAll("\\D", "")',
    'replaceAll("\\\\D", "")',
)

print("Java regex escape corrections complete")
