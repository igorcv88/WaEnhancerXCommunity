package com.waenhancer.xposed.features.customization;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;

import com.waenhancer.config.LiquidGlassSettings;
import com.waenhancer.theme.GlassBudget;
import com.waenhancer.theme.GlassRenderer;
import com.waenhancer.theme.GlassSpec;
import com.waenhancer.theme.GlassSurface;
import com.waenhancer.theme.GlassTargetProbe;
import com.waenhancer.theme.LiquidLens;
import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.WppCore;
import com.waenhancer.xposed.utils.DesignUtils;
import com.waenhancer.xposed.utils.Utils;

import java.util.WeakHashMap;

import de.robv.android.xposed.XposedBridge;

/**
 * Puts the conversation's scroll-to-bottom button behind glass.
 *
 * <p>Chosen over the message input row, which was tried first and dropped. The discriminator is not
 * which surface would look good: it is whether heterogeneous content <em>moves</em> behind it, which
 * is what the material is made of. Measured on one conversation, the backdrop behind the input row
 * varies by 49 luma and never changes — WhatsApp pads the list so bubbles stop above the capsule,
 * and the wallpaper is fixed to the window, so scrolling moves not one pixel behind it. Behind this
 * button the same measurement gives 203, and it changes with every frame of a fling.</p>
 *
 * <p>It is also the first round surface: the corner radius is half the height on both axes, so the
 * rim curves through the whole outline rather than only at the ends. That is a new optical regime
 * for this material and the reason to measure it rather than assume the bar's numbers carry.</p>
 *
 * <h3>Why this one cannot be found by looking once</h3>
 *
 * <p>The button does not exist while the list is at the bottom, which is exactly how a conversation
 * opens. The first build polled for two seconds after the screen resumed and then gave up, so on a
 * chat opened normally it was looking for a view that had not been created yet and never would be
 * within the window it allowed itself. A transient surface has to be waited for, not polled for:
 * the tree itself says when it appears.</p>
 */
public class ConversationScrollButtonGlass extends Feature {

    /**
     * Known names of the button across builds. All are tried, none is required.
     *
     * <p>A name that resolves still has to pass the geometry and position tests, which is what
     * stops a build where the same name means something else. But a name is now the <em>only</em>
     * way in: there is no structural fallback, and the reason is measured. The fallback wrapped
     * {@code input_attach_button} — round, 144px, hard right, low on the screen, correct on every
     * test shape and position can express. No further test separates them, because there is no
     * geometric difference to find.</p>
     *
     * <p>The deeper fault was in when the fallback ran. It ran whenever the named view was absent,
     * and this button is absent most of the time — it exists only while the list is scrolled up. So
     * the fallback was invoked almost exclusively under the one condition guaranteeing the right
     * answer was not in the tree, and a search that must return something will.</p>
     */
    private static final String[] BUTTON_ID_CANDIDATES = {
            // Read off a device, not guessed: the diagnostic dump named it after five guesses had
            // all missed. The rest stay as fallbacks for builds that call it something else.
            "scroll_bottom",
            "scroll_to_bottom_btn",
            "scroll_to_bottom",
            "conversation_scroll_to_bottom",
            "scroll_down_btn",
            "unseen_messages_indicator",
    };

    /** The text field, used only to find where the footer starts. */
    private static final String ENTRY_ID = "entry";

    /**
     * How far in from the right edge the button may start, as a fraction of the screen.
     *
     * <p>Position is what geometry cannot supply: an emoji button and a reaction chip are square
     * and the right size too. This button floats against the right edge of the message area, and
     * nothing else that shape does.</p>
     */
    private static final float RIGHT_EDGE_FRACTION = 0.60f;

    /**
     * How far down the screen the button must start, as a fraction of the screen height.
     *
     * <p>The test that was missing, and its absence is what wrapped the video call button, the
     * voice call button and the overflow menu: all three are round, the right size and hard against
     * the right edge. They are also at the top of the screen, and this button floats at the bottom
     * of the message area. The footer test was supposed to catch them and could not - with the
     * keyboard closed it measured the footer at 2997 on a 2992-pixel screen, so it excluded
     * nothing.</p>
     */
    private static final float TOP_EDGE_FRACTION = 0.45f;

    /** How deep into the tree to look before concluding the button is not there. */
    private static final int MAX_SEARCH_DEPTH = 12;

