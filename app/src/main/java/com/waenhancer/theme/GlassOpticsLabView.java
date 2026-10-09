package com.waenhancer.theme;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;

/** Detached, synthetic-only HWUI lab. Never records WhatsApp or reads its window. */
public final class GlassOpticsLabView extends View {
    private final Paint paint = new Paint();
    private final RenderNode source = new RenderNode("glass lab source");
    private final RenderNode output = new RenderNode("glass lab output");
    private final LensEffect lens = new LensEffect();
    private GlassOptics optics = GlassOptics.ALL;
    private boolean rendered;
    private int recordedWidth, recordedHeight, recordedRevision = -1;
    private int revision;
    private long generation;

    public GlassOpticsLabView(Context context) { super(context); }

    public void configure(GlassOptics value) {
        optics = value;
        revision++;
        rendered = false;
        invalidate();
    }

    public String metadata() {
        return optics.key() + " " + getWidth() + "x" + getHeight()
                + " density=" + getResources().getDisplayMetrics().density
                + " module=" + com.waenhancer.BuildConfig.VERSION_NAME + "(" + com.waenhancer.BuildConfig.VERSION_CODE + ")"
                + " api=" + Build.VERSION.SDK_INT + " device=" + Build.MANUFACTURER + "/" + Build.MODEL
                + " generation=" + generation + " " + lens.status();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        pattern(canvas, getWidth(), getHeight());
        if (Build.VERSION.SDK_INT < 33 || !canvas.isHardwareAccelerated()) return;
        float density = getResources().getDisplayMetrics().density;
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        GlassSpec spec = GlassSpec.resolve(GlassSpec.Variant.LIQUID, true, 0,
                0xFF25D366, 10f, true, false);
        if (optics.clearProfile) spec = spec.clearProfile();
        int padding = LiveBudget.padding(optics, spec, density);
        if (recordedWidth != w || recordedHeight != h || recordedRevision != revision) {
            source.setPosition(0, 0, w + 2*padding, h + 2*padding);
            RecordingCanvas input = source.beginRecording(w + 2*padding, h + 2*padding);
            try {
                input.translate(padding, padding);
                pattern(input, w, h);
            } finally { source.endRecording(); }
            lens.update(spec, w, h, Math.min(h/2f, 28f*density), density,
                    optics, padding, true);
            if (lens.effect() == null) return;
            output.setPosition(-padding, -padding, w + padding, h + padding);
            RecordingCanvas result = output.beginRecording(w + 2*padding, h + 2*padding);
            try { result.drawRenderNode(source); } finally { output.endRecording(); }
            output.setRenderEffect(lens.effect());
            recordedWidth = w;
            recordedHeight = h;
            recordedRevision = revision;
            generation++;
        }
        canvas.drawRenderNode(output);
        rendered = true;
    }

    /** Native-pixel bars 1/2/4/8, diagonal, ESF, grey/color fields; no antialias or resize. */
    private void pattern(Canvas c, int w, int h) {
        c.drawColor(0xFF808080);
        paint.setAntiAlias(false);
        for (int section = 0; section < 4; section++) {
            int period = 1 << section;
            int start = section*w/4, end = (section+1)*w/4;
            for (int x = start; x < end; x += 2*period) {
                paint.setColor(0xFFFFFFFF);
                c.drawRect(x, 0, Math.min(x+period,end), h/3f, paint);
                paint.setColor(0xFF000000);
                c.drawRect(Math.min(x+period,end), 0, Math.min(x+2*period,end), h/3f, paint);
            }
        }
        paint.setColor(0xFF000000); c.drawRect(0,h/3f,w/2f,h*2/3f,paint);
        paint.setColor(0xFFFFFFFF); c.drawRect(w/2f,h/3f,w,h*2/3f,paint);
        int[] colors = {0xFF202020,0xFF808080,0xFFF0F0F0,0xFFFF4040,0xFF40C080,0xFF4080FF};
        for (int i=0;i<colors.length;i++) {
            paint.setColor(colors[i]);
            c.drawRect(i*w/6f,h*2/3f,(i+1)*w/6f,h,paint);
        }
        paint.setColor(0xFF101010);
        paint.setStrokeWidth(2f);
        c.drawLine(0,0,w,h,paint);
    }

    public interface CopyResult { void complete(Bitmap bitmap, String error); }

    /** Read actual window-composited HWUI pixels of the synthetic view, including isolated modes. */
    public void copyGpuPng(Activity activity, CopyResult callback) {
        if (Build.VERSION.SDK_INT < 33 || !rendered || !isHardwareAccelerated()) {
            callback.complete(null, "No complete hardware-rendered frame is available.");
            return;
        }
        int[] at = new int[2];
        Rect visible = new Rect();
        if (!getLocalVisibleRect(visible) || visible.width() != getWidth() || visible.height() != getHeight()) {
            callback.complete(null,"Scroll until the complete lab image is visible.");
            return;
        }
        getLocationInWindow(at);
        Rect bounds = new Rect(at[0],at[1],at[0]+getWidth(),at[1]+getHeight());
        Bitmap bitmap = Bitmap.createBitmap(getWidth(),getHeight(),Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(activity.getWindow(),bounds,bitmap,result -> {
                if (result == PixelCopy.SUCCESS) callback.complete(bitmap,null);
                else { bitmap.recycle(); callback.complete(null,"PixelCopy result="+result); }
            },new Handler(Looper.getMainLooper()));
        } catch (RuntimeException e) {
            bitmap.recycle();
            callback.complete(null,"PixelCopy failed: "+e.getClass().getSimpleName());
        }
    }

    @Override protected void onDetachedFromWindow() {
        source.discardDisplayList(); output.discardDisplayList();
        recordedWidth = 0; recordedRevision = -1; rendered = false;
        super.onDetachedFromWindow();
    }
}
