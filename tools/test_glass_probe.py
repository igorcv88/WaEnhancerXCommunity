#!/usr/bin/env python3
"""Static, device-independent guardrails for the opt-in geometry-only probe."""
from pathlib import Path

root = Path(__file__).resolve().parents[1] / "app/src/main/java/com/waenhancer"
probe = (root / "theme/GlassProbe.java").read_text()
pane = (root / "theme/GlassPane.java").read_text()
drawable = (root / "theme/GlassMaterialDrawable.java").read_text()
conversation = (root / "xposed/features/customization/ConversationGlassPanes.java").read_text()
app = (root / "xposed/features/customization/AppLiquidGlass.java").read_text()

assert 'Log.isLoggable(TAG, Log.DEBUG)' in probe
assert 'MAX_EVENTS_PER_MINUTE = 450' in probe
assert '"WaEnhancerX/GlassDiag"' in probe
for forbidden in (".getText(", ".getContentDescription(", ".getAccessibilityNodeInfo("):
    assert forbidden not in probe, f"probe unexpectedly reads private content: {forbidden}"
for event in ("RECORDER_SIGNAL", "RECORDER_WAKE", "FOOTER_SAMPLE", "ROUTE_VOICE",
              "ROUTE_UNRESOLVED", "VOICE_GEOMETRY", "INPUT_GEOMETRY", "PANE_COMMIT_BEFORE",
              "PANE_COMMIT_AFTER", "INPUT_BACKING_GATE", "VOICE_RESTORE_BEFORE",
              "VOICE_RESTORE_AFTER", "VOICE_BG_CLEARED", "VOICE_BG_RESTORED"):
    assert f'"{event}"' in conversation, f"missing composer event {event}"
for event in ("NATIVE_VISIBILITY_HOOK", "NATIVE_INFLATE_HOOK", "NATIVE_TIMER_CANDIDATE",
              "BACKGROUND_HOOK", "BIND_ATTEMPT", "BIND_COMMIT", "DISCOVERY_START",
              "DISCOVERY_END"):
    assert f'"{event}"' in app, f"missing Xposed event {event}"
for event in ("PANE_PLACE", "CAPTURE_WAIT", "CAPTURE_ATTEMPT", "CAPTURE_RESULT", "PANE_DRAW"):
    assert f'"{event}"' in pane, f"missing pane event {event}"
for event in ("DRAWABLE_CAPTURE_ATTEMPT", "DRAWABLE_CAPTURE_RESULT", "DRAWABLE_DRAW_STATE"):
    assert f'"{event}"' in drawable, f"missing drawable event {event}"
assert 'if (!enabled()) return;' in probe
print("PASS: opt-in bounded, privacy-safe recorder, pane, drawable and ownership telemetry contracts")
