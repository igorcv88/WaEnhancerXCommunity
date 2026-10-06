package com.waenhancer.xposed.core;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/** Snapshot dispatch tolerates concurrent/lazy installation and isolates feature failures. */
public final class FeatureCallbacks<T> {
    private final Set<T> listeners = new CopyOnWriteArraySet<>();

    public void add(T listener) {
        if (listener != null) listeners.add(listener);
    }

    public void dispatch(Consumer<T> callback, Consumer<Throwable> onFailure) {
        for (T listener : listeners) {
            try {
                callback.accept(listener);
            } catch (Throwable failure) {
                onFailure.accept(failure);
            }
        }
    }
}
