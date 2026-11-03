package com.waenhancer.theme;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import com.waenhancer.diagnostics.InspectorTrace;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Opt-in diagnostics for native-drawn bubble glass, enabled by the existing GlassProbe switch
 * ({@code adb shell setprop log.tag.WaEXGlassDiag DEBUG}) or the short-lived Element Inspector
 * session. The latter also saves bounded structural events in the companion's private report.
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
    /** Last wallpaper-source state and its reason: why bubbles have (no) texture. */
    private static String sourceState = "none", sourceReason = "-";

    private BubbleGlassTelemetry() { }

    /** Property read at most once per second: the draw hook runs for every hooked draw. */
    public static boolean on() {
        try {
            long now = SystemClock.uptimeMillis();
            if (enabledAt < 0 || now - enabledAt >= 1000L) { enabled = GlassProbe.enabled(); enabledAt = now; }
            return enabled;
        } catch (RuntimeException unavailable) {
            return false; // Host-JVM tests and any process without logging services.
        }
    }

    static String sourceState() { return sourceState + "(" + sourceReason + ")"; }

    /** Every entry into the bubble draw hook, before any filtering (framework-class hook cost). */
    public static void call() { calls++; }

    public static void glass() { glass++; }

    private static long revivedLens, revivedWallpaper;

    /** Glass nodes whose display list HWUI deleted (row left the frame) and that were rebuilt. */
    static void revived(boolean lens, boolean wallpaper) {
        if (lens) revivedLens++;
        if (wallpaper) revivedWallpaper++;
    }

    public static void stayedNative(Native reason) { NATIVE[reason.ordinal()]++; }

    private static long reassertedText, reassertedIcon, reassertedLayer;

    /**
     * WhatsApp reset the colour of a glass row's content (rebind, playback state) and the glass
     * colour was applied again inside that same call (UI thread). 0 text, 1 icon, 2 layer.
     */
    public static void reasserted(int kind) {
        if (kind == 0) reassertedText++; else if (kind == 1) reassertedIcon++; else reassertedLayer++;
    }

    private static long contrastHeld, contrastRestored, contrastFresh, contrastInDraw;

    /**
     * Glass-row contrast transitions (UI thread). 0 held: a row without glass in its latest
     * recording kept the glass colours inside the hold window; 1 restored: native colours put
     * back after the hold; 2 fresh: a view tinted for the first time in a row that already had
     * glass colours (WhatsApp replaced the view); 3 in-draw: tints applied inside the glass
     * recording itself.
     */
    public static void contrast(int kind) {
        if (kind == 0) contrastHeld++; else if (kind == 1) contrastRestored++;
        else if (kind == 2) contrastFresh++; else contrastInDraw++;
    }

    /** A colour filter set on a managed icon: it overrides the tint, so it is evidence. */
    public static void contrastFilter(View view) {
        if (!on() || !allowed("CONTRAST_FILTER")) return;
        log("CONTRAST_FILTER", "view=" + describe(view));
    }

    /**
     * State of a glass-row icon after its sync (posted pass only): which drawable it shows and
     * whether anything overrides the view tint. A colour filter or tint set on the drawable itself
     * (e.g. a play/pause morph drawable) wins over ImageView.setImageTintList and passes through
     * none of the hooked view setters; this line is the evidence for or against that.
     * Rate-limited per distinct state, so a steady icon logs once a minute at most.
     */
    public static void iconState(android.widget.ImageView icon, boolean viewTintIsOurs) {
        if (!on() || icon == null) return;
        Drawable drawable = icon.getDrawable();
        boolean drawableFilter = drawable != null && drawable.getColorFilter() != null;
        boolean viewFilter = icon.getColorFilter() != null;
        String signature = (drawable == null ? "none" : drawable.getClass().getName())
                + " tint_ours=" + viewTintIsOurs + " drawable_filter=" + drawableFilter + " view_filter=" + viewFilter;
        if (!allowed("ICON_STATE:" + entryName(icon) + ":" + signature)) return;
        log("ICON_STATE", "view=" + describe(icon) + " drawable=" + signature);
    }

    private static final Map<Integer, Long> SWITCH_OFF_DIRECTIONS = new java.util.TreeMap<>();

    /** Factory direction of a draw left native by the side switch (UI thread). */
    public static void switchOff(Integer direction) {
        SWITCH_OFF_DIRECTIONS.merge(direction == null ? -1 : direction, 1L, Long::sum);
    }

    /** Pre-draw: one frequency line every five seconds while something changed. */
    static void report() {
        if (!on()) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastReport < REPORT_MS || calls == reported) return;
        lastReport = now; reported = calls;
        StringBuilder out = new StringBuilder("calls=").append(calls).append(" glass=").append(glass)
                .append(" source=").append(sourceState).append('(').append(sourceReason).append(')');
        for (Native reason : Native.values()) {
            long n = NATIVE[reason.ordinal()];
            if (n != 0) out.append(' ').append(reason.name().toLowerCase(java.util.Locale.ROOT)).append('=').append(n);
        }
        if (!SWITCH_OFF_DIRECTIONS.isEmpty()) out.append(" switch_off_dirs=").append(SWITCH_OFF_DIRECTIONS);
        if (revivedLens + revivedWallpaper > 0)
            out.append(" revived_lens=").append(revivedLens).append(" revived_wallpaper=").append(revivedWallpaper);
        if (reassertedText + reassertedIcon + reassertedLayer > 0)
            out.append(" reasserted_text=").append(reassertedText).append(" reasserted_icon=").append(reassertedIcon)
                    .append(" reasserted_layer=").append(reassertedLayer);
        if (contrastHeld + contrastRestored + contrastFresh + contrastInDraw > 0)
            out.append(" contrast_held=").append(contrastHeld).append(" contrast_restored=").append(contrastRestored)
                    .append(" contrast_fresh=").append(contrastFresh).append(" contrast_in_draw=").append(contrastInDraw);
        log("FREQUENCY", out.toString());
    }

    /** Wallpaper source transitions (UI thread). Logged on change, also carried by FREQUENCY. */
    public static void source(String state, String reason) {
        String why = reason == null ? "-" : reason;
        boolean changed = !state.equals(sourceState) || !why.equals(sourceReason);
        sourceState = state; sourceReason = why;
        if (changed && on() && allowed("SOURCE:" + state)) log("SOURCE", "state=" + state + " reason=" + why);
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

    /** A hosted bubble left native for a non-transient reason, with its row (capped). */
    public static void nativeRow(String reason, View host, Integer direction, String detail) {
        if (!on() || !allowed("NATIVE_ROW:" + reason)) return;
        log("NATIVE_ROW", "reason=" + reason + " dir=" + direction + " host=" + describe(host) + " detail=" + detail);
    }

    /** A cached copy of a factory bubble (same ConstantState) adopted on a verified row. */
    public static void adoptedCopy(Drawable drawable, Integer direction) {
        if (!on() || !allowed("ADOPTED_COPY")) return;
        log("ADOPTED_COPY", "drawable=" + drawable.getClass().getName() + " dir=" + direction);
    }

    /** A drawable on a verified row canvas that is neither a factory result nor a copy of one. */
    public static void unregisteredOnRow(Drawable drawable, View host) {
        if (!on() || !allowed("UNREGISTERED_ON_ROW:" + drawable.getClass().getName())) return;
        Rect b = drawable.getBounds();
        log("UNREGISTERED_ON_ROW", "drawable=" + drawable.getClass().getName()
                + " state=" + (drawable.getConstantState() == null ? "none" : drawable.getConstantState().getClass().getName())
                + " bounds=" + b.width() + "x" + b.height() + " host=" + describe(host));
    }

    /**
     * A row whose previous recording had glass recorded its bubble natively: the visible "glass
     * destroyed" transition. Row state is included because taps and animations change it.
     */
    public static void lost(String reason, View host) {
        if (!on() || !allowed("LOST:" + reason)) return;
        android.view.ViewParent parent = host.getParent();
        log("LOST", "reason=" + reason + " host=" + describe(host)
                + " pressed=" + host.isPressed() + " selected=" + host.isSelected()
                + " activated=" + host.isActivated() + " alpha=" + host.getAlpha()
                + " layer=" + host.getLayerType() + " transient=" + host.hasTransientState()
                + " attached=" + host.isAttachedToWindow()
                + " parent=" + (parent == null ? "none" : parent.getClass().getName())
                + " source=" + sourceState + "(" + sourceReason + ")");
    }

    /** Reply frame inside a bubble: who could paint its corner wedges (class chain, drawables). */
    public static void quoteFrame(View frame) {
        if (!on() || !allowed("QUOTE_FRAME")) return;
        StringBuilder chain = new StringBuilder();
        for (Class<?> c = frame.getClass(); c != null && c != View.class; c = c.getSuperclass()) {
            chain.append(c.getName());
            for (String m : new String[]{"draw", "onDraw", "dispatchDraw", "onDrawForeground"}) {
                try { c.getDeclaredMethod(m, Canvas.class); chain.append('+').append(m); }
                catch (NoSuchMethodException absent) { /* inherited */ }
            }
            chain.append('<');
        }
        View parent = frame.getParent() instanceof View ? (View) frame.getParent() : null;
        View child = frame instanceof ViewGroup && ((ViewGroup) frame).getChildCount() > 0 ? ((ViewGroup) frame).getChildAt(0) : null;
        log("QUOTE_FRAME", "frame=" + describe(frame) + " chain=" + chain
                + " bg=" + name(frame.getBackground()) + " fg=" + name(frame.getForeground())
                + " parent=" + describe(parent) + " parentBg=" + (parent == null ? "-" : name(parent.getBackground()))
                + " parentFg=" + (parent == null ? "-" : name(parent.getForeground()))
                + " child=" + describe(child) + " childBg=" + (child == null ? "-" : name(child.getBackground())));
    }

    private static String name(Drawable d) { return d == null ? "none" : d.getClass().getName(); }

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
        // The companion can display/export the same bounded, content-free evidence. This is
        // only active while the user has explicitly armed Element Inspector.
        InspectorTrace.glassEvent("BUBBLE_GLASS_" + kind, detail);
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
