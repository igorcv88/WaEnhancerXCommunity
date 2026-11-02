#!/usr/bin/env python3
"""Run production shared-source epoch/geometry tests on a host JVM; no GPU/device claim."""
from pathlib import Path
import subprocess
import tempfile
ROOT=Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='waex-shared-') as directory:
    tmp=Path(directory); junit=tmp/'org/junit'; junit.mkdir(parents=True)
    # Minimal assertion API, executing the repository test methods without Gradle/Android.
    (junit/'Test.java').write_text('package org.junit; public @interface Test {}')
    (junit/'Assert.java').write_text('''package org.junit; public class Assert {
      public static void assertTrue(boolean v){if(!v)throw new AssertionError();}
      public static void assertFalse(boolean v){assertTrue(!v);}
      public static void assertEquals(long a,long b){assertTrue(a==b);}
    }''')
    runner=tmp/'Checks.java'; runner.write_text('''import com.waenhancer.theme.*;
    public class Checks { public static void main(String[] args) {
      BubbleCaptureRetentionTest t=new BubbleCaptureRetentionTest();
      t.oneHundredRowMovesDoNotInvalidateSource();
      t.sourceChangeRejectsLateWorkerAndKeepsNativeUntilReady();
      t.noStretchedOrUncoveredWallpaper();
      BackdropEpoch source=new BackdropEpoch(); source.update("A");
      long a=source.revision(); source.complete(a,false);
      if(source.ready())throw new AssertionError("failed source must keep native");
      source.update(null); if(source.complete(a,true))throw new AssertionError("old chat cannot publish");
      float[] cover=new float[3]; WallpaperCover.calculate(0,128,1440,3120,1440,3120,cover);
      if(!BackdropGeometry.covers(new float[]{cover[0],0,cover[1],0,cover[0],128*cover[0]+cover[2],0,0,1},
          1440,2992,1440,3120))throw new AssertionError("approved inset cover must map continuously");
      System.out.println("PASS: 100 row moves preserve source; stale workers, source failure, release, cover, rotation and RTL");
    }}''')
    theme=ROOT/'app/src/main/java/com/waenhancer/theme'
    files=[junit/'Test.java',junit/'Assert.java',runner,
           ROOT/'app/src/test/java/com/waenhancer/theme/BubbleCaptureRetentionTest.java']
    files += [theme/(n+'.java') for n in ('BackdropEpoch','BackdropGeometry','WallpaperCover')]
    subprocess.run(['java','com.sun.tools.javac.Main','-d',str(tmp),*map(str,files)],check=True)
    subprocess.run(['java','-XX:-UsePerfData','-cp',str(tmp),'Checks'],check=True)
theme=ROOT/'app/src/main/java/com/waenhancer/theme'
source=(theme/'ConversationBackdropSource.java').read_text()
consumer=(theme/'BubbleGlassDrawable.java').read_text()
app=(ROOT/'app/src/main/java/com/waenhancer/xposed/features/customization/AppLiquidGlass.java').read_text()
assert 'LiveBackdrop' not in source+consumer and 'captureBehind' not in source+consumer
assert 'view.draw(' not in source and 'root.draw(' not in source and 'window.draw(' not in source
assert 'WORKER.execute' in source and 'setWaitForPresent(true)' in source
assert 'token!=epoch.revision()' in source and 'retry.onResult(token,result.outcome)' in source
# Review on #130: transient delivery retries a bounded number of times; stale jobs skip rendering.
assert 'failedRevision' not in source and 'transientSync(sync)' in source and 'token!=liveToken.get()' in source
assert 'realignSharedBubbles();' in app and 'sharedFactoryWanted(outgoing)' in app
assert 'source.ready()' in consumer and 'nativeDraw(canvas); return;' in consumer
assert 'new RuntimeShader' not in consumer.split('@Override public void draw',1)[1]
assert 'if (surface == Surface.BUBBLES) return;' in app
assert 'installBubbleHook();' not in app and 'installSharedBubbleFactoryHook();' in app
assert '"conversation_row_text".equals(resourceName(row))' in app
assert 'row.getBackground()==owned' in app and 'owned.original()' in app
composer=(ROOT/'app/src/main/java/com/waenhancer/xposed/features/customization/ConversationGlassPanes.java').read_text()
assert 'collectDiscCandidates(action, candidates, 0)' in composer and 'MicColorSource.selectDisc(colors)' in composer
print('PASS: isolated producer, no per-row capture/budget, ready-before-bind, native failure and preserved mic-disc palette contracts')
