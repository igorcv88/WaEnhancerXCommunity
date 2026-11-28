package com.waenhancer.xposed.features.devtools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Qual view o dedo escolheu. Cada caso errado aqui é o usuário copiando o seletor de outra coisa
 * e concluindo que o motor de CSS está quebrado.
 */
public class ViewProbeTest {

    /** Nó de teste: as bounds já vêm recortadas, como o adaptador entrega. */
    private static final class Node implements ProbeNode {
        final int l, t, r, b;
        boolean visible = true;
        float alpha = 1f;
        boolean content = true;
        String entry;
        final List<ProbeNode> kids = new ArrayList<>();
        ProbeNode parent;

        Node(String entry, int l, int t, int r, int b) {
            this.entry = entry;
            this.l = l; this.t = t; this.r = r; this.b = b;
        }

        Node with(Node... children) {
            for (Node c : children) {
                c.parent = this;
                kids.add(c);
            }
            return this;
        }

        Node hidden() { this.visible = false; return this; }
        Node transparent() { this.alpha = 0f; return this; }
        Node empty() { this.content = false; return this; }

        @Override public int left() { return l; }
        @Override public int top() { return t; }
        @Override public int right() { return r; }
        @Override public int bottom() { return b; }
        @Override public boolean visible() { return visible; }
        @Override public float alpha() { return alpha; }
        @Override public List<ProbeNode> children() { return kids; }
        @Override public ProbeNode parent() { return parent; }
        @Override public String entryName() { return entry; }
        @Override public String resourcePackage() { return "com.whatsapp"; }
        @Override public int id() { return entry == null ? -1 : entry.hashCode(); }
        @Override public String className() { return "android.view.View"; }
        @Override public String contentDescription() { return null; }
        @Override public boolean hasContent() { return content; }
    }

    private static String entryOf(ProbeNode node) {
        return node == null ? null : node.entryName();
    }

    @Test
    public void theDeepestLeafWins() {
        Node leaf = new Node("leaf", 10, 10, 90, 90);
        Node root = new Node("root", 0, 0, 100, 100).with(leaf);
        assertEquals("leaf", entryOf(ViewProbe.hit(root, 50, 50)));
    }

    /** Irmãos que se sobrepõem: quem é desenhado depois está por cima. */
    @Test
    public void theLastDrawnSiblingWins() {
        Node under = new Node("under", 0, 0, 100, 100);
        Node over = new Node("over", 0, 0, 100, 100);
        Node root = new Node("root", 0, 0, 100, 100).with(under, over);
        assertEquals("over", entryOf(ViewProbe.hit(root, 50, 50)));
    }

    @Test
    public void anInvisibleChildIsSkipped() {
        Node hidden = new Node("hidden", 0, 0, 100, 100).hidden();
        Node root = new Node("root", 0, 0, 100, 100).with(hidden);
        assertEquals("root", entryOf(ViewProbe.hit(root, 50, 50)));
    }

    @Test
    public void aFullyTransparentChildIsSkipped() {
        Node ghost = new Node("ghost", 0, 0, 100, 100).transparent();
        Node root = new Node("root", 0, 0, 100, 100).with(ghost);
        assertEquals("root", entryOf(ViewProbe.hit(root, 50, 50)));
    }

    /**
     * O caso do getGlobalVisibleRect: a linha rolou para fora da lista, então suas bounds
     * recortadas não contêm mais o ponto, mesmo que as nominais contivessem.
     */
    @Test
    public void aChildClippedOutOfViewIsNotHit() {
        Node scrolledAway = new Node("row", 0, 0, 100, 0);
        Node list = new Node("list", 0, 0, 100, 100).with(scrolledAway);
        assertEquals("list", entryOf(ViewProbe.hit(list, 50, 50)));
    }

    @Test
    public void aPointOutsideEverythingHitsNothing() {
        Node root = new Node("root", 0, 0, 100, 100);
        assertNull(ViewProbe.hit(root, 500, 500));
    }

