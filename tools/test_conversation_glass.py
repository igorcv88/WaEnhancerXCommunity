#!/usr/bin/env python3
"""Device-independent transition/retry regression checks; no Android/GPU claims."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
HARNESS = r'''
import com.waenhancer.theme.*;
public class ConversationGlassChecks {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        ComposerTransition state = new ComposerTransition();
        check(!state.sample(false, "input:170", 0), "first mixed layout must not publish");
        check(!state.sample(false, "input:180", 16), "changed first layout must not publish");
        check(state.sample(false, "input:180", 32), "two coherent frames publish input");
        long now = 100;
        int[] steps = {12,16,6,20};
        for (int round = 0; round < 20; round++) {
            check(state.sample(true, "voice:420", now), "first measured recorder is immediate");
            check(state.state() == ComposerTransition.State.RECORDING_START, "start state");
            check(state.sample(true, "voice:420", now + 90), "voice settles");
            check(state.state() == ComposerTransition.State.RECORDING_STABLE, "stable recorder");
            now += 120;
            for (int i = 0; i < steps[round % steps.length]; i++, now += 30) {
                check(!state.sample(false, "input:" + (420-i*15), now), "cancel must freeze list reservation");
                check(state.state() == ComposerTransition.State.RECORDING_END, "ending state");
            }
            check(!state.sample(false, "input:180", now), "last geometry still needs quiet interval");
            check(!state.sample(false, "input:180", now + 79), "no early commit");
            check(state.sample(false, "input:180", now + 80), "single final geometry within 200ms");
            check(state.state() == ComposerTransition.State.NORMAL, "normal after cancel");
            now += 200;
        }
        state.detach();
        check(state.state() == ComposerTransition.State.DETACHED, "teardown state");
        check(!state.sample(false, "new-chat", now), "detached chat cannot reuse prior geometry");
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
        System.out.println("PASS: 20 cancel/reply-shaped sequences, initial geometry, detach, presentation backoff, trailing damage");
    }
}
'''
with tempfile.TemporaryDirectory(prefix='waex-glass-') as directory:
    tmp = Path(directory)
    harness = tmp / 'ConversationGlassChecks.java'
    harness.write_text(HARNESS)
    sources = [ROOT / 'app/src/main/java/com/waenhancer/theme' / (name + '.java')
               for name in ('ComposerTransition', 'PresentationRetry', 'CaptureScheduler')]
    subprocess.run(['java', 'com.sun.tools.javac.Main', '-d', str(tmp),
                    *map(str, sources), str(harness)], check=True)
    subprocess.run(['java', '-cp', str(tmp), 'ConversationGlassChecks'], check=True)
