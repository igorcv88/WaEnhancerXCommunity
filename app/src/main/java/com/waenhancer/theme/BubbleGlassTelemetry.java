package com.waenhancer.theme;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Opt-in diagnostics for native-drawn bubble glass, enabled with the existing GlassProbe switch
 * ({@code adb shell setprop log.tag.WaEXGlassDiag DEBUG}).
 *
 * <p>Evidence instead of guesses: which view really records the bubble, how its texture was
 * aligned, which candidates a rule rejected, and how often the draw hook runs. Only class names,
 * resource entry names, geometry, matrices and factory primitive arguments are logged — never
 * message text, media, contacts or JIDs. Every kind is capped per minute; counters are plain UI
 * thread longs and cost nothing when the probe is off.</p>
 */
public final class BubbleGlassTelemetry {
    /** Why a hooked draw stayed native. Ordinal order is the report order. */
    public enum Native {
        NOT_REGISTERED, NATIVE_PASS, CAPTURE, UNHOSTED, SWITCH_OFF, NO_SESSION, NO_RENDERER,
        UNSUPPORTED, NOT_HARDWARE, NO_TEXTURE, TOO_SMALL, MATRIX, QUOTE, SLOT_FULL, ROOT, EXCEPTION
    }

    static final int PER_KIND_PER_MINUTE = 8;
    private static final long REPORT_MS = 5000L;
    private static final int CHAIN_DEPTH = 8;
    private static final Map<String, Integer> SENT = new HashMap<>();
    private static final Map<Drawable, String> FACTORY_ARGS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final long[] NATIVE = new long[Native.values().length];
    private static long calls, glass, reported = -1, window = -1, lastReport, enabledAt = -1;
    private static boolean enabled;

    private BubbleGlassTelemetry() { }

    /** Property read at most once per second: the draw hook runs for every hooked draw. */
    public static boolean on() {
        long now = SystemClock.uptimeMillis();
        if (enabledAt < 0 || now - enabledAt >= 1000L) { enabled = GlassProbe.enabled(); enabledAt = now; }
        return enabled;
    }

    /** Every entry into the bubble draw hook, before any filtering (framework-class hook cost). */
    public static void call() { calls++; }

    public static void glass() { glass++; }

    public static void stayedNative(Native reason) { NATIVE[reason.ordinal()]++; }

