#!/usr/bin/env python3
"""Host-JVM regressions for bubble damage/presentation, sticky date and mic palette."""
from pathlib import Path
import subprocess
import tempfile
ROOT = Path(__file__).resolve().parents[1]
HARNESS = r'''
import com.waenhancer.theme.*;
public class LifecycleChecks {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static void near(float a, float b) { check(Math.abs(a-b)<.01f,a+" != "+b); }
    public static void main(String[] args) {
        BubbleCaptureEpoch epoch = new BubbleCaptureEpoch();
        PresentationRetry presentation = new PresentationRetry();
        SurfaceRecovery recovery = new SurfaceRecovery();
        check(epoch.pending(),"initial bind must capture without touch");
        epoch.published(); recovery.ready();
        long initial = epoch.captured();
        epoch.damage(); recovery.revalidate(); presentation.rearm();
        check(epoch.pending() && epoch.captured()==-1 && epoch.revision()>initial,
              "same-sized 19px material-region shift invalidates old pixels");
        check(recovery.mayCapture(0) && presentation.mayCapture(0,false),"damage opens immediate epoch");
        epoch.attempted(); epoch.released();
        check(!epoch.pending(),"budget refusal cannot spin an idle capture loop");
        for(int recycle=0;recycle<100;recycle++) {
            epoch.damage(); recovery.revalidate(); presentation.rearm();
            check(epoch.pending(),"every recycled/native-rebound row requests capture");
            epoch.published(); recovery.ready();
            check(!epoch.pending() && epoch.captured()==epoch.revision(),"published current geometry");
        }
        long now=100;
        for(int i=0;i<3;i++) {
            check(presentation.mayCapture(now,false),"bounded immediate presentation attempts");
            check(presentation.published(now,false),"target must be invalidated");
            now+=16;
        }
        check(!presentation.mayCapture(now,false),"missing native draw backs off");
        long wake=now+presentation.retryDelay(now,false);
        check(presentation.mayCapture(wake,false),"explicit wake does not require a tap");
        check(presentation.retryDelay(wake,true)==0,"actual presentation stops retry wakeups");
        presentation.rearm(); check(presentation.mayCapture(0,false),"geometry change cancels old backoff");
        NativeBubbleDrawLedger<Object> frame = new NativeBubbleDrawLedger<>();
        Object primary=new Object(),secondary=new Object();
        frame.mark(primary); frame.mark(secondary);
        check(frame.saw(primary) && frame.saw(secondary),"suppressed factory still participates in owner ledger");
        frame=new NativeBubbleDrawLedger<>(); frame.mark(secondary);
        check(!frame.saw(primary) && frame.saw(secondary),"displaced primary detected by actual native draw");
        StickyDatePosition date=new StickyDatePosition();
        float placed=date.place(0,0,299,128,4); near(placed,303);
        for(int i=0;i<100;i++) near(date.place(placed,0,299,128,4),303);
        near(date.place(-12,0,299,128,4),291); // Native sticky animation, no accumulated module offset.
        near(date.place(291,0,167,80,4),159); // Rotation/header size change.
        near(date.restore(159),-12);
        placed=date.place(5,0,100,128,4); near(placed,137);
        near(date.restore(17),17); // Native assignment wins on disable.
        ComposerPalette palette=new ComposerPalette();
        check(palette.color()==0xffffffff,"current mic profile is explicitly white");
        check(!palette.update(null) && !palette.update(0xffffffff),"missing mic during draft keeps one reference");
        check(palette.update(0xff123456),"effective mic change is propagated");
        for(int i=0;i<100;i++) check(!palette.update(0xff123456) && palette.color()==0xff123456,
              "all controls retain identical mic colour without per-control decisions");
        check(!palette.update(null) && palette.color()==0xff123456,"temporary route reconstruction retains reference");
        System.out.println("PASS: damage/recycle epochs, presentation deadlines, native owner ledger, reversible date position and mic palette");
    }
}
'''
with tempfile.TemporaryDirectory(prefix='waex-lifecycle-') as directory:
    tmp=Path(directory); source=tmp/'LifecycleChecks.java'; source.write_text(HARNESS)
    names=('BubbleCaptureEpoch','PresentationRetry','SurfaceRecovery','NativeBubbleDrawLedger','StickyDatePosition','ComposerPalette')
    files=[ROOT/'app/src/main/java/com/waenhancer/theme'/f'{name}.java' for name in names]
    subprocess.run(['java','com.sun.tools.javac.Main','-d',str(tmp),*map(str,files),str(source)],check=True)
    subprocess.run(['java','-XX:-UsePerfData','-cp',str(tmp),'LifecycleChecks'],check=True)
# Wiring checks supplement the executable state tests; they do not claim device rendering validation.
custom=ROOT/'app/src/main/java/com/waenhancer/xposed/features/customization'
app=(custom/'AppLiquidGlass.java').read_text()
composer=(custom/'ConversationGlassPanes.java').read_text()
reply=(custom/'ReplyFrameForeground.java').read_text()
backing=(custom/'ComposerBacking.java').read_text()
drawable=(ROOT/'app/src/main/java/com/waenhancer/theme/GlassMaterialDrawable.java').read_text()
assert 'binding.glass.presentationPending()' in app and 'binding.glass.capturePending()' in app
assert 'if (pixelSuppressed) return;' in drawable and 'glass.setPixelSuppressed(true)' in app
assert 'SurfaceContrastSampler' not in composer and 'ensureTextContrast' not in composer
assert 'screen.palette.color()' in composer and 'date_divider_header' in composer
for role in ('voice_note_draft_quoted_message_frame','composer_quoted_message_frame'):
    assert role in reply and role in backing
assert 'current instanceof NinePatchDrawable' in reply and 'entry.owns(view.getForeground())' in reply
assert 'screen.composer.hasLiveRecording()' in composer
print('PASS: capture/presentation wiring, one mic palette and exclusive conditional reply foreground ownership')
