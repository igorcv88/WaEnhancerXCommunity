package com.waenhancer.xposed.core;

/**
 * When the core resolver probe may run. It shares the resolver lock and caches with the
 * features, so it never runs while feature workers are still installing: after a loader timeout
 * it first waits, bounded, for them to finish.
 */
final class ProbeGate {
    static final long LATE_WAIT_SECONDS = 60;

    interface Wait {
        /** True when every worker finished within the bound. */
        boolean await() throws InterruptedException;
    }

    private ProbeGate() {}

    static boolean mayProbe(boolean loaderFinished, Wait lateWait) throws InterruptedException {
        return loaderFinished || lateWait.await();
    }
}
