package com.waenhancer.theme;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import java.util.ArrayList;

/**
 * What WhatsApp actually paints as the conversation wallpaper, as drawables in window space.
 *
 * <p>The previous source accepted only an immutable, unfiltered {@link BitmapDrawable} whose
 * projection covered the whole window. Dark-mode dimming (alpha, colour filter, foreground or an
 * overlay view), a mutable bitmap, a colour/gradient wallpaper or a view that stops below the
 * status bar each made it return nothing, so no bubble ever had a texture. This plan replays the
 * wallpaper subtree's own drawables — background, image (with its image matrix), foreground and
 * view alpha — exactly as the views would, and nothing else.</p>
 *
 * <p>Isolation: only drawables are recorded; no {@link View#draw} call, no child RenderNode and no
 * conversation row can enter the source, so it cannot contain a bubble that samples it. Animated
 * drawables and video surfaces are unsupported and keep bubbles native.</p>
 */
public final class WallpaperCapture {
    static final int MAX_DEPTH = 6;
    static final int MAX_VIEWS = 32;

    /** Window-sized output; {@link #coverage} is the part the wallpaper views paint. */
    public final int width, height;
    final Rect coverage;
    /** Identity of everything recorded; equal keys mean an identical texture. */
    final String key;
    /** Non-null when the wallpaper cannot be reproduced; bubbles then stay native. */
    final String unsupported;
    private final ArrayList<Item> items;

    private WallpaperCapture(int width, int height, Rect coverage, String key, String unsupported,
                             ArrayList<Item> items) {
        this.width = width; this.height = height; this.coverage = coverage;
        this.key = key; this.unsupported = unsupported; this.items = items;
    }

    /** One drawable at its window transform, in the order the views would draw it. */
    private static final class Item {
        final Drawable drawable;
        final Matrix matrix;
        final int alpha;
        final Rect clip;
        Item(Drawable drawable, Matrix matrix, int alpha, Rect clip) {
            this.drawable = drawable; this.matrix = matrix; this.alpha = alpha; this.clip = clip;
        }
    }

    /** Cheap plan on the UI thread (no recording); called on layout/scan, never while drawing. */
    public static WallpaperCapture plan(View wallpaper, View window) {
        if (wallpaper == null || window == null || window.getWidth() <= 0 || window.getHeight() <= 0)
            return unsupported(window, "no-window");
        if (!wallpaper.isShown() || wallpaper.getWidth() <= 0 || wallpaper.getHeight() <= 0)
            return unsupported(window, "wallpaper-hidden");
        Matrix rootGlobal = new Matrix(), rootInverse = new Matrix();
        window.transformMatrixToGlobal(rootGlobal);
        if (!rootGlobal.invert(rootInverse)) return unsupported(window, "root-matrix");
        Walk walk = new Walk(rootInverse);
        // If the wallpaper view also hosts the message list or a glass pane, it is a container:
        // record only its own background/foreground so no consumer can enter its own source.
        walk.leafOnly = hostsConsumers(wallpaper, 0);
        try {
            walk.view(wallpaper, 255, 0);
        } catch (RuntimeException | LinkageError failure) {
            return unsupported(window, "walk-" + failure.getClass().getSimpleName());
        }
        if (walk.reason != null) return unsupported(window, walk.reason);
        if (walk.items.isEmpty()) return unsupported(window, "nothing-drawn");
        Rect coverage = new Rect();
        walk.bounds.roundOut(coverage);
        if (!coverage.intersect(0, 0, window.getWidth(), window.getHeight()) || coverage.width() < 2
                || coverage.height() < 2) return unsupported(window, "off-window");
        String identity = window.getWidth() + "x" + window.getHeight() + "|" + coverage.toShortString() + "|" + walk.key;
        // Identity alone misses in-place changes (ColorDrawable.setColor, tint, wrapper state):
        // the key also carries a tiny rendering of exactly what would be recorded.
        String key = withContent(identity, fingerprint(walk.items, coverage));
        return new WallpaperCapture(window.getWidth(), window.getHeight(), coverage, key, null, walk.items);
    }

    static final int PROBE_W = 12, PROBE_H = 24;
    private static Bitmap probe;
    private static int[] probePixels;

    /** Pure: the texture key changes whenever the drawn content changes. */
    static String withContent(String identity, int[] pixels) {
        return identity + "|px" + (pixels == null ? "none" : Integer.toHexString(java.util.Arrays.hashCode(pixels)));
    }