    @Test
    public void aHiddenParentHidesItsChildren() {
        Node leaf = new Node("leaf", 10, 10, 90, 90);
        Node branch = new Node("branch", 0, 0, 100, 100).hidden().with(leaf);
        Node root = new Node("root", 0, 0, 100, 100).with(branch);
        assertEquals("root", entryOf(ViewProbe.hit(root, 50, 50)));
    }

    @Test
    public void aNullRootHitsNothing() {
        assertNull(ViewProbe.hit(null, 0, 0));
    }

    /** A borda direita/inferior é exclusiva, como Rect.contains. */
    @Test
    public void theRightAndBottomEdgesAreExclusive() {
        Node root = new Node("root", 0, 0, 100, 100);
        assertNull(ViewProbe.hit(root, 100, 50));
        assertEquals("root", entryOf(ViewProbe.hit(root, 99, 99)));
    }

    @Test
    public void siblingsThatDoNotOverlapAreEachHitInTheirOwnArea() {
        Node left = new Node("left", 0, 0, 50, 100);
        Node right = new Node("right", 50, 0, 100, 100);
        Node root = new Node("root", 0, 0, 100, 100).with(left, right);
        assertEquals("left", entryOf(ViewProbe.hit(root, 10, 50)));
        assertEquals("right", entryOf(ViewProbe.hit(root, 90, 50)));
        assertEquals(Arrays.asList(left, right), root.children());
    }

    /** A full-screen overlay that draws and handles nothing yields to the bubble beneath it. */
    @Test
    public void anEmptyOverlayYieldsToTheContentBeneath() {
        Node bubble = new Node("bubble", 10, 10, 90, 40);
        Node list = new Node("list", 0, 0, 100, 100).empty().with(bubble);
        Node overlay = new Node("any_overlay_name", 0, 0, 100, 100).empty();
        Node root = new Node("root", 0, 0, 100, 100).empty().with(list, overlay);
        assertEquals("bubble", entryOf(ViewProbe.hit(root, 50, 20)));
    }

    /** The rule is structural: the same overlay with content (a scrim, a dialog) is chosen. */
    @Test
    public void anOverlayWithContentStillWins() {
        Node bubble = new Node("bubble", 10, 10, 90, 40);
        Node list = new Node("list", 0, 0, 100, 100).empty().with(bubble);
        Node overlay = new Node("any_overlay_name", 0, 0, 100, 100);
        Node root = new Node("root", 0, 0, 100, 100).empty().with(list, overlay);
        assertEquals("any_overlay_name", entryOf(ViewProbe.hit(root, 50, 20)));
    }

    /** An empty overlay inside a container is passed over too, not only at the top level. */
    @Test
    public void aNestedEmptyOverlayIsPassedOver() {
        Node text = new Node("message_text", 10, 10, 90, 40);
        Node row = new Node("row", 0, 0, 100, 50).empty().with(text);
        Node interceptor = new Node("touch_layer", 0, 0, 100, 100).empty().with(new Node("inner", 0, 0, 100, 100).empty());
        Node root = new Node("root", 0, 0, 100, 100).empty().with(row, interceptor);
        assertEquals("message_text", entryOf(ViewProbe.hit(root, 50, 20)));
    }

    /** Nothing under the point has content: the deepest empty view is still reported. */
    @Test
    public void withoutContentTheTopmostEmptyViewIsReported() {
        Node under = new Node("under", 0, 0, 100, 100).empty();
        Node over = new Node("over", 0, 0, 100, 100).empty();
        Node root = new Node("root", 0, 0, 100, 100).empty().with(under, over);
        assertEquals("over", entryOf(ViewProbe.hit(root, 50, 50)));
    }

    /** Empty space of a row (padding) next to a bubble does not jump to an unrelated view. */
    @Test
    public void aContainerWithContentKeepsItsOwnHit() {
        Node bubble = new Node("bubble", 10, 10, 50, 40);
        Node row = new Node("row", 0, 0, 100, 50).with(bubble);
        Node root = new Node("root", 0, 0, 100, 100).empty().with(row);
        assertEquals("row", entryOf(ViewProbe.hit(root, 80, 20)));
    }
}
