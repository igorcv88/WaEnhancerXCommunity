package com.waenhancer.theme;

import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

/** Extend a native bitmap's existing projection beyond its View's clip, without resizing it. */
final class WallpaperProjection {
    private WallpaperProjection() { }
    static boolean draw(Canvas canvas, View wallpaper, View underlay) {
        View source = bitmapSource(wallpaper, 0);
        if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) return false;
        BitmapDrawable drawable = source instanceof ImageView
                && ((ImageView) source).getDrawable() instanceof BitmapDrawable
                ? (BitmapDrawable) ((ImageView) source).getDrawable()
                : (BitmapDrawable) source.getBackground();
        // Tiled and auto-mirrored drawables retain their native renderer.
        if (drawable.getTileModeX() != null || drawable.getTileModeY() != null
                || drawable.isAutoMirrored()) return false;
        if (drawable.getBitmap() == null || drawable.getBitmap().isRecycled()) return false;
        Rect bounds = new Rect(drawable.getBounds());
        if (bounds.isEmpty()) return false;
        Matrix from = new Matrix(), to = new Matrix(), inverse = new Matrix();
        source.transformMatrixToGlobal(from);
        underlay.transformMatrixToGlobal(to);
        if (!to.invert(inverse)) return false;
        from.postConcat(inverse);
        Matrix bitmap = new Matrix();
        if (source instanceof ImageView && ((ImageView) source).getDrawable() == drawable) {
            bitmap.setScale((float) bounds.width() / drawable.getBitmap().getWidth(),
                    (float) bounds.height() / drawable.getBitmap().getHeight());
            bitmap.postTranslate(bounds.left, bounds.top);
            bitmap.postConcat(((ImageView) source).getImageMatrix());
            bitmap.postTranslate(source.getPaddingLeft(), source.getPaddingTop());
        } else {
            // Match BitmapDrawable's gravity in its existing bounds, rather than fit the
            // entire wallpaper View to the taller decor. Native crop and source scale persist.
            Rect destination = new Rect();
            Gravity.apply(drawable.getGravity(), drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(),
                    bounds, destination, source.getLayoutDirection());
            bitmap.setScale((float) destination.width() / drawable.getBitmap().getWidth(),
                    (float) destination.height() / drawable.getBitmap().getHeight());
            bitmap.postTranslate(destination.left, destination.top);
        }
        bitmap.postConcat(from);
        BitmapShader shader = new BitmapShader(drawable.getBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        shader.setLocalMatrix(bitmap);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setShader(shader);
        paint.setAlpha(drawable.getAlpha());
        paint.setColorFilter(drawable.getColorFilter());
        canvas.drawRect(0, 0, underlay.getWidth(), underlay.getHeight(), paint);
        return true;
    }
    private static View bitmapSource(View view, int depth) {
        if (depth > 5 || !view.isShown()) return null;
        if (view instanceof ImageView && ((ImageView) view).getDrawable() instanceof BitmapDrawable) return view;
        if (view.getBackground() instanceof BitmapDrawable) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View result = bitmapSource(((ViewGroup) view).getChildAt(i), depth + 1);
            if (result != null) return result;
        }
        return null;
    }
}
