package com.waenhancer.theme;

import java.util.IdentityHashMap;

/** The factory drawables actually seen in one native row render, compared by identity.
 * A row may draw separate bubble and quote factories; last-draw-wins is not sufficient. */
public final class NativeBubbleDrawLedger<T> {
    private T first;
    private IdentityHashMap<T, Boolean> additional;

    public void mark(T drawable) {
        if (drawable == null) return;
        if (first == null) first = drawable;
        else if (first != drawable) {
            if (additional == null) additional = new IdentityHashMap<>();
            additional.put(drawable, Boolean.TRUE);
        }
    }

    public boolean saw(T drawable) {
        return drawable != null && (first == drawable
                || additional != null && additional.containsKey(drawable));
    }
}
