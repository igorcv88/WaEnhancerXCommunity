#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1] / "app/src/main/res/xml"
changed = []
for path in root.glob("*.xml"):
    text = path.read_text(encoding="utf-8")
    updated = text.replace(
        "<com.waenhancer.preference.ProSwitchPreference",
        "<rikka.material.preference.MaterialSwitchPreference",
    ).replace(
        "<com.waenhancer.preference.ProPreferenceCategory",
        "<PreferenceCategory",
    ).replace(
        "</com.waenhancer.preference.ProPreferenceCategory>",
        "</PreferenceCategory>",
    )
    if updated != text:
        path.write_text(updated, encoding="utf-8")
        changed.append(str(path.relative_to(root.parents[4])))

if not changed:
    raise RuntimeError("No remaining Pro preference XML references were found")
print("updated", *changed, sep="\n")
