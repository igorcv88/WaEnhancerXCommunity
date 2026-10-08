package com.waenhancer.theme;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.view.View;
import android.view.ViewGroup;
import java.lang.ref.WeakReference;

/** Wallpaper continuity beneath a capsule: no blur, refraction, border or optical budget. */
public final class WallpaperUnderlay extends View {
    private WeakReference<View> wallpaper = new WeakReference<>(null);
    private final Matrix local = new Matrix(), inverse = new Matrix(), placement = new Matrix();
    public WallpaperUnderlay(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    public void setWallpaper(View view) {
        if (wallpaper.get() == view) return;
        wallpaper = new WeakReference<>(view);
        invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        View view = wallpaper.get();
        if (view == null || !view.isAttachedToWindow() || view.getVisibility() != VISIBLE) return;
        // Even a plain underlay must never draw itself through an ancestor's cached display list.
        for (View cursor = this; cursor != null;
             cursor = cursor.getParent() instanceof View ? (View) cursor.getParent() : null) {
            if (cursor == view) return;
        }
        if (containsGlass(view)) return;
        local.reset(); transformMatrixToGlobal(local);
        if (!local.invert(inverse)) return;
        placement.reset(); view.transformMatrixToGlobal(placement); placement.postConcat(inverse);
        int saved = canvas.save();
        try {
            canvas.concat(placement);
            canvas.translate(-view.getScrollX(), -view.getScrollY());
            view.draw(canvas);
        } finally { canvas.restoreToCount(saved); }
    }
    private static boolean containsGlass(View view) {
        if (view instanceof GlassPane || view instanceof WallpaperUnderlay) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (containsGlass(group.getChildAt(i))) return true;
        }
        return false;
    }

}
