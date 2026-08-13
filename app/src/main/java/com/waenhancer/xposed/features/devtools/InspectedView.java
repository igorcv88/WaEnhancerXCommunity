package com.waenhancer.xposed.features.devtools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Uma view capturada, já reduzida ao que pode ser mostrado.
 *
 * <p>Note o que não está aqui: texto. O ProbeNode nem sequer expõe um acessor de texto, e esta
 * classe não tem campo para ele. A ausência é a garantia.</p>
 *
 * <p>A frase acima evita escrever o nome do acessor proibido de propósito: o Gate da F2 é um
 * grep literal, e um comentário que o dispara torna o gate inútil.</p>
 */
public final class InspectedView {

    public enum Stability {
        /** Id do próprio WhatsApp: o seletor deve durar. */
        STABLE,
        /** Id de biblioteca: existe, mas muda entre versões. */
        DYNAMIC,
        /** Sem id: não há seletor possível para esta view. */
        UNRESOLVED
    }

    private static final String WHATSAPP_PACKAGE = "com.whatsapp";
    private static final String ANDROID_PACKAGE = "android";

    private final String entryName;
    private final String resourcePackage;
    private final String className;
    private final int id;
    private final String activityClassName;
    private final Stability stability;
    private final boolean targetsAncestor;
    private final List<String> parentChain;

    private InspectedView(String entryName, String resourcePackage, String className, int id,
            String activityClassName, Stability stability, boolean targetsAncestor,
            List<String> parentChain) {
        this.entryName = entryName;
        this.resourcePackage = resourcePackage;
        this.className = className;
        this.id = id;
        this.activityClassName = activityClassName;
        this.stability = stability;
        this.targetsAncestor = targetsAncestor;
        this.parentChain = Collections.unmodifiableList(parentChain);
    }

    public static InspectedView of(ProbeNode node, String activityClassName) {
        List<String> chain = new ArrayList<>();
        for (ProbeNode p = node.parent(); p != null; p = p.parent()) {
            chain.add(p.entryName() == null ? p.className() : p.entryName());
        }

        Stability stability = stabilityOf(node);
        if (stability != Stability.UNRESOLVED) {
            return new InspectedView(node.entryName(), node.resourcePackage(), node.className(),
                    node.id(), activityClassName, stability, false, chain);
        }

        // Sem id próprio: sobe até o ancestral mais próximo que tenha um.
        for (ProbeNode p = node.parent(); p != null; p = p.parent()) {
            if (stabilityOf(p) != Stability.UNRESOLVED) {
                return new InspectedView(p.entryName(), p.resourcePackage(), node.className(),
                        p.id(), activityClassName, Stability.UNRESOLVED, true, chain);
            }
        }
        return new InspectedView(null, null, node.className(), node.id(), activityClassName,
                Stability.UNRESOLVED, false, chain);
    }

    private static Stability stabilityOf(ProbeNode node) {
        String entry = node.entryName();
        if (entry == null || entry.isEmpty()) return Stability.UNRESOLVED;
        String pkg = node.resourcePackage();
        if (WHATSAPP_PACKAGE.equals(pkg) || ANDROID_PACKAGE.equals(pkg)) return Stability.STABLE;
        return Stability.DYNAMIC;
    }

    public String entryName() { return entryName; }

    public String resourcePackage() { return resourcePackage; }

    public String className() { return className; }

    public String activityClassName() { return activityClassName; }

    public Stability stability() { return stability; }

    /** True quando o seletor mira um ancestral porque a view tocada não tem id. */
    public boolean targetsAncestor() { return targetsAncestor; }

    public List<String> parentChain() { return parentChain; }

    public String idHex() {
        return entryName == null ? "" : String.format("0x%08x", id);
    }
}