    /** Pre-draw: one frequency line every five seconds while something changed. */
    static void report() {
        if (!on()) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastReport < REPORT_MS || calls == reported) return;
        lastReport = now; reported = calls;
        StringBuilder out = new StringBuilder("calls=").append(calls).append(" glass=").append(glass);
        for (Native reason : Native.values()) {
            long n = NATIVE[reason.ordinal()];
            if (n != 0) out.append(' ').append(reason.name().toLowerCase(java.util.Locale.ROOT)).append('=').append(n);
        }
        log("FREQUENCY", out.toString());
    }

    /** Factory primitives per drawable: a candidate structural signal for bubble versus quote. */
    public static void factory(Drawable drawable, Object[] args) {
        if (!on() || drawable == null || args == null) return;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < args.length && i < 8; i++) {
            Object a = args[i];
            if (i > 0) out.append(',');
            out.append(a instanceof Number || a instanceof Boolean || a instanceof Character ? String.valueOf(a)
                    : a == null ? "null" : a.getClass().getSimpleName());
        }
        FACTORY_ARGS.put(drawable, out.toString());
    }

    static String factoryArgs(Drawable drawable) {
        String args = drawable == null ? null : FACTORY_ARGS.get(drawable);
        return args == null ? "?" : args;
    }

    /** Point 1: the real recording view, from evidence. */
    static void unhosted(Drawable drawable, Canvas canvas, GlassMaterialDrawable.DrawHost frame) {
        if (!on() || !allowed("UNHOSTED:" + drawable.getClass().getName())) return;
        StringBuilder out = new StringBuilder("drawable=").append(drawable.getClass().getName())
                .append(" args=").append(factoryArgs(drawable))
                .append(" canvas=").append(Integer.toHexString(System.identityHashCode(canvas)))
                .append(" canvasSize=").append(canvas.getWidth()).append('x').append(canvas.getHeight())
                .append(" hw=").append(canvas.isHardwareAccelerated());
        if (frame == null) {
            out.append(" frame=none");
        } else {
            out.append(" frameView=").append(describe(frame.view))
                    .append(" frameCanvas=").append(Integer.toHexString(System.identityHashCode(frame.canvas)))
                    .append(" chain=").append(chain(frame.view))
                    // Descendants whose size equals the recording: the likely real host, as evidence.
                    .append(" sizeMatches=").append(sizeMatches(frame.view, canvas.getWidth(), canvas.getHeight()));
        }
        log("UNHOSTED", out.toString());
    }

    /** Point 1: a verified host, once per drawable class and host class. */
    static void hosted(Drawable drawable, Canvas canvas, View host) {
        if (!on() || !allowed("HOSTED:" + drawable.getClass().getName() + "@" + host.getClass().getName())) return;
        log("HOSTED", "drawable=" + drawable.getClass().getName() + " args=" + factoryArgs(drawable)
                + " host=" + describe(host)
                + " canvas=" + Integer.toHexString(System.identityHashCode(canvas))
                + " canvasSize=" + canvas.getWidth() + "x" + canvas.getHeight()
                + " chain=" + chain(host));
    }

    /** Point 2: inputs of the texture alignment, at first presentation and after movement. */
    static void alignment(String phase, View host, Matrix entry, Matrix local, Matrix hostGlobal,
                          float[] applied) {
        if (!on() || !allowed("ALIGN:" + phase)) return;
        log("ALIGNMENT", "phase=" + phase + " host=" + describe(host)
                + " entry=" + (entry == null ? "-" : entry.toShortString())
                + " local=" + local.toShortString()
                + " hostGlobal=" + hostGlobal.toShortString()
                + " scroll=" + host.getScrollX() + "," + host.getScrollY()
                + " translation=" + host.getTranslationX() + "," + host.getTranslationY()
                + " applied=" + applied[0] + "," + applied[1] + "," + applied[2] + "," + applied[3]);
    }

    /** Point 3: a candidate rejected by a geometric rule, with its relation to the previous one. */
    static void rejected(String rule, View host, Drawable current, RectF currentRect,
                         Drawable previous, RectF previousRect) {
        if (!on() || !allowed("REJECT:" + rule)) return;
        float ratio = previousRect.width() * previousRect.height() <= 0f ? 0f
                : currentRect.width() * currentRect.height() / (previousRect.width() * previousRect.height());
        log("REJECTED", "rule=" + rule + " host=" + describe(host)
                + " drawable=" + current.getClass().getName() + " args=" + factoryArgs(current)
                + " rect=" + currentRect.toShortString()
                + " previous=" + (previous == null ? "-" : previous.getClass().getName())
                + " previousArgs=" + factoryArgs(previous)
                + " previousRect=" + previousRect.toShortString()
                + " sameInstance=" + (current == previous) + " areaRatio=" + ratio);
    }

    /** Point 4: the native silhouette could not be measured; the 12dp fallback is in use. */
    static void silhouetteFallback(String reason, Drawable drawable, int width, int height) {
        if (!on() || !allowed("SILHOUETTE:" + reason)) return;
        log("SILHOUETTE_FALLBACK", "reason=" + reason + " drawable=" + drawable.getClass().getName()
                + " size=" + width + "x" + height);
    }

    static void silhouetteMeasured(Drawable drawable, int width, int height, BubbleSilhouette s, long nanos) {
        if (!on() || !allowed("SILHOUETTE:measured")) return;
        log("SILHOUETTE", "drawable=" + drawable.getClass().getName() + " size=" + width + "x" + height
                + " body=" + s.left + "," + s.top + "," + s.right + "," + s.bottom
                + " radius=" + s.radius + " colour=" + (s.color == 0 ? "none" : "native")
                + " costUs=" + nanos / 1000L);
    }

    static void event(String kind, String detail) {
        if (!on() || !allowed(kind + ":" + detail)) return;
        log(kind, detail);
    }

    private static boolean allowed(String key) {
        long now = SystemClock.uptimeMillis();
        if (window < 0 || now - window >= 60000L) { window = now; SENT.clear(); }
        Integer n = SENT.get(key);
        int next = n == null ? 1 : n + 1;
        if (next > PER_KIND_PER_MINUTE) return false;
        SENT.put(key, next);
        return true;
    }

    private static void log(String kind, String detail) {
        Log.i(GlassProbe.TAG, "event=BUBBLE_GLASS kind=" + kind + " t=" + SystemClock.uptimeMillis() + " " + detail);
    }

    static String describe(View view) {
        if (view == null) return "null";
        return view.getClass().getName() + "#" + entryName(view) + "@" + Integer.toHexString(System.identityHashCode(view))
                + "[" + view.getWidth() + "x" + view.getHeight() + "]";
    }

    private static String entryName(View view) {
        int id = view.getId();
        if (id == View.NO_ID || id == 0) return "-";
        try { return view.getResources().getResourceEntryName(id); }
        catch (RuntimeException unknown) { return Integer.toHexString(id); }
    }

    private static String chain(View view) {
        StringBuilder out = new StringBuilder();
        ViewParent parent = view == null ? null : view.getParent();
        for (int i = 0; i < CHAIN_DEPTH && parent instanceof View; i++, parent = parent.getParent()) {
            View p = (View) parent;
            if (i > 0) out.append('<');
            out.append(p.getClass().getSimpleName()).append('#').append(entryName(p));
        }
        return out.length() == 0 ? "-" : out.toString();
    }

    private static String sizeMatches(View root, int width, int height) {
        StringBuilder out = new StringBuilder();
        collect(root, width, height, 0, out);
        return out.length() == 0 ? "-" : out.toString();
    }

    private static void collect(View view, int width, int height, int depth, StringBuilder out) {
        if (!(view instanceof ViewGroup) || depth >= 4 || out.length() > 600) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getWidth() == width && child.getHeight() == height) {
                if (out.length() > 0) out.append(';');
                out.append(child.getClass().getSimpleName()).append('#').append(entryName(child)).append("@d").append(depth + 1);
            }
            collect(child, width, height, depth + 1, out);
        }
    }
}
