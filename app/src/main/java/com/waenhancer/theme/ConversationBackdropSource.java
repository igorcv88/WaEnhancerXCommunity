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
    /**
     * Last finished texture in this process and what it shows (content key + blur). Every chat
     * opens a new source whose first render takes a few frames; with this, a chat with the same
     * wallpaper look shows that blur at once instead of native bubbles (the "blink" on open),
     * and its own render still replaces it. One texture, replaced by each newer render.
     */
    private static Bitmap lastTexture;
    private static String lastLook;
    private String desiredLook;
    /** First usable plan only: explain why the fast cached path did or did not qualify. */
    private boolean firstLookRouteReported;
    /** Window size the texture covers (a provisional disk texture is smaller and scaled up). */
    private int textureWidth, textureHeight;
    public int textureWidth() { return textureWidth; }
    public int textureHeight() { return textureHeight; }

    // --- Last blurred wallpaper on disk (owner choice 2026-11-03, option 2) -------------------
    // Only the replayed wallpaper (never a screen copy, never chat content), at quarter
    // resolution, in WhatsApp's cache directory: OS-clearable and excluded from backups. Read
    // once per process before the first chat opens; the chat's own render still replaces it.
    static final int DISK_SCALE=4;
    static final long MAX_DISK_BYTES=4L*1024*1024;
    static final String DISK_DIR="waex", DISK_IMAGE="bubble_backdrop.webp", DISK_KEY="bubble_backdrop.key";
    private static volatile java.io.File diskDir;
    private static volatile Bitmap diskTexture;
    private static volatile String diskLook;
    /** Look currently on disk; read and written on WORKER only. */
    private static String savedLook;

    /** Loads the stored blur off the UI thread; at most once per process. */
    public static void preloadDisk(java.io.File cacheDir) {
        if (cacheDir==null || diskDir!=null || Build.VERSION.SDK_INT<33) return;
        final java.io.File dir=new java.io.File(cacheDir,DISK_DIR);
        diskDir=dir;
        WORKER.execute(() -> {
            try {
                java.io.File image=new java.io.File(dir,DISK_IMAGE), key=new java.io.File(dir,DISK_KEY);
                if (!image.isFile() || !key.isFile() || image.length()>MAX_DISK_BYTES || key.length()>4096) return;
                String look=new String(java.nio.file.Files.readAllBytes(key.toPath()),java.nio.charset.StandardCharsets.UTF_8);
                Bitmap decoded=android.graphics.BitmapFactory.decodeFile(image.getPath());
                if (decoded==null) return;
                Bitmap uploaded=decoded.copy(Bitmap.Config.HARDWARE,false);
                decoded.recycle();
                if (uploaded==null) return;
                savedLook=look;
                diskTexture=uploaded;
                diskLook=look; // Published last: a reader that sees the look also sees the texture.
            } catch (Throwable unreadable) {
                // A missing or damaged cache only means the first chat waits for its render.
            }
        });
    }

    /** Bubble glass switched off: nothing of the wallpaper is kept. */
    public static void clearDisk(java.io.File cacheDir) {
        if (cacheDir==null) return;
        final java.io.File dir=new java.io.File(cacheDir,DISK_DIR);
        WORKER.execute(() -> {
            new java.io.File(dir,DISK_KEY).delete();
            new java.io.File(dir,DISK_IMAGE).delete();
            savedLook=null;
        });
    }

    /** WORKER: stores a quarter-resolution copy of a finished texture when its look is new. */
    private static void saveDisk(Bitmap texture, String look) {
        java.io.File dir=diskDir;
        if (dir==null || look==null || look.equals(savedLook)) return;
        int w=Math.max(1,texture.getWidth()/DISK_SCALE), h=Math.max(1,texture.getHeight()/DISK_SCALE);
        RenderNode scaled=new RenderNode("WaEX-backdrop-disk");
        try {
            scaled.setPosition(0,0,w,h);
            Canvas canvas=scaled.beginRecording(w,h);
            try { canvas.drawBitmap(texture,null,new Rect(0,0,w,h),new Paint(Paint.FILTER_BITMAP_FLAG)); }
            finally { scaled.endRecording(); }
            Render small=draw(scaled,w,h);
            if (small.bitmap==null) return;
            Bitmap software=small.bitmap.copy(Bitmap.Config.ARGB_8888,false);
            if (software==null) return;
            try {
                if (!dir.isDirectory() && !dir.mkdirs()) return;
                java.io.File image=new java.io.File(dir,DISK_IMAGE), key=new java.io.File(dir,DISK_KEY);
                java.io.File imageTmp=new java.io.File(dir,DISK_IMAGE+".tmp"), keyTmp=new java.io.File(dir,DISK_KEY+".tmp");
                try (java.io.FileOutputStream out=new java.io.FileOutputStream(imageTmp)) {
                    if (!software.compress(Bitmap.CompressFormat.WEBP_LOSSY,90,out)) return;
                }
                java.nio.file.Files.write(keyTmp.toPath(),look.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                // Key removed first: a crash in between leaves no key rather than a wrong pair.
                key.delete();
                if (!imageTmp.renameTo(image) || !keyTmp.renameTo(key)) return;
                savedLook=look;
            } finally {
                software.recycle();
            }
        } catch (Throwable unwritable) {
            // Disk cache is an optimisation only.
        } finally {
            scaled.discardDisplayList();
        }
    }
    public ConversationBackdropSource(Runnable changed) { this.changed=changed; }
    /**
     * A texture is presentable while a newer revision prepares (stale-while-revalidate): a
     * keyboard or geometry change must not flash every bubble back to native. Only an
     * unsupported wallpaper, a permanent failure or release withdraws it.
     */
    public boolean ready() { return texture!=null && !closed; }
    public Bitmap texture() { return texture; }
    /** The current revision failed for good (render exception or retries exhausted). */
    public boolean failed() { return failed && !closed; }
    private boolean failed;
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
        desired=usable ? capture : null; blur=radius; failed=false;
        desiredContent=null;
        desiredLook=usable && capture.contentKey!=null ? capture.contentKey+"|blur="+radius : null;
        if (usable && !firstLookRouteReported) {
            firstLookRouteReported=true;
            boolean memoryMatch=desiredLook!=null && desiredLook.equals(lastLook) && lastTexture!=null;
            boolean diskMatch=desiredLook!=null && desiredLook.equals(diskLook) && diskTexture!=null;
            probe("BUBBLE_FIRST_LOOK_ROUTE", "eligible="+(desiredLook!=null)
                    +" memoryMatch="+memoryMatch+" diskMatch="+diskMatch
                    +" memoryReady="+(lastTexture!=null)+" diskReady="+(diskTexture!=null));
        }
        if (usable) {
            try { desiredContent=capture.record(); }
            catch (RuntimeException | LinkageError failure) {
                desired=null;
                failed=true; // Same as a permanent render failure: eligible for the bare-wallpaper copy.
                BubbleGlassTelemetry.source("unsupported", "record-"+failure.getClass().getSimpleName());
            }
        }
        debounce.changed(android.os.SystemClock.uptimeMillis());
        if (desired==null && texture!=null) { texture=null; main.post(changed); }
        if (texture==null && desiredLook!=null) {
            // Provisional: same look (this process, else the disk copy); this chat's render still
            // replaces it.
            boolean memory=desiredLook.equals(lastLook) && lastTexture!=null;
            String stored=diskLook;
            Bitmap shared=memory ? lastTexture : desiredLook.equals(stored) ? diskTexture : null;
            if (shared!=null) {
                texture=shared; textureWidth=capture.width; textureHeight=capture.height;
                BubbleGlassTelemetry.source("provisional", memory ? "shared-look" : "disk");
                main.post(changed);
            }
        }
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
        final String look=desiredLook;
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
                    textureWidth=result.bitmap.getWidth(); textureHeight=result.bitmap.getHeight();
                    if (desiredLook!=null) { lastTexture=result.bitmap; lastLook=desiredLook; }
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
                    failed=true;
                    BubbleGlassTelemetry.source("unavailable", String.valueOf(result.outcome));
                    probe("SOURCE_UNAVAILABLE","revision="+token+" reason="+result.outcome);
                    changed.run();
                }
            });
            // After the result is posted: the UI thread does not wait for the disk copy.
            if (result.outcome==SourceRetryPolicy.Outcome.READY) saveDisk(result.bitmap, look);
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
