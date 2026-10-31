package com.waenhancer.xposed.features.customization;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.view.View;
import com.waenhancer.theme.GlassPane;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

/** Tint only registered native decorative drawings; never replace its touch/playback logic. */
final class NativeWaveformTint {
    private static final WeakHashMap<View, Paint> TINTS = new WeakHashMap<>();
    private static final Set<Method> HOOKED = new HashSet<>();
    static boolean apply(View view, int color) {
        try {
            Method method = null;
            for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
                try { method = type.getDeclaredMethod("onDraw", Canvas.class); break; }
                catch (NoSuchMethodException ignored) { }
            }
            if (method == null) return false;
            if (!HOOKED.contains(method)) {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        View target = (View) param.thisObject;
                        Paint paint = TINTS.get(target);
                        if (paint == null || GlassPane.isCapturing()) return;
                        Canvas canvas = (Canvas) param.args[0];
                        param.setObjectExtra("waex-wave-layer", canvas.saveLayer(0, 0,
                                target.getWidth(), target.getHeight(), paint));
                    }
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Object save = param.getObjectExtra("waex-wave-layer");
                        if (save instanceof Integer) ((Canvas) param.args[0]).restoreToCount((Integer) save);
                    }
                });
                HOOKED.add(method);
            }
            Paint previous = TINTS.get(view);
            if (previous != null && previous.getColor() == color) return true;
            Paint paint = new Paint();
            paint.setColor(color);
            paint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            TINTS.put(view, paint);
            view.invalidate();
            return true;
        } catch (RuntimeException | LinkageError failure) {
            return false; // Unsupported waveform keeps native pixels and all native behavior.
        }
    }
    static void remove(View view) { if (TINTS.remove(view) != null) view.invalidate(); }
}
