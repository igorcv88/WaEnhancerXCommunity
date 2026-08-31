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

    public static ProbeNode hit(ProbeNode root, int x, int y) {
        if (root == null) return null;
        if (!isEligible(root) || !contains(root, x, y)) return null;

        List<ProbeNode> children = root.children();
        if (children != null) {
            for (int i = children.size() - 1; i >= 0; i--) {
                ProbeNode found = hit(children.get(i), x, y);
                if (found != null) return found;
            }
        }
        return root;
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
