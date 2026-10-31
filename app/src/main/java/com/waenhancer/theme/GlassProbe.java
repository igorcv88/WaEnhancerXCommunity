package com.waenhancer.theme;

import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewParent;

/**
 * Opt-in, bounded diagnostics for native view ownership and glass frame transitions.
 * Enable with {@code setprop log.tag.WaEXGlassDiag DEBUG}. No message bodies,
 * contact identities, text, image data, URLs, or accessibility content are inspected.
 * All callers run on WhatsApp's UI thread; no listeners or work loops are installed.
 */
public final class GlassProbe {
    /** The property gate and the emitted log must share this *same* Android-valid tag. */
    public static final String TAG = "WaEXGlassDiag";
    private static final GlassProbeBudget BUDGET = new GlassProbeBudget();
    private GlassProbe() { }

    public static boolean enabled() { return Log.isLoggable(TAG, Log.DEBUG); }

    public static void event(String type, View view, String details) {
        if (!enabled()) return;
        long now = SystemClock.uptimeMillis();
        if (BUDGET.beginWindow(now)) {
            // INFO is independently visible and distinguishes an active probe from an absent hook.
            Log.i(TAG, "t=" + now + " event=PROBE_ACTIVE cap="
                    + GlassProbeBudget.MAX_EVENTS_PER_MINUTE + " reserve="
                    + GlassProbeBudget.TRANSITION_RESERVE + " debugGate=true");
        }
        if (!BUDGET.allow(type, view == null ? 0 : System.identityHashCode(view), details, now)) return;
        // Do not rely on Android forwarding DEBUG messages from an injected release process:
        // device PR #111 showed debugGate=true but zero DEBUG entries. The explicit opt-in above
        // already limits INFO events to the operator's short measurement window.
        Log.i(TAG, "t=" + now + " event=" + type + " view=" + describe(view) + " " + details);
    }

    public static String drawable(Drawable drawable) {
        if (drawable == null) return "none";
        return drawable.getClass().getSimpleName() + "@" + GlassTrace.id(drawable);
    }

    /** Resource names are layout metadata; never resolve or inspect a view's TextView content. */
    public static String role(View view) {
        if (view == null || view.getId() == View.NO_ID || view.getId() == 0) return "unnamed";
        try { return view.getResources().getResourceEntryName(view.getId()); }
        catch (Resources.NotFoundException ignored) { return "unknown-id"; }
    }

    public static boolean composerNode(View view) {
        String role = role(view);
        return role.equals("input_layout") || role.startsWith("voice_note_")
                || role.startsWith("composer_quoted_") || role.startsWith("quoted_message_preview_")
                || role.equals("edit_layout") || role.equals("conversation_entry_action_button");
    }

    public static String describe(View view) {
        if (view == null) return "none";
        int[] screen = new int[2];
        view.getLocationOnScreen(screen);
        ViewParent parent = view.getParent();
        return view.getClass().getSimpleName() + "@" + GlassTrace.id(view)
                + " role=" + role(view) + " id=" + Integer.toHexString(view.getId())
                + " parent=" + GlassTrace.id(parent)
                + " bounds=" + screen[0] + "," + screen[1] + ","
                + view.getWidth() + "," + view.getHeight()
                + " offset=" + view.getTranslationX() + "," + view.getTranslationY()
                + " vis=" + view.getVisibility() + " shown=" + view.isShown()
                + " attached=" + view.isAttachedToWindow()
                + " bg=" + drawable(view.getBackground());
    }
}
