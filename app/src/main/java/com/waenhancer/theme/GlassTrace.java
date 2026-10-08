package com.waenhancer.theme;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

/** Transition diagnostics only. Never include message text, contact identifiers or images. */
public final class GlassTrace {
    private GlassTrace() { }
    public static String id(Object object) {
        return object == null ? "none" : Integer.toHexString(System.identityHashCode(object));
    }
    public static void transition(View root, Object surface, Object source,
                                  SurfaceRecovery.State before, SurfaceRecovery recovery,
                                  String reason, String details) {
        if (before == recovery.state()) return;
        event(root, surface, source, before + "->" + recovery.state(), reason,
                "epoch=" + recovery.generation() + " retryCount=" + recovery.failures() + " " + details);
    }
    public static void event(View root, Object surface, Object source, String state,
                             String reason, String details) {
        String activity = "none";
        boolean powerSave = false;
        if (root != null) {
            Context context = root.getContext();
            for (int i = 0; context instanceof ContextWrapper && i < 12; i++) {
                if (context instanceof Activity) { activity = context.getClass().getSimpleName(); break; }
                Context next = ((ContextWrapper) context).getBaseContext();
                if (next == context) break;
                context = next;
            }
            try {
                PowerManager power = (PowerManager) root.getContext().getSystemService(Context.POWER_SERVICE);
                powerSave = power != null && power.isPowerSaveMode();
            } catch (RuntimeException ignored) { }
        }
        Log.i("WaEnhancerX/GlassState", "t=" + SystemClock.uptimeMillis() + " pid=" + Process.myPid()
                + " activity=" + activity + " rootId=" + id(root) + " surfaceId=" + id(surface)
                + " sourceId=" + id(source) + " state=" + state + " reason=" + reason
                + " powerSave=" + powerSave + " " + details);
    }
}
