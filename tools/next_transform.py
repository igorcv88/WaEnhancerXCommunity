#!/usr/bin/env python3
from pathlib import Path

path = Path(__file__).resolve().parents[1] / "app/build.gradle.kts"
text = path.read_text(encoding="utf-8")
needle = "    implementation(libs.arscblamer)\n"
replacement = needle + "    implementation(\"com.google.auto.value:auto-value-annotations:1.11.0\")\n"
if replacement not in text:
    if needle not in text:
        raise RuntimeError("arscblamer dependency anchor not found")
    text = text.replace(needle, replacement)
path.write_text(text, encoding="utf-8")
print("Added AutoValue annotations required by arscblamer during R8")
