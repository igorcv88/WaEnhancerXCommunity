package com.waenhancer.diagnostics;

import java.util.Collection;

/** Compatibility and per-case evidence have different scopes. */
public final class ValidationModel {
    private ValidationModel() {}

    public enum Compatibility { UNKNOWN, INCOMPATIBLE, RUNTIME_COMPATIBLE, DEGRADED }
    public enum Status {
        NOT_TESTED, UNKNOWN, DISABLED, NOT_INSTRUMENTED, NOT_EXERCISED, AMBIGUOUS,
        RESOLVER_FAILED, INSTALL_FAILED, TRIGGERED_UNVERIFIED, BEHAVIOR_PASS,
        BEHAVIOR_FAIL, SERVER_UNAVAILABLE, BLOCKED
    }

    public static boolean occurredDuringSession(long eventAt, long sessionStartedAt, long capturedAt) {
        return sessionStartedAt > 0 && eventAt >= sessionStartedAt && eventAt <= capturedAt;
    }

    public static boolean sameRun(String session, String process, String config,
            String eventSession, String eventProcess, String eventConfig) {
        return !session.isEmpty() && !process.isEmpty() && !config.isEmpty()
                && session.equals(eventSession) && process.equals(eventProcess) && config.equals(eventConfig);
    }

    public static final class FeatureEvidence {
        public boolean enabled = true;
        public boolean instrumented;
        public boolean loaded;
        public boolean resolverFailed;
        public boolean installed;
        public boolean triggered;
        public boolean error;
        public boolean required;
        public boolean manualConfirmed;

        public Status state() {
            if (!enabled) return Status.DISABLED;
            if (resolverFailed) return Status.RESOLVER_FAILED;
            if (error) return Status.INSTALL_FAILED;
            if (!instrumented) return Status.NOT_INSTRUMENTED;
            if (triggered) return Status.TRIGGERED_UNVERIFIED;
            if (loaded) return Status.NOT_EXERCISED;
            return Status.NOT_TESTED;
        }
    }

    public static Compatibility aggregate(boolean currentSnapshot, boolean corePassed,
            boolean optionalFailed, Collection<FeatureEvidence> features) {
        if (!currentSnapshot) return Compatibility.UNKNOWN;
        if (!corePassed) return Compatibility.INCOMPATIBLE;
        for (FeatureEvidence feature : features) {
            optionalFailed |= feature.enabled && (feature.error || feature.resolverFailed);
        }
        return optionalFailed ? Compatibility.DEGRADED : Compatibility.RUNTIME_COMPATIBLE;
    }

    /** Confirmation covers only the enabled diagnostic smoke cases for which controls exist. */
    public static boolean smokeConfirmed(Collection<FeatureEvidence> features) {
        boolean requiredSeen = false;
        for (FeatureEvidence feature : features) {
            if (!feature.required) continue;
            requiredSeen = true;
            if (feature.state() != Status.TRIGGERED_UNVERIFIED || !feature.manualConfirmed) return false;
        }
        return requiredSeen;
    }
}
