package com.waenhancer.diagnostics;

import java.util.Collection;

/** Compatibility and per-case evidence have different scopes. */
public final class ValidationModel {
    private ValidationModel() {}

    public enum Compatibility { UNKNOWN, INCOMPATIBLE, RUNTIME_COMPATIBLE, DEGRADED }

    /** Loader completion as reported by the host; absent means the snapshot is incomplete. */
    public enum Loader { ABSENT, COMPLETE, TIMED_OUT }

    /** Whether the feature's own preference gate is open; UNKNOWN when it is not modelled. */
    public enum Enablement { ENABLED, DISABLED, UNKNOWN }

    public enum Status {
        NOT_TESTED, UNKNOWN, DISABLED, NOT_INSTRUMENTED, NOT_EXERCISED, AMBIGUOUS,
        RESOLVER_FAILED, INSTALL_FAILED, INSTALL_INCOMPLETE, DEGRADED_INSTALL, ENABLEMENT_UNKNOWN,
        REACHED_NOT_ACTED, TRIGGERED_UNVERIFIED, BEHAVIOR_PASS, BEHAVIOR_FAIL, SERVER_UNAVAILABLE, BLOCKED
    }

    public static boolean occurredDuringSession(long eventAt, long sessionStartedAt, long capturedAt) {
        return sessionStartedAt > 0 && eventAt >= sessionStartedAt && eventAt <= capturedAt;
    }

    public static boolean sameRun(String session, String process, String config,
            String eventSession, String eventProcess, String eventConfig) {
        return !session.isEmpty() && !process.isEmpty() && !config.isEmpty()
                && session.equals(eventSession) && process.equals(eventProcess) && config.equals(eventConfig);
    }

    static boolean matchesOfficialRecord(String exactBuild, String recordedBuild, String scope,
            String prerequisites) {
        return exactBuild.matches("[0-9a-f]{64}") && exactBuild.equals(recordedBuild)
                && !scope.isBlank() && !prerequisites.isBlank();
    }

    public static final class FeatureEvidence {
        public Enablement enablement = Enablement.UNKNOWN;
        /** A hooked callback of this feature reports {@code reached}/{@code acted}. */
        public boolean instrumented;
        public boolean loaded;
        public boolean installed;
        public boolean error;
        public boolean degraded;
        public boolean resolverFailed;
        /** A callback ran after the relevant start (session, or the armed exercise). */
        public boolean reached;
        /** The feature acted on the path its case requires, after the relevant start. */
        public boolean acted;
        public boolean required;
        public boolean manualConfirmed;

        public Status state() {
            if (enablement == Enablement.DISABLED) return Status.DISABLED;
            if (error) return resolverFailed ? Status.RESOLVER_FAILED : Status.INSTALL_FAILED;
            if (!loaded && !installed) return Status.NOT_TESTED;
            // Constructed but doHook never returned within the loader's wait.
            if (!installed) return Status.INSTALL_INCOMPLETE;
            if (degraded) return Status.DEGRADED_INSTALL;
            if (!instrumented) return Status.NOT_INSTRUMENTED;
            if (acted) return Status.TRIGGERED_UNVERIFIED;
            if (reached) return Status.REACHED_NOT_ACTED;
            if (enablement == Enablement.UNKNOWN) return Status.ENABLEMENT_UNKNOWN;
            return Status.NOT_EXERCISED;
        }

        /** A failure that applies whenever the feature may be in use. */
        boolean failed() {
            if (enablement == Enablement.DISABLED) return false;
            return error || degraded || (loaded && !installed);
        }
    }

    public static Compatibility aggregate(boolean currentSnapshot, boolean corePassed,
            boolean optionalFailed, Loader loader, Collection<FeatureEvidence> features) {
        if (!currentSnapshot) return Compatibility.UNKNOWN;
        if (!corePassed) return Compatibility.INCOMPATIBLE;
        // Without the loader's completion marker the snapshot may predate failing features.
        if (loader == Loader.ABSENT) return Compatibility.UNKNOWN;
        optionalFailed |= loader == Loader.TIMED_OUT;
        for (FeatureEvidence feature : features) optionalFailed |= feature.failed();
        return optionalFailed ? Compatibility.DEGRADED : Compatibility.RUNTIME_COMPATIBLE;
    }

    /** Confirmation covers only the enabled diagnostic smoke cases for which controls exist. */
    public static boolean smokeConfirmed(Collection<FeatureEvidence> features) {
        boolean requiredSeen = false;
        for (FeatureEvidence feature : features) {
            if (!feature.required) continue;
            requiredSeen = true;
            if (feature.enablement != Enablement.ENABLED
                    || feature.state() != Status.TRIGGERED_UNVERIFIED || !feature.manualConfirmed) return false;
        }
        return requiredSeen;
    }
}
