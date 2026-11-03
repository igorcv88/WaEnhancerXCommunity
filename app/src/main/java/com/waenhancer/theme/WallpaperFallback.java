package com.waenhancer.theme;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.widget.ImageView;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Automatic fallback when {@link WallpaperCapture} cannot reproduce a chat's wallpaper.
 *
 * <p>Like a launcher that asks for a screenshot of its bare wallpaper, but without asking: the
 * views drawn over {@code conversation_background} (message list, header, composer, panes) are
 * made transparent for one committed frame, the window is copied with {@link PixelCopy}, and the
 * views are restored. The image feeds the existing blur pipeline as a plain bitmap.</p>
 *
 * <p>Once per wallpaper: chats can have their own wallpaper, so the image is tied to the wallpaper
 * subtree's drawable identity and taken again only when that changes. Keyboard and geometry
 * changes reuse it (cover-mapped into the new wallpaper rectangle). The image lives only in this
 * process's memory, at half resolution, and is released with the chat. The copy is aborted unless
 * every message list/row and glass pane outside the wallpaper is verifiably transparent.</p>
 */
public final class WallpaperFallback {
    static final long SETTLE_MS = 350L, TIMEOUT_MS = 600L;
    static final int MAX_ATTEMPTS = 2;

    public interface Host { void captured(); }

    /** Created on the first copy request (main thread); nothing is posted before that. */
    private Handler main;
    private Bitmap image;
    private final Rect imageRect = new Rect();
    private String identity, failedIdentity;
    /** Wallpaper identities whose replay failed for good: keep using the copy for them. */
    private final java.util.Set<String> replayFailed = new java.util.HashSet<>();
    private int attempts;
    private boolean pending, released;
    private final ArrayList<View> hidden = new ArrayList<>();
    private final ArrayList<Float> hiddenAlpha = new ArrayList<>();
    private Runnable timeout;

    /** The replay of this wallpaper failed for good; the copy stays selected until it changes. */
    public void replayFailed(String wallpaperIdentity) { replayFailed.add(wallpaperIdentity); }

    /** Whether this wallpaper must use the copy even though its plan looks supported. */
    public boolean sticky(String wallpaperIdentity) { return replayFailed.contains(wallpaperIdentity); }

    /** Reasons for which a screen copy cannot help (no visible wallpaper to copy). */
    public static boolean eligible(String unsupportedReason) {
        return unsupportedReason != null && !unsupportedReason.equals("no-window")
                && !unsupportedReason.equals("wallpaper-hidden") && !unsupportedReason.equals("off-window");
    }

    /** The copied wallpaper as a capture plan, if one exists for this wallpaper identity. */
    public WallpaperCapture plan(View wallpaper, View window, String wallpaperIdentity) {
        if (released || image == null || !wallpaperIdentity.equals(identity)) return null;
        Rect now = WallpaperCapture.rectInWindow(wallpaper, window);
        if (now == null) return null;
        return WallpaperCapture.fromImage(image, imageRect, now, window.getWidth(), window.getHeight(),
                "pixelcopy:" + System.identityHashCode(image));
    }

    /** Schedules one copy for this wallpaper identity (UI thread); no-op while one is pending. */
    public void request(View wallpaper, View window, Window activityWindow, String wallpaperIdentity, Host host,
                        java.util.function.BooleanSupplier stillNeeded) {
        if (released || pending || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || activityWindow == null) return;
        if (wallpaperIdentity.equals(failedIdentity) && attempts >= MAX_ATTEMPTS) return;
        if (!wallpaperIdentity.equals(failedIdentity)) attempts = 0;
        pending = true;
        final WeakReference<View> wallpaperRef = new WeakReference<>(wallpaper), windowRef = new WeakReference<>(window);
        final WeakReference<Window> activityRef = new WeakReference<>(activityWindow);
        if (main == null) main = new Handler(Looper.getMainLooper());
        BubbleGlassTelemetry.source("fallback-scheduled", "attempt=" + (attempts + 1));
        // Let the chat finish laying out (and the keyboard settle) before the one-frame copy.
        main.postDelayed(() -> {
            // The replay often becomes usable within the settle delay (wallpaper drawn late):
            // then there is nothing to copy and no reason to hide the chat for a frame.
            if (!stillNeeded.getAsBoolean()) {
                pending = false;
                BubbleGlassTelemetry.source("fallback-skipped", "replay-ready");
                return;
            }
            copy(wallpaperRef.get(), windowRef.get(), activityRef.get(), wallpaperIdentity, host);
        }, SETTLE_MS);
    }

