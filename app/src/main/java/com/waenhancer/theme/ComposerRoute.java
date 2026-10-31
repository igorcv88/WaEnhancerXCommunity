package com.waenhancer.theme;

/** Native layout evidence, independent of prior decoration state and timer text. */
public final class ComposerRoute {
    private ComposerRoute() { }
    public enum Panel { INPUT, DRAFT, STUB, UNRESOLVED }
    public static Panel resolve(boolean draftMeasured, boolean editorUsable, boolean stubMeasured) {
        if (draftMeasured) return Panel.DRAFT;
        if (editorUsable) return Panel.INPUT;
        return stubMeasured ? Panel.STUB : Panel.UNRESOLVED;
    }
}
