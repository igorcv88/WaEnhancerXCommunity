package com.waenhancer.xposed.features.general;

/** Prevents two callbacks for one toolbar action from reversing the same search toggle. */
public final class HomeSearchActionPolicy {
    private HomeSearchActionPolicy() { }

    public static boolean isDuplicate(long nowMs, long previousMs) {
        return previousMs >= 0 && nowMs >= previousMs && nowMs - previousMs < 175;
    }
}
