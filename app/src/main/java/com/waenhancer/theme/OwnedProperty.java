package com.waenhancer.theme;

/** Identity-based reversible override. Native reassignments always supersede our old value. */
public final class OwnedProperty<T> {
    private T original, installed;
    private boolean owned;
    public boolean owns(T current) { return owned && current == installed; }
    public void install(T current, T replacement) {
        if (!owns(current)) original = current;
        installed = replacement;
        owned = true;
    }
    public T original() { return original; }
}
