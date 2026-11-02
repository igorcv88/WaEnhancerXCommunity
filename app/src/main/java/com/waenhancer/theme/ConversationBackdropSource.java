package com.waenhancer.theme;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.HardwareRenderer;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RenderEffect;
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
    private WallpaperSnapshot desired;
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
    public ConversationBackdropSource(Runnable changed) { this.changed=changed; }
    public boolean ready() { return texture!=null && epoch.ready() && !closed; }
    public Bitmap texture() { return texture; }
    public long revision() { return epoch.revision(); }
    public void update(WallpaperSnapshot snapshot, float radius) {
        if (closed) return;
        if (!epoch.update(snapshot==null ? null : java.util.Arrays.asList(snapshot,radius))) return;
        desired=snapshot; blur=radius; texture=null;
        liveToken.set(epoch.revision());
        main.removeCallbacks(retryStart);
        probe("SOURCE_INVALIDATED", "revision="+epoch.revision());
        start();
    }
    public static float blurRadius(GlassSpec spec, float density) {
        return KyantLens.appSurface(spec,1,density,KyantLens.surfaceAlpha255()).blurRadius;
    }
    private void start() {
        if (working || closed || desired==null || !retry.mayAttempt(epoch.revision()) || Build.VERSION.SDK_INT<33
                || (long)desired.width*desired.height*4*6>MAX_BYTES) return;
        if (PENDING.get()>=MAX_PENDING) { main.postDelayed(retryStart,50L); return; }
        working=true;
        PENDING.incrementAndGet();
        final long token=epoch.revision(); final WallpaperSnapshot snapshot=desired; final float radius=blur;
        WORKER.execute(() -> {
            Render rendered;
            try {
                // A chat closed or a newer revision queued: skip the GPU work entirely.
                rendered=token!=liveToken.get() ? Render.stale() : render(snapshot,radius);
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
                    probe("SOURCE_READY","revision="+token+" size="+result.bitmap.getWidth()+"x"+result.bitmap.getHeight());
                    changed.run();
                } else if (delay>=0) {
                    probe("SOURCE_RETRY","revision="+token+" reason="+result.outcome+" delayMs="+delay);
                    main.postDelayed(retryStart,delay);
                } else if (result.outcome!=SourceRetryPolicy.Outcome.STALE) {
                    epoch.complete(token,false);
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

    private static Render render(WallpaperSnapshot source, float radius) {
        // A changed/recycled wallpaper bitmap means a newer snapshot will arrive via update().
        if (source.bitmap.isRecycled() || source.bitmap.getGenerationId()!=source.generation) return Render.stale();
        ImageReader reader=null; HardwareRenderer renderer=null; RenderNode node=null;
        try {
            reader=ImageReader.newInstance(source.width,source.height,PixelFormat.RGBA_8888,2,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
            node=new RenderNode("WaEX-isolated-wallpaper");
            node.setPosition(0,0,source.width,source.height);
            if (radius>0) node.setRenderEffect(RenderEffect.createBlurEffect(radius,radius,Shader.TileMode.CLAMP));
            Canvas canvas=node.beginRecording(source.width,source.height);
            try {
                Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                BitmapShader shader=new BitmapShader(source.bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
                shader.setLocalMatrix(source.bitmapToWindow); paint.setShader(shader);
                canvas.drawRect(0,0,source.width,source.height,paint);
            } finally { node.endRecording(); }
            renderer=new HardwareRenderer(); renderer.setSurface(reader.getSurface()); renderer.setContentRoot(node);
            int sync=renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
            if (transientSync(sync)) return Render.transientFailure();
            try (Image image=reader.acquireNextImage()) {
                if (image==null) return Render.transientFailure();
                try (HardwareBuffer buffer=image.getHardwareBuffer()) {
                    if (buffer==null) return Render.transientFailure();
                    if (source.bitmap.isRecycled() || source.bitmap.getGenerationId()!=source.generation) return Render.stale();
                    Bitmap wrapped=Bitmap.wrapHardwareBuffer(buffer,ColorSpace.get(ColorSpace.Named.SRGB));
                    return wrapped==null ? Render.transientFailure() : Render.ready(wrapped);
                }
            }
        } finally {
            if (renderer!=null) renderer.destroy();
            if (node!=null) node.discardDisplayList();
            if (reader!=null) reader.close();
        }
    }
    public void release() {
        epoch.release(); desired=null; texture=null; closed=true;
        liveToken.set(-1); main.removeCallbacks(retryStart);
    }
    public static void probe(String event, String detail) {
        if (GlassProbe.enabled()) BubbleLifecycleProbe.event(event,null,null,detail);
    }
}
