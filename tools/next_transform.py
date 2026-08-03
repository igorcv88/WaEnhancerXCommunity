#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def patch(path, replacements):
    target = ROOT / path
    text = target.read_text(encoding="utf-8")
    for old, new in replacements:
        if old not in text:
            raise RuntimeError(f"Expected block missing in {path}: {old[:100]!r}")
        text = text.replace(old, new)
    target.write_text(text, encoding="utf-8")
    print("updated", path)


patch(
    "app/src/main/java/com/waenhancer/xposed/features/customization/FloatingBottomBar.java",
    [
        ("    private static boolean indicatorVisible = true;\n",
         "    private static boolean indicatorVisible = false;\n"),
        ('        indicatorVisible = prefs.getBoolean("floating_bottom_bar_indicator_visible", true);\n',
         '        indicatorVisible = prefs.getBoolean("floating_bottom_bar_indicator_visible", false);\n'),
        ('''                                    if (isMainTabScrollable(v)) {
                                        View bottomNav = findBottomNavForScrollable(v);
''', '''                                    if ("all".equals(scrollHideMode) || isMainTabScrollable(v)) {
                                        View bottomNav = findBottomNavForScrollable(v);
'''),
        ('''                            if (!isMainTabScrollable(scrollView)) {
                                restoreOriginalBottomPadding(scrollView);
                                return;
                            }
''', '''                            if (!"all".equals(scrollHideMode)
                                    && !isMainTabScrollable(scrollView)) {
                                restoreOriginalBottomPadding(scrollView);
                                return;
                            }
'''),
        ('''            if (!isMainTabScrollable(rv)) return;

            View bottomNav = findBottomNavForScrollable(rv);
''', '''            if (!"all".equals(scrollHideMode) && !isMainTabScrollable(rv)) return;

            View bottomNav = findBottomNavForScrollable(rv);
'''),
        ('''    private static void applySelectedIndicator(View bottomNav, int selectedId, float density) {
        try {
            ViewGroup menu = findMenuView(bottomNav);
''', '''    private static void applySelectedIndicator(View bottomNav, int selectedId, float density) {
        if (!indicatorVisible) return;
        try {
            ViewGroup menu = findMenuView(bottomNav);
'''),
        ('''                if (!indicatorVisible || item.getId() != selectedId) {
                    item.setBackground(null);
''', '''                if (item.getId() != selectedId) {
                    item.setBackground(null);
'''),
    ],
)

patch(
    "app/src/main/java/com/waenhancer/activities/BottomBarCustomizationActivity.java",
    [
        ('        addSwitch("Show indicator", "floating_bottom_bar_indicator_visible", true);\n',
         '        addSwitch("Show indicator", "floating_bottom_bar_indicator_visible", false);\n'),
    ],
)

print("floating bar default-preservation and scroll-mode corrections complete")
