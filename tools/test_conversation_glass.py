#!/usr/bin/env python3
"""Device-independent transition/retry regression checks; no Android/GPU claims."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PANES = (ROOT / 'app/src/main/java/com/waenhancer/xposed/features/customization/ConversationGlassPanes.java').read_text()
# The header and recorder may own the same ancestor simultaneously. Keep one native
# snapshot and two ownership sets; neither release path may restore a shared group.
for contract in ('nativeClipFlags', 'headerClipOwners', 'recordingClipOwners',
                 'ownClipFlags(screen, group, true)', 'ownClipFlags(screen, group, false)',
                 'releaseClipFlags(screen, true)', 'releaseClipFlags(screen, false)',
                 'if (other.contains(group)) continue;'):
    assert contract in PANES, f'missing shared clipping contract: {contract}'
for obsolete in ('topClipFlags', 'recordingClipChildren', 'recordingClipPadding'):
    assert obsolete not in PANES, f'competing native clipping snapshot remains: {obsolete}'
HARNESS = r'''
import com.waenhancer.theme.*;
public class ConversationGlassChecks {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        for (int cycle = 0; cycle < 5; cycle++) {
            check(ComposerRoute.resolve(false, true, true) == ComposerRoute.Panel.INPUT, "residual stub cannot block restored editor");
            check(ComposerRoute.resolve(false, false, true) == ComposerRoute.Panel.STUB, "initial recorder");
            check(ComposerRoute.resolve(true, false, true) == ComposerRoute.Panel.DRAFT, "draft replaces stub in same epoch");
            check(ComposerRoute.resolve(true, true, true) == ComposerRoute.Panel.DRAFT, "draft owns native recording controls");
            check(ComposerRoute.resolve(false, false, false) == ComposerRoute.Panel.UNRESOLVED, "layout gap is unresolved");
        }
        int[] nearTrash = {90,157,120,187};
        int[] offsetNearTrash = RecorderLayout.micOffset(nearTrash, new int[]{300,100,1200,244},
                new int[0][], new int[]{0,0,1440,444}, 4, 6, false);
        check(offsetNearTrash != null && offsetNearTrash[0] > 0, "mic cannot remain far from waveform beside trash");
        final int P = ComposerTransition.PRESENT, R = ComposerTransition.RESERVE;
        final ComposerTransition.Route IN = ComposerTransition.Route.INPUT,
                VO = ComposerTransition.Route.VOICE, UN = ComposerTransition.Route.UNRESOLVED;
        ComposerTransition state = new ComposerTransition();
        check(state.sample(IN, "170", 0) == 0, "first mixed layout must not publish");
        check(state.sample(IN, "180", 16) == 0, "changed first layout must not publish");
        check(state.sample(IN, "180", 32) == (P | R), "two coherent frames publish and reserve input");
        check(state.sample(IN, "180", 48) == 0, "unchanged input is idle");
        long now = 100;
        int[] steps = {12,16,6,20};
        // Logged PR #107 device geometries: no quote and quote.
        int[][] growth = {{155, 429, 444}, {323, 609, 624}};
        for (int round = 0; round < 20; round++) {
            long epoch = state.epoch();
            check(state.signal(true, now), "native start opens an epoch");
            check(!state.signal(true, now + 1), "timer tick in the same epoch wakes nothing");
            check(state.state() == ComposerTransition.State.RECORD_START_SIGNAL, "start signal state");
            // Native swap not laid out yet: the still-measured input is presented, not reserved.
            check((state.sample(IN, "180", now + 2) & R) == 0, "input before swap is not reserved");
            check(state.state() == ComposerTransition.State.RECORD_START_SIGNAL, "signal survives old route");
            check(state.sample(UN, null, now + 8) == ComposerTransition.HOLD, "unresolved frame holds the pane");
            int[] g = growth[round % 2];
            check((state.sample(VO, "v" + g[0], now + 16) & P) != 0, "first measured recorder presents immediately");
            check(state.state() == ComposerTransition.State.RECORD_LAYOUT_PENDING, "layout pending");
            check(state.startToMeasuredMs() == 16, "start-to-measured timing");
            check((state.sample(VO, "v" + g[1], now + 153) & P) != 0, "expansion presents in the same frame");
            check((state.sample(VO, "v" + g[1], now + 169) & R) == 0, "expansion not yet quiet");
            check(state.sample(VO, "v" + g[1], now + 233) == R, "settled expansion reserves once");
            check(state.state() == ComposerTransition.State.RECORD_STABLE, "stable recorder");
            check((state.sample(VO, "v" + g[2], now + 1268) & P) != 0, "late growth presents");
            check(state.sample(VO, "v" + g[2], now + 1350) == R, "late growth reserves");
            check(state.epoch() == epoch + 1, "one epoch per recording");
            now += 1400;
            check(state.signal(false, now), "native end opens an epoch");
            // Exit animation: recorder still measurable, then unresolved swap frames.
            check((state.sample(VO, "v" + g[2], now + 4) & R) == 0, "recorder during exit is not reserved");
            check(state.sample(UN, null, now + 12) == ComposerTransition.HOLD, "cancel never blanks the pane");
            now += 20;
            for (int i = 0; i < steps[round % steps.length]; i++, now += 30) {
                int flags = state.sample(IN, "" + (420 - i * 15), now);
                check((flags & P) != 0, "normal pill follows each measured cancel frame");
                check((flags & R) == 0, "cancel must freeze list reservation");
            }
            check(state.endToNormalMs() >= 0 && state.endToNormalMs() <= 40, "end-to-normal timing");
            check((state.sample(IN, "180", now) & P) != 0, "final input presented at once");
            check(state.sample(IN, "180", now + 79) == 0, "no early reservation");
            check(state.sample(IN, "180", now + 80) == R, "single final reservation within 200ms");
            check(state.state() == ComposerTransition.State.NORMAL, "normal after cancel");
            now += 200;
        }
        // A build whose recorder is never identified: bounded bridge, then decoration removed.
        check(state.sample(UN, null, now) == ComposerTransition.HOLD, "bridge starts");
        check(state.sample(UN, null, now + ComposerTransition.BRIDGE_MAX_MS) == ComposerTransition.HOLD, "bridge bound inclusive");
        check(state.sample(UN, null, now + ComposerTransition.BRIDGE_MAX_MS + 1) == ComposerTransition.HIDE, "bridge expires");
        check(state.sample(UN, null, now + 2000) == 0, "nothing left to hold");
        // Review regression: a newly signalled route must not inherit the aged unresolved
        // bridge from the preceding input/voice epoch.
        ComposerTransition bridge = new ComposerTransition();
        bridge.sample(IN, "input", 0);
        bridge.sample(IN, "input", 16);
        check(bridge.sample(UN, null, 100) == ComposerTransition.HOLD, "old bridge begins");
        check(bridge.signal(true, 795), "start opens an independent bridge");
        check(bridge.sample(UN, null, 805) == ComposerTransition.HOLD, "fresh start must not hide");
        check(bridge.sample(UN, null, 1200) == ComposerTransition.HOLD, "fresh start bridge remains");
        check((bridge.sample(VO, "voice", 1216) & P) != 0, "voice recovers after old bridge age");
        check(bridge.sample(UN, null, 1250) == ComposerTransition.HOLD, "cancel bridge begins");
        check(bridge.signal(false, 1900), "cancel opens an independent bridge");
        check(bridge.sample(UN, null, 1910) == ComposerTransition.HOLD, "fresh cancel must not hide");
        check((bridge.sample(IN, "input", 1926) & P) != 0, "input restores after old bridge age");
        state.detach();
        check(state.state() == ComposerTransition.State.DETACHED, "teardown state");
        check(state.sample(IN, "new-chat", now) == 0, "detached chat cannot reuse prior geometry");
        // Recorder lanes: mic packs before the timer when the timer occupies the slot before
        // the waveform; never overlaps timer/trash; RTL mirrors.
        int[] wave = {300, 100, 1200, 244}, timer = {200, 150, 290, 190}, trash = {20, 140, 80, 200};
        int[] mic = {1230, 160, 1260, 190};
        int[] off = RecorderLayout.micOffset(mic, wave, new int[][]{timer, trash}, new int[]{0, 0, 1440, 444}, 4, 6, false);
        check(off != null, "mic placeable");
        int[] placed = {mic[0] + off[0], mic[1] + off[1], mic[2] + off[0], mic[3] + off[1]};
        check(placed[2] + 4 <= wave[0], "mic left of waveform");
        check(!RecorderLayout.intersects(placed, timer) && !RecorderLayout.intersects(placed, trash), "no overlap");
        check(Math.abs((placed[1] + placed[3]) / 2 - (wave[1] + wave[3]) / 2) <= 6, "mic centred on row");
        check(RecorderLayout.micOffset(mic, wave, new int[][]{{0, 0, 1440, 444}}, new int[]{0, 0, 1440, 444}, 4, 6, false) == null,
                "no valid slot keeps native layout");
        check(!RecorderLayout.quoteAbove(new int[]{10, 90, 1200, 160}, wave, timer, 4), "overlapping quote lane detected");
        PresentationRetry retry = new PresentationRetry();
        for (int i=0; i<3; i++) {
            check(retry.mayCapture(i*16, false), "three initial presentation attempts");
            check(retry.published(i*16, false), "first texture publication invalidates");
        }
        check(!retry.mayCapture(1000, false), "unpresented surface backs off");
        check(retry.mayCapture(1032, false), "transient route can recover");
        retry.rearm();
        check(retry.mayCapture(50, false), "new owner/viewport rearms");
        check(!retry.published(50, true), "presented rows do not invalidate themselves");
        CaptureScheduler scheduler = new CaptureScheduler();
        check((scheduler.decide(0, true, false) & CaptureScheduler.CAPTURE) != 0, "first capture");
        check((scheduler.decide(10, true, false) & CaptureScheduler.INVALIDATE_LATER) != 0, "trailing damage");
        check(scheduler.hasPendingCapture(), "pending damage cannot be skipped by idle gate");
        check((scheduler.decide(33, true, false) & CaptureScheduler.CAPTURE) != 0, "last damage captured");
        check(!scheduler.hasPendingCapture(), "no idle heartbeat after trailing capture");
        System.out.println("PASS: 20 signal/record/cancel sequences with logged 155-429-444 and 323-609-624 growth, unresolved bridge, initial geometry, detach, mic lanes, presentation backoff, trailing damage");
    }
}
'''
with tempfile.TemporaryDirectory(prefix='waex-glass-') as directory:
    tmp = Path(directory)
    harness = tmp / 'ConversationGlassChecks.java'
    harness.write_text(HARNESS)
    sources = [ROOT / 'app/src/main/java/com/waenhancer/theme' / (name + '.java')
               for name in ('ComposerTransition', 'PresentationRetry', 'CaptureScheduler', 'RecorderLayout', 'ComposerRoute')]
    subprocess.run(['java', 'com.sun.tools.javac.Main', '-d', str(tmp),
                    *map(str, sources), str(harness)], check=True)
    subprocess.run(['java', '-cp', str(tmp), 'ConversationGlassChecks'], check=True)
