package com.waenhancer.theme;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import java.lang.ref.WeakReference;

/** One cover transform shared by the continuity underlay and the native bitmap slot. */
final class WallpaperProjection {
    private WeakReference<View> source = new WeakReference<>(null), target = new WeakReference<>(null);
    private BitmapDrawable original;
    private ProjectedDrawable installed;
    private boolean image;
    private final Matrix sourceToBand = new Matrix(), bandGlobal = new Matrix(), inverse = new Matrix();
    private final Matrix drawableToBand = new Matrix(), bitmapToBand = new Matrix(), nativeMatrix = new Matrix();
    private final Matrix projectedDrawableMatrix = new Matrix();
    private final Rect bounds = new Rect(), destination = new Rect();
    private final RectF effective = new RectF();
    private final float[] cover = new float[3], values = new float[9];

    /** Called on layout/rebind, never replaces a native slot from inside its draw callback. */
    void prepare(View wallpaper, View underlay) {
        try { prepareNativeSlot(wallpaper, underlay); }
        catch (RuntimeException | LinkageError unsupported) {
            release(); // Only this wallpaper adapter fails; native rendering remains available.
        }
    }
    private void prepareNativeSlot(View wallpaper, View underlay) {
        View old = source.get();
        if (old != null && slot(old) == installed && original != null && isDescendant(wallpaper, old)) {
            target = new WeakReference<>(underlay);
            return;
        }
        release();
        View found = bitmapSource(wallpaper, 0);
        if (found == null) return;
        image = found instanceof ImageView && ((ImageView) found).getDrawable() instanceof BitmapDrawable;
        BitmapDrawable bitmap = (BitmapDrawable) (image ? ((ImageView) found).getDrawable() : found.getBackground());
        if (bitmap.getTileModeX() != null || bitmap.getTileModeY() != null || bitmap.isAutoMirrored()
                || bitmap.getBitmap() == null || bitmap.getBitmap().isRecycled()) return;
        source = new WeakReference<>(found);
        target = new WeakReference<>(underlay);
        original = bitmap;
        installed = new ProjectedDrawable(bitmap);
        installed.setBounds(bitmap.getBounds());
        installed.setState(bitmap.getState());
        installed.setLevel(bitmap.getLevel());
        if (image) ((ImageView) found).setImageDrawable(installed);
        else setBackground(found, installed);
    }
    private Drawable slot(View view) { return image ? ((ImageView) view).getDrawable() : view.getBackground(); }
    void release() {
        View view = source.get();
        if (view != null && installed != null && slot(view) == installed) {
            if (image) ((ImageView) view).setImageDrawable(original); else setBackground(view, original);
        }
        source.clear(); target.clear(); original = null; installed = null;
    }
    boolean draw(Canvas canvas) {
        if (!update()) return false;
        int save = canvas.save();
        try { canvas.concat(projectedDrawableMatrix); original.draw(canvas); }
        finally { canvas.restoreToCount(save); }
        return true;
    }
    private boolean update() {
        View view = source.get(), band = target.get();
        if (view == null || band == null || original == null || slot(view) != installed
                || original.getBitmap().isRecycled() || band.getWidth() <= 0 || band.getHeight() <= 0) return false;
        bounds.set(installed.getBounds());
        if (bounds.isEmpty()) return false;
        sourceToBand.reset(); view.transformMatrixToGlobal(sourceToBand);
        bandGlobal.reset(); band.transformMatrixToGlobal(bandGlobal);
        if (!bandGlobal.invert(inverse)) return false;
        sourceToBand.postConcat(inverse);
        drawableToBand.reset();
        if (image) {
            drawableToBand.set(((ImageView) view).getImageMatrix());
            drawableToBand.postTranslate(view.getPaddingLeft(), view.getPaddingTop());
        }
        drawableToBand.postConcat(sourceToBand);
        if (image) destination.set(bounds);
        else Gravity.apply(original.getGravity(), original.getIntrinsicWidth(), original.getIntrinsicHeight(),
                bounds, destination, view.getLayoutDirection());
        bitmapToBand.setScale((float) destination.width() / original.getBitmap().getWidth(),
                (float) destination.height() / original.getBitmap().getHeight());
        bitmapToBand.postTranslate(destination.left, destination.top);
        bitmapToBand.postConcat(drawableToBand);
        // Unknown skew/perspective or pre-existing anisotropic distortion retains native rendering.
        bitmapToBand.getValues(values);
        float x = (float) Math.hypot(values[0], values[3]), y = (float) Math.hypot(values[1], values[4]);
        if (!bitmapToBand.rectStaysRect() || x <= 0 || Math.abs(x - y) > Math.max(x, y) * .001f) return false;
        effective.set(0, 0, original.getBitmap().getWidth(), original.getBitmap().getHeight());
        bitmapToBand.mapRect(effective);
        if (!WallpaperCover.calculate(effective.left, effective.top, effective.right, effective.bottom,
                band.getWidth(), band.getHeight(), cover)) return false;
        bitmapToBand.postScale(cover[0], cover[0]);
        bitmapToBand.postTranslate(cover[1], cover[2]);
        projectedDrawableMatrix.set(drawableToBand);
        projectedDrawableMatrix.postScale(cover[0], cover[0]);
        projectedDrawableMatrix.postTranslate(cover[1], cover[2]);
        original.setBounds(bounds);
        return true;
    }
    /** Read-only bitmap contract. Never records the native wallpaper View or its descendants. */
    static WallpaperSnapshot snapshot(View wallpaper, View target) {
        if (wallpaper == null || target == null) return null;
        if (wallpaper instanceof ImageView) {
            WallpaperSnapshot result = snapshotSlot(wallpaper, ((ImageView) wallpaper).getDrawable(), true, target);
            if (result != null) return result;
        }
        WallpaperSnapshot result = snapshotSlot(wallpaper, wallpaper.getBackground(), false, target);
        if (result != null) return result;
        return snapshotChildren(wallpaper, target, 0);
    }
    private static WallpaperSnapshot snapshotChildren(View view, View target, int depth) {
        if (!(view instanceof ViewGroup) || depth >= 5) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (!child.isShown()) continue;
            WallpaperSnapshot result = child instanceof ImageView
                    ? snapshotSlot(child, ((ImageView) child).getDrawable(), true, target) : null;
            if (result == null) result = snapshotSlot(child, child.getBackground(), false, target);
            if (result == null) result = snapshotChildren(child, target, depth + 1);
            if (result != null) return result;
        }
        return null;
    }
    private static WallpaperSnapshot snapshotSlot(View view, Drawable slot, boolean image, View target) {
        if (view.getAlpha()!=1f || view.getForeground()!=null
                || (image && ((ImageView)view).getImageTintList()!=null)
                || (!image && view.getBackgroundTintList()!=null)) return null;
        if (slot instanceof WallpaperProjection.ProjectedDrawable)
            return ((ProjectedDrawable) slot).snapshot(target);
        if (!(slot instanceof BitmapDrawable)) return null;
        BitmapDrawable bitmap = (BitmapDrawable) slot;
        if (!WallpaperSnapshot.supported(bitmap)) return null;
        Rect bounds = bitmap.getBounds(), dest = new Rect();
        if (bounds.isEmpty()) return null;
        if (image) dest.set(bounds);
        else Gravity.apply(bitmap.getGravity(), bitmap.getIntrinsicWidth(), bitmap.getIntrinsicHeight(),
                bounds, dest, view.getLayoutDirection());
        Matrix map = new Matrix();
        map.setScale((float) dest.width()/bitmap.getBitmap().getWidth(), (float) dest.height()/bitmap.getBitmap().getHeight());
        map.postTranslate(dest.left, dest.top);
        if (image) {
            map.postConcat(((ImageView) view).getImageMatrix());
            map.postTranslate(view.getPaddingLeft(), view.getPaddingTop());
        }
        Matrix global = new Matrix(), inverse = new Matrix();
        view.transformMatrixToGlobal(global); map.postConcat(global);
        target.transformMatrixToGlobal(global = new Matrix());
        if (!global.invert(inverse)) return null;
        map.postConcat(inverse);
        return WallpaperSnapshot.create(bitmap, map, target.getWidth(), target.getHeight());
    }
    private final class ProjectedDrawable extends Drawable {
        private final BitmapDrawable nativeDrawable;
        ProjectedDrawable(BitmapDrawable drawable) { nativeDrawable = drawable; }
        WallpaperSnapshot snapshot(View output) {
            if (!update() || !WallpaperSnapshot.supported(nativeDrawable)) return null;
            View band = target.get();
            if (band == null) return null;
            Matrix map = new Matrix(bitmapToBand), global = new Matrix(), inverse = new Matrix();
            band.transformMatrixToGlobal(global); map.postConcat(global);
            global.reset(); output.transformMatrixToGlobal(global);
            if (!global.invert(inverse)) return null;
            map.postConcat(inverse);
            return WallpaperSnapshot.create(nativeDrawable, map, output.getWidth(), output.getHeight());
        }
        @Override public void draw(Canvas canvas) {
            if (installed == this && update() && drawableToBand.invert(inverse)) {
                nativeMatrix.set(projectedDrawableMatrix);
                nativeMatrix.postConcat(inverse);
                int save = canvas.save();
                try { canvas.concat(nativeMatrix); nativeDrawable.draw(canvas); }
                finally { canvas.restoreToCount(save); }
            } else {
                nativeDrawable.setBounds(getBounds());
                nativeDrawable.draw(canvas);
            }
        }
        @Override public void invalidateSelf() {
            super.invalidateSelf();
            View band = target.get();
            if (band != null) band.invalidate();
        }
        @Override public void setTintList(android.content.res.ColorStateList tint) {
            nativeDrawable.setTintList(tint); invalidateSelf();
        }
        @Override public void setTintMode(android.graphics.PorterDuff.Mode mode) {
            nativeDrawable.setTintMode(mode); invalidateSelf();
        }
        @Override public void setAlpha(int alpha) { nativeDrawable.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { nativeDrawable.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return nativeDrawable.getIntrinsicWidth(); }
        @Override public int getIntrinsicHeight() { return nativeDrawable.getIntrinsicHeight(); }
        @Override public boolean getPadding(Rect padding) { return nativeDrawable.getPadding(padding); }
        @Override public boolean isStateful() { return nativeDrawable.isStateful(); }
        @Override protected boolean onStateChange(int[] state) { return nativeDrawable.setState(state); }
        @Override protected boolean onLevelChange(int level) { return nativeDrawable.setLevel(level); }
    }
    private static void setBackground(View view, Drawable drawable) {
        int l = view.getPaddingLeft(), t = view.getPaddingTop(), r = view.getPaddingRight(), b = view.getPaddingBottom();
        view.setBackground(drawable);
        view.setPadding(l, t, r, b);
    }
    private static boolean isDescendant(View root, View child) {
        for (View node = child; node != null; node = node.getParent() instanceof View ? (View) node.getParent() : null)
            if (node == root) return true;
        return false;
    }
    private static View bitmapSource(View view, int depth) {
        if (view == null || depth > 5 || !view.isShown()) return null;
        if (view instanceof ImageView && ((ImageView) view).getDrawable() instanceof BitmapDrawable) return view;
        if (view.getBackground() instanceof BitmapDrawable) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = bitmapSource(((ViewGroup) view).getChildAt(i), depth + 1);
            if (found != null) return found;
        }
        return null;
    }
}
