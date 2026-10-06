package com.waenhancer.theme;

import java.util.function.Consumer;
import java.util.function.LongConsumer;

/** UI-thread clock shared across windows, with one outstanding frame request and no idle loop. */
public final class GlassFrameClock {
    private long token = Long.MIN_VALUE;
    private boolean pending;

    public long currentFrame(Consumer<LongConsumer> schedule) {
        if (!pending) {
            pending = true;
            try {
                schedule.accept(frameTimeNanos -> {
                    token = frameTimeNanos;
                    pending = false;
                });
            } catch (RuntimeException failure) {
                pending = false;
                throw failure;
            }
        }
        return token;
    }
}
