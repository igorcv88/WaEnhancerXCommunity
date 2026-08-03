#!/usr/bin/env python3
from pathlib import Path

path = Path(__file__).resolve().parents[1] / "app/src/main/java/com/waenhancer/activities/AboutActivity.java"
text = path.read_text(encoding="utf-8")
replacements = {
    "https://api.github.com/repos/mubashardev/WaEnhancer/contributors":
        "https://api.github.com/repos/igorcv88/WaEnhancerX/contributors",
    "https://github.com/mubashardev/WaEnhancer/issues":
        "https://github.com/igorcv88/WaEnhancerX/issues",
    "https://t.me/WaEnhancer Community": "https://t.me/WaEnhancerX",
}
for old, new in replacements.items():
    if old not in text:
        raise RuntimeError(f"Expected AboutActivity link not found: {old}")
    text = text.replace(old, new)
path.write_text(text, encoding="utf-8")
print("updated", path)
