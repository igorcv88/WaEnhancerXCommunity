#!/usr/bin/env python3
"""Pure Java finishing regressions; not a replacement for Galaxy UI acceptance."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
HARNESS = r'''
import com.waenhancer.theme.*;
import java.util.Arrays;
public class FinishingChecks {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        DraftRouteGuard guard = new DraftRouteGuard();
        for (int cycle = 0; cycle < 5; cycle++) {
            Object draft = new Object();
            long t = cycle * 3000;
            check(guard.hold(draft, false, true, t), "reentry before measurement cannot select input/stub");
            check(guard.pending(), "unmeasured draft must bypass the idle fingerprint path");
            check(!guard.hold(draft, true, true, t+16), "measured draft presents immediately");
            check(!guard.pending(), "stable draft needs no loop");
            check(guard.hold(draft, false, true, t+32), "same owner gap holds geometry");
            check(guard.hold(draft, false, true, t+732), "bounded bridge includes deadline");
            check(!guard.hold(draft, false, true, t+733), "unresolved bridge expires without native event");
            check(!guard.pending(), "no idle redraw after timeout");
            check(!guard.hold(draft, false, false, t+734), "native removal permits input immediately");
            check(!guard.hold(null, false, false, t+735), "detached draft has no residual ownership");
            guard.reset();
        }
        int[] pixels = new int[288];
        Arrays.fill(pixels, 0xff0879a4);
        Arrays.fill(pixels, 0, 30, 0xffffffff);
        Arrays.fill(pixels, 30, 50, 0xff000000);
        check(SurfaceSampleColor.dominant(pixels) == 0xff0879a4, "glyphs cannot choose quote background");
        for (int background : new int[]{0xff0879a4, 0xffeeeeee, 0xff111111, 0xff838383}) {
            int secondary = SemanticTheme.ensureTextContrast(0xff888888, background, 4.5);
            check(SemanticTheme.contrastRatio(secondary, background) >= 4.5, "readable secondary quote text");
        }
        check(SemanticTheme.ensureTextContrast(0xff888888, 0xff111111, 4.5)
                != SemanticTheme.ensureTextContrast(0xff888888, 0xffeeeeee, 4.5), "palette responds to actual surface");
        System.out.println("PASS: five reconstructed drafts, timed bridge/exit/detach, dominant surface sampling and dynamic quote contrast");
    }
}
'''
with tempfile.TemporaryDirectory(prefix='waex-finishing-') as directory:
    tmp = Path(directory)
    source = tmp / 'FinishingChecks.java'
    source.write_text(HARNESS)
    names = ('DraftRouteGuard', 'SurfaceSampleColor', 'SemanticTheme')
    files = [ROOT / 'app/src/main/java/com/waenhancer/theme' / (name+'.java') for name in names]
    subprocess.run(['java','com.sun.tools.javac.Main','-d',str(tmp),*map(str,files),str(source)],check=True)
    subprocess.run(['java','-XX:-UsePerfData','-cp',str(tmp),'FinishingChecks'],check=True)
