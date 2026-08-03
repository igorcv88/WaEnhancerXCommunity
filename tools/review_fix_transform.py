#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

show_path = ROOT / "app/src/main/java/com/waenhancer/xposed/features/general/ShowEditMessage.java"
show = show_path.read_text(encoding="utf-8")
if "import com.waenhancer.BuildConfig;" not in show:
    show = show.replace("import com.waenhancer.R;\n", "import com.waenhancer.R;\nimport com.waenhancer.BuildConfig;\n")
old = 'ctx.createPackageContext("com.waenhancer", android.content.Context.CONTEXT_IGNORE_SECURITY)'
if old not in show:
    raise RuntimeError("ShowEditMessage old package target not found")
show = show.replace(old,
        'ctx.createPackageContext(BuildConfig.APPLICATION_ID, android.content.Context.CONTEXT_IGNORE_SECURITY)')
show_path.write_text(show, encoding="utf-8")
print("updated", show_path)

update_path = ROOT / "app/src/main/java/com/waenhancer/UpdateChecker.java"
update = update_path.read_text(encoding="utf-8")
component_old = 'new android.content.ComponentName("com.waenhancer", "com.waenhancer.activities.ChangelogActivity")'
if update.count(component_old) != 2:
    raise RuntimeError(f"Expected two UpdateChecker component targets, found {update.count(component_old)}")
update = update.replace(component_old,
        'new android.content.ComponentName(BuildConfig.APPLICATION_ID, "com.waenhancer.activities.ChangelogActivity")')
context_old = 'mActivity.createPackageContext("com.waenhancer", android.content.Context.CONTEXT_IGNORE_SECURITY)'
if context_old not in update:
    raise RuntimeError("UpdateChecker old package context target not found")
update = update.replace(context_old,
        'mActivity.createPackageContext(BuildConfig.APPLICATION_ID, android.content.Context.CONTEXT_IGNORE_SECURITY)')
update_path.write_text(update, encoding="utf-8")
print("updated", update_path)
