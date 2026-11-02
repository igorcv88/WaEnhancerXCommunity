package com.waenhancer.theme;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.view.View;

/** Native pass-through except a verified outer factory above an already drawn text-row material. */
public final class BubbleFactoryDrawable extends InsetDrawable {
    private final Matrix matrix=new Matrix(), inverse=new Matrix();
    private final RectF area=new RectF();
    public BubbleFactoryDrawable(Drawable nativeDrawable) { super(nativeDrawable,0); }
    @Override public void draw(Canvas canvas) {
        GlassMaterialDrawable.DrawHost frame=GlassMaterialDrawable.currentRowDraw();
        if (!Boolean.TRUE.equals(BubbleGlassDrawable.MASKING.get()) && frame!=null && frame.canvas==canvas) {
            View row=frame.row();
            if (row.getBackground() instanceof BubbleGlassDrawable && frame.matrix.invert(inverse)) {
                canvas.getMatrix(matrix); matrix.postConcat(inverse);
                area.set(getBounds()); matrix.mapRect(area);
                if (((BubbleGlassDrawable)row.getBackground()).suppressFactory(area)) return;
            }
        }
        super.draw(canvas);
    }
}
