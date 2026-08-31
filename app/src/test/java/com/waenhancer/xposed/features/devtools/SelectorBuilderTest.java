package com.waenhancer.xposed.features.devtools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * O seletor emitido tem que casar com CustomView.buildRuleMaps(). Um seletor que o motor
 * descarta em silêncio é o pior defeito possível desta feature: o usuário cola a regra, nada
 * acontece, e a culpa cai no CSS.
 */
public class SelectorBuilderTest {

    private static final String HOME = "com.whatsapp.HomeActivity";

    private static final class Node implements ProbeNode {
        String entry;
        String pkg = "com.whatsapp";
        String cls = "com.whatsapp.TextEmojiLabel";
        ProbeNode parent;
        final List<ProbeNode> kids = new ArrayList<>();

        Node(String entry) { this.entry = entry; }

        Node under(Node p) { this.parent = p; p.kids.add(this); return this; }

        @Override public int left() { return 0; }
        @Override public int top() { return 0; }
        @Override public int right() { return 100; }
        @Override public int bottom() { return 100; }
        @Override public boolean visible() { return true; }
        @Override public float alpha() { return 1f; }
        @Override public List<ProbeNode> children() { return kids; }
        @Override public ProbeNode parent() { return parent; }
        @Override public String entryName() { return entry; }
        @Override public String resourcePackage() { return pkg; }
        @Override public int id() { return entry == null ? -1 : 0x7f0a0001; }
        @Override public String className() { return cls; }
        @Override public String contentDescription() { return null; }
    }

    /** O caso comum: id do WhatsApp, seletor de um nível, escopado pela Activity. */
    @Test
    public void aStableIdBecomesASingleLevelSelector() {
        InspectedView view = InspectedView.of(new Node("conversations_row_contact_name"), HOME);
        assertEquals(InspectedView.Stability.STABLE, view.stability());
        assertEquals(".com_whatsapp_HomeActivity #conversations_row_contact_name",
                SelectorBuilder.build(view));
        assertFalse(view.targetsAncestor());
    }

    /** Pontos viram underscore porque é assim que o parser lê o nome da classe. */
    @Test
    public void theActivityClassNameUsesUnderscores() {
        InspectedView view = InspectedView.of(new Node("footer"), "com.whatsapp.conversation.ConversationActivity");
        assertEquals(".com_whatsapp_conversation_ConversationActivity #footer",
                SelectorBuilder.build(view));
    }

    /** android.R.id.* não passa por Utils.getID: o parser resolve por reflexão em android.R.id. */
    @Test
    public void androidIdsUseTheAndroidPrefix() {
        Node node = new Node("content");
        node.pkg = "android";
        InspectedView view = InspectedView.of(node, HOME);
        assertEquals(".com_whatsapp_HomeActivity #android_content", SelectorBuilder.build(view));
    }

    /** Id de outro pacote muda entre versões: o seletor sai, mas marcado. */
    @Test
    public void aThirdPartyIdIsDynamic() {
        Node node = new Node("design_bottom_sheet");
        node.pkg = "com.google.android.material";
        InspectedView view = InspectedView.of(node, HOME);
        assertEquals(InspectedView.Stability.DYNAMIC, view.stability());
    }

    /** Sem id não há seletor possível: sobe até o ancestral estável e diz que subiu. */
    @Test
    public void anUnresolvedIdClimbsToTheNearestStableAncestor() {
        Node ancestor = new Node("conversations_row");
        Node anonymous = new Node(null).under(ancestor);
        InspectedView view = InspectedView.of(anonymous, HOME);
        assertEquals(InspectedView.Stability.UNRESOLVED, view.stability());
        assertTrue(view.targetsAncestor());
        assertEquals(".com_whatsapp_HomeActivity #conversations_row", SelectorBuilder.build(view));
    }

    /** Dois níveis anônimos: continua subindo. */
    @Test
    public void climbingSkipsEveryAnonymousLevel() {
        Node ancestor = new Node("conversations_row");
        Node middle = new Node(null).under(ancestor);
        Node anonymous = new Node(null).under(middle);
        InspectedView view = InspectedView.of(anonymous, HOME);
        assertEquals(".com_whatsapp_HomeActivity #conversations_row", SelectorBuilder.build(view));
    }

    /** Nada estável na cadeia inteira: sem seletor, e o painel tem que dizer isso. */
    @Test
    public void aTreeWithNoStableAncestorProducesNoSelector() {
        Node anonymous = new Node(null);
        InspectedView view = InspectedView.of(anonymous, HOME);
        assertEquals("", SelectorBuilder.build(view));
    }

    /** Activity desconhecida: seletor sem escopo continua válido para o parser. */
    @Test
    public void anUnknownActivityProducesAnUnscopedSelector() {
        InspectedView view = InspectedView.of(new Node("footer"), null);
        assertEquals("#footer", SelectorBuilder.build(view));
    }

    @Test
    public void theRuleBlockWrapsTheSelector() {
        InspectedView view = InspectedView.of(new Node("footer"), HOME);
        assertEquals(".com_whatsapp_HomeActivity #footer {\n    \n}",
                SelectorBuilder.ruleBlock(view));
    }

    @Test
    public void anEmptySelectorProducesAnEmptyRuleBlock() {
        assertEquals("", SelectorBuilder.ruleBlock(InspectedView.of(new Node(null), HOME)));
    }

    @Test
    public void theIdIsExposedInHex() {
        InspectedView view = InspectedView.of(new Node("footer"), HOME);
        assertEquals("0x7f0a0001", view.idHex());
    }
}