    /**
     * How deep inside the named view to look for the view that draws it.
     *
     * <p>Shallow on purpose. This is not a search for the button — the name already found it — but
     * the last step from a touch target to the disc inside it, and a disc is not buried.</p>
     */
    private static final int INNER_SEARCH_DEPTH = 3;

    /**
     * Shortest gap between two scans of the tree, in ms.
     *
     * <p>Global layout fires continuously through a fling, and the scan walks the tree. This is
     * what keeps a watcher that exists to catch one view appearing from becoming a cost paid on
     * every frame of every scroll.</p>
     */
    private static final long SCAN_THROTTLE_MS = 300L;

    /** One surface per button, so a screen that re-lays-out does not stack wrappers. */
    private static final WeakHashMap<View, GlassSurface> surfaces = new WeakHashMap<>();

    /** Screens already being watched, so resuming a chat twice does not stack listeners. */
    private static final WeakHashMap<View, Boolean> watched = new WeakHashMap<>();

    /**
     * The surface currently installed, if any.
     *
     * <p>This feature owns exactly one surface. Without that rule a scan that finds a second
     * plausible view installs a second surface, and since a host is the same shape and in the same
     * place as what it wraps, the next scan finds the host and wraps that. Twenty wrappers in six
     * seconds and the whole lens budget spent, measured.</p>
     */
    private static java.lang.ref.WeakReference<View> live;

    private static SharedPreferences activePrefs;
    private static long lastScanAt;
    /** Whether the tree has been dumped for diagnosis. Once per process is enough. */
    private static boolean dumped;
    /** Same, for the inside of the named view. */
    private static boolean subtreeDumped;

    public ConversationScrollButtonGlass(@NonNull ClassLoader loader,
                                         @NonNull SharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        activePrefs = prefs;
        boolean enabled = LiquidGlassSettings.isScrollButtonEnabled(prefs);
        // Emitted before the preference is allowed to stop anything, so its absence means this
        // feature never ran in this process - which is a different problem from every other one,
        // and the only one that cannot be told apart from a silent failure without the line.
        XposedBridge.log("liquid glass scroll button: boot enabled=" + enabled
                + " lensSupported=" + LiquidLens.isSupported());
        if (!enabled) return;

        WppCore.addListenerActivity((activity, type) -> {
            if (type != WppCore.ActivityChangeState.ChangeType.RESUMED) return;
            if (activity == null || !isConversation(activity)) return;
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (decor != null) decor.post(() -> watch(decor));
        });
    }

    private static boolean isConversation(Activity activity) {
        return activity.getClass().getSimpleName().contains("Conversation");
    }

