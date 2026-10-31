#!/usr/bin/env python3
"""Host JVM checks of cover, ownership and event coalescing. Android checks live in androidTest."""
from pathlib import Path
import subprocess
import tempfile
ROOT = Path(__file__).resolve().parents[1]
HARNESS = r'''
import com.waenhancer.theme.*;
public class BackingChecks {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static void near(float a, float b) { check(Math.abs(a-b)<.01, a+" != "+b); }
    public static void main(String[] args) {
        float[] out = new float[3];
        float[][] cases = {{0,128,1440,3120,1440,3120}, {0,0,4000,2000,1440,3120},
            {0,0,2000,4000,3120,1440}, {-200,128,1640,3120,1440,3120},
            {0,90,1080,2340,1080,1440}, {50,30,1050,1030,1000,1000}};
        for (float[] c : cases) {
            check(WallpaperCover.calculate(c[0],c[1],c[2],c[3],c[4],c[5],out), "valid cover");
            float l=c[0]*out[0]+out[1], t=c[1]*out[0]+out[2];
            float r=c[2]*out[0]+out[1], b=c[3]*out[0]+out[2];
            check(l<=.01 && t<=.01 && r>=c[4]-.01 && b>=c[5]-.01,"no uncovered strip");
            near((r-l)/(b-t),(c[2]-c[0])/(c[3]-c[1]));
            near((l+r)*.5f,c[4]*.5f); near((t+b)*.5f,c[5]*.5f);
        }
        WallpaperCover.calculate(0,128,1440,3120,1440,3120,out);
        near(out[0],3120f/2992f);
        check(!WallpaperCover.calculate(0,0,0,1,2,3,out),"zero source rejected");
        check(!WallpaperCover.calculate(0,0,Float.NaN,1,2,3,out),"NaN rejected");
        OwnedProperty<Object> slot = new OwnedProperty<>();
        Object nativeA=new Object(), nativeB=new Object(), transparentA=new Object(), transparentB=new Object();
        slot.install(nativeA,transparentA);
        check(slot.owns(transparentA) && slot.original()==nativeA,"exact owned identity");
        check(!slot.owns(nativeB),"native rebind wins even without bounds change");
        slot.install(nativeB,transparentB);
        check(slot.original()==nativeB && !slot.owns(transparentA),"latest native original retained");
        check(slot.owns(transparentB),"conditional restoration permitted");
        slot.install(nativeA,null);
        check(slot.owns(null) && !slot.owns(nativeB),"nullable tint/foreground ownership");
        ComposerRebind rebind = new ComposerRebind();
        check(rebind.request(),"first setter queues");
        for(int i=0;i<100;i++) check(!rebind.request(),"same-frame setters coalesce");
        check(rebind.consume() && !rebind.consume(),"one reconciliation");
        check(rebind.request(),"later same-bounds rebind still schedules");
        rebind.reset(); check(!rebind.consume(),"stale detach callback discarded");
        check(VoiceBackingPolicy.player(true,true,true,true,false,true),"expanded and compact full-height player");
        check(!VoiceBackingPolicy.player(true,true,true,true,true,true),"panel containing resume/send rejected");
        check(!VoiceBackingPolicy.player(false,true,true,true,false,true),"unassociated waveform rejected");
        check(!VoiceBackingPolicy.player(true,true,false,true,false,true),"timer required");
        check(!VoiceBackingPolicy.player(true,true,true,true,false,false),"out-of-pane player rejected");
        System.out.println("PASS: uniform cover/crop, native property ownership, coalesced rebind and audio backing policy");
    }
}
'''
with tempfile.TemporaryDirectory(prefix='waex-backing-') as directory:
    tmp = Path(directory)
    source = tmp/'BackingChecks.java'
    source.write_text(HARNESS)
    files = [ROOT/'app/src/main/java/com/waenhancer/theme'/f'{name}.java'
             for name in ('WallpaperCover','OwnedProperty','ComposerRebind','VoiceBackingPolicy')]
    subprocess.run(['java','com.sun.tools.javac.Main','-d',str(tmp),*map(str,files),str(source)],check=True)
    subprocess.run(['java','-XX:-UsePerfData','-cp',str(tmp),'BackingChecks'],check=True)
