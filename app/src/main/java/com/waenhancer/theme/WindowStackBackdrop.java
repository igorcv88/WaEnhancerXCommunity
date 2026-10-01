package com.waenhancer.theme;

import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.WindowManager;
import android.view.inspector.WindowInspector;

import java.util.ArrayList;
import java.util.List;

/** Same-process windows only. Screen coordinates include the dim layer below each window. */
final class WindowStackBackdrop {
    private WindowStackBackdrop() { }
    static List<View> layers(View own) {
        List<View> result = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 29) { result.add(own); return result; }
        List<GlassWindowOrder.Entry<View>> entries = new ArrayList<>();
        for (View candidate : WindowInspector.getGlobalWindowViews()) {
            WindowManager.LayoutParams lp = params(candidate);
            if (lp != null) entries.add(new GlassWindowOrder.Entry<>(candidate, lp.token,
                    candidate.getWindowToken(), lp.type, candidate.isAttachedToWindow() && candidate.isShown()));
        }
        return GlassWindowOrder.through(entries, own);
    }
    static void drawLayer(Canvas canvas, View layer, int originX, int originY, int width, int height) {
        WindowManager.LayoutParams lp = params(layer);
        if (lp != null && (lp.flags & WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0) {
            canvas.drawColor(Color.argb(Math.round(Math.max(0f, Math.min(1f, lp.dimAmount)) * 255), 0, 0, 0));
        }
        int[] location = new int[2];
        layer.getLocationOnScreen(location);
        int save = canvas.save();
        try {
            canvas.clipRect(0, 0, width, height);
            canvas.translate(location[0] - originX, location[1] - originY);
            canvas.clipRect(0, 0, layer.getWidth(), layer.getHeight());
            if (layer.getAlpha() < 1f) canvas.saveLayerAlpha(0, 0, layer.getWidth(), layer.getHeight(), Math.round(layer.getAlpha() * 255));
            canvas.translate(-layer.getScrollX(), -layer.getScrollY());
            layer.draw(canvas);
        } finally { canvas.restoreToCount(save); }
    }
    private static WindowManager.LayoutParams params(View view) {
        return view != null && view.getLayoutParams() instanceof WindowManager.LayoutParams
                ? (WindowManager.LayoutParams) view.getLayoutParams() : null;
    }
}