    private void copy(View wallpaper, View window, Window activityWindow, String wallpaperIdentity, Host host) {
        if (released || wallpaper == null || window == null || activityWindow == null || !window.isAttachedToWindow()
                || !window.hasWindowFocus() || !wallpaper.isShown()) {
            // Transition/dialog: try again on a later scan without spending an attempt.
            pending = false;
            BubbleGlassTelemetry.source("fallback-waiting", "not-ready");
            return;
        }
        Rect rect = WallpaperCapture.rectInWindow(wallpaper, window);
        if (rect == null) { fail(wallpaperIdentity, "no-rect"); return; }
        // Only views over the wallpaper are hidden; a wallpaper view that itself hosts messages
        // or panes would be copied with them, so it is never copied.
        if (WallpaperCapture.hostsConsumers(wallpaper, 0)) { fail(wallpaperIdentity, "wallpaper-hosts-consumers"); return; }
        hideAbove(wallpaper, window);
        if (!consumersHidden(window, wallpaper, 0, new int[1])) { restore(); fail(wallpaperIdentity, "consumer-visible"); return; }
        final Bitmap destination;
        try {
            destination = Bitmap.createBitmap(Math.max(1, rect.width() / 2), Math.max(1, rect.height() / 2), Bitmap.Config.ARGB_8888);
        } catch (RuntimeException | OutOfMemoryError noMemory) { restore(); fail(wallpaperIdentity, "no-memory"); return; }
        timeout = () -> { timeout = null; restore(); destination.recycle(); fail(wallpaperIdentity, "timeout"); };
        main.postDelayed(timeout, TIMEOUT_MS);
        BubbleGlassTelemetry.source("fallback-capturing", rect.toShortString());
        // The next committed frame is the one without the covering views; copy exactly that.
        window.getViewTreeObserver().registerFrameCommitCallback(() -> main.post(() -> {
            if (timeout == null) return; // Timed out already.
            try {
                PixelCopy.request(activityWindow, rect, destination, result -> {
                    if (timeout == null) return;
                    main.removeCallbacks(timeout); timeout = null;
                    restore();
                    if (released || result != PixelCopy.SUCCESS) {
                        destination.recycle();
                        fail(wallpaperIdentity, "pixelcopy-" + result);
                        return;
                    }
                    if (image != null && image != destination) image.recycle();
                    image = destination; imageRect.set(rect); identity = wallpaperIdentity;
                    failedIdentity = null; attempts = 0; pending = false;
                    BubbleGlassTelemetry.source("fallback-ready", destination.getWidth() + "x" + destination.getHeight());
                    host.captured();
                }, main);
            } catch (RuntimeException failure) {
                main.removeCallbacks(timeout); timeout = null;
                restore(); destination.recycle();
                fail(wallpaperIdentity, "pixelcopy-" + failure.getClass().getSimpleName());
            }
        }));
        window.invalidate();
    }

    /** Siblings drawn over the wallpaper path, at every level up to the window, become transparent. */
    private void hideAbove(View wallpaper, View window) {
        for (View node = wallpaper; node != window && node.getParent() instanceof ViewGroup; ) {
            ViewGroup parent = (ViewGroup) node.getParent();
            int index = parent.indexOfChild(node);
            float z = node.getZ();
            for (int i = 0; i < parent.getChildCount(); i++) {
                View sibling = parent.getChildAt(i);
                if (sibling == node || sibling.getVisibility() != View.VISIBLE || sibling.getAlpha() == 0f) continue;
                if (i > index || sibling.getZ() > z) { hidden.add(sibling); hiddenAlpha.add(sibling.getAlpha()); sibling.setAlpha(0f); }
            }
            node = parent;
        }
    }

    private void restore() {
        for (int i = 0; i < hidden.size(); i++) {
            View view = hidden.get(i);
            // Only undo our own change: WhatsApp may have animated the view meanwhile.
            if (view.getAlpha() == 0f) view.setAlpha(hiddenAlpha.get(i));
        }
        hidden.clear(); hiddenAlpha.clear();
    }

    /** Every consumer outside the wallpaper subtree must be under a view we made transparent. */
    private boolean consumersHidden(View view, View wallpaper, int depth, int[] visited) {
        if (view == wallpaper || ++visited[0] > 4000 || depth > 40) return view == wallpaper;
        if (view.getVisibility() != View.VISIBLE || view.getAlpha() == 0f) return true;
        if (WallpaperCapture.consumerView(view) && !isAncestor(view, wallpaper)) return false;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                if (!consumersHidden(group.getChildAt(i), wallpaper, depth + 1, visited)) return false;
        }
        return true;
    }

    private static boolean isAncestor(View ancestor, View child) {
        for (ViewParent p = child.getParent(); p instanceof View; p = p.getParent()) if (p == ancestor) return true;
        return false;
    }

    private void fail(String wallpaperIdentity, String reason) {
        pending = false;
        failedIdentity = wallpaperIdentity;
        attempts++;
        BubbleGlassTelemetry.source("fallback-failed", reason + " attempt=" + attempts);
    }

    public void release() {
        released = true;
        if (main != null) {
            if (timeout != null) main.removeCallbacks(timeout);
            main.removeCallbacksAndMessages(null);
        }
        timeout = null;
        restore();
        if (image != null) { image.recycle(); image = null; }
    }
}
