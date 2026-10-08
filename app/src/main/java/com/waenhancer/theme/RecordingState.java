package com.waenhancer.theme;

/** Why pixels are absent is independent of renderer capability and recovery timing. */
public enum RecordingState {
    UNINITIALIZED, RECORDED, DROPPED, INTENTIONALLY_RELEASED, BUDGET_REFUSED, TEMPORARILY_UNAVAILABLE;

    public boolean requestsImmediateRecovery(boolean eligible) {
        return eligible && this == DROPPED;
    }
}
