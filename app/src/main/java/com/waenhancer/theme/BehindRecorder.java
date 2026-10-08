package com.waenhancer.theme;

import android.graphics.RecordingCanvas;
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
