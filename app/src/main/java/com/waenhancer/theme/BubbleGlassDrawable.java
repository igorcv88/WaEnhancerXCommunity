package com.waenhancer.theme;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.View;
import java.lang.ref.WeakReference;

/** Text-row consumer: one compiled optical shader, native alpha mask and a shared prepared texture. */
public final class BubbleGlassDrawable extends Drawable implements Drawable.Callback {
    static final ThreadLocal<Boolean> MASKING = new ThreadLocal<>();
    private final Drawable original;
    private final WeakReference<View> row, root;
    private final ConversationBackdropSource source;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG), mask=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect region=new Rect();
    private final Matrix rowGlobal=new Matrix(), rootGlobal=new Matrix(), inverse=new Matrix(), textureToLocal=new Matrix();
    private final float[] values=new float[9];
    // Separate scratch for the pre-draw alignment check; draw() keeps its own matrices.
    private final Matrix checkRow=new Matrix(), checkRoot=new Matrix(), checkInverse=new Matrix(), checkOut=new Matrix();
    private final float[] checkValues=new float[9];
    private final TextureAlignment alignment=new TextureAlignment();
    private RuntimeShader shader;
    private BitmapShader textureShader;
    private Bitmap texture;
    private GlassSpec material;
    private boolean broken, presented, dirty;
    private int opticRevision=-1;
    public BubbleGlassDrawable(View row, View root, Drawable original, ConversationBackdropSource source) {
        this.row=new WeakReference<>(row); this.root=new WeakReference<>(root);
        this.original=original; this.source=source;
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }
    public Drawable original() { return original; }
    public boolean consumeDamage() { boolean value=dirty; dirty=false; return value; }
    public void attached() { original.setCallback(this); }
    boolean suppressFactory(RectF bounds) {
        return presented && !broken && source.ready() && texture==source.texture()
                && bounds.width()>=region.width()*.75f && bounds.height()>=region.height()*.75f
                && bounds.left>=region.left-2 && bounds.top>=region.top-2
                && bounds.right<=region.right+2 && bounds.bottom<=region.bottom+2;
    }
    @Override public void invalidateDrawable(Drawable who) { invalidateSelf(); }
    @Override public void scheduleDrawable(Drawable who, Runnable what, long when) { scheduleSelf(what,when); }
    @Override public void unscheduleDrawable(Drawable who, Runnable what) { unscheduleSelf(what); }
    /** Called during layout reconciliation, never shader compilation in a draw callback. */
    public boolean prepare(Rect area, GlassSpec spec) {
        if (broken || !source.ready() || spec==null || area.isEmpty() || android.os.Build.VERSION.SDK_INT<33) return false;
        try {
            if (shader==null) shader=new RuntimeShader(KyantLens.SHADER);
            if (!area.equals(region) || !spec.equals(material) || opticRevision!=GlassOptics.revision()) {
                opticRevision=GlassOptics.revision(); dirty=true;
                region.set(area); material=spec;
                View view=row.get(); if (view==null) return false;
                float density=view.getResources().getDisplayMetrics().density;
                KyantLens.configure(shader,KyantLens.appSurface(spec,area.height(),density,KyantLens.surfaceAlpha255()),
                        area.width(),area.height(),16f*density,0,0);
            }
            if (texture!=source.texture()) {
                texture=source.texture(); dirty=true;
                textureShader=new BitmapShader(texture,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
                presented=false; alignment.reset();
            }
            paint.setShader(shader);
            return true;
        } catch (RuntimeException | LinkageError failure) { broken=true; return false; }
    }
    @Override public void draw(Canvas canvas) {
        View view=row.get(), window=root.get();
        if (broken || !canvas.isHardwareAccelerated() || !source.ready() || texture!=source.texture()
                || shader==null || view==null || window==null || region.isEmpty()) { nativeDraw(canvas); return; }
        // Window texture -> row -> material. Scrolling changes only these matrices, never the source.
        rowGlobal.reset(); view.transformMatrixToGlobal(rowGlobal);
        rootGlobal.reset(); window.transformMatrixToGlobal(rootGlobal);
        if (!rowGlobal.invert(inverse)) { nativeDraw(canvas); return; }
        textureToLocal.set(rootGlobal); textureToLocal.postConcat(inverse);
        textureToLocal.postTranslate(-region.left,-region.top);
        textureToLocal.getValues(values);
        if (!textureToLocal.rectStaysRect()) { nativeDraw(canvas); return; }
        alignment.recordDrawn(values);
        int save=canvas.save();
        try {
            textureShader.setLocalMatrix(textureToLocal); shader.setInputShader("content",textureShader);
            canvas.translate(region.left,region.top);
            int layer=canvas.saveLayer(0,0,region.width(),region.height(),null);
            paint.setAlpha(255); canvas.drawRect(0,0,region.width(),region.height(),paint);
            int silhouette=canvas.saveLayer(0,0,region.width(),region.height(),mask);
            canvas.translate(-region.left,-region.top); nativeDraw(canvas);
            canvas.restoreToCount(silhouette); canvas.restoreToCount(layer);
            if (!presented) {
                presented=true;
                ConversationBackdropSource.probe("BUBBLE_FIRST_PIXEL_PRESENTED","revision="+source.revision());
            }
        } catch (RuntimeException | LinkageError failure) {
            broken=true;
        } finally { canvas.restoreToCount(save); }
        if (broken) nativeDraw(canvas);
    }
    /**
     * Pre-draw check (main thread): RecyclerView moves rows by RenderNode offset and replays the
     * recorded display list, so the BitmapShader matrix from the last draw would keep sampling
     * the previous wallpaper region. Only a row whose transform changed is invalidated; it then
     * re-records with the same shared texture (no re-blur, no reallocation).
     *
     * @return true when the row was invalidated
     */
    public boolean realignIfMoved() {
        View view=row.get(), window=root.get();
        if (view==null || window==null || region.isEmpty() || !presented) return false;
        checkRow.reset(); view.transformMatrixToGlobal(checkRow);
        checkRoot.reset(); window.transformMatrixToGlobal(checkRoot);
        if (!checkRow.invert(checkInverse)) return false;
        checkOut.set(checkRoot); checkOut.postConcat(checkInverse);
        checkOut.postTranslate(-region.left,-region.top);
        checkOut.getValues(checkValues);
        if (!alignment.moved(checkValues)) return false;
        invalidateSelf();
        return true;
    }

    private void nativeDraw(Canvas canvas) {
        Boolean previous=MASKING.get(); MASKING.set(true);
        try { original.setBounds(getBounds()); original.draw(canvas); }
        finally { if (previous==null) MASKING.remove(); else MASKING.set(previous); }
    }
    @Override public void setAlpha(int value) { original.setAlpha(value); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter value) { original.setColorFilter(value); invalidateSelf(); }
    @Override public void setTintList(android.content.res.ColorStateList tint) { original.setTintList(tint); invalidateSelf(); }
    @Override public void setTintMode(PorterDuff.Mode mode) { original.setTintMode(mode); invalidateSelf(); }
    @Override public int getIntrinsicWidth() { return original.getIntrinsicWidth(); }
    @Override public int getIntrinsicHeight() { return original.getIntrinsicHeight(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public boolean getPadding(Rect out) { return original.getPadding(out); }
    @Override public boolean isStateful() { return original.isStateful(); }
    @Override protected boolean onStateChange(int[] state) { return original.setState(state); }
    @Override protected boolean onLevelChange(int level) { return original.setLevel(level); }
}
