#!/usr/bin/env python3
"""Static guard for the inspector's host-window and module-resource boundaries.

These checks are wiring assertions, not Android/UI instrumentation.
"""

from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java"
LAYOUT = ROOT / "app/src/main/res/layout/inspector_panel.xml"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


class InspectorOverlayHostTest(unittest.TestCase):
    def test_panel_uses_module_resources_and_framework_widgets(self):
        overlay = (JAVA / "com/waenhancer/xposed/features/devtools/InspectorOverlay.java").read_text()
        self.assertIn("new ModuleContextWrapper(activity)", overlay)
        self.assertIn("LayoutInflater.from(panelResourceContext)", overlay)
        self.assertNotIn("LayoutInflater.from(activity).inflate(R.layout.inspector_panel", overlay)
        self.assertIn("panelResourceContext.getString(resId)", overlay)
        self.assertNotIn("Toast.makeText(activity, R.string.inspector_", overlay)
        self.assertNotIn(".setTitle(R.string.inspector_choose_child)", overlay)

        root = ET.parse(LAYOUT).getroot()
        self.assertEqual("LinearLayout", root.tag)
        self.assertIn("Theme.Material.Light", root.attrib[ANDROID_NS + "theme"])
        for view in root.iter():
            self.assertFalse(view.tag.startswith("com.google.android.material"), view.tag)
            self.assertFalse(view.tag.startswith("androidx.cardview"), view.tag)

        ids = {view.attrib.get(ANDROID_NS + "id") for view in root.iter()}
        for name in ("copy_id", "copy_class", "copy_selector", "add_css", "parent", "child", "exit"):
            self.assertIn("@+id/inspector_action_" + name, ids)

    def test_bounded_deferred_attach_and_owner_aware_cleanup(self):
        feature = (JAVA / "com/waenhancer/xposed/features/devtools/InspectorFeature.java").read_text()
        trace = (JAVA / "com/waenhancer/diagnostics/InspectorTrace.java").read_text()
        self.assertIn("WppCore.addListenerActivity", feature)
        self.assertIn("MAX_ATTACH_ATTEMPTS = 3", feature)
        self.assertIn("decor.isAttachedToWindow()", feature)
        self.assertIn("decor.getWindowToken()", feature)
        self.assertIn("WppCore.getActivityState(activity)", feature)
        self.assertIn("activity == attachedActivity || activity == pendingActivity", feature)
        self.assertIn("failure instanceof WindowManager.BadTokenException", feature)
        self.assertIn("INSPECTOR_ATTACH_FAILED", feature)
        self.assertIn("INSPECTOR_ATTACH_OK", feature)
        self.assertIn("currentOverlay.detach()", feature)
        self.assertIn("InspectorTrace.disarm()", feature)
        self.assertIn('reason.startsWith("INSPECTOR_")', trace)


if __name__ == "__main__":
    unittest.main(verbosity=2)
