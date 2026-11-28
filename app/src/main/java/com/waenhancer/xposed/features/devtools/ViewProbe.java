package com.waenhancer.xposed.features.devtools;

import java.util.List;

/**
 * Descobre qual view está sob o dedo.
 *
 * <p>Percorre os filhos de trás para frente porque essa é a ordem de desenho: o último filho é
 * o que está por cima, e portanto o que o usuário acha que tocou.</p>
 */
public final class ViewProbe {

    private ViewProbe() {
    }

    /**
     * The topmost view with content under the point. Among overlapping candidates (drawing
     * order, last first), a hit that shows and handles nothing — an empty overlay covering the
     * screen — is passed over for the next candidate beneath it; it is chosen only when nothing
     * under the point has content. The rule is structural; no view is excluded by name.
     */
    public static ProbeNode hit(ProbeNode root, int x, int y) {
        if (root == null) return null;
        if (!isEligible(root) || !contains(root, x, y)) return null;

        ProbeNode emptyHit = null;
        List<ProbeNode> children = root.children();
        if (children != null) {
            for (int i = children.size() - 1; i >= 0; i--) {
                ProbeNode found = hit(children.get(i), x, y);
                if (found == null) continue;
                if (found.hasContent()) return found;
                if (emptyHit == null) emptyHit = found;
            }
        }
        if (root.hasContent() || emptyHit == null) return root;
        return emptyHit;
    }

    private static boolean isEligible(ProbeNode node) {
        return node.visible() && node.alpha() > 0f;
    }

    /** Borda direita e inferior exclusivas, igual a Rect.contains. */
    private static boolean contains(ProbeNode node, int x, int y) {
        return x >= node.left() && x < node.right()
                && y >= node.top() && y < node.bottom();
    }
}
