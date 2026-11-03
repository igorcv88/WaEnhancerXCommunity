package com.waenhancer.theme;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.HardwareRenderer;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RenderEffect;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** One isolated wallpaper producer per screen epoch. Never records a window, row or chat content. */
public final class ConversationBackdropSource {
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor(r -> {
        Thread t=new Thread(r,"WaEX-wallpaper"); t.setDaemon(true); return t;
    });
    // Full resolution. Conservative six RGBA-buffer equivalents for output, layer and blur scratch.
    // This is an admission estimate, not a measured vendor GPU allocation. No silent downsampling.
    static final long MAX_BYTES=128L*1024*1024;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable changed;
    private WallpaperCapture desired;
    /** Recorded once per revision on the UI thread; afterwards only the worker reads it. */
    private RenderNode desiredContent;
    private Bitmap texture;
    private final BackdropEpoch epoch=new BackdropEpoch();
    private float blur;
    private boolean working, closed;
    /** Review on #130: transient delivery failures retry a few times; real incompatibility does not. */
    private final SourceRetryPolicy retry=new SourceRetryPolicy();
    /** Revision the worker may still render; -1 once released. Read off the main thread. */
    private final AtomicLong liveToken=new AtomicLong(-1);
    /** Global cap on queued renders: a stale job from a closed chat must not delay the next. */
    private static final int MAX_PENDING=2;
    private static final AtomicInteger PENDING=new AtomicInteger();
    private final Runnable retryStart=this::start;
    /** Quiet time before re-rendering a replacement texture. */
    static final long SETTLE_MS=150L;
    /**
     * Single scheduling policy: the first texture renders at once; a replacement waits until the
     * source has been quiet for SETTLE_MS, whichever path starts it (update, stale completion).
     */
    private final RenderDebounce debounce=new RenderDebounce(SETTLE_MS);
    public ConversationBackdropSource(Runnable changed) { this.changed=changed; }
    /**
     * A texture is presentable while a newer revision prepares (stale-while-revalidate): a
     * keyboard or geometry change must not flash every bubble back to native. Only an
     * unsupported wallpaper, a permanent failure or release withdraws it.
     */
    public boolean ready() { return texture!=null && !closed; }
    public Bitmap texture() { return texture; }
    public long revision() { return epoch.revision(); }
    /**
     * UI thread, on layout/scan. Records the wallpaper only when its identity, geometry or blur
     * changed; an unsupported wallpaper withdraws the texture so bubbles stay native.
     */
    public void update(WallpaperCapture capture, float radius) {
        if (closed) return;
        boolean usable=capture!=null && capture.unsupported==null;
        // Report before the revision check: the epoch key starts null, so a first unsupported
        // capture is "no change" for rendering but must still say why there is no texture.
        if (!usable) BubbleGlassTelemetry.source("unsupported", capture==null ? "no-capture" : capture.unsupported);
        if (!epoch.update(usable ? java.util.Arrays.asList(capture.key,radius) : null)) return;
        desired=usable ? capture : null; blur=radius;
        desiredContent=null;
        if (usable) {
            try { desiredContent=capture.record(); }
            catch (RuntimeException | LinkageError failure) {
                desired=null;
                BubbleGlassTelemetry.source("unsupported", "record-"+failure.getClass().getSimpleName());
            }
        }
        debounce.changed(android.os.SystemClock.uptimeMillis());
        if (desired==null && texture!=null) { texture=null; main.post(changed); }
        liveToken.set(epoch.revision());
        main.removeCallbacks(retryStart);
        probe("SOURCE_INVALIDATED", "revision="+epoch.revision()+(desired==null ? " unsupported" : ""));
        start();
    }
    public static float blurRadius(GlassSpec spec, float density) {
        return KyantLens.appSurface(spec,1,density,KyantLens.surfaceAlpha255()).blurRadius;
    }
    private void start() {
        if (working || closed || desired==null || desiredContent==null || !retry.mayAttempt(epoch.revision())) return;
        long settle=debounce.delay(android.os.SystemClock.uptimeMillis(), texture!=null);
        if (settle>0) { main.removeCallbacks(retryStart); main.postDelayed(retryStart,settle); return; }
        if (Build.VERSION.SDK_INT<33) { BubbleGlassTelemetry.source("unsupported","api"); return; }
        if (desired.bytes()>MAX_BYTES) { BubbleGlassTelemetry.source("unsupported","too-large"); return; }
        if (PENDING.get()>=MAX_PENDING) { main.postDelayed(retryStart,50L); return; }
        working=true;
        PENDING.incrementAndGet();
        final long token=epoch.revision(); final WallpaperCapture snapshot=desired; final float radius=blur;
        final RenderNode content=desiredContent;
        BubbleGlassTelemetry.source("preparing", "revision="+token);
        WORKER.execute(() -> {
            Render rendered;
            try {
                // A chat closed or a newer revision queued: skip the GPU work entirely.
                rendered=token!=liveToken.get() ? Render.stale() : render(snapshot,content,radius);
            } catch (RuntimeException | LinkageError | OutOfMemoryError unsupported) {
                rendered=Render.permanent(); // Native remains visible; no retry for this revision.
            } finally {
                PENDING.decrementAndGet();
            }
            final Render result=rendered;
            main.post(() -> {
                working=false;
                if (closed) return;
                if (token!=epoch.revision()) { start(); return; }
                long delay=retry.onResult(token,result.outcome);
                if (result.outcome==SourceRetryPolicy.Outcome.READY) {
                    epoch.complete(token,true);
                    texture=result.bitmap;
                    BubbleGlassTelemetry.source("ready", result.bitmap.getWidth()+"x"+result.bitmap.getHeight());
                    probe("SOURCE_READY","revision="+token+" size="+result.bitmap.getWidth()+"x"+result.bitmap.getHeight());
                    changed.run();
                } else if (delay>=0) {
                    BubbleGlassTelemetry.source("retry", String.valueOf(result.outcome));
                    probe("SOURCE_RETRY","revision="+token+" reason="+result.outcome+" delayMs="+delay);
                    main.postDelayed(retryStart,delay);
                } else if (result.outcome!=SourceRetryPolicy.Outcome.STALE) {
                    epoch.complete(token,false);
                    texture=null; // The previous wallpaper no longer describes this chat.
                    BubbleGlassTelemetry.source("unavailable", String.valueOf(result.outcome));
                    probe("SOURCE_UNAVAILABLE","revision="+token+" reason="+result.outcome);
                    changed.run();
                }
            });
        });
    }