    /**
     * Renders the plan at {@link #PROBE_W}×{@link #PROBE_H} on the CPU (UI thread, reused bitmap).
     * Items that cannot be drawn in software (hardware bitmaps) are skipped; their bitmap identity
     * and generation remain in the identity part of the key.
     */
    private static int[] fingerprint(ArrayList<Item> items, Rect coverage) {
        try {
            if (probe == null) { probe = Bitmap.createBitmap(PROBE_W, PROBE_H, Bitmap.Config.ARGB_8888); probePixels = new int[PROBE_W * PROBE_H]; }
            probe.eraseColor(0);
            android.graphics.Canvas canvas = new android.graphics.Canvas(probe);
            canvas.scale((float) PROBE_W / coverage.width(), (float) PROBE_H / coverage.height());
            canvas.translate(-coverage.left, -coverage.top);
            for (Item item : items) {
                int save = canvas.save();
                try {
                    canvas.concat(item.matrix);
                    if (item.clip != null) canvas.clipRect(item.clip);
                    if (item.alpha < 255) canvas.saveLayerAlpha(null, item.alpha);
                    item.drawable.draw(canvas);
                } catch (RuntimeException softwareUnsupported) {
                    // e.g. a hardware bitmap: identity/generation still describe it.
                } finally {
                    canvas.restoreToCount(save);
                }
            }
            probe.getPixels(probePixels, 0, PROBE_W, 0, 0, PROBE_W, PROBE_H);
            return probePixels;
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            return null;
        }
    }

    static final int CONSUMER_SEARCH_DEPTH = 8, CONSUMER_SEARCH_NODES = 96;

    /**
     * Message list, recycler, scroller, message row or glass pane anywhere below {@code view}.
     * Bounded, and fails closed: a subtree too deep or too large to inspect counts as a consumer.
     */
    static boolean hostsConsumers(View view, int depth) {
        return hostsConsumers(view, depth, new int[1]);
    }

    private static boolean hostsConsumers(View view, int depth, int[] visited) {
        if (++visited[0] > CONSUMER_SEARCH_NODES) return true;
        if (consumer(view)) return true;
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        if (group.getChildCount() == 0) return false;
        if (depth >= CONSUMER_SEARCH_DEPTH) return true;
        for (int i = 0; i < group.getChildCount(); i++)
            if (hostsConsumers(group.getChildAt(i), depth + 1, visited)) return true;
        return false;
    }

    private static boolean consumer(View view) {
        java.util.ArrayList<String> chain = new java.util.ArrayList<>();
        for (Class<?> c = view.getClass(); c != null && c != View.class && c != Object.class; c = c.getSuperclass())
            chain.add(c.getName());
        // R8 renames androidx RecyclerView in WhatsApp, so behaviour matters as much as names.
        return consumerSignals(chain, view.isScrollContainer(),
                view.canScrollVertically(1) || view.canScrollVertically(-1)
                        || view.canScrollHorizontally(1) || view.canScrollHorizontally(-1),
                entryName(view));
    }

    /**
     * Pure decision (host-JVM tested): a list/scroller by any superclass name, any scrolling
     * behaviour, our own glass panes, or WhatsApp message rows and composer containers.
     */
    static boolean consumerSignals(java.util.List<String> classChain, boolean scrollContainer,
                                   boolean scrollable, String entryName) {
        for (String name : classChain) {
            if (name.equals("android.widget.AbsListView") || name.equals("android.widget.AdapterView")
                    || name.equals("android.widget.ScrollView") || name.equals("android.widget.HorizontalScrollView")
                    || name.endsWith(".RecyclerView") || name.endsWith(".NestedScrollView")
                    || name.equals("com.waenhancer.theme.GlassPane")) return true;
        }
        if (scrollContainer || scrollable) return true;
        return entryName != null && (entryName.startsWith("conversation_row") || entryName.equals("footer")
                || entryName.equals("input_layout") || entryName.equals("conversation_list_view_host"));
    }

    private static String entryName(View view) {
        int id = view.getId();
        if (id == View.NO_ID || id == 0) return null;
        try { return view.getResources().getResourceEntryName(id); }
        catch (RuntimeException unknown) { return null; }
    }

    static boolean consumerView(View view) { return consumer(view); }

    /** Null when the plan is usable; otherwise why the wallpaper cannot be replayed. */
    public String unsupportedReason() { return unsupported; }

    /** The wallpaper view's rectangle in window coordinates, clipped to the window; null if empty. */
    static Rect rectInWindow(View wallpaper, View window) {
        if (wallpaper == null || window == null || wallpaper.getWidth() <= 0 || wallpaper.getHeight() <= 0) return null;
        Matrix toWindow = new Matrix(), rootGlobal = new Matrix(), rootInverse = new Matrix();
        wallpaper.transformMatrixToGlobal(toWindow);
        window.transformMatrixToGlobal(rootGlobal);
        if (!rootGlobal.invert(rootInverse)) return null;
        toWindow.postConcat(rootInverse);
        RectF r = new RectF(0, 0, wallpaper.getWidth(), wallpaper.getHeight());
        toWindow.mapRect(r);
        Rect out = new Rect();
        r.roundOut(out);
        return out.intersect(0, 0, window.getWidth(), window.getHeight()) && out.width() > 1 && out.height() > 1 ? out : null;
    }

