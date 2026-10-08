package com.waenhancer.theme;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Decides whether a pane may record its source without the render tree reaching itself.
 *
 * <p>A pane draws a recording of its source into its own display list. If the source holds the
 * pane, directly or through another pane whose source holds this one, HWUI recurses on the
 * RenderThread and the process dies natively, beyond any Java catch. Ported from WaThemer's
 * {@code BackdropCapture.safeToCapture} (GPL-3.0, ayane-04/wathemer@d39b293); kept free of Android types so it is tested on the JVM.</p>
 */
final class GlassPaneGraph {

    interface Tree<V> {
        V parentOf(V view);
    }

    /** One live pane: the view it draws in and the subtree it records, or null when underlay-only. */
    static final class Pane<V> {
        final V host;
        final V source;

        Pane(V host, V source) {
            this.host = host;
            this.source = source;
        }
    }

    private GlassPaneGraph() { }

    static <V> boolean isAncestor(Tree<V> tree, V ancestor, V view) {
        if (ancestor == null || view == null) return false;
        for (V p = tree.parentOf(view); p != null; p = tree.parentOf(p)) {
            if (p == ancestor) return true;
        }
        return false;
    }

    /** Null when {@code source} is safe for {@code host}, else why it is refused. */
    static <V> String refusal(Tree<V> tree, V host, V source, List<Pane<V>> live) {
        if (source == null) return null;
        if (source == host || isAncestor(tree, source, host)) return "source contains the pane";
        // Panes inside the source are drawn into this recording. Follow what each of them records:
        // reaching a subtree that holds this pane closes the loop.
        Deque<Pane<V>> pending = new ArrayDeque<>();
        List<Pane<V>> visited = new ArrayList<>();
        for (Pane<V> other : live) {
            if (other.host != host && other.source != null && isAncestor(tree, source, other.host)) {
                pending.push(other);
            }
        }
        while (!pending.isEmpty()) {
            Pane<V> next = pending.pop();
            if (visited.contains(next)) continue;
            visited.add(next);
            if (next.source == host || isAncestor(tree, next.source, host)) {
                return "a pane inside the source records this pane";
            }
            for (Pane<V> other : live) {
                if (other.host != host && other.source != null && !visited.contains(other)
                        && isAncestor(tree, next.source, other.host)) {
                    pending.push(other);
                }
            }
        }
        return null;
    }
}
