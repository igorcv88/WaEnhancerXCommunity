package com.waenhancer.theme;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import java.util.WeakHashMap;

/** Transition diagnostics only. Never include message text, contact identifiers or images. */
public final class GlassTrace {
    private static final WeakHashMap<View, ResumeEpoch> resumes = new WeakHashMap<>();
    private static long nextResume;
    private static final class ResumeEpoch {
        final long id = ++nextResume;
        final long at = SystemClock.uptimeMillis();
        long frame;
        final WeakHashMap<Object, ResumeTiming> surfaces = new WeakHashMap<>();
        ResumeTiming timing(Object surface) {
            ResumeTiming timing = surfaces.get(surface);
            if (timing == null) { timing = new ResumeTiming(at); surfaces.put(surface, timing); }
            return timing;
        }
        String details(ResumeTiming timing) {
            return "resumeEpoch=" + id + " firstPresentationMs=" + timing.presentationDelay()
                    + " firstCompleteCaptureMs=" + timing.captureDelay() + " firstLiveMs=" + timing.liveDelay()
                    + " fallbackFrames=" + timing.fallbackFrames();
        }
    }
    private GlassTrace() { }
    /** Main thread only; weak ownership does not extend the Activity/surface lifetime. */
    public static void resumed(View root) {
        if (root == null) return;
        ResumeEpoch epoch = new ResumeEpoch();
        resumes.put(root, epoch);
        event(root, root, root, "RESUMED", "presentation-epoch", "resumeEpoch=" + epoch.id);
    }
    public static void suspended(View root) { resumes.remove(root); }
    public static void frame(View root) {
        ResumeEpoch epoch = resumes.get(root);
        if (epoch != null) epoch.frame++;
    }
    public static void captured(View root, Object surface, Object source) {
        ResumeEpoch epoch = resumes.get(root);
        if (epoch == null) return;
        ResumeTiming timing = epoch.timing(surface);
        if (timing.captured(SystemClock.uptimeMillis())) {
            event(root, surface, source, "FIRST_COMPLETE_CAPTURE", "resume-capture", epoch.details(timing));
        }
    }
    public static void presented(View root, Object surface, Object source, boolean live) {
        ResumeEpoch epoch = resumes.get(root);
        if (epoch == null) return;
        long now = SystemClock.uptimeMillis();
        ResumeTiming timing = epoch.timing(surface);
        int events = timing.presented(now, epoch.frame, live);
        if ((events & ResumeTiming.FIRST_PRESENTATION) != 0) {
            event(root, surface, source, "FIRST_PRESENTATION", live ? "live" : "fallback", epoch.details(timing));
        }
        if ((events & ResumeTiming.FIRST_LIVE) != 0) {
            event(root, surface, source, "PRESENTING_LIVE", "first-after-resume", epoch.details(timing));
        }
    }
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