    /** Worker result: a bitmap or why there is none. */
    static final class Render {
        final SourceRetryPolicy.Outcome outcome;
        final Bitmap bitmap;
        private Render(SourceRetryPolicy.Outcome outcome, Bitmap bitmap) { this.outcome=outcome; this.bitmap=bitmap; }
        static Render ready(Bitmap b) { return new Render(SourceRetryPolicy.Outcome.READY,b); }
        static Render transientFailure() { return new Render(SourceRetryPolicy.Outcome.TRANSIENT,null); }
        static Render permanent() { return new Render(SourceRetryPolicy.Outcome.PERMANENT,null); }
        static Render stale() { return new Render(SourceRetryPolicy.Outcome.STALE,null); }
    }

    /** Transient HardwareRenderer outcomes: the frame was not delivered, but may be next time. */
    static boolean transientSync(int result) {
        int transientBits=HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND
                | HardwareRenderer.SYNC_CONTEXT_IS_STOPPED | HardwareRenderer.SYNC_FRAME_DROPPED;
        return (result & transientBits)!=0;
    }

    /**
     * Worker: (1) render the recorded wallpaper region at full resolution, (2) draw it over the
     * whole window with clamped edges (a wallpaper view that stops below the status bar or above
     * the composer still covers every bubble position) under the shared blur.
     */
    private static Render render(WallpaperCapture source, RenderNode content, float radius) {
        Rect area=source.coverage;
        Render region=draw(content, area.width(), area.height());
        if (region.bitmap==null) return region;
        RenderNode output=new RenderNode("WaEX-isolated-wallpaper");
        try {
            output.setPosition(0,0,source.width,source.height);
            if (radius>0) output.setRenderEffect(RenderEffect.createBlurEffect(radius,radius,Shader.TileMode.CLAMP));
            Canvas canvas=output.beginRecording(source.width,source.height);
            try {
                Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                BitmapShader shader=new BitmapShader(region.bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
                android.graphics.Matrix at=new android.graphics.Matrix();
                at.setTranslate(area.left,area.top);
                shader.setLocalMatrix(at); paint.setShader(shader);
                canvas.drawRect(0,0,source.width,source.height,paint);
            } finally { output.endRecording(); }
            return draw(output, source.width, source.height);
        } finally {
            output.discardDisplayList();
        }
    }

    /** Renders one isolated node to a hardware bitmap, distinguishing transient delivery failures. */
    private static Render draw(RenderNode node, int width, int height) {
        ImageReader reader=null; HardwareRenderer renderer=null;
        try {
            reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
            renderer=new HardwareRenderer(); renderer.setSurface(reader.getSurface()); renderer.setContentRoot(node);
            int sync=renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
            if (transientSync(sync)) return Render.transientFailure();
            try (Image image=reader.acquireNextImage()) {
                if (image==null) return Render.transientFailure();
                try (HardwareBuffer buffer=image.getHardwareBuffer()) {
                    if (buffer==null) return Render.transientFailure();
                    Bitmap wrapped=Bitmap.wrapHardwareBuffer(buffer,ColorSpace.get(ColorSpace.Named.SRGB));
                    return wrapped==null ? Render.transientFailure() : Render.ready(wrapped);
                }
            }
        } finally {
            if (renderer!=null) renderer.destroy();
            if (reader!=null) reader.close();
        }
    }
    public void release() {
        epoch.release(); desired=null; desiredContent=null; texture=null; closed=true;
        liveToken.set(-1); main.removeCallbacks(retryStart);
    }
    public static void probe(String event, String detail) {
        if (GlassProbe.enabled()) BubbleLifecycleProbe.event(event,null,null,detail);
    }
}