    /**
     * Identity of the wallpaper the chat shows (drawable/bitmap identities and generations of the
     * non-consumer wallpaper subtree), even when its content cannot be replayed. Chats with their
     * own wallpaper differ; keyboard/geometry changes do not.
     */
    public static String identity(View wallpaper) {
        StringBuilder out = new StringBuilder();
        identity(wallpaper, 0, out, new int[1]);
        return out.length() == 0 ? "none" : out.toString();
    }

    private static void identity(View view, int depth, StringBuilder out, int[] count) {
        if (view == null || depth > MAX_DEPTH || ++count[0] > MAX_VIEWS || out.length() > 2000
                || view.getVisibility() != View.VISIBLE || consumer(view)) return;
        append(out, view.getBackground());
        if (view instanceof ImageView) append(out, ((ImageView) view).getDrawable());
        append(out, view.getForeground());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (!hostsConsumers(child, 0)) identity(child, depth + 1, out, count);
            }
        }
    }

    private static void append(StringBuilder out, Drawable drawable) {
        if (drawable == null) return;
        out.append(drawable.getClass().getSimpleName()).append('@').append(System.identityHashCode(drawable));
        if (drawable instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            if (bitmap != null) out.append(":bmp").append(System.identityHashCode(bitmap)).append('/').append(bitmap.getGenerationId());
        }
        out.append(';');
    }

    /**
     * A copied wallpaper image as a plan: cover-mapped from the rectangle it was copied from into
     * the wallpaper's current rectangle (keyboard/geometry), clipped there. Pure geometry is in
     * {@link #coverRect}.
     */
    static WallpaperCapture fromImage(Bitmap image, Rect copiedFrom, Rect now, int width, int height, String imageKey) {
        BitmapDrawable drawable = new BitmapDrawable(null, image);
        drawable.setFilterBitmap(true);
        Rect target = coverRect(copiedFrom.width(), copiedFrom.height(), now);
        drawable.setBounds(target);
        ArrayList<Item> items = new ArrayList<>();
        items.add(new Item(drawable, new Matrix(), 255, new Rect(now)));
        String key = width + "x" + height + "|" + now.toShortString() + "|" + imageKey;
        return new WallpaperCapture(width, height, new Rect(now), key, null, items);
    }

    /** Pure: centre a source-aspect rectangle that covers {@code target} (centre-crop scaling). */
    static Rect coverRect(int sourceWidth, int sourceHeight, Rect target) {
        float scale = Math.max((float) target.width() / Math.max(1, sourceWidth),
                (float) target.height() / Math.max(1, sourceHeight));
        int w = Math.round(sourceWidth * scale), h = Math.round(sourceHeight * scale);
        int left = target.left + (target.width() - w) / 2, top = target.top + (target.height() - h) / 2;
        return new Rect(left, top, left + w, top + h);
    }

    private static WallpaperCapture unsupported(View window, String reason) {
        int w = window == null ? 0 : window.getWidth(), h = window == null ? 0 : window.getHeight();
        return new WallpaperCapture(w, h, new Rect(), "unsupported:" + reason, reason, new ArrayList<>());
    }

    /**
     * Records the plan into an isolated node sized to {@link #coverage} (UI thread). The node only
     * holds drawable ops; the worker renders it and discards it.
     */
    RenderNode record() {
        RenderNode node = new RenderNode("WaEX-wallpaper-capture");
        node.setPosition(0, 0, coverage.width(), coverage.height());
        RecordingCanvas canvas = node.beginRecording(coverage.width(), coverage.height());
        try {
            canvas.translate(-coverage.left, -coverage.top);
            for (Item item : items) {
                int save = canvas.save();
                try {
                    canvas.concat(item.matrix);
                    if (item.clip != null) canvas.clipRect(item.clip);
                    if (item.alpha < 255) canvas.saveLayerAlpha(null, item.alpha);
                    item.drawable.draw(canvas);
                } finally {
                    canvas.restoreToCount(save);
                }
            }
        } finally {
            node.endRecording();
        }
        return node;
    }

    long bytes() {
        // Coverage pass + window-sized blurred output; generous scratch for the blur layer.
        return (long) coverage.width() * coverage.height() * 4L + (long) width * height * 4L * 3L;
    }

    private static final class Walk {
        final Matrix rootInverse;
        final ArrayList<Item> items = new ArrayList<>();
        final RectF bounds = new RectF();
        final StringBuilder key = new StringBuilder();
        String reason;
        int views;
        boolean leafOnly;
        private final RectF scratch = new RectF();

        Walk(Matrix rootInverse) { this.rootInverse = rootInverse; }

        void view(View view, int parentAlpha, int depth) {
            if (reason != null || depth > MAX_DEPTH || view.getVisibility() != View.VISIBLE) return;
            if (++views > MAX_VIEWS) { reason = "too-many-views"; return; }
            if (consumer(view)) return; // Never part of the wallpaper.
            if (view instanceof SurfaceView || view instanceof TextureView) { reason = "video-surface"; return; }
            int alpha = Math.round(parentAlpha * Math.max(0f, Math.min(1f, view.getAlpha())));
            if (alpha == 0 || view.getWidth() <= 0 || view.getHeight() <= 0) return;
            Matrix toWindow = new Matrix();
            view.transformMatrixToGlobal(toWindow);
            toWindow.postConcat(rootInverse);
            Rect viewBounds = new Rect(0, 0, view.getWidth(), view.getHeight());
            // View.drawBackground: the background spans the view, shifted by its scroll.
            Drawable background = view.getBackground();
            if (background != null) {
                if (background.getBounds().isEmpty()) background.setBounds(viewBounds);
                Matrix m = new Matrix(toWindow);
                m.preTranslate(view.getScrollX(), view.getScrollY());
                add(background, m, alpha, null, view);
            }
            if (view instanceof ImageView) {
                ImageView image = (ImageView) view;
                Drawable content = image.getDrawable();
                if (content != null) {
                    // ImageView.onDraw: padding, then the image matrix, clipped to padding if asked.
                    Matrix m = new Matrix(toWindow);
                    m.preTranslate(view.getScrollX(), view.getScrollY());
                    Rect clip = image.getCropToPadding()
                            ? new Rect(view.getPaddingLeft(), view.getPaddingTop(),
                                    view.getWidth() - view.getPaddingRight(), view.getHeight() - view.getPaddingBottom())
                            : null;
                    m.preTranslate(view.getPaddingLeft(), view.getPaddingTop());
                    if (clip != null) clip.offset(-view.getPaddingLeft(), -view.getPaddingTop());
                    Matrix imageMatrix = image.getImageMatrix();
                    if (imageMatrix != null && !imageMatrix.isIdentity()) {
                        if (clip != null) {
                            // Express the padding clip in drawable space through the inverse.
                            Matrix inverse = new Matrix();
                            if (imageMatrix.invert(inverse)) {
                                RectF c = new RectF(clip); inverse.mapRect(c); clip = new Rect(); c.roundOut(clip);
                            } else clip = null;
                        }
                        m.preConcat(imageMatrix);
                    }
                    add(content, m, alpha, clip, view);
                }
            }
            if (view instanceof ViewGroup && !(leafOnly && depth == 0)) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount() && reason == null; i++) {
                    View child = group.getChildAt(i);
                    // Any subtree that holds a consumer is left out entirely, at every depth.
                    if (hostsConsumers(child, 0)) continue;
                    view(child, alpha, depth + 1);
                }
            }
            Drawable foreground = view.getForeground();
            if (foreground != null && reason == null) {
                if (foreground.getBounds().isEmpty()) foreground.setBounds(viewBounds);
                add(foreground, toWindow, alpha, null, view);
            }
        }

        private void add(Drawable drawable, Matrix matrix, int alpha, Rect clip, View owner) {
            if (reason != null || !drawable.isVisible()) return;
            if (drawable instanceof RippleDrawable) return; // Touch feedback, not wallpaper.
            if (drawable instanceof Animatable) { reason = "animated-" + drawable.getClass().getSimpleName(); return; }
            Rect b = drawable.getBounds();
            if (b.isEmpty()) return;
            items.add(new Item(drawable, matrix, alpha, clip));
            scratch.set(b);
            if (clip != null && !scratch.intersect(clip.left, clip.top, clip.right, clip.bottom)) return;
            matrix.mapRect(scratch);
            bounds.union(scratch);
            float[] v = new float[9];
            matrix.getValues(v);
            key.append(drawable.getClass().getName()).append('@').append(System.identityHashCode(drawable));
            if (drawable instanceof BitmapDrawable) {
                Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
                key.append(":bmp").append(System.identityHashCode(bitmap))
                        .append('/').append(bitmap == null ? 0 : bitmap.getGenerationId());
            }
            key.append(":b").append(b.toShortString()).append(":a").append(alpha).append('/').append(drawable.getAlpha())
                    .append(":cf").append(System.identityHashCode(drawable.getColorFilter()))
                    .append(":s").append(java.util.Arrays.hashCode(drawable.getState()))
                    .append(":l").append(drawable.getLevel())
                    .append(":m");
            for (float f : v) key.append(Math.round(f * 100f)).append(',');
            if (clip != null) key.append(":c").append(clip.toShortString());
            key.append(':').append(owner.getId()).append(';');
        }
    }
}
