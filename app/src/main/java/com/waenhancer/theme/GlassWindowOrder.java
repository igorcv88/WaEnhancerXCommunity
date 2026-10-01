package com.waenhancer.theme;

import java.util.ArrayList;
import java.util.List;

/** Window topology without Android dependencies. Input siblings retain their registration order. */
final class GlassWindowOrder {
    record Entry<T>(T value, Object appToken, Object windowToken, int type, boolean visible) {
        boolean subwindow() { return type >= 1000 && type <= 1999; }
    }
    static <T> List<T> through(List<Entry<T>> windows, T own) {
        Entry<T> target = null;
        for (Entry<T> entry : windows) if (entry.value == own) { target = entry; break; }
        if (target == null) return List.of(own);
        Entry<T> top = target;
        for (int depth = 0; top.subwindow(); depth++) {
            if (depth >= 8) return List.of(own);
            Entry<T> parent = null;
            for (Entry<T> entry : windows) {
                if (entry != top && entry.windowToken != null && entry.windowToken == top.appToken) { parent = entry; break; }
            }
            if (parent == null) return List.of(own);
            top = parent;
        }
        if (top.appToken == null) return List.of(own);
        List<Entry<T>> ordered = new ArrayList<>();
        for (Entry<T> entry : windows) {
            if (!entry.subwindow() && entry.appToken == top.appToken && entry.type == 1) ordered.add(entry);
        }
        for (Entry<T> entry : windows) {
            if (!entry.subwindow() && entry.appToken == top.appToken && entry.type != 1) ordered.add(entry);
        }
        List<T> result = new ArrayList<>();
        for (Entry<T> entry : ordered) if (append(entry, own, windows, result, 0)) return result;
        return List.of(own);
    }
    private static <T> boolean append(Entry<T> entry, T own, List<Entry<T>> all, List<T> out, int depth) {
        if (!entry.visible || depth > 8) return false;
        out.add(entry.value);
        if (entry.value == own) return true;
        if (entry.windowToken != null) for (Entry<T> child : all) {
            if (child != entry && child.subwindow() && child.appToken == entry.windowToken
                    && append(child, own, all, out, depth + 1)) return true;
        }
        return false;
    }
}
