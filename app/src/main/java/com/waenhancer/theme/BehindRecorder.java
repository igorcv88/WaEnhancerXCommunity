package com.waenhancer.theme;

import android.graphics.Matrix;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.util.ArrayList;
import java.util.List;

/**
 * Records what a window draws beneath one view: its ancestors' backgrounds and, at every level,
 * the siblings drawn before the branch that leads to it.
 *
 * <p>This is the generic form of a pane's source. Nothing drawn after the view, and nothing inside
 * it, is ever recorded, so two surfaces can never record each other: whichever comes later in
 * draw order may hold the earlier one, never the reverse. That ordering is what keeps the render
 * tree acyclic. The one thing it cannot see is a {@link GlassPane} whose explicit source holds the
 * view; a sibling containing such a pane is skipped.</p>
 */
final class BehindRecorder {

    private BehindRecorder() { }

    /**
     * Whether a sibling at {@code (siblingZ, siblingIndex)} is drawn before the branch at
     * {@code (branchZ, branchIndex)}: ViewGroup sorts children by Z, stably by index.
     */
    static boolean drawnBefore(float siblingZ, int siblingIndex, float branchZ, int branchIndex) {
        if (siblingIndex == branchIndex) return false;
        if (siblingZ != branchZ) return siblingZ < branchZ;
        return siblingIndex < branchIndex;
    }

    /** Whether two screen rects overlap. */
    static boolean intersects(int l1, int t1, int r1, int b1, int l2, int t2, int r2, int b2) {
        return l1 < r2 && l2 < r1 && t1 < b2 && t2 < b1;
    }

    /**
     * Draws what lies beneath {@code target}, in target-local pixels.
     *
     * @param targetX target's left on screen
     * @param targetY target's top on screen
     * @return false when nothing was drawn
     */
    static boolean paint(RecordingCanvas canvas, View target, int targetX, int targetY,
                         int width, int height) {
        List<View> path = new ArrayList<>();
        for (View v = target; v != null; v = parentOf(v)) path.add(0, v);
        List<View> forbidden = panesRecording(target);
        int[] at = new int[2];
        boolean drew = false;
        for (int i = 0; i < path.size() - 1; i++) {
            View ancestor = path.get(i);
            View branch = path.get(i + 1);
            if (ancestor.getVisibility() != View.VISIBLE) return drew;
            ancestor.getLocationOnScreen(at);
            int ax = at[0], ay = at[1];

            Drawable background = ancestor.getBackground();
            if (background != null) {
                int save = canvas.save();
                canvas.translate(ax - targetX, ay - targetY);
                background.draw(canvas);
                canvas.restoreToCount(save);
                drew = true;
            }
            if (!(ancestor instanceof ViewGroup)) continue;
            ViewGroup group = (ViewGroup) ancestor;
            int branchIndex = group.indexOfChild(branch);
            float branchZ = branch.getZ();
            for (int c = 0; c < group.getChildCount(); c++) {
                View child = group.getChildAt(c);
                if (child == null || child == branch || child.getVisibility() != View.VISIBLE
                        || child.getAlpha() <= 0f || child.getWidth() <= 0 || child.getHeight() <= 0) continue;
                if (!drawnBefore(child.getZ(), c, branchZ, branchIndex)) continue;
                // The child's rect on screen from its parent's, without a location walk per child.
                int cx = ax + child.getLeft() + Math.round(child.getTranslationX()) - group.getScrollX();
                int cy = ay + child.getTop() + Math.round(child.getTranslationY()) - group.getScrollY();
                if (!intersects(cx, cy, cx + child.getWidth(), cy + child.getHeight(),
                        targetX, targetY, targetX + width, targetY + height)) continue;
                if (containsAny(child, forbidden)) continue;
                int save = child.getAlpha() < 1f
                        ? canvas.saveLayerAlpha(cx - targetX, cy - targetY,
                                cx - targetX + child.getWidth(), cy - targetY + child.getHeight(),
                                Math.round(child.getAlpha() * 255))
                        : canvas.save();
                canvas.translate(cx - targetX, cy - targetY);
                child.draw(canvas);
                canvas.restoreToCount(save);
                drew = true;
            }
        }
        return drew;
    }