    /**
     * Waits for the button to appear, rather than polling for it.
     *
     * <p>The listener stays for the life of the screen and does almost nothing: once a surface is
     * live the scan is skipped outright, and until then it is throttled. It has to stay rather than
     * unregister on the first success, because the button is destroyed and recreated as the list
     * reaches the bottom and leaves it again.</p>
     */
    private static void watch(View decorView) {
        try {
            if (Boolean.TRUE.equals(watched.get(decorView))) {
                scan(decorView);
                return;
            }
            watched.put(decorView, true);
            ViewTreeObserver observer = decorView.getViewTreeObserver();
            if (observer == null || !observer.isAlive()) return;
            observer.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    scan(decorView);
                }
            });
            scan(decorView);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static void scan(View decorView) {
        try {
            if (hasLiveSurface()) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastScanAt < SCAN_THROTTLE_MS) return;
            lastScanAt = now;

            View target = findButton(decorView);
            if (target == null) {
                dumpCandidatesOnce(decorView);
                return;
            }
            if (surfaces.containsKey(target)) return;
            install(decorView, target);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static boolean hasLiveSurface() {
        View current = live == null ? null : live.get();
        if (current == null) return false;
        if (surfaces.containsKey(current) && current.isAttachedToWindow()) return true;
        live = null;
        return false;
    }

    private static void install(View decorView, View target) {
        ViewGroup blurRoot = blurRoot(decorView, target);
        if (blurRoot == null) return;

        GlassSurface.Config config = GlassSurface.Config.of(ConversationScrollButtonGlass::spec)
                // Laid out once, with the message list moving behind it. That the backdrop changes
                // every frame is the point of the surface, not a reason to refuse it: what LAYERED
                // refuses is a surface that moves with the content.
                .kind(GlassBudget.Kind.STATIC_CHROME)
                // Round. GlassSurface clamps to half the height, which on a square is a circle, so
                // no arithmetic about this shape is written here.
                .cornerRadiusDp(1000f)
                .elevated(true)
                .takeOverTargetBackground(true)
                .build();

        GlassSurface surface = GlassSurface.wrap(target, blurRoot, config);
        if (surface == null) {
            XposedBridge.log("liquid glass scroll button: wrap refused for "
                    + target.getClass().getName());
            return;
        }
        surfaces.put(target, surface);
        live = new java.lang.ref.WeakReference<>(target);

        target.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                GlassSurface attached = surfaces.remove(v);
                if (attached != null) attached.detach();
                live = null;
            }
        });

        // The install line can only ever report "not measured yet", because nothing has been laid
        // out at the moment of wrapping. This is the line that says what the surface became.
        final boolean[] reported = {false};
        surface.host().addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            surface.refresh();
            if (!reported[0] && surface.host().getWidth() > 0) {
                reported[0] = true;
                XposedBridge.log("liquid glass scroll button: settled lensed=" + surface.isLensed()
                        + " host=" + surface.host().getWidth() + "x" + surface.host().getHeight()
                        + " status=" + LiquidLens.status());
            }
        });

        GlassSpec resolved = surface.currentSpec();
        XposedBridge.log("liquid glass scroll button: installed lensed=" + surface.isLensed()
                + " size=" + target.getWidth() + "x" + target.getHeight()
                + " target=" + target.getClass().getName()
                + " id=" + idName(target)
                + " blurRoot=" + blurRoot.getClass().getName()
                + " fill=#" + (resolved == null ? "?" : Integer.toHexString(resolved.fillColor))
                + " blur=" + (resolved == null ? "?" : String.valueOf(resolved.blurRadius))
                + " fallback=" + (resolved != null && resolved.usingFallback)
                + " lensedSurfaces=" + GlassBudget.shared().lensedCount()
                + "/" + GlassBudget.MAX_LENSED_SURFACES
                + " status=" + LiquidLens.status());
    }

    /**
     * The button, by name, and by nothing else.
     *
     * <p>Finding nothing is the ordinary answer here, not a failure to work around: for most of a
     * conversation's life this button does not exist. Answering "not here" is the correct answer
     * and the caller simply waits for the next layout.</p>
     */
    private static View findButton(View decorView) {
        int footerTop = footerTop(decorView);
        for (String name : BUTTON_ID_CANDIDATES) {
            View candidate = findByName(decorView, name);
            if (isTheButton(candidate, footerTop)) return visualButton(candidate);
        }
        return null;
    }

    /**
     * Descends from the view the name points at to the view that actually draws the button.
     *
     * <p>Measured, because assuming otherwise produced a visibly wrong surface: {@code scroll_bottom}
     * is a 168x138 FrameLayout — a touch target with slack for the shadow — and the button inside it
     * is a 98x98 disc pushed into its bottom-right corner. Wrapping the frame gave a 162x137 oblong
     * of glass with the original grey disc still opaque on top of it, and a chromatic fringe computed
     * from 138px of height smeared across a 98px circle.</p>
     *
     * <p>Three defects, one cause, and none of them in the material. The rim was not circular because
     * the radius clamps to half of 138 against a width of 168. The grey survived because the frame's
     * own background is null and the disc's covers 41% of the frame — below the 85% that lets
     * {@code GlassSurface} take a descendant's background over, which is the right threshold applied
     * to the wrong view.</p>
     *
     * <p>What is looked for is a descendant that is round-button shaped <em>and</em> paints something,
     * preferring the largest. A view with no background and no image draws nothing, so wrapping it
     * would leave the paint on whatever does. Finding none, the named view is used as it stands:
     * being wrong about which view draws is recoverable, refusing to install is not.</p>
     */
    private static View visualButton(View named) {
        if (!(named instanceof ViewGroup)) return named;
        dumpSubtreeOnce(named);
        View best = null;
        long bestArea = 0;
        for (View child : descendants((ViewGroup) named, INNER_SEARCH_DEPTH)) {
            if (child.getVisibility() != View.VISIBLE) continue;
            if (child.getBackground() == null && !(child instanceof android.widget.ImageView)) {
                continue;
            }
            float density = child.getResources().getDisplayMetrics().density;
            if (!GlassTargetProbe.isRoundButton(child.getWidth(), child.getHeight(), density)) {
                continue;
            }
            long area = (long) child.getWidth() * child.getHeight();
            if (area > bestArea) {
                bestArea = area;
                best = child;
            }
        }
        return best == null ? named : best;
    }

    private static java.util.List<View> descendants(ViewGroup group, int depth) {
        java.util.List<View> out = new java.util.ArrayList<>();
        if (depth <= 0) return out;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == null) continue;
            out.add(child);
            if (child instanceof ViewGroup) {
                out.addAll(descendants((ViewGroup) child, depth - 1));
            }
        }
        return out;
    }

    /** What is inside the named view, once, so the choice above is checkable against the device. */
    private static void dumpSubtreeOnce(View named) {
        if (subtreeDumped) return;
        subtreeDumped = true;
        XposedBridge.log("liquid glass scroll button: inside " + idName(named)
                + " " + named.getWidth() + "x" + named.getHeight());
        for (View child : descendants((ViewGroup) named, INNER_SEARCH_DEPTH)) {
            int[] location = new int[2];
            child.getLocationInWindow(location);
            XposedBridge.log("    child id=" + idName(child)
                    + " cls=" + child.getClass().getName()
                    + " " + child.getWidth() + "x" + child.getHeight()
                    + " at " + location[0] + "," + location[1]
                    + " vis=" + (child.getVisibility() == View.VISIBLE)
                    + " bg=" + (child.getBackground() == null
                            ? "-" : child.getBackground().getClass().getSimpleName()));
        }
    }

    /**
     * Whether a view that carries one of the known names really is the button.
     *
     * <p>Not identity — the name supplies that now. This is the second half: whether the view is
     * laid out yet, whether it is where this button goes, and whether it is a list row. The last of
     * those is about cost rather than identity. A view that scrolls with the content is
     * {@code Kind.LAYERED} by definition, and putting a lens on one is the thing {@link GlassBudget}
     * exists to refuse; a candidate that turned out to be a list row would slip past that refusal,
     * because the budget is told the kind rather than asked.</p>
     */
    private static boolean isTheButton(View candidate, int footerTop) {
        if (candidate == null || candidate.getVisibility() != View.VISIBLE) return false;
        if (GlassSurface.isGlassHost(candidate) || isInsideGlassHost(candidate)) return false;
        android.util.DisplayMetrics metrics = candidate.getResources().getDisplayMetrics();
        if (!GlassTargetProbe.isRoundButton(
                candidate.getWidth(), candidate.getHeight(), metrics.density)) {
            return false;
        }
        int[] location = new int[2];
        candidate.getLocationInWindow(location);
        if (location[0] < metrics.widthPixels * RIGHT_EDGE_FRACTION) return false;
        if (location[1] < metrics.heightPixels * TOP_EDGE_FRACTION) return false;
        if (footerTop > 0 && location[1] + candidate.getHeight() > footerTop) return false;
        return !isInsideScrollingContainer(candidate);
    }

    private static boolean isInsideGlassHost(View view) {
        for (ViewParent p = view.getParent(); p instanceof View; p = p.getParent()) {
            if (GlassSurface.isGlassHost((View) p)) return true;
        }
        return false;
    }

    /**
     * Where the footer begins, so nothing below it is considered.
     *
     * <p>The mic button is round, the right size and hard against the right edge — every test above
     * except this one. It is also the wrong answer twice over: it belongs to the input row, and
     * there is nothing behind it but wallpaper.</p>
     */
    private static int footerTop(View decorView) {
        View entry = findByName(decorView, ENTRY_ID);
        if (entry == null) return -1;
        int[] location = new int[2];
        entry.getLocationInWindow(location);
        int screenHeight = decorView.getResources().getDisplayMetrics().heightPixels;
        // With the keyboard closed this measured 2997 on a 2992-pixel screen: a figure at or past
        // the bottom of the display excludes nothing, and a test that excludes nothing is worse
        // than no test, because it reads as one.
        if (location[1] <= 0 || location[1] >= screenHeight) return -1;
        return location[1];
    }

    private static boolean isInsideScrollingContainer(View view) {
        for (ViewParent p = view.getParent(); p instanceof View; p = p.getParent()) {
            String name = p.getClass().getName();
            if (name.contains("RecyclerView") || name.contains("ListView")
                    || name.contains("ScrollView") || name.contains("ViewPager")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Writes out everything the search rejected, once, so the next round is fact rather than
     * another guess at a resource name.
     *
     * <p>Discovery that fails silently is the one failure mode that looks exactly like a device
     * refusing the shader, a preference that did not stick, and a module that never loaded. The
     * dump costs one tree walk in the life of the process and it tells all four apart.</p>
     */
    private static void dumpCandidatesOnce(View decorView) {
        if (dumped) return;
        ViewGroup root = contentRoot(decorView);
        if (root == null) return;
        dumped = true;
        int footerTop = footerTop(decorView);
        XposedBridge.log("liquid glass scroll button: not found; footerTop=" + footerTop
                + " screen=" + decorView.getResources().getDisplayMetrics().widthPixels
                + "x" + decorView.getResources().getDisplayMetrics().heightPixels
                + " - candidates follow");
        dumpTree(root, footerTop, 0, new int[]{0});
    }

    private static void dumpTree(ViewGroup group, int footerTop, int depth, int[] budget) {
        if (depth > MAX_SEARCH_DEPTH || budget[0] > DUMP_MAX_LINES) return;
        float density = group.getResources().getDisplayMetrics().density;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == null) continue;
            int w = child.getWidth();
            int h = child.getHeight();
            float largerDp = Math.max(w, h) / Math.max(density, 0.1f);
            String name = idName(child);
            boolean interesting = (largerDp >= DUMP_MIN_DP && largerDp <= DUMP_MAX_DP)
                    || name.contains("scroll") || name.contains("unseen") || name.contains("jump");
            if (interesting && budget[0]++ <= DUMP_MAX_LINES) {
                int[] location = new int[2];
                child.getLocationInWindow(location);
                XposedBridge.log("  cand id=" + name
                        + " cls=" + child.getClass().getName()
                        + " " + w + "x" + h + " at " + location[0] + "," + location[1]
                        + " vis=" + (child.getVisibility() == View.VISIBLE)
                        + " scrolling=" + isInsideScrollingContainer(child)
                        + " bg=" + (child.getBackground() != null));
            }
            if (child instanceof ViewGroup) {
                dumpTree((ViewGroup) child, footerTop, depth + 1, budget);
            }
        }
    }

    /** Bounds of what the dump considers worth a line, and how many lines it may spend. */
    private static final float DUMP_MIN_DP = 20f;
    private static final float DUMP_MAX_DP = 96f;
    private static final int DUMP_MAX_LINES = 60;

    private static String idName(View view) {
        try {
            if (view.getId() == View.NO_ID) return "-";
            return view.getResources().getResourceEntryName(view.getId());
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private static ViewGroup contentRoot(View decorView) {
        View content = decorView.findViewById(android.R.id.content);
        if (content instanceof ViewGroup) return (ViewGroup) content;
        return decorView instanceof ViewGroup ? (ViewGroup) decorView : null;
    }

    /**
     * The tree the capture reads from: the activity's whole content view.
     *
     * <p>The widest root deliberately. A narrower one chosen by guesswork is one that might not
     * draw the wallpaper, and then the capture holds nothing but the window background — refracting
     * a flat colour produces a flat colour. Containing this surface's own host is not a problem;
     * the host declines to draw into the capture pass.</p>
     */
    private static ViewGroup blurRoot(View decorView, View target) {
        ViewGroup content = contentRoot(decorView);
        if (content != null) return content;
        View root = target.getRootView();
        return root instanceof ViewGroup ? (ViewGroup) root : null;
    }

    private static View findByName(View root, String name) {
        try {
            int id = Utils.getID(name, "id");
            return id > 0 ? root.findViewById(id) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * What this surface looks like.
     *
     * <p>The theme's material, resolved through the same call the bar makes. The one figure that
     * has to differ for a circle — the rim being bounded by the surface's own height rather than by
     * the variant's request in dp — is already inside {@link LiquidLens}. A parameter that diverges
     * here has to be earned by a measurement first.</p>
     */
    private static GlassSpec spec() {
        android.content.Context ctx = Utils.getApplication();
        if (ctx == null) return null;
        return GlassRenderer.resolveFor(ctx,
                LiquidGlassSettings.MATERIAL.key(),
                DesignUtils.isNightMode(ctx),
                0,
                DesignUtils.getPrimaryColor(),
                LiquidGlassSettings.opacityPercent(activePrefs));
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Liquid Glass: scroll-to-bottom button";
    }
}
