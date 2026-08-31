package com.waenhancer.xposed.features.devtools;

/**
 * Gera a string que CustomView.buildRuleMaps() sabe indexar.
 *
 * <p>O dialeto não é CSS genérico. A primeira parte com className vira o escopo de Activity
 * (CachedRuleItem.targetActivityClassName), os pontos do nome viram underscore, e ids de
 * android.R.id usam o prefixo android_ porque o parser os resolve por reflexão em vez de
 * Utils.getID. Ver CustomView.java, linhas ~240-330.</p>
 */
public final class SelectorBuilder {

    private SelectorBuilder() {
    }

    public static String build(InspectedView view) {
        String entry = view.entryName();
        if (entry == null || entry.isEmpty()) return "";

        String id = "android".equals(view.resourcePackage()) ? "android_" + entry : entry;
        String activity = view.activityClassName();
        if (activity == null || activity.isEmpty()) {
            return "#" + id;
        }
        return "." + activity.replace('.', '_') + " #" + id;
    }

    /** O bloco pronto para colar no editor de CSS. */
    public static String ruleBlock(InspectedView view) {
        String selector = build(view);
        if (selector.isEmpty()) return "";
        return selector + " {\n    \n}";
    }
}