    // Main-thread scratch for paintExact: captures run every frame while scrolling, so no allocation.
    private static final Matrix TARGET = new Matrix();
    private static final Matrix BASE = new Matrix();
    private static final Matrix PLACED = new Matrix();
    private static final RectF TARGET_RECT = new RectF();
    private static final RectF CHILD_RECT = new RectF();

    /**
     * {@link #paint} with exact transforms (the geometry group): every view is placed by
     * {@code T(-offset) · inverse(G_target) · G_view}, so a rotated or scaled ancestor or sibling,
     * and a scrolled sibling's content, land where the window draws them. The draw order and the
     * acyclicity argument are the same as {@link #paint}'s.
     *
     * @param offsetX the drawable's left inside {@code target}
     * @param offsetY the drawable's top inside {@code target}
     */
    static boolean paintExact(RecordingCanvas canvas, View target, int offsetX, int offsetY,
                              int width, int height) {
        TARGET.reset();
        target.transformMatrixToGlobal(TARGET);
        if (!TARGET.invert(BASE)) return false;
        BASE.postTranslate(-offsetX, -offsetY);
        TARGET_RECT.set(offsetX, offsetY, offsetX + width, offsetY + height);
        TARGET.mapRect(TARGET_RECT);
        List<View> path = new ArrayList<>();
        for (View v = target; v != null; v = parentOf(v)) path.add(0, v);
        List<View> forbidden = panesRecording(target);
        boolean drew = false;
        for (int i = 0; i < path.size() - 1; i++) {
            View ancestor = path.get(i);
            View branch = path.get(i + 1);
            if (ancestor.getVisibility() != View.VISIBLE) return drew;
            Drawable background = ancestor.getBackground();
            if (background != null) {
                place(ancestor);
                int save = canvas.save();
                canvas.concat(PLACED);
                background.draw(canvas);
                canvas.restoreToCount(save);
                drew = true;
            }
            if (!(ancestor instanceof ViewGroup)) continue;
            ViewGroup group = (ViewGroup) ancestor;
            int branchIndex = group.indexOfChild(branch);
            float branchZ = branch.getZ();
            for (int c = 0; c < group.getChildCount(); c++) {
                View child = group.getChildAt(c);
                if (child == null || child == branch || child.getVisibility() != View.VISIBLE
                        || child.getAlpha() <= 0f || child.getWidth() <= 0 || child.getHeight() <= 0) continue;
                if (!drawnBefore(child.getZ(), c, branchZ, branchIndex)) continue;
                place(child);
                CHILD_RECT.set(0, 0, child.getWidth(), child.getHeight());
                PLACED.mapRect(CHILD_RECT);
                // In drawable-local pixels now; the drawable spans (0, 0, width, height).
                if (!CHILD_RECT.intersects(0, 0, width, height)) continue;
                if (containsAny(child, forbidden)) continue;
                int save = child.getAlpha() < 1f
                        ? canvas.saveLayerAlpha(null, Math.round(child.getAlpha() * 255))
                        : canvas.save();
                canvas.concat(PLACED);
                canvas.translate(-child.getScrollX(), -child.getScrollY());
                child.draw(canvas);
                canvas.restoreToCount(save);
                drew = true;
            }
        }
        return drew;
    }

    /** Sets {@link #PLACED} to {@code view}'s local space mapped into the drawable's. */
    private static void place(View view) {
        PLACED.reset();
        view.transformMatrixToGlobal(PLACED);
        PLACED.postConcat(BASE);
    }

    /** Live panes whose explicit source holds the target: recording them would close a loop. */
    private static List<View> panesRecording(View target) {
        List<View> result = new ArrayList<>();
        for (GlassPane pane : GlassPane.live()) {
            View source = pane.source();
            if (source != null && (source == target || isAncestor(source, target))) result.add(pane);
        }
        return result;
    }

    private static boolean containsAny(View root, List<View> views) {
        for (View v : views) if (v == root || isAncestor(root, v)) return true;
        return false;
    }

    private static boolean isAncestor(View ancestor, View view) {
        for (View v = parentOf(view); v != null; v = parentOf(v)) if (v == ancestor) return true;
        return false;
    }

    private static View parentOf(View view) {
        ViewParent parent = view.getParent();
        return parent instanceof View ? (View) parent : null;
    }
}
