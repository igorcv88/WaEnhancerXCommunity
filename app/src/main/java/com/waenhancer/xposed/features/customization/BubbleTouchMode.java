package com.waenhancer.xposed.features.customization;

import java.util.Locale;

/** Independent controls for the operations performed by the original gesture probe. */
enum BubbleTouchMode {
    UNSET, INVALID, OFF, RESOLVE, EMPTY, LATE_EMPTY, CALLBACKS, LIST, ROW,
    HIERARCHY, REFLECT, FORMAT, LOG, ROW_RESOLVE, ROW_HOOKS, ROW_WATCH, ADAPTER_RESOLVE, ADAPTER_EMPTY,
    ADAPTER_WATCH, FULL;

    static BubbleTouchMode parse(String value) {
        if (value.isEmpty()) return UNSET;
        try {
            BubbleTouchMode mode = valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
            return mode == UNSET || mode == INVALID ? INVALID : mode;
        } catch (IllegalArgumentException ignored) {
            return INVALID;
        }
    }

    boolean callbacks() {
        return ordinal() >= CALLBACKS.ordinal();
    }

    boolean list() {
        return ordinal() >= LIST.ordinal();
    }

    boolean row() {
        return ordinal() >= ROW.ordinal();
    }

    boolean hierarchy() {
        return this == HIERARCHY || this == REFLECT || formats() || resolvesRowHooks();
    }

    boolean reflects() {
        return this == REFLECT || formats();
    }

    boolean formats() {
        return this == FORMAT || this == LOG || this == FULL;
    }

    boolean logs() {
        return this == LOG || this == FULL;
    }

    boolean resolvesRowHooks() {
        return this == ROW_RESOLVE || this == ROW_HOOKS || this == ROW_WATCH || this == FULL;
    }

    boolean adapter() {
        return this == ADAPTER_RESOLVE || this == ADAPTER_EMPTY || this == ADAPTER_WATCH || this == FULL;
    }
}
