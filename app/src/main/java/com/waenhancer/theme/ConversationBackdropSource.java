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
    private long failedRevision=-1;
    public ConversationBackdropSource(Runnable changed) { this.changed=changed; }
    public boolean ready() { return texture!=null && epoch.ready() && !closed; }
    public Bitmap texture() { return texture; }
    public long revision() { return epoch.revision(); }
    public void update(WallpaperSnapshot snapshot, float radius) {
        if (closed) return;
        if (!epoch.update(snapshot==null ? null : java.util.Arrays.asList(snapshot,radius))) return;
        desired=snapshot; blur=radius; texture=null;
        probe("SOURCE_INVALIDATED", "revision="+epoch.revision());
        start();
    }
    public static float blurRadius(GlassSpec spec, float density) {
        return KyantLens.appSurface(spec,1,density,KyantLens.surfaceAlpha255()).blurRadius;
    }
    private void start() {
        if (working || closed || desired==null || failedRevision==epoch.revision() || Build.VERSION.SDK_INT<33
                || (long)desired.width*desired.height*4*6>MAX_BYTES) return;
        working=true;
        final long token=epoch.revision(); final WallpaperSnapshot snapshot=desired; final float radius=blur;
        WORKER.execute(() -> {
            Bitmap output=null;
            try { output=render(snapshot,radius); }
            catch (RuntimeException | LinkageError | OutOfMemoryError unsupported) { /* Native remains visible. */ }
            final Bitmap result=output;
            main.post(() -> {
                working=false;
                if (closed) return;
                if (token!=epoch.revision()) { start(); return; }
                epoch.complete(token,result!=null);
                if (result==null) { failedRevision=token; probe("SOURCE_UNAVAILABLE","revision="+token); }
                else {
                    texture=result;
                    probe("SOURCE_READY","revision="+token+" size="+result.getWidth()+"x"+result.getHeight());
                }
                changed.run();
            });
        });
    }
    private static Bitmap render(WallpaperSnapshot source, float radius) {
        if (source.bitmap.isRecycled() || source.bitmap.getGenerationId()!=source.generation) return null;
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
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
            try (Image image=reader.acquireNextImage()) {
                if (image==null) return null;
                try (HardwareBuffer buffer=image.getHardwareBuffer()) {
                    if (buffer==null) return null;
                    if (source.bitmap.isRecycled() || source.bitmap.getGenerationId()!=source.generation) return null;
                    return Bitmap.wrapHardwareBuffer(buffer,ColorSpace.get(ColorSpace.Named.SRGB));
                }
            }
        } finally {
            if (renderer!=null) renderer.destroy();
            if (node!=null) node.discardDisplayList();
            if (reader!=null) reader.close();
        }
    }
    public void release() { epoch.release(); desired=null; texture=null; closed=true; }
    public static void probe(String event, String detail) {
        if (GlassProbe.enabled()) BubbleLifecycleProbe.event(event,null,null,detail);
    }
}
