package com.waenhancer.xposed.features.privacy;

import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;

/** Publish a user-authorized receipt before enqueueing: a worker may run immediately. */
public final class ReceiptRelease {
    private ReceiptRelease() {}

    public interface Enqueue { void run() throws Exception; }

    public static <T> void enqueue(List<T> messages, Consumer<T> authorize,
                                  Consumer<T> rollback, Enqueue enqueue) throws Exception {
        List<T> authorized = new ArrayList<>();
        try {
            for (T message : messages) {
                authorized.add(message);
                authorize.accept(message);
            }
            enqueue.run();
        } catch (Exception failure) {
            for (T message : authorized) {
                try {
                    rollback.accept(message);
                } catch (Exception rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
    }
}
