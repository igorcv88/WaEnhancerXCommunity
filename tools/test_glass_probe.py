#!/usr/bin/env python3
"""Static, device-independent guardrails for the opt-in geometry-only probe."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1] / "app/src/main/java/com/waenhancer"
probe = (root / "theme/GlassProbe.java").read_text()
budget = (root / "theme/GlassProbeBudget.java").read_text()
pane = (root / "theme/GlassPane.java").read_text()
drawable = (root / "theme/GlassMaterialDrawable.java").read_text()
conversation = (root / "xposed/features/customization/ConversationGlassPanes.java").read_text()
app = (root / "xposed/features/customization/AppLiquidGlass.java").read_text()

assert 'Log.isLoggable(TAG, Log.DEBUG)' in probe
assert '"WaEXGlassDiag"' in probe
assert 'public static final String TAG = "WaEXGlassDiag"' in probe
assert 'Log.i(TAG, "t=" + now + " event=PROBE_ACTIVE' in probe
assert 'Log.i(TAG, "t=" + now + " event=" + type' in probe
assert 'Log.d(TAG' not in probe
assert '"WaEnhancerX/GlassDiag"' not in probe
assert 'MAX_EVENTS_PER_MINUTE = 450' in budget
assert 'TRANSITION_RESERVE = 150' in budget
assert 'BUDGET.beginWindow(now)' in probe
assert 'BUDGET.allow(type,' in probe
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
HARNESS = r'''
import com.waenhancer.theme.GlassProbeBudget;

public class GlassProbeBudgetChecks {
    static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }
    public static void main(String[] args) {
        GlassProbeBudget probe = new GlassProbeBudget();
        check(probe.beginWindow(100), "first window opens");
        check(!probe.beginWindow(101), "same window stays active");
        int noise = 0;
        for (int i = 0; i < 1000; i++) {
            if (probe.allow("NATIVE_TIMER_CANDIDATE", i, "length=5", 200 + i)) noise++;
        }
        check(noise == 8, "unrelated message timers must not exhaust the probe");
        int captures = 0;
        for (int ms = 1000; ms < 31000; ms += 10) {
            if (probe.allow("CAPTURE_RESULT", 1, "captureId=" + ms, ms)) captures++;
            if (probe.allow("CAPTURE_RESULT", 2, "captureId=" + ms, ms)) captures++;
        }
        check(captures >= 35 && captures <= 45, "capture sampling must cover the full run");
        check(probe.allow("RECORDER_SIGNAL", 3, "shown=true", 32000), "record start survives passive noise");
        check(probe.allow("RECORDER_SIGNAL", 3, "shown=false", 32010), "record end survives immediately");
        check(probe.allow("VOICE_RESTORE_BEFORE", 3, "epoch=2", 32011), "restore is observable");
        check(probe.allow("INPUT_GEOMETRY", 3, "mode=NORMAL", 32012), "normal input is observable");
        check(probe.allow("FOOTER_SAMPLE", 3, "state=RECORD_STABLE", 32015), "first footer sample");
        check(!probe.allow("FOOTER_SAMPLE", 3, "state=RECORD_STABLE", 32020), "identical sample is deduplicated");
        check(probe.allow("FOOTER_SAMPLE", 3, "state=NORMAL_LAYOUT_PENDING", 32021), "state changes bypass deduplication");
        check(probe.allow("FOOTER_SAMPLE", 3, "state=RECORD_STABLE", 32022), "return to a prior state is visible");
        check(!probe.allow("FOOTER_SAMPLE", 3, "state=RECORD_STABLE", 32023), "same state does not flood");
        check(probe.allow("PANE_DRAW", 9, "captureId=1 drawnLive=true size=100x100", 33000), "first live draw");
        check(probe.allow("PANE_DRAW", 9, "captureId=2 drawnLive=false size=100x100", 33001), "first failed draw is never sampled out");
        check(probe.allow("PANE_DRAW", 9, "captureId=3 drawnLive=true size=100x100", 33002), "recovery is immediate");
        check(!probe.allow("PANE_DRAW", 9, "captureId=4 drawnLive=true size=100x100", 33003), "generation alone is not a state change");
        check(probe.allow("PANE_DRAW", 9, "captureId=5 drawnLive=true size=100x200", 33004), "new geometry is immediate");
        check(probe.beginWindow(60100), "new minute reopens every type's budget");
        check(probe.allow("NATIVE_TIMER_CANDIDATE", 1, "length=5", 60100), "noisy budget resets");

        GlassProbeBudget reserve = new GlassProbeBudget();
        reserve.beginWindow(100);
        int ordinary = 0;
        for (int i = 0; i < 600; i++) {
            if (reserve.allow("PASSIVE_" + i, i, "changed", 100 + i)) ordinary++;
        }
        check(ordinary == 300, "150 slots are reserved from passive event floods");
        check(reserve.allow("RECORDER_MODE_CHANGED", 1, "shown=true", 900), "reserved transition slot works");
        check(!reserve.allow("FOOTER_SAMPLE", 1, "changed", 900), "samples cannot consume semantic reserve");
        check(!reserve.allow("VOICE_GEOMETRY", 1, "changed", 900), "geometry cannot consume semantic reserve");
        check(reserve.allow("VOICE_RESTORE_AFTER", 1, "done", 901), "restoration remains visible");
        System.out.println("PASS: noise limits, distributed capture samples, native start/end, restore, deduplication and reserved transitions");
    }
}
'''
with tempfile.TemporaryDirectory(prefix="waex-probe-") as directory:
    temp = Path(directory)
    harness = temp / "GlassProbeBudgetChecks.java"
    harness.write_text(HARNESS)
    subprocess.run(["java", "com.sun.tools.javac.Main", "-d", str(temp),
                    str(root / "theme/GlassProbeBudget.java"), str(harness)], check=True)
    subprocess.run(["java", "-XX:-UsePerfData", "-cp", str(temp),
                    "GlassProbeBudgetChecks"], check=True)
print("PASS: opt-in bounded, privacy-safe recorder, pane, drawable and ownership telemetry contracts")
